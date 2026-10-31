// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.engine.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 候选帧版本号：首屏取 restoreCandidates 的 requestId，追加页取批次号，
 * 未版本化（合成帧如清空、联想）缺省 0。消费侧先比版本号（旧帧丢弃），
 * 再比内容（P2 无变化丢弃）；追加页不走 setCandidates，版本号不与
 * 首屏混比（追加仍凭批次＋已加载条数守卫）。
 */
class CandidateRevisionTest {

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
    fun shouldApply_firstFrame() {
        assertTrue(shouldApplyCandidateFrame(revision = 1L, lastAppliedRevision = -1L))
    }

    @Test
    fun shouldApply_newerFrame() {
        assertTrue(shouldApplyCandidateFrame(revision = 6L, lastAppliedRevision = 5L))
    }

    @Test
    fun shouldApply_duplicateFrame_dropped() {
        assertFalse(shouldApplyCandidateFrame(revision = 5L, lastAppliedRevision = 5L))
    }

    @Test
    fun shouldApply_staleFrame_dropped() {
        assertFalse(shouldApplyCandidateFrame(revision = 4L, lastAppliedRevision = 5L))
    }

    @Test
    fun shouldApply_unversioned_alwaysApplies() {
        assertTrue(shouldApplyCandidateFrame(revision = 0L, lastAppliedRevision = 5L))
        assertTrue(shouldApplyCandidateFrame(revision = 0L, lastAppliedRevision = -1L))
    }
}
