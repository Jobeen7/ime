package com.jobeen.ime.skin.pkg

import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** zip 安全解包（定稿十一.1/11.2）。 */
class ZipSafeExtractorTest {

    private fun zipOf(entries: List<Pair<String, ByteArray>>): File {
        val file = File(Files.createTempDirectory("zip-safe").toFile(), "test.jime")
        ZipOutputStream(file.outputStream()).use { zip ->
            for ((name, bytes) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return file
    }

    private fun rejected(file: File): String {
        val result = ZipSafeExtractor.extract(file)
        assertTrue("expected Rejected, got $result", result is ZipSafeExtractor.Result.Rejected)
        return (result as ZipSafeExtractor.Result.Rejected).reason
    }

    @Test
    fun validZipExtracts() {
        val file = zipOf(listOf("a.txt" to "hello".encodeToByteArray()))
        val result = ZipSafeExtractor.extract(file)
        assertTrue(result is ZipSafeExtractor.Result.Ok)
        val files = (result as ZipSafeExtractor.Result.Ok).files
        assertEquals(1, files.size)
        assertEquals("hello", files.single().bytes.decodeToString())
    }

    @Test
    fun traversalRejected() {
        val reason = rejected(zipOf(listOf("../evil.txt" to byteArrayOf(1))))
        assertTrue(reason.contains("parent_ref"))
    }

    @Test
    fun backslashRejected() {
        val reason = rejected(zipOf(listOf("a\\b.txt" to byteArrayOf(1))))
        assertTrue(reason.contains("backslash"))
    }

    @Test
    fun absolutePathRejected() {
        val reason = rejected(zipOf(listOf("/etc/passwd" to byteArrayOf(1))))
        assertTrue(reason.contains("absolute"))
    }

    @Test
    fun nestedArchiveRejected() {
        val reason = rejected(zipOf(listOf("inner.jime" to byteArrayOf(1))))
        assertTrue(reason.contains("nested_archive"))
    }

    @Test
    fun duplicateNormalizedPathRejected() {
        val reason = rejected(
            zipOf(
                listOf(
                    "Assets/BG.png" to byteArrayOf(1),
                    "assets/bg.png" to byteArrayOf(2),
                ),
            ),
        )
        assertTrue(reason.contains("duplicate_path"))
    }

    @Test
    fun tooManyEntriesRejected() {
        val entries = (1..101).map { "f$it.txt" to byteArrayOf(1) }
        val reason = rejected(zipOf(entries))
        assertTrue(reason.contains("too_many_entries"))
    }

    @Test
    fun compressionBombRejected() {
        // 2MB 全零数据压缩后仅约 2KB，压缩比远超 100:1
        val reason = rejected(zipOf(listOf("bomb.bin" to ByteArray(2 * 1024 * 1024))))
        assertTrue(reason.contains("compression_ratio"))
    }
}
