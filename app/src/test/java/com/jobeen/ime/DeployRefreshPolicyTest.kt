// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime

import com.jobeen.ime.input.keyboard.window.DeployRefreshPolicy
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 缺陷 8 补齐策略的规格测试：断言均从“单飞本意不变、只补部署期丢失”
 * 推导，不从实现反推。
 * - 部署中早退 / 部署中被丢弃的刷新，必须在部署结束后补齐，且仅补齐一次；
 * - 正常无部署刷新行为不变；真正的并发重复刷新仍被单飞丢弃；
 * - 补齐尝试撞上在飞刷新时不得消费标志（补齐本身不能被单飞吃掉）；
 * - 补齐跑本身又遇部署中早退时只重记标志、不形成重跑链。
 */
class DeployRefreshPolicyTest {

    @Test
    fun normalRefreshWithoutDeploy_isUnchanged() {
        val policy = DeployRefreshPolicy()
        assertTrue(policy.tryStart(isDeploying = false))
        assertFalse(policy.isPending)
        policy.release()
        // 第二次正常刷新仍可启动，单飞权已释放。
        assertTrue(policy.tryStart(isDeploying = false))
        policy.markAligned()
        policy.release()
        assertFalse(policy.tryStartAlignment(isDeploying = false))
    }

    @Test
    fun concurrentDuplicateOutsideDeploy_isStillDroppedWithoutPending() {
        val policy = DeployRefreshPolicy()
        assertTrue(policy.tryStart(isDeploying = false))
        assertFalse(policy.tryStart(isDeploying = false))
        assertFalse(policy.isPending)
        policy.release()
        // 在飞刷新已完整跑完，部署外丢弃的那次无需补齐。
        assertFalse(policy.tryStartAlignment(isDeploying = false))
    }

    @Test
    fun earlyReturnDuringDeploy_alignsExactlyOnceAfterDeployEnds() {
        val policy = DeployRefreshPolicy()
        assertTrue(policy.tryStart(isDeploying = true))
        policy.markPending()
        policy.release()
        // 部署未结束前不补齐。
        assertFalse(policy.tryStartAlignment(isDeploying = true))
        assertTrue(policy.isPending)
        // 部署结束（成功 Finish 复位与失败复位走同一标志）后补齐一次。
        assertTrue(policy.tryStartAlignment(isDeploying = false))
        assertFalse(policy.isPending)
        policy.release()
        // 标志已消费，第二次补齐尝试不再触发。
        assertFalse(policy.tryStartAlignment(isDeploying = false))
    }

    @Test
    fun droppedFinishDuringDeploy_alignsOnceAfterDeployEnds() {
        val policy = DeployRefreshPolicy()
        // R1：部署中启动的早退刷新持有单飞权。
        assertTrue(policy.tryStart(isDeploying = true))
        // R2：Finish 到达时撞单飞被丢弃，且正值部署中，记待补齐。
        assertFalse(policy.tryStart(isDeploying = true))
        assertTrue(policy.isPending)
        // R1 早退收尾后、部署结束前仍不补齐。
        policy.release()
        assertFalse(policy.tryStartAlignment(isDeploying = true))
        // 部署结束后来一次，且仅一次。
        assertTrue(policy.tryStartAlignment(isDeploying = false))
        policy.release()
        assertFalse(policy.tryStartAlignment(isDeploying = false))
    }

    @Test
    fun alignmentAttemptWhileInFlight_doesNotConsumePending() {
        val policy = DeployRefreshPolicy()
        assertTrue(policy.tryStart(isDeploying = true))
        assertFalse(policy.tryStart(isDeploying = true))
        assertTrue(policy.isPending)
        // 部署结束信号到达时在飞刷新尚未收尾：补齐撞单飞，标志必须保留。
        assertFalse(policy.tryStartAlignment(isDeploying = false))
        assertTrue(policy.isPending)
        // 在飞收尾重试时补齐，且仅一次。
        policy.release()
        assertTrue(policy.tryStartAlignment(isDeploying = false))
        policy.release()
        assertFalse(policy.tryStartAlignment(isDeploying = false))
    }

    @Test
    fun fullUpdateAfterDeployEnds_clearsPendingWithoutRedundantAlignment() {
        val policy = DeployRefreshPolicy()
        assertTrue(policy.tryStart(isDeploying = true))
        policy.markPending()
        policy.release()
        // 部署结束后一次正常完整刷新已对齐 UI，待补齐不再欠账。
        assertTrue(policy.tryStart(isDeploying = false))
        policy.markAligned()
        policy.release()
        assertFalse(policy.tryStartAlignment(isDeploying = false))
    }

    @Test
    fun alignmentRunEarlyReturningAgain_onlyReMarksWithoutRetryChain() {
        val policy = DeployRefreshPolicy()
        assertTrue(policy.tryStart(isDeploying = true))
        policy.markPending()
        policy.release()
        assertTrue(policy.tryStartAlignment(isDeploying = false))
        // 补齐跑本身又遇部署中（新部署在读盘检查前启动）而早退：只重记标志。
        policy.markPending()
        policy.release()
        // 部署仍在跑时多次补齐尝试都不触发，不形成重跑链。
        assertFalse(policy.tryStartAlignment(isDeploying = true))
        assertFalse(policy.tryStartAlignment(isDeploying = true))
        assertTrue(policy.isPending)
        // 部署结束后来一次，且仅一次。
        assertTrue(policy.tryStartAlignment(isDeploying = false))
        policy.release()
        assertFalse(policy.tryStartAlignment(isDeploying = false))
    }
}
