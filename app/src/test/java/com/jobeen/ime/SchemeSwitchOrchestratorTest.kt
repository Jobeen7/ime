// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime

import com.jobeen.ime.data.manager.SchemeSwitchOrchestrator
import com.jobeen.ime.data.manager.SchemeSwitchPolicy
import com.jobeen.ime.data.manager.SchemeSwitchState
import com.jobeen.ime.data.manager.SwitchVerification
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 第一期方案切换编排：顺序运算、维护期守卫、切换 / 部署 / 回退状态转移、
 * 来源判定与键盘标签映射。引擎动作不在此测（由调用方执行并回填结果）。
 */
class SchemeSwitchOrchestratorTest {

    @Test
    fun moveToTop_movesTargetFirstAndKeepsOthersInOrder() {
        val order = listOf("wanxiang_lite", "wanxiang_t9", "wanxiang_english")
        assertEquals(
            listOf("wanxiang_t9", "wanxiang_lite", "wanxiang_english"),
            SchemeSwitchPolicy.moveToTop(order, "wanxiang_t9"),
        )
    }

    @Test
    fun moveToTop_targetAlreadyFirstIsNoop() {
        val order = listOf("a", "b")
        assertEquals(order, SchemeSwitchPolicy.moveToTop(order, "a"))
    }

    @Test
    fun moveToTop_targetAbsentIsPrepended() {
        // 兜底 Lite 不在已启用列表时点底部行：先加入首位（启用并切换）。
        assertEquals(
            listOf("wanxiang_lite", "a"),
            SchemeSwitchPolicy.moveToTop(listOf("a"), "wanxiang_lite"),
        )
    }

    @Test
    fun beginSwitch_sameTargetIsNoop() {
        val orchestrator = SchemeSwitchOrchestrator()
        assertNull(orchestrator.beginSwitch("a", "a", listOf("a", "b")))
        assertEquals(SchemeSwitchState.Idle, orchestrator.state)
    }

    @Test
    fun beginSwitch_busyStateRefusesNewSwitch() {
        val orchestrator = SchemeSwitchOrchestrator()
        assertNotNull(orchestrator.beginSwitch("a", "b", listOf("a", "b")))
        // Switching 态再点别的方案：拒绝，不改状态。
        assertNull(orchestrator.beginSwitch("a", "c", listOf("b", "a")))
        assertTrue(orchestrator.state is SchemeSwitchState.Switching)
    }

    @Test
    fun switchFlow_successReturnsToIdle() {
        val orchestrator = SchemeSwitchOrchestrator()
        val plan = orchestrator.beginSwitch("a", "b", listOf("a", "b"))!!
        assertEquals(listOf("b", "a"), plan.newOrder)
        assertEquals("a", plan.snapshot.previousId)
        assertEquals(listOf("a", "b"), plan.snapshot.previousOrder)
        assertEquals(SwitchVerification.SUCCESS, orchestrator.resolveSwitch(true))
        assertEquals(SchemeSwitchState.Idle, orchestrator.state)
    }

    @Test
    fun switchFlow_failedVerifyGoesDeployThenRollback() {
        val orchestrator = SchemeSwitchOrchestrator()
        orchestrator.beginSwitch("a", "b", listOf("a", "b"))
        // 切换确认失败 → 部署重试态。
        assertEquals(SwitchVerification.NEEDS_DEPLOY, orchestrator.resolveSwitch(false))
        assertTrue(orchestrator.state is SchemeSwitchState.Deploying)
        // 部署后仍失败 → Failed，等待回退。
        assertFalse(orchestrator.resolveDeploy(false))
        assertTrue(orchestrator.state is SchemeSwitchState.Failed)
        // 回退恢复切换前顺序。
        val rollback = orchestrator.beginRollback()!!
        assertEquals("a", rollback.previousId)
        assertEquals("b", rollback.failedTargetId)
        assertEquals(listOf("a", "b"), rollback.restoredOrder)
        // 回退确认成功 → RolledBack（页内明示已回退）。
        assertTrue(orchestrator.resolveRollback(true))
        val state = orchestrator.state as SchemeSwitchState.RolledBack
        assertEquals("a", state.previousId)
        assertEquals("b", state.failedTargetId)
    }

