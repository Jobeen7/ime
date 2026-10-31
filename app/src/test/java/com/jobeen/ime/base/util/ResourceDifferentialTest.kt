package com.jobeen.ime.base.util

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Z2：内置数据按校验清单差量同步。
 * 清单 JSON 由 tools/gen_resource_manifest.py 从 resource.zip 的中央目录生成
 * （路径 + 未压缩长度 + CRC32 + 整包 SHA-256）；运行侧按清单比对目标文件，
 * 未变跳过、变化/缺失才写、shared/ 与 model/ 下清单外文件修剪、
 * Custom_* 用户词典只填空不覆盖不修剪、清单与整包失配时报 STALE 由调用方全量重解。
 */
class ResourceDifferentialTest {

    private fun zipBytes(entries: Map<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            for ((name, content) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(content)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private fun crcHex(content: ByteArray): String {
        val crc = CRC32()
        crc.update(content)
        return "%08x".format(crc.value)
    }

    private fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun manifestJson(
        zip: ByteArray,
        entries: Map<String, ByteArray>,
        preserve: List<String> = emptyList(),
    ): String {
        val entryJson = entries.entries.joinToString(",") { (path, content) ->
            """{"path":"$path","size":${content.size},"crc":"${crcHex(content)}"}"""
        }
        val preserveJson = preserve.joinToString(",") { "\"$it\"" }
        return """{"version":1,"zipSha256":"${sha256Hex(zip)}","preserve":[$preserveJson],"entries":[$entryJson]}"""
    }

    private fun runDifferential(
        zip: ByteArray,
        entries: Map<String, ByteArray>,
        destDir: File,
        preserve: List<String> = emptyList(),
    ): DifferentialResult {
        val manifest = parseManifest(manifestJson(zip, entries, preserve))!!
        return extractDifferential(ByteArrayInputStream(zip), destDir, manifest)
    }

    private fun runWithTempDir(block: (File) -> Unit) {
        val root = Files.createTempDirectory("resource-diff-").toFile()
        try {
            block(root)
        } finally {
            root.deleteRecursively()
        }
    }

    // ---- 清单解析 ----

    @Test
    fun parse_valid() {
        val zip = zipBytes(mapOf("shared/a.txt" to "hello".toByteArray()))
        val manifest = parseManifest(
            manifestJson(zip, mapOf("shared/a.txt" to "hello".toByteArray())),
        )!!
        assertEquals(1, manifest.version)
        assertEquals(sha256Hex(zip), manifest.zipSha256)
        assertEquals(1, manifest.entries.size)
        assertEquals("shared/a.txt", manifest.entries.single().path)
        assertEquals(5L, manifest.entries.single().size)
        assertEquals(crcHex("hello".toByteArray()), manifest.entries.single().crc)
    }

    @Test
    fun parse_invalidJson_null() {
        assertNull(parseManifest("{not json"))
    }

    @Test
    fun parse_wrongVersion_null() {
        val zip = zipBytes(mapOf("shared/a.txt" to "x".toByteArray()))
        val json = manifestJson(zip, mapOf("shared/a.txt" to "x".toByteArray()))
            .replace("\"version\":1", "\"version\":2")
        assertNull(parseManifest(json))
    }

    @Test
    fun parse_emptyEntries_null() {
        val zip = zipBytes(mapOf("shared/a.txt" to "x".toByteArray()))
        val json = """{"version":1,"zipSha256":"${sha256Hex(zip)}","preserve":[],"entries":[]}"""
        assertNull(parseManifest(json))
    }

    // ---- 差量行为 ----

    @Test
    fun unchanged_skipped_mtimeUntouched() {
        runWithTempDir { dest ->
            val content = "hello".toByteArray()
            val entries = mapOf("shared/a.txt" to content)
            val zip = zipBytes(entries)
            val target = File(dest, "shared/a.txt")
            target.parentFile.mkdirs()
            target.writeBytes(content)
            target.setLastModified(1000L)
            assertEquals(
                DifferentialResult.OK,
                runDifferential(zip, entries, dest),
            )
            assertEquals(1000L, target.lastModified())
            assertEquals("hello", target.readText())
        }
    }

    @Test
    fun changed_overwritten_and_missing_extracted() {
        runWithTempDir { dest ->
            val entries = mapOf(
                "shared/a.txt" to "new".toByteArray(),
                "shared/b.txt" to "bee".toByteArray(),
            )
            val zip = zipBytes(entries)
            val target = File(dest, "shared/a.txt")
            target.parentFile.mkdirs()
            target.writeText("old-different-length-content")
            assertEquals(
                DifferentialResult.OK,
                runDifferential(zip, entries, dest),
            )
            assertEquals("new", target.readText())
            assertEquals("bee", File(dest, "shared/b.txt").readText())
        }
    }

    @Test
    fun surplus_pruned_onlySharedAndModel() {
        runWithTempDir { dest ->
            val entries = mapOf("shared/a.txt" to "a".toByteArray())
            val zip = zipBytes(entries)
            File(dest, "shared/stale.txt").apply { parentFile.mkdirs(); writeText("s") }
            File(dest, "model/stale2.txt").apply { parentFile.mkdirs(); writeText("s") }
            File(dest, "user/keep.txt").apply { parentFile.mkdirs(); writeText("k") }
            File(dest, "other.txt").writeText("k")
            assertEquals(
                DifferentialResult.OK,
                runDifferential(zip, entries, dest),
            )
            assertFalse(File(dest, "shared/stale.txt").exists())
            assertFalse(File(dest, "model/stale2.txt").exists())
            assertEquals("k", File(dest, "user/keep.txt").readText())
            assertEquals("k", File(dest, "other.txt").readText())
        }
    }

    @Test
    fun preserved_neverOverwritten_butFilledWhenMissing() {
        runWithTempDir { dest ->
            val customPath = "shared/opencc/wanxiang/Custom_STPhrases.txt"
            val entries = mapOf(
                customPath to "builtin".toByteArray(),
                "shared/a.txt" to "a".toByteArray(),
            )
            val zip = zipBytes(entries)
            val preserve = listOf("shared/opencc/wanxiang/Custom_")
            val target = File(dest, customPath)
            target.parentFile.mkdirs()
            target.writeText("user-custom")
            assertEquals(
                DifferentialResult.OK,
                runDifferential(zip, entries, dest, preserve),
            )
            assertEquals("user-custom", target.readText())
            // 填空：删掉后重跑，应按包内内容补齐
            target.delete()
            assertEquals(
                DifferentialResult.OK,
                runDifferential(zip, entries, dest, preserve),
            )
            assertEquals("builtin", target.readText())
        }
    }

    @Test
    fun preserved_surplus_neverPruned() {
        runWithTempDir { dest ->
            val entries = mapOf("shared/a.txt" to "a".toByteArray())
            val zip = zipBytes(entries)
            val mine = File(dest, "shared/opencc/wanxiang/Custom_Mine.txt")
            mine.parentFile.mkdirs()
            mine.writeText("mine")
            assertEquals(
                DifferentialResult.OK,
                runDifferential(
                    zip, entries, dest,
                    preserve = listOf("shared/opencc/wanxiang/Custom_"),
                ),
            )
            assertEquals("mine", mine.readText())
        }
    }

    @Test
    fun staleManifest_newFileExtractedAndNotPruned() {
        runWithTempDir { dest ->
            // 模拟清单过期：清单只列 a.txt，zip 里多了 b.txt（新包新增）。
            val zipEntries = mapOf(
                "shared/a.txt" to "a".toByteArray(),
                "shared/b.txt" to "new-file".toByteArray(),
            )
            val zip = zipBytes(zipEntries)
            val manifest = parseManifest(
                manifestJson(zip, mapOf("shared/a.txt" to "a".toByteArray())),
            )!!
            File(dest, "shared/stale.txt").apply { parentFile.mkdirs(); writeText("s") }
            assertEquals(
                DifferentialResult.OK,
                extractDifferential(ByteArrayInputStream(zip), dest, manifest),
            )
            // 新文件：清单外但流内——已解压且修剪放过。
            assertEquals("new-file", File(dest, "shared/b.txt").readText())
            // 真残留：清单外且流外——照常修剪。
            assertFalse(File(dest, "shared/stale.txt").exists())
        }
    }

    @Test
    fun blockedEntry_returnsFailedAndSkipsPrune() {
        runWithTempDir { dest ->
            val entries = mapOf("shared/a.txt" to "a".toByteArray())
            val zip = zipBytes(entries)
            val blocked = File(dest, "shared/a.txt")
            assertTrue(blocked.mkdirs())
            File(blocked, "keep.txt").writeText("old")
            File(dest, "shared/stale.txt").apply { writeText("s") }
            assertEquals(
                DifferentialResult.FAILED,
                runDifferential(zip, entries, dest),
            )
            assertTrue(File(dest, "shared/stale.txt").exists())
        }
    }
}
