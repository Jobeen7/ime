// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.input.panel.component

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 候选网格代次门（WO38 E1-10/1）：状态空间分区——
 * 未版本化／首帧／新帧／同代次（追加与重显）／迟到旧帧／不回退。
 * 门本身是纯状态机；网格／面板的接线（show／updateCandidates
 * 的 revision 参数、State 的 revision 透传）无 Robolectric，
 * 以编译＋对抗自审覆盖，见报告。
 */
class Wo38CandidateGridRevisionTest {

    @Test
    fun unversioned_zero_alwaysApplies_withoutAdvancing() {
        val gate = CandidateGridRevisionGate()
        assertTrue(gate.tryApply(0L))
        assertEquals(0L, gate.lastAppliedRevision)
        assertTrue(gate.tryApply(-1L))
        assertEquals(0L, gate.lastAppliedRevision)
        // 未版本化帧不推进：随后首个版本化帧仍以 0 为基线
        assertTrue(gate.tryApply(1L))
        assertEquals(1L, gate.lastAppliedRevision)
    }

    @Test
    fun firstVersionedFrame_applies() {
        val gate = CandidateGridRevisionGate()
        assertTrue(gate.tryApply(3L))
        assertEquals(3L, gate.lastAppliedRevision)
    }

    @Test
    fun newerFrame_appliesAndAdvances() {
        val gate = CandidateGridRevisionGate()
        assertTrue(gate.tryApply(2L))
        assertTrue(gate.tryApply(5L))
        assertEquals(5L, gate.lastAppliedRevision)
    }

    @Test
    fun sameRevision_applies_appendAndReshowAreSameGeneration() {
        // 同代次＝同一批次的追加页合并／重显，不可误拦
        val gate = CandidateGridRevisionGate()
        assertTrue(gate.tryApply(4L))
        assertTrue(gate.tryApply(4L))
        assertTrue(gate.tryApply(4L))
        assertEquals(4L, gate.lastAppliedRevision)
    }

    @Test
    fun olderFrame_dropped_withoutMovingBaseline() {
        val gate = CandidateGridRevisionGate()
        assertTrue(gate.tryApply(5L))
        assertFalse(gate.tryApply(4L))
        assertFalse(gate.tryApply(1L))
        assertEquals(5L, gate.lastAppliedRevision)
        // 丢弃后新帧仍正常放行
        assertTrue(gate.tryApply(6L))
    }

    @Test
    fun predicate_matchesGate() {
        // 纯函数版与状态机版同规则：0 恒放行、同代次放行、旧帧丢弃
        assertTrue(shouldApplyCandidateGridFrame(0L, 5L))
        assertTrue(shouldApplyCandidateGridFrame(5L, 5L))
        assertTrue(shouldApplyCandidateGridFrame(6L, 5L))
        assertFalse(shouldApplyCandidateGridFrame(4L, 5L))
        assertTrue(shouldApplyCandidateGridFrame(1L, 0L))
    }

    @Test
    fun gate_neverRegresses_acrossInterleavedFrames() {
        val gate = CandidateGridRevisionGate()
        assertTrue(gate.tryApply(2L))
        assertFalse(gate.tryApply(1L))
        assertTrue(gate.tryApply(2L))
        assertTrue(gate.tryApply(3L))
        assertFalse(gate.tryApply(2L))
        assertEquals(3L, gate.lastAppliedRevision)
    }
}
