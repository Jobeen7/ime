package com.jobeen.ime.skin.runtime

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 首帧闩等待回归测试（2026-10-28 修复轮：真机 first_render_failed 根因）。
 *
 * 旧实现在调用线程阻塞 latch.await(2s)，而窗口刷新 post 到主线程——
 * 主线程应用时自锁，首帧回调永远排不上队，每次都 2s 超时回滚。
 * 本测试用单线程执行器模拟主线程：等待在该线程上启动，首帧回调
 * 以 post 形式排队到同一线程；若等待阻塞调用线程，回调无法执行，
 * withTimeout 抛错（回归信号，而非挂死）；若等待已移到 IO 线程，
 * 回调立即执行、等待迅速返回 true。
 */
class FirstRenderLatchTest {

    @Test
    fun releasedFromCallingThread_doesNotBlockCaller() = runBlocking {
        val main = Executors.newSingleThreadExecutor()
        try {
            val mainDispatcher = main.asCoroutineDispatcher()
            val latch = CountDownLatch(1)
            val started = System.nanoTime()
            val result = withTimeout(4000) {
                val waiting = async(mainDispatcher) { awaitFirstDrawLatch(latch, 5000) }
                withContext(mainDispatcher) { latch.countDown() }
                waiting.await()
            }
            val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
            assertTrue(result)
            assertTrue("等待应远快于超时，实际 ${elapsedMs}ms", elapsedMs < 3000)
        } finally {
            main.shutdownNow()
        }
    }

    @Test
    fun neverReleased_returnsFalseAfterTimeout() = runBlocking {
        val started = System.nanoTime()
        val result = awaitFirstDrawLatch(CountDownLatch(1), 200)
        val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
        assertFalse(result)
        assertTrue("应等待约满超时，实际 ${elapsedMs}ms", elapsedMs in 150..2000)
    }
}
