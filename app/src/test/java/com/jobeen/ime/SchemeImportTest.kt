// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime

import com.jobeen.ime.data.manager.ImportFailure
import com.jobeen.ime.data.manager.ImportOutcome
import com.jobeen.ime.data.manager.MAX_IMPORT_ZIP_BYTES
import com.jobeen.ime.data.manager.SchemeEnabled
import com.jobeen.ime.data.manager.determineDirName
import com.jobeen.ime.data.manager.extractZip
import com.jobeen.ime.data.manager.importPackage
import com.jobeen.ime.data.manager.injectT9Fields
import com.jobeen.ime.data.manager.isProtectedEntry
import com.jobeen.ime.data.manager.stripTopDir
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 新目录导入：解压、目录名、T9 注入、编排的纯逻辑与假依赖测试；不碰引擎与偏好。 */
class SchemeImportTest {

    private fun runWithTempDir(block: (File) -> Unit) {
        val root = Files.createTempDirectory("scheme-import-test-").toFile()
        try {
            block(root)
        } finally {
            root.deleteRecursively()
        }
    }

    private suspend fun runWithTempDirSuspend(block: suspend (File) -> Unit) {
        val root = Files.createTempDirectory("scheme-import-test-").toFile()
        try {
            block(root)
        } finally {
            root.deleteRecursively()
        }
    }

