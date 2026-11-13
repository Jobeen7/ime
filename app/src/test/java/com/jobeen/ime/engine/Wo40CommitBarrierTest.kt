// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.engine

import com.jobeen.ime.engine.data.EngineMessage
import com.jobeen.ime.engine.rime.core.EngineMessageConverter
import com.jobeen.ime.engine.rime.core.RimeMessage
import java.util.Collections
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WO40 上屏落屏完成等待（CommitAndAct 竞态修法）：
 * 栅栏信标经 notificationQueue→messageChannel 与 Commit 同序投递，
 * 观察者按序处理到栅栏即证明 Commit 已落屏；等待有界超时、永不卡键，
 * 迟到栅栏不得完成下一轮等待。
 *
 * 覆盖边界（验收清单）：
 * - 落屏信号先于动作：通道内 Commit 在 CommitBarrier 之前到达同一观察者
 *   （channelDeliversCommitBeforeBarrier）；
 * - 超时放行：栅栏不到时 await 在有界时间内返回 false（gateTimeoutReturnsFalse）；
 * - 会话过期丢弃动作：复检谓词 EnterSession.isCurrent 由 EnterSessionTest
 *   覆盖（6 例：同字段同代次放行、字段变／代次进／输入结束皆丢弃），
 *   sendEnterJob 等待后的复检与入队复检是同一调用；端到端（等待中换框→
 *   无动作）须真机验收，见工作单已知差距；
 * - 非组字 PerformAction 路径不变：WO40 未动该分支（git diff 可证），
 *   决策仍由 EnterDecisionTest 覆盖。
 */
class Wo40CommitBarrierTest {

    @Test
    fun gateCompletesOnMatchingBarrier() = runBlocking {
        val gate = CommitBarrierGate()
        val id = gate.arm()
        gate.complete(id)
        assertTrue("matching barrier must complete the wait", gate.await(id, 500L))
    }

    @Test
    fun gateIgnoresWrongBarrierId() = runBlocking {
        val gate = CommitBarrierGate()
        val id = gate.arm()
        gate.complete(id + 1)
        assertFalse("foreign barrier must not complete the wait", gate.await(id, 100L))
    }

    @Test
    fun gateTimeoutReturnsFalseWithinBound() = runBlocking {
        val gate = CommitBarrierGate()
        val id = gate.arm()
        val start = System.nanoTime()
        val landed = gate.await(id, 300L)
        val elapsedMs = (System.nanoTime() - start) / 1_000_000
        assertFalse("no barrier must time out", landed)
        assertTrue("await must actually wait, not return instantly (elapsed=$elapsedMs)", elapsedMs >= 200)
        assertTrue("await must not exceed its bound by far (elapsed=$elapsedMs)", elapsedMs < 5_000)
    }

    @Test
    fun gateLateBarrierDoesNotCompleteNextWait() = runBlocking {
        val gate = CommitBarrierGate()
        val first = gate.arm()
        assertFalse("first wait must time out", gate.await(first, 50L))
        val second = gate.arm()
        gate.complete(first)
        assertFalse("late barrier from the timed-out round must not complete the next wait", gate.await(second, 50L))
        val third = gate.arm()
        gate.complete(third)
        assertTrue("complete before await must complete the wait", gate.await(third, 50L))
    }

    @Test
    fun gateIdsIncreaseFromOne() {
        val gate = CommitBarrierGate()
        val first = gate.arm()
        val second = gate.arm()
        assertTrue("ids start at 1", first >= 1L)
        assertTrue("ids increase", second > first)
    }

    @Test
    fun channelDeliversCommitBeforeBarrier() = runBlocking {
        val channel = EngineMessageChannel()
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val received = Collections.synchronizedList(mutableListOf<EngineMessage>())
        val job: Job = channel.observe(scope) { msg -> received.add(msg) }
        try {
            withTimeout(2_000) { channel.subscriptionCount.first { it >= 1 } }
            channel.emit(EngineMessage.Commit("x"))
            channel.emit(EngineMessage.CommitBarrier(7L))
            withTimeout(2_000) { while (received.size < 2) delay(10) }
            assertEquals(
                listOf(
                    EngineMessage.Commit("x"),
                    EngineMessage.CommitBarrier(7L),
                ),
                received.toList(),
            )
        } finally {
            job.cancel()
            scope.cancel()
        }
    }

    @Test
    fun converterMapsBarrier() {
        assertEquals(
            EngineMessage.CommitBarrier(42L),
            EngineMessageConverter.convert(RimeMessage.BarrierMessage(42L)),
        )
    }

    @Test
    fun nativeCreateBarrier() {
        assertEquals(
            RimeMessage.BarrierMessage(7L),
            RimeMessage.nativeCreate(
                RimeMessage.MessageType.Barrier.ordinal,
                arrayOf<Any>(7L),
            ),
        )
        assertEquals(
            RimeMessage.BarrierMessage(-1L),
            RimeMessage.nativeCreate(
                RimeMessage.MessageType.Barrier.ordinal,
                arrayOf<Any>(),
            ),
        )
        assertTrue(
            "unknown type code must degrade to UnknownMessage",
            RimeMessage.nativeCreate(999, arrayOf<Any>()) is RimeMessage.UnknownMessage,
        )
    }

    @Test
    fun existingOrdinalsAreStable() {
        assertEquals(0, RimeMessage.MessageType.Unknown.ordinal)
        assertEquals(1, RimeMessage.MessageType.Schema.ordinal)
        assertEquals(2, RimeMessage.MessageType.Option.ordinal)
        assertEquals(3, RimeMessage.MessageType.Deploy.ordinal)
        assertEquals(4, RimeMessage.MessageType.Commit.ordinal)
        assertEquals(5, RimeMessage.MessageType.InlinePreedit.ordinal)
        assertEquals(6, RimeMessage.MessageType.Composition.ordinal)
        assertEquals(7, RimeMessage.MessageType.Menu.ordinal)
        assertEquals(8, RimeMessage.MessageType.Status.ordinal)
        assertEquals(9, RimeMessage.MessageType.Candidate.ordinal)
        assertEquals(10, RimeMessage.MessageType.Key.ordinal)
        assertEquals(11, RimeMessage.MessageType.DynamicPreedit.ordinal)
        assertEquals("Barrier must be appended last", 12, RimeMessage.MessageType.Barrier.ordinal)
    }
}
