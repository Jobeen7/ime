// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime

import com.jobeen.ime.data.manager.DeployAwaitResult
import com.jobeen.ime.engine.deployGateAllows
import com.jobeen.ime.engine.deployWaitTimeoutResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 部署入口守门谓词与等待超时结果的规格测试。
 * 规格来源（非实现反推）：
 * - 部署进行中禁新部署：新部署只会串行排队、白白重编一次。
 * - 超时孤儿存活期禁新部署：孤儿复位点在作业收尾，新部署不得插队。
 * - 作用域已死禁新部署：作业永不执行，入口置位将无复位方。
 * - 等待超时时作用域已死：部署作业已死，“仍在后台继续”不成立，
 *   不得返回 TIMEOUT_RUNNING（否则记孤儿且永无复位方）。
 * RimeEngine 本体依赖 Android/native，无法在单元测试中实例化，
 * 故谓词以纯函数抽出、由 reload() 与 deployAndAwait() 两路共用。
 */
class DeployGateTest {

    @Test
    fun gate_allowsWhenIdle() {
        assertTrue(deployGateAllows(running = false, orphanRunning = false, scopeActive = true))
    }

    @Test
    fun gate_blocksWhenRunning() {
        assertFalse(deployGateAllows(running = true, orphanRunning = false, scopeActive = true))
    }

    @Test
    fun gate_blocksWhenOrphanRunning() {
        assertFalse(deployGateAllows(running = false, orphanRunning = true, scopeActive = true))
    }

    @Test
    fun gate_blocksWhenBothRunningAndOrphan() {
        assertFalse(deployGateAllows(running = true, orphanRunning = true, scopeActive = true))
    }

    @Test
    fun gate_blocksWhenScopeDeadEvenIfIdle() {
        assertFalse(deployGateAllows(running = false, orphanRunning = false, scopeActive = false))
    }

    @Test
    fun waitTimeout_liveScopeKeepsTimeoutRunning() {
        assertEquals(DeployAwaitResult.TIMEOUT_RUNNING, deployWaitTimeoutResult(scopeActive = true))
    }

    @Test
    fun waitTimeout_deadScopeReturnsFailedNotTimeout() {
        assertEquals(DeployAwaitResult.FAILED, deployWaitTimeoutResult(scopeActive = false))
    }
}
