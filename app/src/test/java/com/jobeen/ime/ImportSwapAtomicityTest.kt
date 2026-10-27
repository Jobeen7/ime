// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime

import com.jobeen.ime.data.manager.DeployAwaitResult
import com.jobeen.ime.data.manager.ImportFailure
import com.jobeen.ime.data.manager.ImportOutcome
import com.jobeen.ime.data.manager.SchemePackageImport
import com.jobeen.ime.data.manager.injectOptionsSegments
import com.jobeen.ime.data.manager.injectT9Fields
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 换位后注入原子性（案-O4）测试：注入写盘 tmp＋rename、成功路径校验 skipped。
 * 用例从 spec 推导，不按实现反推期望：
 * - 注入 skipped（解析不到 schema: 段）时事务必须在部署前失败，不静默成功；
 * - 成功注入后目标目录不得残留 *.tmp，文件内容须完整；
 * - 未映射的合法方案不得被 skipped 校验误杀（skipped 只来自解析/写盘失败，
 *   不来自“id 不在注入映射内”，后者是原样不动、既不计 injected 也不计 skipped）。
 */
class ImportSwapAtomicityTest {

    private data class Captures(
        var mirror: List<String>? = null,
        var currentDir: String? = null,
        var deployCalled: Boolean = false,
        var selected: String? = null,
    )

    private fun writeZip(zip: File, entries: List<Pair<String, ByteArray>>) {
        ZipOutputStream(zip.outputStream()).use { out ->
            for ((name, bytes) in entries) {
                out.putNextEntry(ZipEntry(name))
                out.write(bytes)
                out.closeEntry()
            }
        }
    }

    private fun runTx(
        zip: File,
        fallbackName: String,
        roots: File,
        sharedDir: File,
        cacheDir: File,
    ): Pair<ImportOutcome, Captures> {
        val cap = Captures()
        val outcome = runBlocking {
            SchemePackageImport.importPackageTransaction(
                zipFile = zip,
                fallbackName = fallbackName,
                schemeDirsRoot = roots,
                sharedDir = sharedDir,
                cacheDir = cacheDir,
                writeMirror = { cap.mirror = it },
                setCurrentDir = { cap.currentDir = it },
                deploy = {
                    cap.deployCalled = true
                    DeployAwaitResult.COMPLETED
                },
                selectSchema = { cap.selected = it },
            )
        }
        return outcome to cap
    }

    private fun tmpLeftovers(dir: File): List<File> =
        if (!dir.isDirectory) emptyList()
        else dir.walkTopDown().filter { it.isFile && it.name.endsWith(".tmp") }.toList()

    @Test
    fun postSwapInjectSkipped_failsBeforeDeploy_noSelect() {
        val root = Files.createTempDirectory("import-swap-test").toFile()
        try {
            val roots = File(root, "dirs").apply { mkdirs() }
            val shared = File(root, "shared").apply { mkdirs() }
            val cache = File(root, "cache").apply { mkdirs() }
            val zip = File(root, "pkg.zip")
            // 非空文本可过换位前校验（只查非空可读），但无顶格 schema: 段，
            // parseSchemaFile 返回 null → 两路注入均记 skipped。
            writeZip(
                zip,
                listOf("bad.schema.yaml" to "not a schema file\n".toByteArray()),
            )
            val (outcome, cap) = runTx(zip, "newdir", roots, shared, cache)
            val failed = outcome as ImportOutcome.Failed
            assertEquals(ImportFailure.IO_ERROR, failed.failure)
            assertTrue(failed.cause?.message?.contains("bad") == true)
            // 部署前失败：部署未启动、方案未选。
            assertFalse(cap.deployCalled)
            assertNull(cap.selected)
            // 本路径不回滚指针/镜像（调用方仅在 DEPLOY_* 时回滚）：
            // 指针与镜像已切新目录，写死此契约，改动此处须同步改调用方。
            assertEquals("newdir", cap.currentDir)
            assertEquals(listOf("bad"), cap.mirror)
            // 已换位内容原样保留（非截断半写），无 tmp 残留。
            val target = File(roots, "newdir")
            assertEquals("not a schema file\n", File(target, "bad.schema.yaml").readText())
            assertTrue(tmpLeftovers(target).isEmpty())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun injectSkippedFile_leftUntouched_noTmp() {
        val root = Files.createTempDirectory("import-swap-test").toFile()
        try {
            val dir = File(root, "dir").apply { mkdirs() }
            val original = "not a schema file\n"
            File(dir, "bad.schema.yaml").writeText(original)
            val t9 = injectT9Fields(dir, listOf("bad"))
            assertEquals(listOf("bad"), t9.skipped)
            assertEquals(0, t9.injected)
            val options = injectOptionsSegments(dir, listOf("bad"))
            assertEquals(listOf("bad"), options.skipped)
            assertEquals(0, options.injected)
            // skipped 文件原样不动，且无 tmp 残留。
            assertEquals(original, File(dir, "bad.schema.yaml").readText())
            assertTrue(tmpLeftovers(dir).isEmpty())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun postSwapUnmappedValidScheme_stillSucceeds() {
        val root = Files.createTempDirectory("import-swap-test").toFile()
        try {
            val roots = File(root, "dirs").apply { mkdirs() }
            val shared = File(root, "shared").apply { mkdirs() }
            val cache = File(root, "cache").apply { mkdirs() }
            val zip = File(root, "pkg.zip")
            writeZip(
                zip,
                listOf(
                    "my.schema.yaml" to
                        "schema:\n  schema_id: my_scheme\n  name: Mine\n".toByteArray(),
                ),
            )
            val (outcome, cap) = runTx(zip, "newdir", roots, shared, cache)
            val success = outcome as ImportOutcome.Success
            assertEquals(listOf("my"), success.newSchemaIds)
            assertTrue(success.injectSkipped.isEmpty())
            assertTrue(cap.deployCalled)
            assertEquals("my", cap.selected)
            assertTrue(tmpLeftovers(File(roots, "newdir")).isEmpty())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun postSwapInjectSuccess_leavesNoTmpAndFullContent() {
        val root = Files.createTempDirectory("import-swap-test").toFile()
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
            val target = File(roots, "newdir")
            val after = File(target, "wanxiang_t9.schema.yaml").readText()
            assertTrue(after.contains("layout"))
            assertTrue(after.contains("options"))
            assertTrue(tmpLeftovers(target).isEmpty())
        } finally {
            root.deleteRecursively()
        }
    }
}
