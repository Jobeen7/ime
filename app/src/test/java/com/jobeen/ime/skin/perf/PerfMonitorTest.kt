package com.jobeen.ime.skin.perf

import java.io.File
import java.nio.file.Files
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 键盘性能埋点（2026-10-29 item-1 B）：聚合、配对、落盘。 */
class PerfMonitorTest {

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
