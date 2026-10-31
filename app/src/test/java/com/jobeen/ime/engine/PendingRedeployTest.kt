// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 部署失败单发补跑的状态机：失败标记 → 下次键盘出现时恰好消费一次；
 * 补跑自身的失败不得续订（否则每次出现都重编），补跑结束后的新失败
 * 仍可再次标记。
 */
class PendingRedeployTest {

    @Test
    fun consume_exactlyOnce() {
        val pending = PendingRedeploy()
        pending.markFailed()
        assertTrue(pending.consumeForRetry())
        assertFalse(pending.consumeForRetry())
        assertFalse(pending.consumeForRetry())
    }

    @Test
    fun consume_whenIdle_isFalse() {
        val pending = PendingRedeploy()
        assertFalse(pending.consumeForRetry())
    }

    @Test
    fun markDuringRetry_suppressed() {
        val pending = PendingRedeploy()
        pending.markFailed()
        assertTrue(pending.consumeForRetry())
        // 补跑自身的失败（native Failure 处理器与作业 catch 均调 markFailed）
        // 不得续订：补跑结束时下次出现不再重编。
        pending.markFailed()
        pending.finishRetry()
        assertFalse(pending.consumeForRetry())
    }

    @Test
    fun markAfterRetry_rearms() {
        val pending = PendingRedeploy()
        pending.markFailed()
        assertTrue(pending.consumeForRetry())
        pending.finishRetry()
        // 补跑结束后的新失败是另一次 genuine 失败，仍可标记。
        pending.markFailed()
        assertTrue(pending.consumeForRetry())
    }

    @Test
    fun finishRetryWithoutConsume_isNoop() {
        val pending = PendingRedeploy()
        pending.finishRetry()
        assertFalse(pending.consumeForRetry())
    }
}
