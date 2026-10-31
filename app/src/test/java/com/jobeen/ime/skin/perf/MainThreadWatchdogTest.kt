package com.jobeen.ime.skin.perf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** P4 主线程阻塞看门狗：滞后上报、重投、停止语义（手动调度驱动）。 */
class MainThreadWatchdogTest {

    private class Harness(
        var now: Long = 0L,
        onLag: ((Long) -> Unit)? = null,
    ) {
        val posts = mutableListOf<Runnable>()
        val removes = mutableListOf<Runnable>()
        val lags = mutableListOf<Long>()
        val watchdog = MainThreadWatchdog(
            post = { r -> posts.add(r) },
            remove = { r -> removes.add(r) },
            clockMs = { now },
            onLag = onLag ?: { lag -> lags += lag },
        )

        fun runLastTick() {
            posts.last().run()
        }
    }

    @Test
    fun lagOverThreshold_reportsOnceAndReposts() {
        val h = Harness()
        h.watchdog.start()
        assertEquals(1, h.posts.size)
        h.now = 1500L
        h.runLastTick()
        assertEquals(listOf(1500L), h.lags)
        assertEquals(2, h.posts.size)
        h.now = 1600L
        h.runLastTick()
        assertEquals(listOf(1500L), h.lags)
        assertEquals(3, h.posts.size)
    }

    @Test
    fun lagAtOrUnderThreshold_noReport() {
        val h = Harness()
        h.watchdog.start()
        h.now = 1000L
        h.runLastTick()
        assertTrue(h.lags.isEmpty())
        assertEquals(2, h.posts.size)
        h.now = 1500L
        h.runLastTick()
        assertTrue(h.lags.isEmpty())
    }

    @Test
    fun stop_preventsTickWork() {
        val h = Harness()
        h.watchdog.start()
        val tick = h.posts.single()
        h.watchdog.stop()
        assertEquals(listOf(tick), h.removes)
        h.now = 5000L
        tick.run()
        assertTrue(h.lags.isEmpty())
        assertEquals(1, h.posts.size)
    }

    @Test
    fun stopFromOnLag_noRepost() {
        lateinit var h: Harness
        h = Harness(onLag = { lag ->
            h.lags.add(lag)
            h.watchdog.stop()
        })
        h.watchdog.start()
        h.now = 2000L
        h.runLastTick()
        assertEquals(listOf(2000L), h.lags)
        assertEquals(1, h.posts.size)
    }

    @Test
    fun doubleStart_postsOnce() {
        val h = Harness()
        h.watchdog.start()
        h.watchdog.start()
        assertEquals(1, h.posts.size)
    }
}
