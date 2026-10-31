package com.jobeen.ime.skin.perf

import java.io.File
import java.nio.file.Files
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 键盘性能埋点（2026-10-29 item-1 B）：聚合、配对、落盘。 */
class PerfMonitorTest {

    // 门控是跨测试类的静态状态：每个测试结束都复位，防止 gate-on 漏到别的测试类。
    @After
    fun tearDown() {
        PerfMonitor.resetForTest()
    }

    @Test
    fun aggregatesTrackCountAvgMax() {
        PerfMonitor.resetForTest()
        PerfMonitor.record(PerfMonitor.Event.SKIN_APPLY, 100)
        PerfMonitor.record(PerfMonitor.Event.SKIN_APPLY, 200)
        PerfMonitor.record(PerfMonitor.Event.SKIN_APPLY, 300)
        val agg = PerfMonitor.aggregates()[PerfMonitor.Event.SKIN_APPLY]!!
        assertEquals(3L, agg.count)
        assertEquals(200.0, agg.avg, 0.001)
        assertEquals(300L, agg.max)
    }

    @Test
    fun keyToCommitPairsFirstCommitWithinWindow() {
        PerfMonitor.resetForTest()
        var now = 1000L
        PerfMonitor.clockMs = { now }
        PerfMonitor.noteKeyPress()
        now = 1150L
        PerfMonitor.noteCommit()
        val agg = PerfMonitor.aggregates()[PerfMonitor.Event.KEY_TO_COMMIT]!!
        assertEquals(1L, agg.count)
        assertEquals(150L, agg.max)
        // 无新按键的提交不记
        now = 1200L
        PerfMonitor.noteCommit()
        assertEquals(1L, PerfMonitor.aggregates()[PerfMonitor.Event.KEY_TO_COMMIT]!!.count)
    }

    @Test
    fun staleKeyPressNotPaired() {
        PerfMonitor.resetForTest()
        var now = 1000L
        PerfMonitor.clockMs = { now }
        PerfMonitor.noteKeyPress()
        now = 1000L + PerfMonitor.KEY_COMMIT_WINDOW_MS + 1L
        PerfMonitor.noteCommit()
        assertNull(PerfMonitor.aggregates()[PerfMonitor.Event.KEY_TO_COMMIT])
    }

    @Test
    fun coldStartRecordedOnce() {
        PerfMonitor.resetForTest()
        var now = 1500L
        PerfMonitor.clockMs = { now }
        PerfMonitor.noteProcessStart(500L)
        PerfMonitor.noteFirstFrame()
        PerfMonitor.noteFirstFrame()
        val agg = PerfMonitor.aggregates()[PerfMonitor.Event.COLD_START]!!
        assertEquals(1L, agg.count)
        assertEquals(1000L, agg.max)
    }

    @Test
    fun slowFrameThreshold() {
        PerfMonitor.resetForTest()
        PerfMonitor.noteFrameInterval(50L)
        assertNull(PerfMonitor.aggregates()[PerfMonitor.Event.SLOW_FRAME])
        PerfMonitor.noteFrameInterval(150L)
        assertEquals(1L, PerfMonitor.aggregates()[PerfMonitor.Event.SLOW_FRAME]!!.count)
    }

    // ---- P4：诊断门控与分段探针 ----

    @Test
    fun gateOff_probes_recordNothing() {
        PerfMonitor.resetForTest()
        assertEquals(-1L, PerfMonitor.stageStart())
        PerfMonitor.noteStage(PerfMonitor.Event.KEY_ENGINE, -1L)
        val out = PerfMonitor.measureStage(PerfMonitor.Event.KEY_ENGINE) { "ok" }
        assertEquals("ok", out)
        PerfMonitor.noteStartupStep("setupEngine", 321L, true)
        assertNull(PerfMonitor.aggregates()[PerfMonitor.Event.KEY_ENGINE])
        assertNull(PerfMonitor.aggregates()[PerfMonitor.Event.STARTUP_STEP])
    }

    @Test
    fun gateOff_measureStage_propagatesExceptionWithoutRecording() {
        PerfMonitor.resetForTest()
        var threw = false
        try {
            PerfMonitor.measureStage(PerfMonitor.Event.KEY_ENGINE) { throw IllegalStateException("x") }
        } catch (e: IllegalStateException) {
            threw = true
        }
        assertTrue(threw)
        assertNull(PerfMonitor.aggregates()[PerfMonitor.Event.KEY_ENGINE])
    }

    @Test
    fun gateOn_measureStage_recordsDuration() {
        PerfMonitor.resetForTest()
        var now = 1000L
        PerfMonitor.clockMs = { now }
        PerfMonitor.setDiagnosticEnabled(true)
        val out = PerfMonitor.measureStage(PerfMonitor.Event.KEY_ENGINE) {
            now = 1140L
            "ok"
        }
        assertEquals("ok", out)
        val agg = PerfMonitor.aggregates()[PerfMonitor.Event.KEY_ENGINE]!!
        assertEquals(1L, agg.count)
        assertEquals(140L, agg.max)
    }

