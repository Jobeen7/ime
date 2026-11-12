// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.engine.rime.core

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * RimeDispatcher 重启语义（E-S2 的机制层）：一次 native 启动失败不得
 * 毒化 dispatcher——isRunning 须复位、后续 start() 仍能拉起；运行中
 * 的二次 start() 是空操作；stop() 后可再次 start()。
 *
 * 注意：dispatcher 内部执行线程是非守护线程，测试必须在 finally 里
 * stop()，否则挂住测试 JVM。
 */
class RimeDispatcherRestartTest {

    private class FakeController : RimeDispatcher.RimeController {
        val startups = AtomicInteger(0)
        val finalizes = AtomicInteger(0)
        val failNextStartup = AtomicBoolean(false)

        override fun nativeStartup() {
            startups.incrementAndGet()
            if (failNextStartup.getAndSet(false)) {
                throw RuntimeException("native startup boom")
            }
        }

        override fun nativeFinalize() {
            finalizes.incrementAndGet()
        }
    }

    private fun awaitTrue(timeoutMs: Long, cond: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!cond()) {
            if (System.currentTimeMillis() > deadline) {
                throw AssertionError("condition not met within ${timeoutMs}ms")
            }
            Thread.sleep(10)
        }
    }

    @Test
    fun failedStartupDoesNotPoisonDispatcher() {
        val controller = FakeController().apply { failNextStartup.set(true) }
        val failures = CountDownLatch(1)
        val dispatcher = RimeDispatcher(controller) { failures.countDown() }
        try {
            dispatcher.start()
            assertTrue("onStartupFailed must fire", failures.await(5, TimeUnit.SECONDS))
            assertEquals(1, controller.startups.get())
            dispatcher.start()
            awaitTrue(5_000) { controller.startups.get() >= 2 }
            assertEquals(2, controller.startups.get())
        } finally {
            dispatcher.stop()
        }
    }

    @Test
    fun doubleStartIsNoopAndStopThenStartRestarts() {
        val controller = FakeController()
        val dispatcher = RimeDispatcher(controller)
        try {
            dispatcher.start()
            awaitTrue(5_000) { controller.startups.get() >= 1 }
            dispatcher.start()
            Thread.sleep(300)
            assertEquals("second start() while running must be a no-op",
                1, controller.startups.get())
            dispatcher.stop()
            assertEquals("stop() must finalize exactly once", 1, controller.finalizes.get())
            dispatcher.start()
            awaitTrue(5_000) { controller.startups.get() >= 2 }
            assertEquals(2, controller.startups.get())
        } finally {
            dispatcher.stop()
        }
    }
}
