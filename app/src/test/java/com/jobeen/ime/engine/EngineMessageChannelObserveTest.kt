// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.engine

import com.jobeen.ime.engine.data.EngineMessage
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
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * EngineMessageChannel.observe 逐条容错（E-M1）：单条 onMessage 抛错
 * 不得杀死整条引擎→UI 通道。旧行为：收集侧一次偶发异常让 collect
 * 抛错、launch 作业失败，UI 从此再也收不到 Commit／候选／组字／联想。
 */
class EngineMessageChannelObserveTest {

    @Test
    fun throwingConsumerDoesNotKillChannel() = runBlocking {
        val channel = EngineMessageChannel()
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val received = Collections.synchronizedList(mutableListOf<EngineMessage>())
        val job: Job = channel.observe(scope) { msg ->
            received.add(msg)
            if (msg is EngineMessage.Status && msg.isComposing) {
                throw RuntimeException("boom")
            }
        }
        try {
            withTimeout(2_000) { channel.subscriptionCount.first { it >= 1 } }
            channel.emit(EngineMessage.Status(isComposing = true))
            channel.emit(EngineMessage.Status(isComposing = false))
            channel.emit(EngineMessage.Commit("x"))
            withTimeout(2_000) { while (received.size < 3) delay(10) }
            assertEquals(
                listOf(
                    EngineMessage.Status(isComposing = true),
                    EngineMessage.Status(isComposing = false),
                    EngineMessage.Commit("x"),
                ),
                received.toList(),
            )
            assertTrue("channel job must stay alive after a consumer throw", job.isActive)
        } finally {
            job.cancel()
            scope.cancel()
        }
    }

    @Test
    fun throwingConsumerDoesNotLoseLaterMessages() = runBlocking {
        val channel = EngineMessageChannel()
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val received = Collections.synchronizedList(mutableListOf<EngineMessage>())
        val job: Job = channel.observe(scope) { msg ->
            received.add(msg)
            throw RuntimeException("always boom")
        }
        try {
            withTimeout(2_000) { channel.subscriptionCount.first { it >= 1 } }
            repeat(3) { channel.emit(EngineMessage.Status(isComposing = false)) }
            withTimeout(2_000) { while (received.size < 3) delay(10) }
            assertEquals(3, received.size)
            assertTrue(job.isActive)
        } finally {
            job.cancel()
            scope.cancel()
        }
    }
}
