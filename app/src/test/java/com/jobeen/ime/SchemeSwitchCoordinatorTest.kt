// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime

import com.jobeen.ime.data.manager.DirSwitchMonitor
import com.jobeen.ime.data.manager.SchemeSwitchCoordinator
import com.jobeen.ime.data.manager.SchemeSwitchOperationKind
import com.jobeen.ime.data.manager.SchemeSwitchState
import com.jobeen.ime.data.manager.SwitchVerification
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 应用级协调器：单例状态机镜像、三路操作锁互斥、operationId 凭据
 * 释放。状态机转移语义本身由 SchemeSwitchOrchestratorTest 覆盖，
 * 这里只测协调器层（锁、状态流、互斥守门），不测引擎动作。
 */
class SchemeSwitchCoordinatorTest {

    @Before
    fun setUp() {
        SchemeSwitchCoordinator.resetForTest()
        DirSwitchMonitor.clear()
    }

    @After
    fun tearDown() {
        SchemeSwitchCoordinator.resetForTest()
        DirSwitchMonitor.clear()
    }

    @Test
    fun state_mirrorsOrchestratorTransitions() {
        assertEquals(SchemeSwitchState.Idle, SchemeSwitchCoordinator.state.value)
        val plan = SchemeSwitchCoordinator.beginSwitch("a", "b", listOf("a", "b"))
        assertNotNull(plan)
        assertTrue(SchemeSwitchCoordinator.state.value is SchemeSwitchState.Switching)
        assertEquals(
            SwitchVerification.SUCCESS,
            SchemeSwitchCoordinator.resolveSwitch(true),
        )
        assertEquals(SchemeSwitchState.Idle, SchemeSwitchCoordinator.state.value)
    }

    @Test
    fun tryBegin_secondOperationRefusedWhileFirstHoldsLock() {
        val first = SchemeSwitchCoordinator.tryBegin(
            SchemeSwitchOperationKind.POINT_SWITCH,
            "b",
            "default",
        )
        assertNotNull(first)
        assertTrue(SchemeSwitchCoordinator.isBusy())
        // 目录切换与导入安装在点选切换进行中一律拒绝，不排队。
        assertNull(
            SchemeSwitchCoordinator.tryBegin(
                SchemeSwitchOperationKind.DIR_SWITCH,
                "other",
                "other",
            ),
        )
        assertNull(
            SchemeSwitchCoordinator.tryBegin(
                SchemeSwitchOperationKind.IMPORT_INSTALL,
                "pkg",
                "pkg",
            ),
        )
        SchemeSwitchCoordinator.endOperation(first!!)
        assertFalse(SchemeSwitchCoordinator.isBusy())
        val dirOp = SchemeSwitchCoordinator.tryBegin(
            SchemeSwitchOperationKind.DIR_SWITCH,
            "other",
            "other",
        )
        assertNotNull(dirOp)
        SchemeSwitchCoordinator.endOperation(dirOp!!)
    }

    @Test
    fun operationIds_uniqueAcrossOperations() {
        val first = SchemeSwitchCoordinator.tryBegin(
            SchemeSwitchOperationKind.POINT_SWITCH,
            "b",
            "default",
        )!!
        SchemeSwitchCoordinator.endOperation(first)
        val second = SchemeSwitchCoordinator.tryBegin(
            SchemeSwitchOperationKind.POINT_SWITCH,
            "c",
            "default",
        )!!
        assertNotEquals(first, second)
        assertEquals(second, SchemeSwitchCoordinator.currentOperation.value?.id)
        SchemeSwitchCoordinator.endOperation(second)
    }

    @Test
    fun endOperation_wrongIdDoesNotReleaseLock() {
        val id = SchemeSwitchCoordinator.tryBegin(
            SchemeSwitchOperationKind.DIR_SWITCH,
            "other",
            "other",
        )!!
        SchemeSwitchCoordinator.endOperation("not-the-id")
        assertTrue(SchemeSwitchCoordinator.isBusy())
        assertEquals(id, SchemeSwitchCoordinator.currentOperation.value?.id)
        SchemeSwitchCoordinator.endOperation(id)
        assertFalse(SchemeSwitchCoordinator.isBusy())
    }

    @Test
    fun endOperation_fromAnotherCoroutineReleasesLock() = runBlocking {
        val id = SchemeSwitchCoordinator.tryBegin(
            SchemeSwitchOperationKind.POINT_SWITCH,
            "b",
            "default",
        )!!
        // 页面在 UI 线程抢锁、应用级协程结束时释放：跨协程解锁必须生效，
        // 否则切换协程结束後锁永久滞留、后续入口全部被拒。
        launch { SchemeSwitchCoordinator.endOperation(id) }.join()
        assertFalse(SchemeSwitchCoordinator.isBusy())
    }

    @Test
    fun isBusy_dirSwitchMonitorSwitchingBlocksPointSwitch() {
        DirSwitchMonitor.begin("other")
        assertTrue(SchemeSwitchCoordinator.isBusy())
        assertNull(
            SchemeSwitchCoordinator.tryBegin(
                SchemeSwitchOperationKind.POINT_SWITCH,
                "b",
                "default",
            ),
        )
        DirSwitchMonitor.clear()
        assertFalse(SchemeSwitchCoordinator.isBusy())
    }

    @Test
    fun rollbackResult_branchesThroughCoordinatorState() {
        SchemeSwitchCoordinator.beginSwitch("a", "b", listOf("a", "b"))
        SchemeSwitchCoordinator.resolveSwitch(false)
        assertTrue(SchemeSwitchCoordinator.state.value is SchemeSwitchState.Deploying)
        assertFalse(SchemeSwitchCoordinator.resolveDeploy(false))
        assertTrue(SchemeSwitchCoordinator.state.value is SchemeSwitchState.Failed)
        assertNotNull(SchemeSwitchCoordinator.beginRollback())
        assertTrue(SchemeSwitchCoordinator.state.value is SchemeSwitchState.RollingBack)
        assertTrue(SchemeSwitchCoordinator.resolveRollback(true))
        assertEquals(
            SchemeSwitchState.RolledBack(previousId = "a", failedTargetId = "b"),
            SchemeSwitchCoordinator.state.value,
        )
        SchemeSwitchCoordinator.dismiss()
        assertEquals(SchemeSwitchState.Idle, SchemeSwitchCoordinator.state.value)
    }

    @Test
    fun rollbackFailure_returnsToFailedState() {
        SchemeSwitchCoordinator.beginSwitch("a", "b", listOf("a", "b"))
        SchemeSwitchCoordinator.resolveSwitch(false)
        SchemeSwitchCoordinator.resolveDeploy(false)
        SchemeSwitchCoordinator.beginRollback()
        assertFalse(SchemeSwitchCoordinator.resolveRollback(false))
        val failed = SchemeSwitchCoordinator.state.value as SchemeSwitchState.Failed
        assertEquals("rollback_failed", failed.reason)
        assertEquals("a", failed.snapshot.previousId)
        SchemeSwitchCoordinator.dismiss()
        assertEquals(SchemeSwitchState.Idle, SchemeSwitchCoordinator.state.value)
    }
}
