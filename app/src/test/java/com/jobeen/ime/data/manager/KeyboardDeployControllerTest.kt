// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.data.manager

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
}
