// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.ui.screen

import com.jobeen.ime.ui.SingleFlightClaim
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * ui 审计修复（2026-11-12）的可测部分：单飞守门、拖拽态、忙计数、
 * 日志跟随判定。按状态空间分区枚举：每个修复点覆盖正常／边界／
 * 取消／并发回归态。Activity／Compose 接线本身不在 JVM 单测范围。
 */
class UiAuditFixesTest {

    // ── U-S1：SingleFlightClaim ──

    @Test
    fun claim_firstWins_secondLoses() {
        val claim = SingleFlightClaim()
        assertTrue(claim.tryClaim())
        assertFalse(claim.tryClaim())
        assertFalse(claim.tryClaim())
    }

    @Test
    fun claim_release_reclaims() {
        val claim = SingleFlightClaim()
        assertTrue(claim.tryClaim())
        claim.release()
        assertTrue(claim.tryClaim())
    }

    @Test
    fun claim_releaseWithoutClaim_isIdempotent() {
        val claim = SingleFlightClaim()
        claim.release()
        claim.release()
        assertTrue(claim.tryClaim())
    }

    @Test
    fun claim_concurrentRacers_exactlyOneWins() {
        val claim = SingleFlightClaim()
        val threads = 8
        val ready = CountDownLatch(threads)
        val go = CountDownLatch(1)
        val wins = AtomicInteger(0)
        val pool = Executors.newFixedThreadPool(threads)
        try {
            repeat(threads) {
                pool.submit {
                    ready.countDown()
                    go.await()
                    if (claim.tryClaim()) wins.incrementAndGet()
                }
            }
            ready.await()
            go.countDown()
            pool.shutdown()
            while (!pool.isTerminated) Thread.sleep(10)
        } finally {
            pool.shutdownNow()
        }
        assertEquals(1, wins.get())
    }

    // ── U-S7：DragReorderState ──

    private fun dragState(itemHeight: Float = 100f): DragReorderState =
        DragReorderState().also { it.itemHeight = itemHeight }

    @Test
    fun drag_start_setsIndexAndZeroesOffset() {
        val state = dragState()
        state.onDragStart(2)
        assertEquals(2, state.dragIndex)
        assertEquals(0f, state.dragOffset, 0f)
        assertTrue(state.isDragging)
    }

    @Test
    fun drag_restart_resetsOffset() {
        val state = dragState()
        state.onDragStart(1)
        state.onDrag(80f)
        state.onDragStart(3)
        assertEquals(3, state.dragIndex)
        assertEquals(0f, state.dragOffset, 0f)
    }

    @Test
    fun drag_withoutStart_ignored() {
        val state = dragState()
        state.onDrag(50f)
        assertEquals(-1, state.dragIndex)
        assertEquals(0f, state.dragOffset, 0f)
        assertNull(state.onDragEnd(5))
    }

    @Test
    fun dragEnd_moveDownOneRow() {
        val state = dragState()
        state.onDragStart(1)
        state.onDrag(120f)
        assertEquals(1 to 2, state.onDragEnd(5))
        assertEquals(-1, state.dragIndex)
        assertEquals(0f, state.dragOffset, 0f)
    }

    @Test
    fun dragEnd_moveUpTwoRows() {
        val state = dragState()
        state.onDragStart(3)
        state.onDrag(-190f)
        assertEquals(3 to 1, state.onDragEnd(5))
    }

    @Test
    fun dragEnd_halfRowRoundsToNearestRow() {
        val state = dragState()
        state.onDragStart(1)
        state.onDrag(49f)
        assertNull(state.onDragEnd(5))
        state.onDragStart(1)
        state.onDrag(50f)
        assertEquals(1 to 2, state.onDragEnd(5))
    }

    @Test
    fun dragEnd_clampsToFirstAndLast() {
        val state = dragState()
        state.onDragStart(1)
        state.onDrag(-500f)
        assertEquals(1 to 0, state.onDragEnd(5))
        state.onDragStart(3)
        state.onDrag(500f)
        assertEquals(3 to 4, state.onDragEnd(5))
    }

