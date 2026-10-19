package com.jobeen.ime

import com.jobeen.ime.engine.rime.core.RimeDispatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * RimeDispatcher 派发契约测试（第九份报告 §2.12 + drain 取消语义）。
 *
 * 规格（从已核实行为推导，不是照实现抄）：
 * - 未运行（未 start / stop 已完成）时派发 → IllegalStateException，
 *   任务体永不执行；
 * - 运行中派发 → 任务被消费侧执行恰好一次，派发不报错；
 * - 任务已入队未被消费时 stop → 任务体不执行，其协程等待方以
 *   CancellationException 收尾（drain 的「取消 Job＋补 run」），不
 *   挂死、也不以其他异常收尾（v1.1.11.1 的教训：等待方从「挂死」
 *   被改成收到取消，这条语义不许回退）；
 * - 正在执行的任务不被 stop 误伤：正常跑完、结果照常交付；
 * - 派发与 stop 竞态下：**已执行的任务不得被上报为派发失败**
 *   （dispatch 抛错 ⇒ 该任务从未执行）。复查瞬间无法从外部冻结，
 *   故这条以不变量形式断言，而非构造单一时序。
 */
class RimeDispatcherDispatchContractTest {

    private class FakeController : RimeDispatcher.RimeController {
        val startupLatch = CountDownLatch(1)
        val finalizeLatch = CountDownLatch(1)

        override fun nativeStartup() {
            startupLatch.countDown()
        }

        override fun nativeFinalize() {
            finalizeLatch.countDown()
        }
    }

    private fun startedDispatcher(controller: FakeController): RimeDispatcher {
        val dispatcher = RimeDispatcher(controller)
        dispatcher.start()
        assertTrue(
            "dispatcher should start",
            controller.startupLatch.await(5, TimeUnit.SECONDS),
        )
        return dispatcher
    }

    @Test
    fun dispatchBeforeStart_throwsAndNeverRuns() {
        val dispatcher = RimeDispatcher(FakeController())
        val ran = AtomicBoolean(false)
        assertThrows(IllegalStateException::class.java) {
            runBlocking {
                withTimeout(5_000) {
                    withContext(dispatcher) { ran.set(true) }
                }
            }
        }
        assertFalse("task dispatched while stopped must never run", ran.get())
    }

    @Test
    fun dispatchWhileRunning_executesAndDeliversResult() {
        val controller = FakeController()
        val dispatcher = startedDispatcher(controller)
        try {
            val result = runBlocking {
                withTimeout(5_000) { withContext(dispatcher) { 42 } }
            }
            assertEquals(42, result)
        } finally {
            dispatcher.stop()
        }
    }

    @Test
    fun dispatchAfterStop_throwsAndNeverRuns() {
        val controller = FakeController()
        val dispatcher = startedDispatcher(controller)
        dispatcher.stop()
        assertTrue(
            "consumer loop should finalize on stop",
            controller.finalizeLatch.await(5, TimeUnit.SECONDS),
        )
        val ran = AtomicBoolean(false)
        assertThrows(IllegalStateException::class.java) {
            runBlocking {
                withTimeout(5_000) {
                    withContext(dispatcher) { ran.set(true) }
                }
            }
        }
        assertFalse("task dispatched after stop must never run", ran.get())
    }

    @Test
    fun queuedTaskAtStop_waiterCancelledAndBodyNeverRuns() {
        val controller = FakeController()
        val dispatcher = startedDispatcher(controller)
        val scope = CoroutineScope(SupervisorJob())
        val task1Started = CountDownLatch(1)
        val releaseTask1 = CountDownLatch(1)
        // 任务 1 占住消费循环（rime-main 单线程），任务 2 只能入队
        val task1 = scope.async(dispatcher) {
            task1Started.countDown()
            releaseTask1.await(10, TimeUnit.SECONDS)
            "done"
        }
        assertTrue(task1Started.await(5, TimeUnit.SECONDS))
        val task2BodyRan = AtomicBoolean(false)
        val task2 = scope.async(dispatcher) {
            task2BodyRan.set(true)
            "unreachable"
        }
        // 给任务 2 的派发落队列留出时间（派发在 async 启动时同步发生）
        Thread.sleep(300)

        val stopThread = thread { dispatcher.stop() }
        // 让 stop() 完成 CAS 并进入等锁阶段，再放行任务 1：消费循环
        // 在下一轮检查 isRunning 时退出，退出段排空把任务 2 取消
        Thread.sleep(300)
        releaseTask1.countDown()
        stopThread.join(10_000)
        assertFalse("stop() should return", stopThread.isAlive)

        runBlocking {
            // 正在执行的任务不被 stop 误伤：正常交付结果
            assertEquals("done", withTimeout(5_000) { task1.await() })
            // 已入队未消费的任务：等待方以取消收尾，任务体不执行
            try {
                withTimeout(5_000) { task2.await() }
                fail("queued task's waiter must complete with CancellationException")
            } catch (expected: CancellationException) {
                // 规格期望：取消语义，不是挂死、不是 IllegalStateException
            }
        }
        assertFalse("queued task body must never run", task2BodyRan.get())
    }

    @Test
    fun executedTaskIsNeverReportedAsDispatchFailure() {
        // 竞态不变量：多轮 start→并发派发+stop，逐任务断言
        // 「dispatch 抛了 IllegalStateException」与「任务体执行过」
        // 不同时成立（§2.12 的原始缺陷正是已执行却被报失败）
        repeat(20) { round ->
            val controller = FakeController()
            val dispatcher = RimeDispatcher(controller)
            dispatcher.start()
            assertTrue(controller.startupLatch.await(5, TimeUnit.SECONDS))

            val taskCount = 30
            val executed = Array(taskCount) { AtomicBoolean(false) }
            val threw = Array(taskCount) { AtomicBoolean(false) }
            val stopper = thread {
                Thread.sleep((round % 3).toLong())
                dispatcher.stop()
            }
            for (i in 0 until taskCount) {
                try {
                    dispatcher.dispatch(kotlinx.coroutines.Job()) {
                        executed[i].set(true)
                    }
                } catch (e: IllegalStateException) {
                    threw[i].set(true)
                }
            }
            stopper.join(10_000)
            assertTrue(controller.finalizeLatch.await(5, TimeUnit.SECONDS))
            // 等待已接管任务跑完/drain 收尾落地，再做不变量断言
            Thread.sleep(50)
            for (i in 0 until taskCount) {
                assertFalse(
                    "round $round task $i: executed task must not be reported as dispatch failure",
                    threw[i].get() && executed[i].get(),
                )
            }
        }
    }
}