    private fun writeZip(zipFile: File, entries: Map<String, String>) {
        ZipOutputStream(zipFile.outputStream()).use { zip ->
            for ((name, content) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray())
                zip.closeEntry()
            }
        }
    }

    private fun liteSchema(): String =
        "schema:\n" +
            "  schema_id: wanxiang_lite\n" +
            "  name: lite\n" +
            "  layout: Qwerty\n"

    @Test
    fun stripTopDir_sharedPrefix_mixed_empty() {
        assertEquals("pkg", stripTopDir(listOf("pkg/a.txt", "pkg/sub/b.txt")))
        assertEquals(null, stripTopDir(listOf("pkg/a.txt", "other/b.txt")))
        assertEquals(null, stripTopDir(emptyList()))
        assertEquals(null, stripTopDir(listOf("a.txt", "b.txt")))
        assertEquals(null, stripTopDir(listOf("/a.txt", "/b.txt")))
    }

    @Test
    fun isProtectedEntry_rules() {
        assertTrue(isProtectedEntry("user.userdb"))
        assertTrue(isProtectedEntry("sub/dir/user.userdb"))
        assertTrue(isProtectedEntry("default.yaml"))
        assertFalse(isProtectedEntry("sub/default.yaml"))
        assertTrue(isProtectedEntry("x.custom.yaml"))
        assertTrue(isProtectedEntry("custom_phrase.txt"))
        assertTrue(isProtectedEntry("x.userdict.txt"))
        assertFalse(isProtectedEntry("wanxiang_lite.schema.yaml"))
        assertFalse(isProtectedEntry(""))
    }

    @Test
    fun extractZip_stripsTopDir_protects_skipsUnsafe_recordsSchemas() {
        runWithTempDir { root ->
            val zipFile = File(root, "pkg.zip")
            writeZip(
                zipFile,
                mapOf(
                    "pkg/wanxiang_lite.schema.yaml" to "schema-content",
                    "pkg/wanxiang_lite.dict.yaml" to "dict",
                    "pkg/user.userdb" to "userdb",
                    "pkg/default.yaml" to "default",
                    "pkg/keep.custom.yaml" to "custom",
                    "pkg/sub/deep.txt" to "deep",
                    "pkg/../evil.txt" to "evil",
                    "pkg/../../abs.txt" to "abs",
                ),
            )
            val target = File(root, "out")
            val result = extractZip(zipFile, target)
            assertEquals(3, result.installed)
            assertEquals(3, result.skippedProtected)
            assertEquals(2, result.skippedUnsafe)
            assertEquals(listOf("wanxiang_lite"), result.newSchemaIds)
            assertTrue(result.totalBytes > 0)
            assertTrue(File(target, "wanxiang_lite.schema.yaml").isFile)
            assertTrue(File(target, "wanxiang_lite.dict.yaml").isFile)
            assertTrue(File(target, "sub/deep.txt").isFile)
            assertFalse(File(target, "user.userdb").exists())
            assertFalse(File(target, "default.yaml").exists())
            assertFalse(File(target, "keep.custom.yaml").exists())
            assertFalse(File(root, "evil.txt").exists())
            assertFalse(File(target, "evil.txt").exists())
            assertFalse(File(target, "abs.txt").exists())
        }
    }

    @Test
    fun extractZip_mixedRoot_noStrip() {
        runWithTempDir { root ->
            val zipFile = File(root, "mix.zip")
            writeZip(
                zipFile,
                mapOf(
                    "a.schema.yaml" to "schema",
                    "sub/b.txt" to "b",
                ),
            )
            val target = File(root, "out")
            val result = extractZip(zipFile, target)
            assertEquals(2, result.installed)
            assertEquals(listOf("a"), result.newSchemaIds)
            assertTrue(File(target, "a.schema.yaml").isFile)
            assertTrue(File(target, "sub/b.txt").isFile)
        }
    }

    @Test
    fun injectT9Fields_t9_replacesAndInserts_othersUntouched() {
        runWithTempDir { root ->
            val file = File(root, "wanxiang_t9.schema.yaml")
            file.writeText(
                "# wanxiang_t9.schema.yaml\n" +
                    "schema:\n" +
                    "  schema_id: wanxiang_t9\n" +
                    "  name: \"t9\"\n" +
                    "  version: \"1.0\"\n" +
                    "  layout: Qwerty\n" +
                    "  punctuation: HalfWidth\n" +
                    "options:\n" +
                    "  ascii_mode: false\n" +
                    "speller:\n" +
                    "  alphabet: abc\n"
            )
            val result = injectT9Fields(root, listOf("wanxiang_t9"))
            assertEquals(1, result.injected)
            assertTrue(result.skipped.isEmpty())
            assertEquals(
                "# wanxiang_t9.schema.yaml\n" +
                    "schema:\n" +
                    "  schema_id: wanxiang_t9\n" +
                    "  name: \"t9\"\n" +
                    "  version: \"1.0\"\n" +
                    "  layout: \"T9\"\n" +
                    "  punctuation: \"FullWidth\"\n" +
                    "  kind: \"PinYin\"\n" +
                    "  candidateKind: \"T9PinYin\"\n" +
                    "options:\n" +
                    "  ascii_mode: false\n" +
                    "speller:\n" +
                    "  alphabet: abc\n",
                file.readText(),
            )
        }
    }

    @Test
    fun injectT9Fields_lite_replacesWrongCandidateKind() {
        runWithTempDir { root ->
            val file = File(root, "wanxiang_lite.schema.yaml")
            file.writeText(
                "schema:\n" +
                    "  schema_id: wanxiang_lite\n" +
                    "  layout: Qwerty\n" +
                    "  punctuation: FullWidth\n" +
                    "  kind: PinYin\n" +
                    "  candidateKind: Wrong\n"
            )
            val result = injectT9Fields(root, listOf("wanxiang_lite"))
            assertEquals(1, result.injected)
            assertTrue(file.readText().contains("  candidateKind: \"PinYin\"\n"))
            assertFalse(file.readText().contains("Wrong"))
        }
    }

    @Test
    fun injectT9Fields_english_deletesCandidateKind() {
        runWithTempDir { root ->
            val file = File(root, "wanxiang_english.schema.yaml")
            file.writeText(
                "schema:\n" +
                    "  schema_id: wanxiang_english\n" +
                    "  layout: Qwerty\n" +
                    "  punctuation: FullWidth\n" +
                    "  kind: Other\n" +
                    "  candidateKind: Something\n"
            )
            val result = injectT9Fields(root, listOf("wanxiang_english"))
            assertEquals(1, result.injected)
            val text = file.readText()
            assertFalse(text.contains("candidateKind"))
            assertTrue(text.contains("  punctuation: \"HalfWidth\"\n"))
            assertTrue(text.contains("  kind: \"English\"\n"))
        }
    }

    @Test
    fun injectT9Fields_nonTarget_untouched_noSchemaSkipped() {
        runWithTempDir { root ->
            val other = File(root, "other.schema.yaml")
            val otherText = "schema:\n  schema_id: other_foo\n  layout: Qwerty\n"
            other.writeText(otherText)
            val noschema = File(root, "plain.schema.yaml")
            noschema.writeText("name: x\n")
            val result = injectT9Fields(root, listOf("other", "plain"))
            assertEquals(0, result.injected)
            assertEquals(listOf("plain"), result.skipped)
            assertEquals(otherText, other.readText())
        }
    }

    @Test
    fun determineDirName_rules() {
        assertEquals("mypkg", determineDirName("mypkg", "fallback.zip"))
        assertEquals("mypkg", determineDirName(null, "mypkg.zip"))
        assertEquals("build-pkg", determineDirName("build", "fallback.zip"))
        assertEquals("imported", determineDirName(null, ".zip"))
        assertEquals("imported", determineDirName("", ""))
    }

    @Test
    fun importPackage_success_newDir() = runBlocking {
        runWithTempDirSuspend { root ->
            val schemeDirsRoot = File(root, "root")
            val sharedDir = File(root, "shared")
            val zipFile = File(root, "newpkg.zip")
            writeZip(
                zipFile,
                mapOf(
                    "newpkg/wanxiang_lite.schema.yaml" to liteSchema(),
                    "newpkg/wanxiang_lite.dict.yaml" to "dict",
                ),
            )
            var mirrored: List<String>? = null
            var pointer: String? = null
            var deployTimeout: Long? = null
            var selected: String? = null
            val outcome = importPackage(
                zipFile = zipFile,
                fallbackName = "newpkg.zip",
                schemeDirsRoot = schemeDirsRoot,
                sharedDir = sharedDir,
                writeMirror = { mirrored = it },
                setCurrentDir = { pointer = it },
                deploy = { timeout ->
                    deployTimeout = timeout
                    true
                },
                selectSchema = { selected = it },
            )
            val success = outcome as ImportOutcome.Success
            assertEquals("newpkg", success.dirName)
            assertEquals(listOf("wanxiang_lite"), success.newSchemaIds)
            assertEquals(1, success.injected)
            assertEquals(listOf("wanxiang_lite"), mirrored)
            assertEquals("newpkg", pointer)
            assertEquals(180_000L, deployTimeout)
            assertEquals("wanxiang_lite", selected)
            val custom = File(File(schemeDirsRoot, "newpkg"), "default.custom.yaml")
            assertTrue(custom.readText().contains("    - schema: wanxiang_lite\n"))
        }
    }

    @Test
    fun importPackage_merge_prependsNew_keepsUniqueFiles() = runBlocking {
        runWithTempDirSuspend { root ->
            val schemeDirsRoot = File(root, "root")
            val sharedDir = File(root, "shared")
            val existing = File(schemeDirsRoot, "newpkg")
            existing.mkdirs()
            File(existing, "old_one.schema.yaml").writeText(
                "schema:\n  schema_id: old_one\n"
            )
            File(existing, "unique.txt").writeText("keep")
            SchemeEnabled.writeEnabledCustomFile(
                File(existing, "default.custom.yaml"),
                listOf("old_one"),
            )
            val zipFile = File(root, "newpkg.zip")
            writeZip(
                zipFile,
                mapOf("newpkg/wanxiang_lite.schema.yaml" to liteSchema()),
            )
            var mirrored: List<String>? = null
            val outcome = importPackage(
                zipFile = zipFile,
                fallbackName = "newpkg.zip",
                schemeDirsRoot = schemeDirsRoot,
                sharedDir = sharedDir,
                writeMirror = { mirrored = it },
                setCurrentDir = {},
                deploy = { true },
                selectSchema = {},
            )
            assertTrue(outcome is ImportOutcome.Success)
            assertEquals(listOf("wanxiang_lite", "old_one"), mirrored)
            assertEquals("keep", File(existing, "unique.txt").readText())
            val customText = File(existing, "default.custom.yaml").readText()
            assertTrue(customText.indexOf("wanxiang_lite") < customText.indexOf("old_one"))
        }
    }

    @Test
    fun importPackage_tooLarge_sparse() = runBlocking {
        runWithTempDirSuspend { root ->
            val schemeDirsRoot = File(root, "root")
            val sharedDir = File(root, "shared")
            val zipFile = File(root, "big.zip")
            RandomAccessFile(zipFile, "rw").use { it.setLength(MAX_IMPORT_ZIP_BYTES + 1) }
            val outcome = importPackage(
                zipFile = zipFile,
                fallbackName = "big.zip",
                schemeDirsRoot = schemeDirsRoot,
                sharedDir = sharedDir,
                writeMirror = {},
                setCurrentDir = {},
                deploy = { true },
                selectSchema = {},
            )
            assertEquals(
                ImportOutcome.Failed(ImportFailure.TOO_LARGE),
                outcome,
            )
            assertFalse(schemeDirsRoot.exists())
        }
    }

    @Test
    fun importPackage_notZip() = runBlocking {
        runWithTempDirSuspend { root ->
            val schemeDirsRoot = File(root, "root")
            val sharedDir = File(root, "shared")
            val zipFile = File(root, "note.zip")
            zipFile.writeText("this is not a zip")
            val outcome = importPackage(
                zipFile = zipFile,
                fallbackName = "note.zip",
                schemeDirsRoot = schemeDirsRoot,
                sharedDir = sharedDir,
                writeMirror = {},
                setCurrentDir = {},
                deploy = { true },
                selectSchema = {},
            )
            assertEquals(ImportOutcome.Failed(ImportFailure.NOT_ZIP), outcome)
        }
    }

    @Test
    fun importPackage_noSchema_precheckLeavesNoDir() = runBlocking {
        runWithTempDirSuspend { root ->
            val schemeDirsRoot = File(root, "root")
            val sharedDir = File(root, "shared")
            val zipFile = File(root, "pkg.zip")
            writeZip(zipFile, mapOf("pkg/only.dict.yaml" to "dict"))
            val outcome = importPackage(
                zipFile = zipFile,
                fallbackName = "pkg.zip",
                schemeDirsRoot = schemeDirsRoot,
                sharedDir = sharedDir,
                writeMirror = {},
                setCurrentDir = {},
                deploy = { true },
                selectSchema = {},
            )
            assertEquals(ImportOutcome.Failed(ImportFailure.NO_SCHEMA), outcome)
            assertFalse(File(schemeDirsRoot, "pkg").exists())
        }
    }

    @Test
    fun importPackage_deployFailed_leavesPointer_noSelect() = runBlocking {
        runWithTempDirSuspend { root ->
            val schemeDirsRoot = File(root, "root")
            val sharedDir = File(root, "shared")
            val zipFile = File(root, "newpkg.zip")
            writeZip(
                zipFile,
                mapOf("newpkg/wanxiang_lite.schema.yaml" to liteSchema()),
            )
            var pointer: String? = null
            var selected: String? = null
            val outcome = importPackage(
                zipFile = zipFile,
                fallbackName = "newpkg.zip",
                schemeDirsRoot = schemeDirsRoot,
                sharedDir = sharedDir,
                writeMirror = {},
                setCurrentDir = { pointer = it },
                deploy = { false },
                selectSchema = { selected = it },
            )
            assertEquals(ImportOutcome.Failed(ImportFailure.DEPLOY_FAILED), outcome)
            assertEquals("newpkg", pointer)
            assertEquals(null, selected)
            assertTrue(File(File(schemeDirsRoot, "newpkg"), "default.custom.yaml").isFile)
        }
    }
}