    @Test
    fun gateOn_measureStage_recordsEvenOnThrow() {
        PerfMonitor.resetForTest()
        var now = 2000L
        PerfMonitor.clockMs = { now }
        PerfMonitor.setDiagnosticEnabled(true)
        try {
            PerfMonitor.measureStage(PerfMonitor.Event.KEY_ENGINE) {
                now = 2050L
                throw IllegalStateException("x")
            }
        } catch (ignored: IllegalStateException) {
        }
        assertEquals(50L, PerfMonitor.aggregates()[PerfMonitor.Event.KEY_ENGINE]!!.max)
    }

    @Test
    fun gateOn_noteStage_recordsDuration() {
        PerfMonitor.resetForTest()
        var now = 3000L
        PerfMonitor.clockMs = { now }
        PerfMonitor.setDiagnosticEnabled(true)
        val start = PerfMonitor.stageStart()
        now = 3075L
        PerfMonitor.noteStage(PerfMonitor.Event.KEY_DISPATCH, start)
        assertEquals(75L, PerfMonitor.aggregates()[PerfMonitor.Event.KEY_DISPATCH]!!.max)
    }

    @Test
    fun gateToggle_stopsRecording() {
        PerfMonitor.resetForTest()
        var now = 4000L
        PerfMonitor.clockMs = { now }
        PerfMonitor.setDiagnosticEnabled(true)
        PerfMonitor.measureStage(PerfMonitor.Event.KEY_COMMIT) { now = 4010L }
        PerfMonitor.setDiagnosticEnabled(false)
        PerfMonitor.measureStage(PerfMonitor.Event.KEY_COMMIT) { now = 4999L }
        assertEquals(1L, PerfMonitor.aggregates()[PerfMonitor.Event.KEY_COMMIT]!!.count)
    }

    @Test
    fun gateOn_startupStep_recordsWithMeta() {
        PerfMonitor.resetForTest()
        val root = Files.createTempDirectory("perf").toFile()
        try {
            PerfMonitor.configure(root)
            PerfMonitor.setDiagnosticEnabled(true)
            PerfMonitor.noteStartupStep("setupEngine", 321L, true)
            PerfMonitor.noteStartupStep("setupEngine", 50L, false)
            val agg = PerfMonitor.aggregates()[PerfMonitor.Event.STARTUP_STEP]!!
            assertEquals(2L, agg.count)
            assertEquals(321L, agg.max)
            PerfMonitor.flush()
            val dir = File(root, "Logs/performance")
            val lines = dir.listFiles { f -> f.name.endsWith(".jsonl") }!!
                .single().readText().trim().lines()
            assertEquals(2, lines.size)
            val first = Json.parseToJsonElement(lines[0]).jsonObject
            assertEquals("STARTUP_STEP", first["event"]!!.jsonPrimitive.content)
            assertEquals(321L, first["value"]!!.jsonPrimitive.content.toLong())
            assertEquals("setupEngine", first["meta"]!!.jsonObject["step"]!!.jsonPrimitive.content)
            assertEquals("true", first["meta"]!!.jsonObject["ok"]!!.jsonPrimitive.content)
            val second = Json.parseToJsonElement(lines[1]).jsonObject
            assertEquals("false", second["meta"]!!.jsonObject["ok"]!!.jsonPrimitive.content)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun gateOn_watchdogFactory_startStop() {
        PerfMonitor.resetForTest()
        val posts = mutableListOf<Runnable>()
        val removes = mutableListOf<Runnable>()
        PerfMonitor.watchdogFactory = { onLag ->
            MainThreadWatchdog(
                post = { r -> posts.add(r) },
                remove = { r -> removes.add(r) },
                clockMs = { 0L },
                onLag = onLag,
            )
        }
        PerfMonitor.setDiagnosticEnabled(true)
        assertEquals(1, posts.size)
        PerfMonitor.setDiagnosticEnabled(true)
        assertEquals(1, posts.size)
        PerfMonitor.setDiagnosticEnabled(false)
        assertEquals(1, removes.size)
        assertTrue(removes.single() === posts.single())
        PerfMonitor.setDiagnosticEnabled(true)
        assertEquals(2, posts.size)
        assertFalse(removes.last() === posts.last())
    }

    @Test
    fun jsonlWrittenWithEventAndMetaOnly() {
        PerfMonitor.resetForTest()
        val root = Files.createTempDirectory("perf").toFile()
        try {
            PerfMonitor.configure(root)
            PerfMonitor.record(
                PerfMonitor.Event.SKIN_APPLY, 123,
                mapOf("skinId" to "skin-1"),
            )
            PerfMonitor.flush()
            val dir = File(root, "Logs/performance")
            val files = dir.listFiles { f -> f.name.endsWith(".jsonl") }!!
            assertEquals(1, files.size)
            val parsed = Json.parseToJsonElement(files.single().readText().trim()).jsonObject
            assertEquals("SKIN_APPLY", parsed["event"]!!.jsonPrimitive.content)
            assertEquals(123L, parsed["value"]!!.jsonPrimitive.content.toLong())
            assertEquals(
                "skin-1",
                parsed["meta"]!!.jsonObject["skinId"]!!.jsonPrimitive.content,
            )
        } finally {
            root.deleteRecursively()
        }
    }
}
