// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.data.manager

import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** 键盘内执行部署／同步（重点候选 8-4）：确认门、单飞、终态与失败原因。 */
class KeyboardDeployControllerTest {

    private fun controller(
        deployResult: DeployAwaitResult = DeployAwaitResult.COMPLETED,
        syncResult: Boolean? = null,
        hasPreedit: Boolean = false,
        deployThrows: Throwable? = null,
    ): KeyboardDeployController {
        val syncFn: (suspend () -> Boolean)? = syncResult?.let { result ->
            { result }
        }
        return KeyboardDeployController(
            deploy = {
                deployThrows?.let { throw it }
                deployResult
            },
            sync = syncFn,
            hasPreedit = { hasPreedit },
        )
    }

    @Test
    fun noPreedit_deploysAndSucceeds() = runBlocking {
        val c = controller()
        assertTrue(c.requestDeploy())
        assertEquals(KeyboardDeployPhase.DEPLOYING, c.progress.phase)
        assertTrue(c.execute())
        assertEquals(KeyboardDeployPhase.SUCCESS, c.progress.phase)
        assertEquals(null, c.progress.failureReason)
    }

    @Test
    fun preedit_needsConfirm_thenProceedsOrCancels() = runBlocking {
        val c = controller(hasPreedit = true)
        assertFalse(c.requestDeploy())
        assertEquals(KeyboardDeployPhase.NEEDS_CONFIRM, c.progress.phase)
        assertFalse(c.requestDeploy()) // 待确认中重复请求忽略
        assertTrue(c.confirmPending())
        assertEquals(KeyboardDeployPhase.DEPLOYING, c.progress.phase)
        assertTrue(c.execute())
        assertEquals(KeyboardDeployPhase.SUCCESS, c.progress.phase)

        val c2 = controller(hasPreedit = true)
        assertFalse(c2.requestDeploy())
        assertTrue(c2.cancelPending())
        assertEquals(KeyboardDeployPhase.IDLE, c2.progress.phase)
        assertFalse(c2.confirmPending())
        assertFalse(c2.cancelPending())
    }

    @Test
    fun running_duplicateRequestIgnored() {
        val c = controller()
        assertTrue(c.requestDeploy())
        assertFalse(c.requestDeploy())
        assertEquals(KeyboardDeployPhase.DEPLOYING, c.progress.phase)
    }

    @Test
    fun deployFailureReasons() = runBlocking {
        val failed = controller(deployResult = DeployAwaitResult.FAILED)
        assertTrue(failed.requestDeploy())
        assertTrue(failed.execute())
        assertEquals(KeyboardDeployPhase.FAILED, failed.progress.phase)
        assertEquals("deploy_failed", failed.progress.failureReason)

        val timeout = controller(deployResult = DeployAwaitResult.TIMEOUT_RUNNING)
        assertTrue(timeout.requestDeploy())
        assertTrue(timeout.execute())
        assertEquals("deploy_timeout_running", timeout.progress.failureReason)

        val syncFailed = controller(syncResult = false)
        assertTrue(syncFailed.requestDeploy())
        assertTrue(syncFailed.execute())
        assertEquals(KeyboardDeployPhase.FAILED, syncFailed.progress.phase)
        assertEquals("sync_failed", syncFailed.progress.failureReason)

        val thrown = controller(deployThrows = IllegalStateException("boom"))
        assertTrue(thrown.requestDeploy())
        assertTrue(thrown.execute())
        assertEquals("IllegalStateException", thrown.progress.failureReason)
    }

    @Test
    fun executeWithoutStart_doesNothing() = runBlocking {
        var called = false
        val c = KeyboardDeployController(
            deploy = { called = true; DeployAwaitResult.COMPLETED },
            sync = null,
            hasPreedit = { false },
        )
        assertFalse(c.execute())
        assertFalse(called)
        assertEquals(KeyboardDeployPhase.IDLE, c.progress.phase)
    }

    @Test
    fun progressClampedAndRunningOnly() {
        val c = controller()
        c.updateProgress(0.5f) // 非运行中忽略
        assertEquals(null, c.progress.progress)
        assertTrue(c.requestDeploy())
        c.updateProgress(1.5f)
        assertEquals(1f, c.progress.progress)
        c.updateProgress(Float.NaN)
        assertEquals(null, c.progress.progress)
        assertTrue(c.reset().not()) // 运行中不可 reset
    }

    @Test
    fun terminalCanRerunAndReset() = runBlocking {
        val c = controller()
        assertTrue(c.requestDeploy())
        assertTrue(c.execute())
        assertTrue(c.reset())
        assertEquals(KeyboardDeployPhase.IDLE, c.progress.phase)
        assertTrue(c.requestDeploy())
        assertTrue(c.execute())
        assertEquals(KeyboardDeployPhase.SUCCESS, c.progress.phase)
    }

    @Test
    fun deployCancelled_resetsPhaseToIdleAndCanRedeploy() = runBlocking {
        val c = controller(deployThrows = CancellationException("cancelled"))
        assertTrue(c.requestDeploy())
        try {
            c.execute()
            fail("应重抛 CancellationException")
        } catch (_: CancellationException) {
            // 预期：取消不吞
        }
        assertEquals(KeyboardDeployPhase.IDLE, c.progress.phase)
        assertTrue(c.requestDeploy())
    }

    @Test
    fun syncCancelled_resetsPhaseToIdleAndCanRedeploy() = runBlocking {
        val c = KeyboardDeployController(
            deploy = { DeployAwaitResult.COMPLETED },
            sync = { throw CancellationException("cancelled") },
            hasPreedit = { false },
        )
        assertTrue(c.requestDeploy())
        try {
            c.execute()
            fail("应重抛 CancellationException")
        } catch (_: CancellationException) {
            // 预期：取消不吞
        }
        assertEquals(KeyboardDeployPhase.IDLE, c.progress.phase)
        assertTrue(c.requestDeploy())
    }

    @Test
    fun concurrentRequestDeploy_exactlyOneWins() {
        // D-M7：requestDeploy 的“检查＋置位”须原子——两路并发只应
        // 有一路拿到 true（旧版单飞无锁，并发下两路都能进入 DEPLOYING）。
        val c = controller()
        val gate = CountDownLatch(1)
        val wins = AtomicInteger(0)
        val threads = List(2) {
            thread {
                gate.await()
                if (c.requestDeploy()) wins.incrementAndGet()
            }
        }
        gate.countDown()
        threads.forEach { it.join(10_000) }
        assertEquals(1, wins.get())
        assertEquals(KeyboardDeployPhase.DEPLOYING, c.progress.phase)
    }

    @Test
    fun concurrentExecute_exactlyOneRuns() = runBlocking {
        // D-M7：execute 的抢占须原子——两路并发执行只应有一路真正
        // 跑部署，另一路返回 false；部署本体只调用一次。
        val deployCalls = AtomicInteger(0)
        val c = KeyboardDeployController(
            deploy = {
                deployCalls.incrementAndGet()
                delay(200)
                DeployAwaitResult.COMPLETED
            },
            sync = null,
            hasPreedit = { false },
        )
        assertTrue(c.requestDeploy())
        val first = async { c.execute() }
        val second = async { c.execute() }
        withTimeout(10_000) {
            val results = listOf(first.await(), second.await())
            assertEquals(1, results.count { it })
        }
        assertEquals(1, deployCalls.get())
        assertEquals(KeyboardDeployPhase.SUCCESS, c.progress.phase)
    }
}
