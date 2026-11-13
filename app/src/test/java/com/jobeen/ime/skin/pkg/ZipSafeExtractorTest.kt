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
    fun highRatioLegitimatePackageExtracts() {
        // WO42 E-1：压缩比规则已删——2MB 全零数据压缩后仅约 2KB、
        // 压缩比远超旧 100:1 上限，旧规则会误拒；绝对上限（单条 8MB／
        // 总量 40MB）才是内存上界，此包远未触线，应正常解包。
        val file = zipOf(listOf("bg.bin" to ByteArray(2 * 1024 * 1024)))
        val result = ZipSafeExtractor.extract(file)
        assertTrue("expected Ok, got $result", result is ZipSafeExtractor.Result.Ok)
        assertEquals(2 * 1024 * 1024, (result as ZipSafeExtractor.Result.Ok).files.single().bytes.size)
    }

    @Test
    fun entryTooLargeRejected() {
        // 单条目 9MB（> 8MB 单条上限）：压缩比规则已删，绝对上限仍挡。
        val reason = rejected(zipOf(listOf("big.bin" to ByteArray(9 * 1024 * 1024))))
        assertTrue(reason.contains("entry_too_large"))
    }

    @Test
    fun expandedTotalTooLargeRejected() {
        // 总量 6×7MB＝42MB（> 40MB 总量上限），单条均未超 8MB：
        // 压缩比规则已删，总量上限仍挡。
        val entries = (1..6).map { "f$it.bin" to ByteArray(7 * 1024 * 1024) }
        val reason = rejected(zipOf(entries))
        assertTrue(reason.contains("expanded_too_large"))
    }
}
