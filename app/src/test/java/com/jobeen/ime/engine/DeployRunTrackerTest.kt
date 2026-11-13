// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * DeployRunTracker 状态机（E-S1）：合成的 Deploy Finish 必须查本轮
 * 的实际结果，不能无条件报成功。
 *
 * 状态空间（runId / failedRunId / consumedRunId）：
 * - 从未部署：Finish 按成功（失败标志初值 -1 与轮次 0 不相等）。
 * - Start → Finish：成功。
 * - Start → Failure → Finish：失败；同一轮第二个 Finish 不再失败。
 * - Start → Failure → Start（新一轮）→ Finish：旧失败按轮次失效。
 * - 无 Start 的 Failure → Finish：失败（部署未及发 Start 即失败）。
 * - 同一轮两次 Failure → Finish：失败一次。
 */
class DeployRunTrackerTest {

    @Test
    fun finishWithoutAnyDeployIsSuccess() {
        val tracker = DeployRunTracker()
        assertFalse(tracker.consumeFinishFailed())
    }

    @Test
    fun startThenFinishIsSuccess() {
        val tracker = DeployRunTracker()
        tracker.onStart()
        assertFalse(tracker.consumeFinishFailed())
    }

    @Test
    fun failurePoisonsAllFinishesOfTheRound() {
        val tracker = DeployRunTracker()
        tracker.onStart()
        tracker.onFailure()
        assertTrue(tracker.consumeFinishFailed())
        // I-M1：失败毒化本轮全部 Finish，第二次 Finish 同样返回 true
        //（判定是轮次属性，不是“一次性票据”）。
        assertTrue("second Finish of the same round is poisoned too",
            tracker.consumeFinishFailed())
    }

    @Test
    fun failureExpiresWhenNewRoundStarts() {
        val tracker = DeployRunTracker()
        tracker.onStart()
        tracker.onFailure()
        tracker.onStart()
        assertFalse(tracker.consumeFinishFailed())
    }

    @Test
    fun failureWithoutStartPoisonsAllFinishes() {
        val tracker = DeployRunTracker()
        tracker.onFailure()
        assertTrue(tracker.consumeFinishFailed())
        // I-M1：同上，无 Start 的失败同样毒化本轮全部 Finish。
        assertTrue(tracker.consumeFinishFailed())
    }

    @Test
    fun doubleFailureInOneRoundPoisonsAllFinishes() {
        val tracker = DeployRunTracker()
        tracker.onStart()
        tracker.onFailure()
        tracker.onFailure()
        assertTrue(tracker.consumeFinishFailed())
        // I-M1：同上，双失败同样毒化本轮全部 Finish。
        assertTrue(tracker.consumeFinishFailed())
    }

    @Test
    fun successRoundAfterFailedRoundIsClean() {
        val tracker = DeployRunTracker()
        tracker.onStart()
        tracker.onFailure()
        assertTrue(tracker.consumeFinishFailed())
        tracker.onStart()
        assertFalse(tracker.consumeFinishFailed())
    }
}
