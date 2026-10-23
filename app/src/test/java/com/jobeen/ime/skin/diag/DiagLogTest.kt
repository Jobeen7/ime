package com.jobeen.ime.skin.diag

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 诊断日志设施（详设十三章）：条目格式、DEBUG 门控、上下文净化、
 * 滚动保留上限。时钟固定，断言对时间戳只验结构不验具体值（免时区耦合）。
 */
class DiagLogTest {
    private val fixedClock = { 1_762_000_000_000L }

    private fun newTempDir(): File = Files.createTempDirectory("diag-test").toFile()

    private fun readAllLines(dir: File): List<String> =
        dir.listFiles { f -> DiagLogCore.isLogFileName(f.name) }
            ?.sortedBy { it.name }
            ?.flatMap { it.readLines() }
            ?: emptyList()

    @Test
    fun writesFormattedEntry() {
        val dir = newTempDir()
        val core = DiagLogCore(dir, clock = fixedClock)
        core.i("store", "import_done", mapOf("skinId" to "abc", "gen" to 3, "result" to "ok"))
        core.flush()
        core.close()

        val lines = readAllLines(dir)
        assertEquals(1, lines.size)
        assertTrue(
            lines[0].matches(
                Regex("""\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3} INFO \[store] import_done skinId=abc gen=3 result=ok"""),
            ),
        )
    }

    @Test
    fun debugDroppedUnlessEnabled() {
        val dir = newTempDir()
        var enabled = false
        val core = DiagLogCore(dir, debugEnabled = { enabled }, clock = fixedClock)
        core.d("store", "detail")
        core.i("store", "headline")
        enabled = true
        core.d("store", "detail_after_enable")
        core.flush()
        core.close()

        val lines = readAllLines(dir)
        assertEquals(2, lines.size)
        assertTrue(lines.none { it.contains("] detail ") || it.endsWith("] detail") })
        assertTrue(lines.any { it.contains("headline") })
        assertTrue(lines.any { it.contains("detail_after_enable") })
    }

    @Test
    fun throwableStackAppended() {
        val dir = newTempDir()
        val core = DiagLogCore(dir, clock = fixedClock)
        core.e("sync", "unit_failed", mapOf("unit" to "skin:x"), IllegalStateException("boom"))
        core.flush()
        core.close()

        val text = readAllLines(dir).joinToString("\n")
        assertTrue(text.contains("ERROR [sync] unit_failed unit=skin:x"))
        assertTrue(text.contains("java.lang.IllegalStateException: boom"))
    }

    @Test
    fun contextValuesSanitizedToSingleLine() {
        val dir = newTempDir()
        val core = DiagLogCore(dir, clock = fixedClock)
        core.i("store", "evt", mapOf("note" to "line1\nline2\r\nline3", "long" to "x".repeat(500)))
        core.flush()
        core.close()

        val lines = readAllLines(dir)
        assertEquals(1, lines.size)
        assertTrue(lines[0].contains("note=line1 line2  line3"))
        assertTrue(lines[0].contains("long=" + "x".repeat(256) + "…"))
    }

    @Test
    fun retentionPrunesOldestBeyondCaps() {
        val dir = newTempDir()
        // 小上限：单文件 300B、最多 3 个文件；固定时钟下同名靠序号递增区分
        val core = DiagLogCore(
            dir,
            clock = fixedClock,
            maxFiles = 3,
            maxTotalBytes = 100_000,
            maxFileBytes = 300,
        )
        repeat(60) { n -> core.i("store", "entry_$n", mapOf("payload" to "y".repeat(40))) }
        core.flush()
        core.close()

        val files = dir.listFiles { f -> DiagLogCore.isLogFileName(f.name) } ?: emptyArray()
        assertTrue("file count ${files.size} must be <= 3", files.size <= 3)
        // 最新条目仍在（剪枝只删最旧）
        assertTrue(readAllLines(dir).any { it.contains("entry_59") })
    }
}
