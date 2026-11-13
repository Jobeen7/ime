// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.input.stats

import com.jobeen.ime.input.keyboard.key.KeyboardAction
import java.util.Calendar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 输入事件快照（WO38 E1-10/6）：载体＋捕获口径——
 * 摘要与同点 snapshot 逐值一致／事件标识与方案透传／
 * 事件之间摘要只随记账推进／方案过滤快照只含该方案桶。
 */
class Wo38InputEventSnapshotTest {

    private fun wallMs(year: Int, month: Int, day: Int, hour: Int = 12): Long {
        val cal = Calendar.getInstance()
        cal.set(year, month - 1, day, hour, 0, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    private fun setup(nowWall: Long, nowClock: Long) {
        InputStats.resetForTest()
        var wall = nowWall
        var clock = nowClock
        InputStats.wallMs = { wall }
        InputStats.clockMs = { clock }
    }

    @Test
    fun capture_summaryMatchesSnapshotAtSamePoint() {
        setup(wallMs(2026, 11, 13), 1000L)
        InputStats.setScheme("wanxiang")
        InputStats.noteKeyPress(KeyboardAction.SpaceAction)
        InputStats.noteCommit("你好")
        val snap = captureInputEventSnapshot(42L, InputStats.Period.DAY, nowMs = 5000L)
        val direct = InputStats.snapshot(InputStats.Period.DAY)
        assertEquals(42L, snap.eventId)
        assertEquals(InputStats.Period.DAY, snap.period)
        assertNull(snap.scheme)
        assertEquals(5000L, snap.capturedAtMs)
        assertEquals(direct, snap.summary)
        assertEquals(1L, snap.summary.total.commits)
        assertEquals(2L, snap.summary.total.chars)
    }

    @Test
    fun capture_beforeAnyInput_summaryIsEmpty() {
        setup(wallMs(2026, 11, 13), 1000L)
        val snap = captureInputEventSnapshot(0L, InputStats.Period.DAY, nowMs = 100L)
        assertEquals(0L, snap.summary.total.commits)
        assertEquals(0L, snap.summary.total.chars)
        assertNull(snap.summary.avgSpeed)
        assertNull(snap.summary.accuracy)
    }

    @Test
    fun capture_betweenEvents_summaryAdvancesOnlyWithBookkeeping() {
        setup(wallMs(2026, 11, 13), 1000L)
        InputStats.noteCommit("你好")
        val first = captureInputEventSnapshot(1L, InputStats.Period.DAY, nowMs = 100L)
        // 同一时刻再捕获：摘要不变（不另算、不缓存旧值之外的量）
        val same = captureInputEventSnapshot(2L, InputStats.Period.DAY, nowMs = 100L)
        assertEquals(first.summary, same.summary)
        assertEquals(2L, same.eventId)
        // 再记一笔：下一次捕获的摘要推进
        InputStats.noteCommit("世界")
        val third = captureInputEventSnapshot(3L, InputStats.Period.DAY, nowMs = 200L)
        assertEquals(2L, third.summary.total.commits)
        assertEquals(4L, third.summary.total.chars)
        assertEquals(1L, first.summary.total.commits)
    }

    @Test
    fun capture_schemeFiltered_summaryOnlyHasThatScheme() {
        setup(wallMs(2026, 11, 13), 1000L)
        InputStats.setScheme("wanxiang")
        InputStats.noteCommit("你好")
        InputStats.setScheme("other")
        InputStats.noteCommit("世界世界")
        val snap = captureInputEventSnapshot(7L, InputStats.Period.DAY, scheme = "wanxiang", nowMs = 300L)
        assertEquals("wanxiang", snap.scheme)
        assertEquals(2L, snap.summary.total.chars)
        assertEquals(1L, snap.summary.total.commits)
        assertTrue(snap.summary.schemes.keys == setOf("wanxiang"))
        // 全方案快照作对照：两方案合计 6 字
        val all = captureInputEventSnapshot(8L, InputStats.Period.DAY, nowMs = 300L)
        assertEquals(6L, all.summary.total.chars)
    }
}