    @Test
    fun rollbackFailed_keepsFailedButOrderAlreadyRestored() {
        val orchestrator = SchemeSwitchOrchestrator()
        orchestrator.beginSwitch("a", "b", listOf("a", "b"))
        orchestrator.resolveSwitch(false)
        orchestrator.resolveDeploy(false)
        val rollback = orchestrator.beginRollback()!!
        // 调用方已按 restoredOrder 写回偏好：顺序不再是半吊子，
        // 即使回退确认失败，状态也只停在 Failed，不自动再滚。
        assertEquals(listOf("a", "b"), rollback.restoredOrder)
        assertFalse(orchestrator.resolveRollback(false))
        val state = orchestrator.state as SchemeSwitchState.Failed
        assertEquals("rollback_failed", state.reason)
    }

    @Test
    fun beginRollback_onlyFromFailed() {
        val orchestrator = SchemeSwitchOrchestrator()
        assertNull(orchestrator.beginRollback())
        orchestrator.beginSwitch("a", "b", listOf("a", "b"))
        assertNull(orchestrator.beginRollback())
    }

    @Test
    fun dismiss_onlyClearsFailedOrRolledBack() {
        val orchestrator = SchemeSwitchOrchestrator()
        orchestrator.beginSwitch("a", "b", listOf("a", "b"))
        orchestrator.dismiss()
        assertTrue(orchestrator.state is SchemeSwitchState.Switching)
        orchestrator.resolveSwitch(false)
        orchestrator.resolveDeploy(false)
        orchestrator.dismiss()
        assertEquals(SchemeSwitchState.Idle, orchestrator.state)
    }

    @Test
    fun isBusy_onlyDuringSwitchDeployRollback() {
        val orchestrator = SchemeSwitchOrchestrator()
        assertFalse(orchestrator.isBusy())
        orchestrator.beginSwitch("a", "b", listOf("a", "b"))
        assertTrue(orchestrator.isBusy())
        orchestrator.resolveSwitch(false)
        assertTrue(orchestrator.isBusy())
        orchestrator.resolveDeploy(false)
        // Failed 不是忙态：可重试或关闭。
        assertFalse(orchestrator.isBusy())
        orchestrator.beginRollback()
        assertTrue(orchestrator.isBusy())
    }

    @Test
    fun classifySource_userDirWinsOtherwiseBuiltin() {
        assertEquals(
            SchemeSwitchPolicy.SchemeSource.USER,
            SchemeSwitchPolicy.classifySource(inUserDir = true, inSharedDir = true),
        )
        assertEquals(
            SchemeSwitchPolicy.SchemeSource.BUILTIN,
            SchemeSwitchPolicy.classifySource(inUserDir = false, inSharedDir = true),
        )
        // 两层都没有时回落内置：第一期列表只含已部署的内置方案。
        assertEquals(
            SchemeSwitchPolicy.SchemeSource.BUILTIN,
            SchemeSwitchPolicy.classifySource(inUserDir = false, inSharedDir = false),
        )
    }

    @Test
    fun keyboardTagFor_unknownOrMissingFallsBackToFull() {
        assertEquals(
            SchemeSwitchPolicy.SchemeKeyboardTag.T9,
            SchemeSwitchPolicy.keyboardTagFor("T9"),
        )
        assertEquals(
            SchemeSwitchPolicy.SchemeKeyboardTag.T15,
            SchemeSwitchPolicy.keyboardTagFor("T15"),
        )
        assertEquals(
            SchemeSwitchPolicy.SchemeKeyboardTag.FULL,
            SchemeSwitchPolicy.keyboardTagFor(""),
        )
        assertEquals(
            SchemeSwitchPolicy.SchemeKeyboardTag.FULL,
            SchemeSwitchPolicy.keyboardTagFor("Qwerty"),
        )
        assertEquals(
            SchemeSwitchPolicy.SchemeKeyboardTag.FULL,
            SchemeSwitchPolicy.keyboardTagFor("CustomLayout"),
        )
    }

    @Test
    fun builtinLiteId_isWanxiangLite() {
        assertEquals("wanxiang_lite", SchemeSwitchPolicy.BUILTIN_LITE_ID)
    }
}
