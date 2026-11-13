// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * DeployRunTracker 毒化口径（I-M1，2026-11-13 复查修正旧口径）：
 * 失败毒化本轮的**全部** Finish，直到下一轮 Start 为止——判定是
 * 轮次属性，不是“一次性票据”。旧口径“失败只毒化其后的第一个
 * Finish”之前提不成立：跳部署的 Reload 合成的第二个 Finish 跑的
 * 是完整收尾（含方案表刷新），磁盘产物可能是失败部署留下的半成品；
 * 若第二个 Finish 按成功处理，引擎会被重新标为 initialized=true
 * 并用半成品方案表工作。
 *
 * 既有测试冲突（按简报要求明说、不悄悄改）：
 * DeployRunTrackerTest 中 failurePoisonsOnlyFirstFinishOfTheRound、
 * failureWithoutStartStillPoisonsNextFinish（第二断言）、
 * doubleFailureInOneRoundPoisonsOnce（第二断言）三处钉死旧的一次性
 * 口径，新行为下失败；须由协调员按复查报告 I-M1 决议处理。
 * 另两测试 failureExpiresWhenNewRoundStarts、
 * successRoundAfterFailedRoundIsClean 不受影响。
 */
class DeployRunTrackerPoisonAllFinishesTest {

    @Test
    fun failurePoisonsAllFinishesOfTheRound() {
        val tracker = DeployRunTracker()
        tracker.onStart()
        tracker.onFailure()
        assertTrue("first Finish of the failed round", tracker.consumeFinishFailed())
        assertTrue("second Finish (skip-deploy Reload) of the same round",
            tracker.consumeFinishFailed())
        assertTrue("third Finish of the same round", tracker.consumeFinishFailed())
    }

    @Test
    fun failureWithoutStartPoisonsAllFinishes() {
        // 部署未及发 Start 即失败：无 Start 的 Failure 同样记在本轮。
        val tracker = DeployRunTracker()
        tracker.onFailure()
        assertTrue(tracker.consumeFinishFailed())
        assertTrue(tracker.consumeFinishFailed())
    }

    @Test
    fun doubleFailureInOneRoundStillPoisonsAllFinishes() {
        val tracker = DeployRunTracker()
        tracker.onStart()
        tracker.onFailure()
        tracker.onFailure()
        assertTrue(tracker.consumeFinishFailed())
        assertTrue(tracker.consumeFinishFailed())
    }

    @Test
    fun poisonExpiresWhenNewRoundStarts() {
        val tracker = DeployRunTracker()
        tracker.onStart()
        tracker.onFailure()
        assertTrue(tracker.consumeFinishFailed())
        tracker.onStart()
        assertFalse("new round after Start is clean", tracker.consumeFinishFailed())
    }

    @Test
    fun successRoundAfterFailedRoundIsClean() {
        val tracker = DeployRunTracker()
        tracker.onStart()
        tracker.onFailure()
        assertTrue(tracker.consumeFinishFailed())
        tracker.onStart()
        assertFalse(tracker.consumeFinishFailed())
        assertFalse(tracker.consumeFinishFailed())
    }
}
