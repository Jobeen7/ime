// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.engine

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 队列满时控制类动作/作业的异步补投顺序：补投协程若在多线程域上
 * 乱序起跑，后起跑的 send 会先进入通道发送队列、超车先起跑的
 * （通道发送队列本身是 FIFO，但起跑顺序已乱）。重发域限定单并发：
 * 任务按起跑顺序执行、挂起后按通道发送队列顺序恢复，合起来严格 FIFO。
 * 本测试用生产工厂建域，验证该机制本身（RimeEngine 本体需
 * Android Context，无法在 JVM 单测中实例化，故接线处只做代码核验）。
 */
class ResendDispatcherTest {

    @Test
    fun resendDispatcher_preservesLaunchOrderUnderFullChannel() = runBlocking {
        val scope = CoroutineScope(createResendDispatcher())
        try {
            // 零缓冲通道：每个 send 都挂起等接收，20 个补投按起跑顺序挂起。
            val channel = Channel<Int>(capacity = 0)
            val sent = (1..20).map { i -> scope.async { channel.send(i); i } }
            val received = (1..20).map { channel.receive() }
            assertEquals((1..20).toList(), received)
            sent.forEach { it.await() }
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun resendDispatcher_neverRunsConcurrently() = runBlocking {
        val scope = CoroutineScope(createResendDispatcher())
        try {
            val concurrent = AtomicInteger(0)
            val maxConcurrent = AtomicInteger(0)
            val jobs = (1..50).map {
                scope.launch {
                    val current = concurrent.incrementAndGet()
                    maxConcurrent.accumulateAndGet(current) { a, b -> maxOf(a, b) }
                    concurrent.decrementAndGet()
                }
            }
            jobs.forEach { it.join() }
            assertEquals(1, maxConcurrent.get())
        } finally {
            scope.cancel()
        }
    }

}