    @Test
    fun dragEnd_noMove_returnsNullAndResets() {
        val state = dragState()
        state.onDragStart(2)
        state.onDrag(10f)
        assertNull(state.onDragEnd(5))
        assertEquals(-1, state.dragIndex)
        assertFalse(state.isDragging)
    }

    @Test
    fun dragEnd_withoutStart_returnsNull() {
        val state = dragState()
        assertNull(state.onDragEnd(5))
    }

    @Test
    fun dragEnd_zeroItemHeight_noMoveAndResets() {
        val state = dragState(itemHeight = 0f)
        state.onDragStart(1)
        state.onDrag(300f)
        assertNull(state.onDragEnd(5))
        assertEquals(-1, state.dragIndex)
        assertEquals(0f, state.dragOffset, 0f)
    }

    @Test
    fun dragCancel_afterPartialDrag_resetsWithoutMove() {
        val state = dragState()
        state.onDragStart(1)
        state.onDrag(120f)
        state.onDragCancel()
        assertEquals(-1, state.dragIndex)
        assertEquals(0f, state.dragOffset, 0f)
        assertFalse(state.isDragging)
        // 取消后结束不再产生移动（取消不是“落点在原地”的结束）。
        assertNull(state.onDragEnd(5))
    }

    @Test
    fun dragCancel_withoutStart_isNoop() {
        val state = dragState()
        state.onDragCancel()
        assertEquals(-1, state.dragIndex)
        assertEquals(0f, state.dragOffset, 0f)
    }

    @Test
    fun dragEnd_singleItemList_neverMoves() {
        val state = dragState()
        state.onDragStart(0)
        state.onDrag(400f)
        assertNull(state.onDragEnd(1))
    }

    // ── U-M8：BusyCounter ──

    @Test
    fun busyCounter_initiallyIdle() {
        val counter = BusyCounter()
        assertEquals(0, counter.count)
        assertFalse(counter.isBusy)
    }

    @Test
    fun busyCounter_oneAcquire_isBusy() {
        val counter = BusyCounter()
        counter.acquire()
        assertEquals(1, counter.count)
        assertTrue(counter.isBusy)
    }

    @Test
    fun busyCounter_firstRelease_keepsBusyWhileSecondRuns() {
        // 旧 Boolean 实现的回归态：先完成的任务把后完成者的忙态提前清零。
        val counter = BusyCounter()
        counter.acquire()
        counter.acquire()
        counter.release()
        assertEquals(1, counter.count)
        assertTrue(counter.isBusy)
        counter.release()
        assertEquals(0, counter.count)
        assertFalse(counter.isBusy)
    }

    @Test
    fun busyCounter_extraRelease_neverGoesNegative() {
        val counter = BusyCounter()
        counter.release()
        counter.acquire()
        counter.release()
        counter.release()
        assertEquals(0, counter.count)
        assertFalse(counter.isBusy)
    }

    // 注：不测多线程 acquire/release——BusyCounter 持 Compose 快照态，
    // 调用点只在主线程（runBusy 的同步抢占也在主线程）；SnapshotState
    // 的 count++/-- 本身不是原子操作，并发测是无效前提。

    // ── U-L2：shouldFollowTail ──

    @Test
    fun followTail_firstLayout_noVisibleRow_follows() {
        assertTrue(shouldFollowTail(null, 0))
        assertTrue(shouldFollowTail(null, 50))
    }

    @Test
    fun followTail_atOldTail_follows() {
        assertTrue(shouldFollowTail(49, 50))
        assertTrue(shouldFollowTail(9, 10))
    }

    @Test
    fun followTail_oneRowAboveTail_countsAsTail() {
        assertTrue(shouldFollowTail(48, 50))
    }

    @Test
    fun followTail_readingOldLogs_doesNotFollow() {
        assertFalse(shouldFollowTail(10, 50))
        assertFalse(shouldFollowTail(0, 50))
        assertFalse(shouldFollowTail(47, 50))
    }

    @Test
    fun followTail_emptyPreviousList_follows() {
        assertTrue(shouldFollowTail(0, 0))
    }
}
