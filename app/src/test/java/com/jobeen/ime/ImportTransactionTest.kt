// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime

import com.jobeen.ime.data.manager.DeployAwaitResult
import com.jobeen.ime.data.manager.ImportFailure
import com.jobeen.ime.data.manager.ImportOutcome
import com.jobeen.ime.data.manager.SchemeEnabled
import com.jobeen.ime.data.manager.SchemePackageImport
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * 导入事务编排（importPackageTransaction）测试：按 2026-10-27 七项决议
 * （union、共享无条件覆盖、patch custom、FAILED 内容回滚＋TIMEOUT 只回滚
 * 指针、BAD_PACKAGE、256MiB、SWAPPED 扫尾）。
 * 旧 importPackage 不动（SchemeImportTest 覆盖），本文件只测新编排；
 * toast 映射是 Composable 内局部函数、JVM 测不了，以代码核对为准
 * （BAD_PACKAGE → schema_import_error_invalid_package，见 runImport）。
 */
class ImportTransactionTest {

    private data class TxCaptures(
        var mirror: List<String>? = null,
        var currentDir: String? = null,
        var deployTimeoutMs: Long? = null,
        var selected: String? = null,
    )

    private fun schemaText(name: String): String =
        "schema:\n  name: $name\n  version: 1.0\n"

    private fun writeZip(zip: File, entries: List<Pair<String, ByteArray>>) {
        ZipOutputStream(zip.outputStream()).use { out ->
            for ((name, bytes) in entries) {
                out.putNextEntry(ZipEntry(name))
                out.write(bytes)
                out.closeEntry()
            }
        }
    }

    private fun writeZipWithBigEntries(
        zip: File,
        small: List<Pair<String, ByteArray>>,
        big: List<Pair<String, Long>>,
    ) {
        val chunk = ByteArray(1024 * 1024)
        ZipOutputStream(zip.outputStream()).use { out ->
            for ((name, bytes) in small) {
                out.putNextEntry(ZipEntry(name))
                out.write(bytes)
                out.closeEntry()
            }
            for ((name, size) in big) {
                out.putNextEntry(ZipEntry(name))
                var remaining = size
                while (remaining > 0) {
                    val n = minOf(chunk.size.toLong(), remaining).toInt()
                    out.write(chunk, 0, n)
                    remaining -= n
                }
                out.closeEntry()
            }
        }
    }

    /**
     * 磁盘根目录（build/ 下）：/tmp 是 512M tmpfs，大体积测试放
     * 磁盘（79G 余量），finally 照常删。
     */
    private fun diskRoot(): File =
        File("build", "import-tx-disk-" + System.nanoTime()).apply { mkdirs() }

    private fun runTx(
        zip: File,
        fallbackName: String,
        roots: File,
        sharedDir: File,
        cacheDir: File,
        deployResult: DeployAwaitResult = DeployAwaitResult.COMPLETED,
        deploy: (suspend (Long) -> DeployAwaitResult)? = null,
    ): Pair<ImportOutcome, TxCaptures> {
        val cap = TxCaptures()
        val outcome = runBlocking {
            SchemePackageImport.importPackageTransaction(
                zipFile = zip,
                fallbackName = fallbackName,
                schemeDirsRoot = roots,
                sharedDir = sharedDir,
                cacheDir = cacheDir,
                writeMirror = { cap.mirror = it },
                setCurrentDir = { cap.currentDir = it },
                deploy = deploy ?: { timeout ->
                    cap.deployTimeoutMs = timeout
                    deployResult
                },
                selectSchema = { cap.selected = it },
            )
        }
        return outcome to cap
    }

    private fun noLeftovers(roots: File, cache: File) {
        assertTrue(
            roots.listFiles()?.none {
                it.name.startsWith(".install-") || it.name.startsWith(".backup-")
            } ?: true,
        )
        assertTrue(
            cache.listFiles()?.none { it.name.startsWith("import-staging-") } ?: true,
        )
    }

    private fun snapshot(dir: File): Map<String, ByteArray> =
        dir.walkTopDown().filter { it.isFile }.associate {
            it.relativeTo(dir).path to it.readBytes()
        }

