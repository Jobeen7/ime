package com.jobeen.ime.base

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * S4：有界崩溃日志（截断/轮转）+ 崩溃日志 + 循环熔断判定。
 * Native 信号处理器本身无法在 JVM 单测验证（见发布验证：编译 + dex grep），
 * 此处只测纯函数与文件语义层。
 */
class CrashBreakerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    // ---- 截断 ----

    @Test
    fun truncate_short_unchanged() {
        assertEquals("hello", truncateCrashText("hello", 100))
    }

    @Test
    fun truncate_long_cappedAndMarked() {
        val text = "x".repeat(300)
        val out = truncateCrashText(text, 100)
        assertTrue(out.startsWith("x".repeat(100)))
        assertTrue(out.contains("已截断"))
        assertEquals(100, out.substringBefore('\n').codePointCount(0, out.substringBefore('\n').length))
    }

    @Test
    fun truncate_defaultCap_isMaxChars() {
        val out = truncateCrashText("y".repeat(30_000))
        val head = out.substringBefore('\n')
        assertEquals(CrashTrap.MAX_CHARS, head.codePointCount(0, head.length))
        assertTrue(out.contains("已截断"))
    }

    @Test
    fun truncate_emoji_notSplit() {
        // 每个 emoji 占 1 个字素、2 个 char；按字素截断不得拆开代理对。
        val emoji = "\uD83D\uDE00"
        val out = truncateCrashText(emoji.repeat(200), 100)
        val head = out.substringBefore('\n')
        assertEquals(100, head.codePointCount(0, head.length))
        assertEquals(200, head.length)
    }

    // ---- 轮转 ----

    @Test
    fun rotate_keepsThree() {
        val dir = tmp.newFolder("crash")
        File(dir, "last_crash-proc.txt").writeText("new0")
        File(dir, "last_crash-proc.1.txt").writeText("old1")
        File(dir, "last_crash-proc.2.txt").writeText("old2")
        rotateCrashFiles(dir, "last_crash-proc.txt")
        File(dir, "last_crash-proc.txt").writeText("new")
        assertEquals("new", File(dir, "last_crash-proc.txt").readText())
        assertEquals("new0", File(dir, "last_crash-proc.1.txt").readText())
        assertEquals("old1", File(dir, "last_crash-proc.2.txt").readText())
        assertFalse(File(dir, "last_crash-proc.3.txt").exists())
    }

    @Test
    fun rotate_missingCurrent_shiftsBackupsOnly() {
        val dir = tmp.newFolder("crash")
        File(dir, "last_crash-proc.1.txt").writeText("old1")
        rotateCrashFiles(dir, "last_crash-proc.txt")
        assertFalse(File(dir, "last_crash-proc.txt").exists())
        assertEquals("old1", File(dir, "last_crash-proc.2.txt").readText())
        assertFalse(File(dir, "last_crash-proc.1.txt").exists())
    }

    // ---- 日志 ----

    @Test
    fun journal_countRecent_windowAndMalformed() {
        val journal = File(tmp.newFolder("crash"), "crash_journal.txt")
        val now = 1_000_000L
        appendCrashJournal(journal, now - 20_000, "java")
        appendCrashJournal(journal, now - 9_000, "java")
        appendCrashJournal(journal, now - 5_000, "native:11:0")
        appendCrashJournal(journal, now + 1_000, "java")
        journal.appendText("garbage line\n")
        journal.appendText("notanumber java\n")
        assertEquals(2, countRecentCrashes(journal, now))
    }

    @Test
    fun journal_prunesToMaxLines() {
        val journal = File(tmp.newFolder("crash"), "crash_journal.txt")
        for (i in 1..105) appendCrashJournal(journal, 1000L + i, "java", maxLines = 100)
        val lines = journal.readLines()
        assertEquals(100, lines.size)
        assertEquals("1006 java", lines.first())
        assertEquals("1105 java", lines.last())
    }

    // ---- 熔断 ----

    private fun gateFiles(): Pair<File, File> {
        val dir = tmp.newFolder("crash")
        return File(dir, JOURNAL_FILE_NAME) to File(dir, TRIP_FILE_NAME)
    }

    @Test
    fun gate_belowThreshold_notGated() {
        val (journal, trip) = gateFiles()
        val now = 5_000_000L
        appendCrashJournal(journal, now - 1000, "java")
        appendCrashJournal(journal, now - 2000, "java")
        assertFalse(crashBreakerGated(journal, trip, now))
        assertFalse(trip.exists())
    }

    @Test
    fun gate_atThreshold_gatedAndTrips() {
        val (journal, trip) = gateFiles()
        val now = 5_000_000L
        appendCrashJournal(journal, now - 1000, "java")
        appendCrashJournal(journal, now - 2000, "java")
        appendCrashJournal(journal, now - 3000, "native:11:0")
        assertTrue(crashBreakerGated(journal, trip, now))
        assertEquals(now.toString(), trip.readText())
    }

    @Test
    fun gate_freshTrip_gatedWithoutJournal() {
        val (journal, trip) = gateFiles()
        val now = 5_000_000L
        trip.writeText((now - 60_000).toString())
        assertTrue(crashBreakerGated(journal, trip, now))
        assertEquals((now - 60_000).toString(), trip.readText())
    }

    @Test
    fun gate_expiredTripCleanJournal_notGatedAndTripCleared() {
        val (journal, trip) = gateFiles()
        val now = 5_000_000L
        trip.writeText((now - 700_000).toString())
        assertFalse(crashBreakerGated(journal, trip, now))
        assertFalse(trip.exists())
    }

    @Test
    fun gate_expiredTripRecentJournal_regated() {
        val (journal, trip) = gateFiles()
        val now = 5_000_000L
        trip.writeText((now - 700_000).toString())
        appendCrashJournal(journal, now - 1000, "java")
        appendCrashJournal(journal, now - 2000, "java")
        appendCrashJournal(journal, now - 3000, "java")
        assertTrue(crashBreakerGated(journal, trip, now))
        assertEquals(now.toString(), trip.readText())
    }
}
