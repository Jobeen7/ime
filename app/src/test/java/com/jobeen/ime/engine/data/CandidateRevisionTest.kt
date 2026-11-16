// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.engine.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 候选帧代次门（松烟模型，2026-11-16 取代 shouldApplyCandidateFrame
 * 的纯 revision 比较）：首屏取 restoreCandidates 的 requestId，追加页
 * 取批次号，未版本化（合成帧如清空、联想）缺省 0。消费侧先比代次，
 * 再比内容（P2 无变化丢弃）；追加页不走 setCandidates，版本号不与
 * 首屏混比（追加仍凭批次＋已加载条数守卫）。
 *
 * .2 回归（2026-11-16 debug 包不能打字）：引擎重建后 requestId 从头
 * 计，新帧 revision 小于窗口已应用值；旧门当迟到旧帧丢掉，浮动窗
 * 网格永远等不到首帧。本门显式代次：浮动窗解散调 newGeneration()，
 * 其余更小 revision 按新 epoch 首帧放行（通道同类保序＋发射门保证
 * 同一 epoch 内 revision 单调递增，更小值只可能是新引擎实例）。
 */
class CandidateRevisionTest {

    private fun cand(index: Int, text: String) =
        EngineMessage.Candidate(index = index, text = text)

    private fun frame(vararg texts: String) = texts.mapIndexed { i, t -> cand(i, t) }

    @Test
    fun defaultRevision_isZero_unversioned() {
        assertEquals(0L, EngineMessage.Candidates(emptyList(), 0, 0).revision)
    }

    @Test
    fun copy_stampsRevision() {
        val stamped = EngineMessage.Candidates(emptyList(), 0, 0).copy(revision = 7L)
        assertEquals(7L, stamped.revision)
    }

    @Test
    fun gate_firstFrame_applies() {
        assertTrue(CandidateFrameGate().shouldApply(1L, list = frame("wo", "我")))
    }

    @Test
    fun gate_newerFrame_applies() {
        val gate = CandidateFrameGate()
        assertTrue(gate.shouldApply(5L, list = frame("a")))
        assertTrue(gate.shouldApply(6L, list = frame("b")))
    }

    @Test
    fun gate_identicalRedelivery_dropped() {
        val gate = CandidateFrameGate()
        assertTrue(gate.shouldApply(5L, list = frame("wo", "我")))
        assertFalse(gate.shouldApply(5L, list = frame("wo", "我")))
    }

    @Test
    fun gate_sameRevision_differentPage_applies() {
        val gate = CandidateFrameGate()
        assertTrue(gate.shouldApply(5L, pageNo = 0, list = frame("a")))
        assertTrue(gate.shouldApply(5L, pageNo = 1, list = frame("a")))
    }

    @Test
    fun gate_sameRevision_contentChanged_applies() {
        val gate = CandidateFrameGate()
        assertTrue(gate.shouldApply(5L, list = frame("a")))
        assertTrue(gate.shouldApply(5L, list = frame("b")))
    }

    @Test
    fun gate_sameRevision_lastPageFlip_applies() {
        // 末页翻转（“下一页”消失）是同代次同内容帧：必须放行，否则
        // 末页钮态滞留——.2 回归用例之一。
        val gate = CandidateFrameGate()
        assertTrue(gate.shouldApply(5L, isLastPage = false, list = frame("a")))
        assertTrue(gate.shouldApply(5L, isLastPage = true, list = frame("a")))
    }

    @Test
    fun gate_unversioned_alwaysApplies_withoutAdvancing() {
        val gate = CandidateFrameGate()
        assertTrue(gate.shouldApply(5L, list = frame("a")))
        assertTrue(gate.shouldApply(0L, list = frame("b")))
        assertEquals(5L, gate.lastAppliedRevision)
        // 未版本化帧不推进：随后同代次 5 的重投仍按指纹判定。
        assertFalse(gate.shouldApply(5L, list = frame("a")))
    }

    @Test
    fun gate_smallerRevision_isNewEpoch_applies() {
        // 引擎重建首帧：新引擎 requestId 从头计（.2 回归用例）。
        val gate = CandidateFrameGate()
        assertTrue(gate.shouldApply(47L, list = frame("a")))
        assertTrue(gate.shouldApply(1L, list = frame("wo", "我")))
        assertEquals(1L, gate.lastAppliedRevision)
        // 新 epoch 内继续递增。
        assertTrue(gate.shouldApply(2L, list = frame("b")))
    }

    @Test
    fun gate_newGeneration_resetsBaseline() {
        // 浮动窗解散即新代次：复位后旧会话顶高的代次不再拦新首帧。
        val gate = CandidateFrameGate()
        assertTrue(gate.shouldApply(47L, list = frame("a")))
        gate.newGeneration()
        assertEquals(-1L, gate.lastAppliedRevision)
        assertTrue(gate.shouldApply(1L, list = frame("wo", "我")))
    }

    @Test
    fun gate_t9wo_redelivery_noFlicker() {
        // T9 "wo"：首帧放行，同代次同内容重投丢弃（不重绘、不闪）。
        val gate = CandidateFrameGate()
        val wo = frame("wo", "我", "喔")
        assertTrue(gate.shouldApply(1L, list = wo))
        assertFalse(gate.shouldApply(1L, list = wo))
        // 同代次翻页放行。
        assertTrue(gate.shouldApply(1L, pageNo = 1, list = wo))
    }

    @Test
    fun gate_clear_freshEmpty_applies() {
        // N5 清空路径带代次：新代次的空帧（清空）放行，候选栏清掉；
        // 同代次空帧再投递一次即丢弃（幂等，不重复清、不闪）。
        val gate = CandidateFrameGate()
        assertTrue(gate.shouldApply(6L, list = frame("a", "b")))
        assertTrue(gate.shouldApply(7L, list = emptyList()))
        assertEquals(7L, gate.lastAppliedRevision)
        assertFalse(gate.shouldApply(7L, list = emptyList()))
    }

    @Test
    fun gate_clear_newEpochEmpty_applies() {
        // N5：引擎重建后新 epoch 首帧即空帧（重建瞬间无候选），
        // 更小 revision 按新 epoch 放行并复位基线，不留旧候选。
        val gate = CandidateFrameGate()
        assertTrue(gate.shouldApply(47L, list = frame("a")))
        assertTrue(gate.shouldApply(1L, list = emptyList()))
        assertEquals(1L, gate.lastAppliedRevision)
    }
}