    @Test
    fun successNewDir_installsSchemaWritesTableSelectsFirst() {
        val root = Files.createTempDirectory("import-tx-test").toFile()
        try {
            val roots = File(root, "dirs").apply { mkdirs() }
            val shared = File(root, "shared").apply { mkdirs() }
            val cache = File(root, "cache").apply { mkdirs() }
            val zip = File(root, "pkg.zip")
            writeZip(
                zip,
                listOf(
                    "c.schema.yaml" to schemaText("C").toByteArray(),
                    "d.schema.yaml" to schemaText("D").toByteArray(),
                    "c.dict.yaml" to "dict".toByteArray(),
                ),
            )
            val (outcome, cap) = runTx(zip, "newdir", roots, shared, cache)
            val success = outcome as ImportOutcome.Success
            assertEquals("newdir", success.dirName)
            assertEquals(listOf("c", "d"), success.newSchemaIds)
            assertEquals("c", cap.selected)
            assertEquals("newdir", cap.currentDir)
            assertEquals(listOf("c", "d"), cap.mirror)
            assertEquals(180_000L, cap.deployTimeoutMs)
            val target = File(roots, "newdir")
            assertEquals(
                listOf("c", "d"),
                SchemePackageImport.parseSchemaListFromCustomYaml(
                    File(target, "default.custom.yaml").readText(),
                ),
            )
            assertTrue(File(target, "c.schema.yaml").isFile)
            assertTrue(File(target, "c.dict.yaml").isFile)
            noLeftovers(roots, cache)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun successMergeDir_unionPreservesAndOrdersTable() {
        val root = Files.createTempDirectory("import-tx-test").toFile()
        try {
            val roots = File(root, "dirs").apply { mkdirs() }
            val shared = File(root, "shared").apply { mkdirs() }
            val cache = File(root, "cache").apply { mkdirs() }
            val old = File(roots, "mydir").apply { mkdirs() }
            File(old, "a.schema.yaml").writeText(schemaText("A"))
            File(old, "b.schema.yaml").writeText(schemaText("B"))
            File(old, "default.custom.yaml").writeText(
                SchemeEnabled.patchSchemaListYaml(null, listOf("a", "b")),
            )
            // 受保护（inherit）与 union 各一路。
            File(old, "wanxiang.userdb").writeText("learning")
            File(old, "custom_phrase.txt").writeText("phrase")
            File(old, "user.yaml").writeText("user")
            File(old, "installation.yaml").writeText("install")
            File(old, "notes.txt").writeText("unique")
            File(old, "extra.dict.yaml").writeText("old dict")
            File(old, "build").mkdirs()
            File(old, "build/old.bin").writeText("stale build")
            val zip = File(root, "pkg.zip")
            writeZip(
                zip,
                listOf(
                    "c.schema.yaml" to schemaText("C").toByteArray(),
                    "d.schema.yaml" to schemaText("D").toByteArray(),
                ),
            )
            val (outcome, cap) = runTx(zip, "mydir", roots, shared, cache)
            val success = outcome as ImportOutcome.Success
            assertEquals(listOf("c", "d"), success.newSchemaIds)
            assertEquals(listOf("c", "d", "a", "b"), cap.mirror)
            assertEquals(
                listOf("c", "d", "a", "b"),
                SchemePackageImport.parseSchemaListFromCustomYaml(
                    File(old, "default.custom.yaml").readText(),
                ),
            )
            assertEquals("learning", File(old, "wanxiang.userdb").readText())
            assertEquals("phrase", File(old, "custom_phrase.txt").readText())
            assertEquals("user", File(old, "user.yaml").readText())
            assertEquals("install", File(old, "installation.yaml").readText())
            assertEquals("unique", File(old, "notes.txt").readText())
            assertEquals("old dict", File(old, "extra.dict.yaml").readText())
            assertEquals("stale build", File(old, "build/old.bin").readText())
            assertTrue(File(old, "a.schema.yaml").isFile)
            assertTrue(File(old, "c.schema.yaml").isFile)
            noLeftovers(roots, cache)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun mergeDeployFailed_rollsBackContentByteIdentical() {
        val root = Files.createTempDirectory("import-tx-test").toFile()
        try {
            val roots = File(root, "dirs").apply { mkdirs() }
            val shared = File(root, "shared").apply { mkdirs() }
            val cache = File(root, "cache").apply { mkdirs() }
            val old = File(roots, "mydir").apply { mkdirs() }
            File(old, "a.schema.yaml").writeText(schemaText("A"))
            File(old, "default.custom.yaml").writeText(
                SchemeEnabled.patchSchemaListYaml(null, listOf("a")),
            )
            File(old, "wanxiang.userdb").writeText("learning")
            val before = snapshot(old)
            val zip = File(root, "pkg.zip")
            writeZip(zip, listOf("c.schema.yaml" to schemaText("C").toByteArray()))
            val (outcome, _) = runTx(
                zip, "mydir", roots, shared, cache,
                deployResult = DeployAwaitResult.FAILED,
            )
            val failed = outcome as ImportOutcome.Failed
            assertEquals(ImportFailure.DEPLOY_FAILED, failed.failure)
            // 内容回滚：目标与失败前逐字节一致，备份与日志清掉。
            val after = snapshot(old)
            assertEquals(before.keys, after.keys)
            for ((rel, bytes) in before) {
                assertTrue(after[rel]!!.contentEquals(bytes))
            }
            noLeftovers(roots, cache)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun newDirDeployFailed_leavesCompleteOrphanAndJournalForStartupSweep() {
        val root = Files.createTempDirectory("import-tx-test").toFile()
        try {
            val roots = File(root, "dirs").apply { mkdirs() }
            val shared = File(root, "shared").apply { mkdirs() }
            val cache = File(root, "cache").apply { mkdirs() }
            val zip = File(root, "pkg.zip")
            writeZip(zip, listOf("c.schema.yaml" to schemaText("C").toByteArray()))
            val (outcome, _) = runTx(
                zip, "newdir", roots, shared, cache,
                deployResult = DeployAwaitResult.FAILED,
            )
            assertEquals(
                ImportFailure.DEPLOY_FAILED,
                (outcome as ImportOutcome.Failed).failure,
            )
            // 新目录无备份可回滚：完整新目录留存（孤儿，可切过去用），
            // SWAPPED 日志留存，待启动扫尾（指针已回滚到旧目录时删日志）。
            val target = File(roots, "newdir")
            assertTrue(File(target, "c.schema.yaml").isFile)
            assertTrue(File(target, "default.custom.yaml").isFile)
            val journals = roots.listFiles()?.filter {
                it.name.startsWith(".install-") && it.name.endsWith(".journal")
            } ?: emptyList()
            assertEquals(1, journals.size)
            assertTrue(journals[0].readText().contains("stage=SWAPPED"))
            assertEquals(1, SchemePackageImport.sweepSwappedBackups(roots, "prevdir"))
            assertEquals(0, roots.listFiles()?.count {
                it.name.startsWith(".install-")
            })
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun mergeDeployTimeout_keepsNewContentBackupAndJournal() {
        val root = Files.createTempDirectory("import-tx-test").toFile()
        try {
            val roots = File(root, "dirs").apply { mkdirs() }
            val shared = File(root, "shared").apply { mkdirs() }
            val cache = File(root, "cache").apply { mkdirs() }
            val old = File(roots, "mydir").apply { mkdirs() }
            File(old, "a.schema.yaml").writeText(schemaText("A"))
            File(old, "default.custom.yaml").writeText(
                SchemeEnabled.patchSchemaListYaml(null, listOf("a")),
            )
            val zip = File(root, "pkg.zip")
            writeZip(zip, listOf("c.schema.yaml" to schemaText("C").toByteArray()))
            val (outcome, _) = runTx(
                zip, "mydir", roots, shared, cache,
                deployResult = DeployAwaitResult.TIMEOUT_RUNNING,
            )
            assertEquals(
                ImportFailure.DEPLOY_TIMEOUT,
                (outcome as ImportOutcome.Failed).failure,
            )
            // 超时绝不内容回滚：新内容、备份、SWAPPED 日志全留存。
            assertTrue(File(old, "c.schema.yaml").isFile)
            val backups = roots.listFiles()?.filter {
                it.name.startsWith(".backup-")
            } ?: emptyList()
            assertEquals(1, backups.size)
            assertTrue(File(backups[0], "a.schema.yaml").isFile)
            val journals = roots.listFiles()?.filter {
                it.name.startsWith(".install-") && it.name.endsWith(".journal")
            } ?: emptyList()
            assertEquals(1, journals.size)
            assertTrue(journals[0].readText().contains("stage=SWAPPED"))
            // 指针仍在该目录时扫尾保留（部署未确认时保旧版）。
            assertEquals(0, SchemePackageImport.sweepSwappedBackups(roots, "mydir"))
            assertTrue(backups[0].exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun caps_zipOver256MiB_rejected() {
        val root = Files.createTempDirectory("import-tx-test").toFile()
        try {
            val roots = File(root, "dirs").apply { mkdirs() }
            val shared = File(root, "shared").apply { mkdirs() }
            val cache = File(root, "cache").apply { mkdirs() }
            // 稀疏文件：length() 超限即拒，不写实际内容。
            val zip = File(root, "big.zip")
            RandomAccessFile(zip, "rw").use { it.setLength(300L * 1024 * 1024) }
            val (outcome, _) = runTx(zip, "newdir", roots, shared, cache)
            assertEquals(
                ImportFailure.TOO_LARGE,
                (outcome as ImportOutcome.Failed).failure,
            )
            assertFalse(File(roots, "newdir").exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun caps_singleEntryOver256MiB_rejected() {
        val root = diskRoot()
        try {
            val roots = File(root, "dirs").apply { mkdirs() }
            val shared = File(root, "shared").apply { mkdirs() }
            val cache = File(root, "cache").apply { mkdirs() }
            val zip = File(root, "pkg.zip")
            writeZipWithBigEntries(
                zip,
                small = listOf("c.schema.yaml" to schemaText("C").toByteArray()),
                big = listOf("big.dict.yaml" to 300L * 1024 * 1024),
            )
            val (outcome, _) = runTx(zip, "newdir", roots, shared, cache)
            assertEquals(
                ImportFailure.TOO_LARGE,
                (outcome as ImportOutcome.Failed).failure,
            )
            assertFalse(File(roots, "newdir").exists())
            noLeftovers(roots, cache)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun caps_totalOver1GiB_rejected() {
        val root = diskRoot()
        try {
            val roots = File(root, "dirs").apply { mkdirs() }
            val shared = File(root, "shared").apply { mkdirs() }
            val cache = File(root, "cache").apply { mkdirs() }
            val zip = File(root, "pkg.zip")
            // 单条均未超 256MiB 条目上限，合计 1100MiB 超 1GiB 总量上限。
            writeZipWithBigEntries(
                zip,
                small = listOf("c.schema.yaml" to schemaText("C").toByteArray()),
                big = (1..5).map { "big$it.dict.yaml" to 220L * 1024 * 1024 },
            )
            val (outcome, _) = runTx(zip, "newdir", roots, shared, cache)
            assertEquals(
                ImportFailure.TOO_LARGE,
                (outcome as ImportOutcome.Failed).failure,
            )
            assertFalse(File(roots, "newdir").exists())
            noLeftovers(roots, cache)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun caps_tooManyEntries_badPackage() {
        val root = Files.createTempDirectory("import-tx-test").toFile()
        try {
            val roots = File(root, "dirs").apply { mkdirs() }
            val shared = File(root, "shared").apply { mkdirs() }
            val cache = File(root, "cache").apply { mkdirs() }
            val zip = File(root, "pkg.zip")
            val entries = mutableListOf<Pair<String, ByteArray>>()
            entries.add("c.schema.yaml" to schemaText("C").toByteArray())
            for (i in 0 until 20000) {
                entries.add("f%05d.txt".format(i) to "x".toByteArray())
            }
            writeZip(zip, entries)
            val (outcome, _) = runTx(zip, "newdir", roots, shared, cache)
            assertEquals(
                ImportFailure.BAD_PACKAGE,
                (outcome as ImportOutcome.Failed).failure,
            )
            assertFalse(File(roots, "newdir").exists())
            noLeftovers(roots, cache)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun caps_pathTooLong_badPackage() {
        val root = Files.createTempDirectory("import-tx-test").toFile()
        try {
            val roots = File(root, "dirs").apply { mkdirs() }
            val shared = File(root, "shared").apply { mkdirs() }
            val cache = File(root, "cache").apply { mkdirs() }
            val zip = File(root, "pkg.zip")
            val longName = "a".repeat(1100) + ".schema.yaml"
            writeZip(
                zip,
                listOf(
                    "c.schema.yaml" to schemaText("C").toByteArray(),
                    longName to schemaText("L").toByteArray(),
                ),
            )
            val (outcome, _) = runTx(zip, "newdir", roots, shared, cache)
            assertEquals(
                ImportFailure.BAD_PACKAGE,
                (outcome as ImportOutcome.Failed).failure,
            )
            assertFalse(File(roots, "newdir").exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun caps_pathTooDeep_badPackage() {
        val root = Files.createTempDirectory("import-tx-test").toFile()
        try {
            val roots = File(root, "dirs").apply { mkdirs() }
            val shared = File(root, "shared").apply { mkdirs() }
            val cache = File(root, "cache").apply { mkdirs() }
            val zip = File(root, "pkg.zip")
            val deep = (1..33).joinToString("/") { "d" } + "/x.schema.yaml"
            writeZip(
                zip,
                listOf(
                    "c.schema.yaml" to schemaText("C").toByteArray(),
                    deep to schemaText("X").toByteArray(),
                ),
            )
            val (outcome, _) = runTx(zip, "newdir", roots, shared, cache)
            assertEquals(
                ImportFailure.BAD_PACKAGE,
                (outcome as ImportOutcome.Failed).failure,
            )
            assertFalse(File(roots, "newdir").exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun weird_duplicateNormalizedPath_badPackage() {
        val root = Files.createTempDirectory("import-tx-test").toFile()
        try {
            val roots = File(root, "dirs").apply { mkdirs() }
            val shared = File(root, "shared").apply { mkdirs() }
            val cache = File(root, "cache").apply { mkdirs() }
            val zip = File(root, "pkg.zip")
            writeZip(
                zip,
                listOf(
                    "a/../x.schema.yaml" to schemaText("X").toByteArray(),
                    "x.schema.yaml" to schemaText("X2").toByteArray(),
                ),
            )
            val (outcome, _) = runTx(zip, "newdir", roots, shared, cache)
            assertEquals(
                ImportFailure.BAD_PACKAGE,
                (outcome as ImportOutcome.Failed).failure,
            )
            assertFalse(File(roots, "newdir").exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun weird_fileDirConflict_badPackage() {
        val root = Files.createTempDirectory("import-tx-test").toFile()
        try {
            val roots = File(root, "dirs").apply { mkdirs() }
            val shared = File(root, "shared").apply { mkdirs() }
            val cache = File(root, "cache").apply { mkdirs() }
            val zip = File(root, "pkg.zip")
            writeZip(
                zip,
                listOf(
                    "a" to "file".toByteArray(),
                    "a/b.schema.yaml" to schemaText("B").toByteArray(),
                ),
            )
            val (outcome, _) = runTx(zip, "newdir", roots, shared, cache)
            assertEquals(
                ImportFailure.BAD_PACKAGE,
                (outcome as ImportOutcome.Failed).failure,
            )
            assertFalse(File(roots, "newdir").exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun weird_traversalSkippedRestSucceeds() {
        val root = Files.createTempDirectory("import-tx-test").toFile()
        try {
            val roots = File(root, "dirs").apply { mkdirs() }
            val shared = File(root, "shared").apply { mkdirs() }
            val cache = File(root, "cache").apply { mkdirs() }
            val zip = File(root, "pkg.zip")
            writeZip(
                zip,
                listOf(
                    "../evil.yaml" to "evil".toByteArray(),
                    "c.schema.yaml" to schemaText("C").toByteArray(),
                ),
            )
            val (outcome, _) = runTx(zip, "newdir", roots, shared, cache)
            val success = outcome as ImportOutcome.Success
            assertEquals(listOf("c"), success.newSchemaIds)
            val target = File(roots, "newdir")
            assertTrue(File(target, "c.schema.yaml").isFile)
            assertFalse(File(roots, "evil.yaml").exists())
            assertFalse(File(target, "evil.yaml").exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun weird_inRootDotDot_resolvesToRootId() {
        val root = Files.createTempDirectory("import-tx-test").toFile()
        try {
            val roots = File(root, "dirs").apply { mkdirs() }
            val shared = File(root, "shared").apply { mkdirs() }
            val cache = File(root, "cache").apply { mkdirs() }
            val zip = File(root, "pkg.zip")
            writeZip(
                zip,
                listOf("a/../c.schema.yaml" to schemaText("C").toByteArray()),
            )
            val (outcome, _) = runTx(zip, "newdir", roots, shared, cache)
            val success = outcome as ImportOutcome.Success
            assertEquals(listOf("c"), success.newSchemaIds)
            val target = File(roots, "newdir")
            assertTrue(File(target, "c.schema.yaml").isFile)
            assertFalse(File(target, "a").exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun injection_failedBeforeSwap_leavesNoTraces() {
        val root = Files.createTempDirectory("import-tx-test").toFile()
        try {
            val roots = File(root, "dirs").apply { mkdirs() }
            val shared = File(root, "shared").apply { mkdirs() }
            val cache = File(root, "cache").apply { mkdirs() }
            val old = File(roots, "mydir").apply { mkdirs() }
            val t9Before = "schema:\n  schema_id: wanxiang_t9\n  name: T9\n"
            File(old, "wanxiang_t9.schema.yaml").writeText(t9Before)
            File(old, "default.custom.yaml").writeText(
                SchemeEnabled.patchSchemaListYaml(null, listOf("wanxiang_t9")),
            )
            // 空方案文件：换位前校验失败，注入绝不跑。
            val zip = File(root, "pkg.zip")
            writeZip(zip, listOf("new.schema.yaml" to "   ".toByteArray()))
            val (outcome, _) = runTx(zip, "mydir", roots, shared, cache)
            assertEquals(
                ImportFailure.IO_ERROR,
                (outcome as ImportOutcome.Failed).failure,
            )
            assertEquals(
                t9Before,
                File(old, "wanxiang_t9.schema.yaml").readText(),
            )
            assertFalse(File(old, "new.schema.yaml").exists())
            noLeftovers(roots, cache)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun injection_t9FieldsAndOptionsAppliedAfterSwap() {
        val root = Files.createTempDirectory("import-tx-test").toFile()
        try {
            val roots = File(root, "dirs").apply { mkdirs() }
            val shared = File(root, "shared").apply { mkdirs() }
            val cache = File(root, "cache").apply { mkdirs() }
            val zip = File(root, "pkg.zip")
            writeZip(
                zip,
                listOf(
                    "wanxiang_t9.schema.yaml" to
                        "schema:\n  schema_id: wanxiang_t9\n  name: T9\n".toByteArray(),
                ),
            )
            val (outcome, _) = runTx(zip, "newdir", roots, shared, cache)
            assertTrue(outcome is ImportOutcome.Success)
            val after = File(File(roots, "newdir"), "wanxiang_t9.schema.yaml").readText()
            // 注入前文件无 layout 行、无 options 段；注入后两者都有。
            assertTrue(after.contains("layout"))
            assertTrue(after.contains("options"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun emptyPackage_noSchemaNoResidue() {
        val root = Files.createTempDirectory("import-tx-test").toFile()
        try {
            val roots = File(root, "dirs").apply { mkdirs() }
            val shared = File(root, "shared").apply { mkdirs() }
            val cache = File(root, "cache").apply { mkdirs() }
            // 只有词典、无方案。
            val zip1 = File(root, "pkg1.zip")
            writeZip(zip1, listOf("x.dict.yaml" to "dict".toByteArray()))
            val (o1, _) = runTx(zip1, "newdir", roots, shared, cache)
            assertEquals(ImportFailure.NO_SCHEMA, (o1 as ImportOutcome.Failed).failure)
            assertFalse(File(roots, "newdir").exists())
            // 只有嵌套方案：规划成功但根级 id 为空，同样 NO_SCHEMA。
            // 注意：全包同属单一顶目录会被当成包装目录剥掉（与今天
            // inspectZip 同口径），故嵌套用例须混一个根文件破包装。
            val zip2 = File(root, "pkg2.zip")
            writeZip(
                zip2,
                listOf(
                    "sub/nested.schema.yaml" to schemaText("N").toByteArray(),
                    "readme.txt" to "readme".toByteArray(),
                ),
            )
            val (o2, _) = runTx(zip2, "newdir", roots, shared, cache)
            assertEquals(ImportFailure.NO_SCHEMA, (o2 as ImportOutcome.Failed).failure)
            assertFalse(File(roots, "newdir").exists())
            noLeftovers(roots, cache)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun customNonTableContent_preservedByPatch() {
        val root = Files.createTempDirectory("import-tx-test").toFile()
        try {
            val roots = File(root, "dirs").apply { mkdirs() }
            val shared = File(root, "shared").apply { mkdirs() }
            val cache = File(root, "cache").apply { mkdirs() }
            val old = File(roots, "mydir").apply { mkdirs() }
            File(old, "a.schema.yaml").writeText(schemaText("A"))
            File(old, "default.custom.yaml").writeText(
                "# header\npatch:\n  schema_list:\n    - schema: a\n  other_key: keepme\n",
            )
            val zip = File(root, "pkg.zip")
            writeZip(zip, listOf("c.schema.yaml" to schemaText("C").toByteArray()))
            val (outcome, cap) = runTx(zip, "mydir", roots, shared, cache)
            assertTrue(outcome is ImportOutcome.Success)
            assertEquals(listOf("c", "a"), cap.mirror)
            val custom = File(old, "default.custom.yaml").readText()
            assertTrue(custom.contains("# header"))
            assertTrue(custom.contains("other_key: keepme"))
            assertEquals(
                listOf("c", "a"),
                SchemePackageImport.parseSchemaListFromCustomYaml(custom),
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun timeoutFormula_smallPackage180s() {
        val root = Files.createTempDirectory("import-tx-test").toFile()
        try {
            val roots = File(root, "dirs").apply { mkdirs() }
            val shared = File(root, "shared").apply { mkdirs() }
            val cache = File(root, "cache").apply { mkdirs() }
            val zip = File(root, "pkg.zip")
            writeZip(zip, listOf("c.schema.yaml" to schemaText("C").toByteArray()))
            val (_, cap) = runTx(zip, "newdir", roots, shared, cache)
            assertEquals(180_000L, cap.deployTimeoutMs)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun timeoutFormula_63MiBInstalledBytes390s() {
        val root = Files.createTempDirectory("import-tx-test").toFile()
        try {
            val roots = File(root, "dirs").apply { mkdirs() }
            val shared = File(root, "shared").apply { mkdirs() }
            val cache = File(root, "cache").apply { mkdirs() }
            val zip = File(root, "pkg.zip")
            // 已安装字节和（不含跳过文件）：63MiB 词典＋小方案 → 180＋7×30＝390s。
            writeZipWithBigEntries(
                zip,
                small = listOf("c.schema.yaml" to schemaText("C").toByteArray()),
                big = listOf("big.dict.yaml" to 63L * 1024 * 1024),
            )
            val (_, cap) = runTx(zip, "newdir", roots, shared, cache)
            assertEquals(390_000L, cap.deployTimeoutMs)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test(expected = CancellationException::class)
    fun cancelAtDeployWait_propagatesNotFailed() {
        val root = Files.createTempDirectory("import-tx-test").toFile()
        try {
            val roots = File(root, "dirs").apply { mkdirs() }
            val shared = File(root, "shared").apply { mkdirs() }
            val cache = File(root, "cache").apply { mkdirs() }
            val zip = File(root, "pkg.zip")
            writeZip(zip, listOf("c.schema.yaml" to schemaText("C").toByteArray()))
            runTx(
                zip, "newdir", roots, shared, cache,
                deploy = { throw CancellationException("cancelled") },
            )
            fail("expected CancellationException")
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun recovery_preSwapRestoresOld() {
        val root = Files.createTempDirectory("import-tx-test").toFile()
        try {
            val roots = File(root, "dirs").apply { mkdirs() }
            val target = File(roots, "mydir").apply { mkdirs() }
            File(target, "a.schema.yaml").writeText(schemaText("A"))
            val candidate = File(roots, ".install-op1").apply { mkdirs() }
            File(candidate, "c.schema.yaml").writeText(schemaText("C"))
            val backup = File(roots, ".backup-op1")
            File(roots, ".install-op1.journal").writeText(
                "operationId=op1\n" +
                    "target=${target.absolutePath}\n" +
                    "candidate=${candidate.absolutePath}\n" +
                    "backup=${backup.absolutePath}\n" +
                    "stage=VERIFIED\n",
            )
            val results = SchemePackageImport.recoverIncompleteTransactions(roots)
            assertEquals(1, results.size)
            assertTrue(results[0].restoredOld)
            assertFalse(candidate.exists())
            assertFalse(File(roots, ".install-op1.journal").exists())
            assertEquals(schemaText("A"), File(target, "a.schema.yaml").readText())
            assertFalse(File(target, "c.schema.yaml").exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun recovery_targetMovedRestoresBackup() {
        val root = Files.createTempDirectory("import-tx-test").toFile()
        try {
            val roots = File(root, "dirs").apply { mkdirs() }
            val target = File(roots, "mydir")
            val candidate = File(roots, ".install-op1").apply { mkdirs() }
            val backup = File(roots, ".backup-op1").apply { mkdirs() }
            File(backup, "a.schema.yaml").writeText(schemaText("A"))
            File(roots, ".install-op1.journal").writeText(
                "operationId=op1\n" +
                    "target=${target.absolutePath}\n" +
                    "candidate=${candidate.absolutePath}\n" +
                    "backup=${backup.absolutePath}\n" +
                    "stage=TARGET_MOVED\n",
            )
            val results = SchemePackageImport.recoverIncompleteTransactions(roots)
            assertEquals(1, results.size)
            assertTrue(results[0].restoredOld)
            assertFalse(candidate.exists())
            assertFalse(backup.exists())
            assertFalse(File(roots, ".install-op1.journal").exists())
            assertEquals(schemaText("A"), File(target, "a.schema.yaml").readText())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun recovery_targetMovedRenameFailed_keepsJournalAndBackupForRetry() {
        val root = Files.createTempDirectory("import-tx-test").toFile()
        try {
            val roots = File(root, "dirs").apply { mkdirs() }
            // 目标父路径被普通文件挡住：renameTo 必定返回 false，
            // 且与权限/用户无关、可稳定复现移回失败。
            val blocker = File(roots, "blocker").apply { writeText("not a dir") }
            val target = File(blocker, "mydir")
            val candidate = File(roots, ".install-op1").apply { mkdirs() }
            File(candidate, "c.schema.yaml").writeText(schemaText("C"))
            val backup = File(roots, ".backup-op1").apply { mkdirs() }
            File(backup, "a.schema.yaml").writeText(schemaText("A"))
            File(roots, ".install-op1.journal").writeText(
                "operationId=op1\n" +
                    "target=${target.absolutePath}\n" +
                    "candidate=${candidate.absolutePath}\n" +
                    "backup=${backup.absolutePath}\n" +
                    "stage=TARGET_MOVED\n",
            )
            val failed = SchemePackageImport.recoverIncompleteTransactions(roots)
            assertEquals(0, failed.size)
            assertTrue(backup.exists())
            assertEquals(schemaText("A"), File(backup, "a.schema.yaml").readText())
            assertTrue(File(roots, ".install-op1.journal").exists())
            assertTrue(candidate.exists())
            // 挡路文件撤掉后下次启动重试：备份移回目标、日志与候选清掉。
            blocker.delete()
            blocker.mkdirs()
            val retried = SchemePackageImport.recoverIncompleteTransactions(roots)
            assertEquals(1, retried.size)
            assertTrue(retried[0].restoredOld)
            assertFalse(backup.exists())
            assertFalse(candidate.exists())
            assertFalse(File(roots, ".install-op1.journal").exists())
            assertEquals(schemaText("A"), File(target, "a.schema.yaml").readText())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun recovery_swappedKeptThenSweepByPointer() {
        val root = Files.createTempDirectory("import-tx-test").toFile()
        try {
            val roots = File(root, "dirs").apply { mkdirs() }
            val target = File(roots, "mydir").apply { mkdirs() }
            File(target, "c.schema.yaml").writeText(schemaText("C"))
            val candidate = File(roots, ".install-op1")
            val backup = File(roots, ".backup-op1").apply { mkdirs() }
            File(backup, "a.schema.yaml").writeText(schemaText("A"))
            File(roots, ".install-op1.journal").writeText(
                "operationId=op1\n" +
                    "target=${target.absolutePath}\n" +
                    "candidate=${candidate.absolutePath}\n" +
                    "backup=${backup.absolutePath}\n" +
                    "stage=SWAPPED\n",
            )
            val results = SchemePackageImport.recoverIncompleteTransactions(roots)
            assertEquals(1, results.size)
            assertFalse(results[0].restoredOld)
            assertTrue(backup.exists())
            assertTrue(File(roots, ".install-op1.journal").exists())
            // 指针仍在该目录：保留。
            assertEquals(0, SchemePackageImport.sweepSwappedBackups(roots, "mydir"))
            assertTrue(backup.exists())
            // 指针已不在该目录：删备份＋日志，目标不动。
            assertEquals(1, SchemePackageImport.sweepSwappedBackups(roots, "other"))
            assertFalse(backup.exists())
            assertFalse(File(roots, ".install-op1.journal").exists())
            assertTrue(File(target, "c.schema.yaml").isFile)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun deploySuccess_cleansSwappedResidualForDir() {
        val root = Files.createTempDirectory("import-tx-test").toFile()
        try {
            val roots = File(root, "dirs").apply { mkdirs() }
            val shared = File(root, "shared").apply { mkdirs() }
            val cache = File(root, "cache").apply { mkdirs() }
            val old = File(roots, "mydir").apply { mkdirs() }
            File(old, "a.schema.yaml").writeText(schemaText("A"))
            File(old, "default.custom.yaml").writeText(
                SchemeEnabled.patchSchemaListYaml(null, listOf("a")),
            )
            // 旧 SWAPPED 残留（上次部署成功未清干净的模拟）。
            val staleBackup = File(roots, ".backup-old").apply { mkdirs() }
            File(staleBackup, "a.schema.yaml").writeText(schemaText("A"))
            File(roots, ".install-old.journal").writeText(
                "operationId=old\n" +
                    "target=${old.absolutePath}\n" +
                    "candidate=${File(roots, ".install-old").absolutePath}\n" +
                    "backup=${staleBackup.absolutePath}\n" +
                    "stage=SWAPPED\n",
            )
            val zip = File(root, "pkg.zip")
            writeZip(zip, listOf("c.schema.yaml" to schemaText("C").toByteArray()))
            val (outcome, _) = runTx(zip, "mydir", roots, shared, cache)
            assertTrue(outcome is ImportOutcome.Success)
            assertFalse(staleBackup.exists())
            assertFalse(File(roots, ".install-old.journal").exists())
            noLeftovers(roots, cache)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun sharedForcedOverwrite_newContentWins() {
        val root = Files.createTempDirectory("import-tx-test").toFile()
        try {
            val roots = File(root, "dirs").apply { mkdirs() }
            val shared = File(root, "shared").apply { mkdirs() }
            val cache = File(root, "cache").apply { mkdirs() }
            val old = File(roots, "mydir").apply { mkdirs() }
            File(old, "a.schema.yaml").writeText(schemaText("A"))
            File(old, "default.custom.yaml").writeText(
                SchemeEnabled.patchSchemaListYaml(null, listOf("a")),
            )
            File(old, "wanxiang_symbols.yaml").writeText("old symbols")
            val zip = File(root, "pkg.zip")
            writeZip(
                zip,
                listOf(
                    "c.schema.yaml" to schemaText("C").toByteArray(),
                    "wanxiang_symbols.yaml" to "new symbols".toByteArray(),
                ),
            )
            val (outcome, _) = runTx(zip, "mydir", roots, shared, cache)
            assertTrue(outcome is ImportOutcome.Success)
            assertEquals("new symbols", File(old, "wanxiang_symbols.yaml").readText())
        } finally {
            root.deleteRecursively()
        }
    }
}
