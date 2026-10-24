// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime

import com.jobeen.ime.data.manager.DirSwitchMonitor
import com.jobeen.ime.data.manager.DirSwitchOutcome
import com.jobeen.ime.data.manager.DirSwitchPhase
import com.jobeen.ime.data.manager.DirSwitchResumePolicy
import com.jobeen.ime.data.manager.EngineDeployState
import com.jobeen.ime.data.manager.awaitSchemasReady
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 目录切换 stranded 修复的纯逻辑部分：退出口径、续切判定、
 * 方案表就绪轮询、进程级状态流转。引擎与页面行为需真机复核。
 */
class DirSwitchMonitorTest {

    @Test
    fun outcome_success_needsNoRollback() {
        assertFalse(DirSwitchOutcome.needsRollback(DirSwitchOutcome.Exit.SUCCESS))
    }

    @Test
    fun outcome_failed_needsRollback() {
        assertTrue(DirSwitchOutcome.needsRollback(DirSwitchOutcome.Exit.FAILED))
    }

    @Test
    fun outcome_cancelled_needsRollback() {
        // 关键回归：此前取消直接重抛、跳过回滚，导致 stranded。
        assertTrue(DirSwitchOutcome.needsRollback(DirSwitchOutcome.Exit.CANCELLED))
    }

    @Test
    fun resume_onlyWhenEngineEmptyAndDiskHasSchemesAndIdle() {
        assertTrue(DirSwitchResumePolicy.shouldResume(true, true, false, false))
        assertFalse(DirSwitchResumePolicy.shouldResume(false, true, false, false))
        assertFalse(DirSwitchResumePolicy.shouldResume(true, false, false, false))
        assertFalse(DirSwitchResumePolicy.shouldResume(true, true, true, false))
        assertFalse(DirSwitchResumePolicy.shouldResume(false, false, true, false))
    }

    @Test
    fun resume_neverWhenDeployRunning() {
        // 关键回归（2026-10-25 Base 导入）：部署进行中引擎表空是正常态
        // （作业队列被部署作业占住、awaitJob 超时读回空表），续切 reload
        // 会把进行中的全量编译推倒重来，表空 + 磁盘有方案 + 无切换在跑
        // 也必须禁续切，否则重试/FAB 一点就重启编译、永远部署中。
        assertFalse(DirSwitchResumePolicy.shouldResume(true, true, false, true))
        assertFalse(DirSwitchResumePolicy.shouldResume(true, true, true, true))
        assertTrue(DirSwitchResumePolicy.shouldResume(true, true, false, false))
    }

    @Test
    fun deployState_flagRoundTrip() {
        EngineDeployState.setRunning(false)
        assertFalse(EngineDeployState.isRunning())
        EngineDeployState.setRunning(true)
        assertTrue(EngineDeployState.isRunning())
        assertTrue(EngineDeployState.running.value)
        EngineDeployState.setRunning(false)
        assertFalse(EngineDeployState.isRunning())
    }

    @Test
    fun awaitReady_returnsTrueImmediately() = runBlocking {
        assertTrue(awaitSchemasReady(poll = { true }, timeoutMs = 1000L, intervalMs = 10L))
    }

    @Test
    fun awaitReady_returnsTrueWhenPollTurnsTrue() = runBlocking {
        var calls = 0
        val ready = awaitSchemasReady(
            poll = { ++calls >= 3 },
            timeoutMs = 1000L,
            intervalMs = 10L,
        )
        assertTrue(ready)
        assertEquals(3, calls)
    }

    @Test
    fun awaitReady_returnsFalseOnTimeout() = runBlocking {
        assertFalse(awaitSchemasReady(poll = { false }, timeoutMs = 50L, intervalMs = 10L))
    }

    @Test
    fun awaitReady_pollExceptionCountsAsNotReady() = runBlocking {
        var calls = 0
        val ready = awaitSchemasReady(
            poll = {
                calls++
                if (calls < 3) throw IllegalStateException("engine busy") else true
            },
            timeoutMs = 1000L,
            intervalMs = 10L,
        )
        assertTrue(ready)
        assertEquals(3, calls)
    }

    @Test
    fun monitor_stateTransitions() {
        DirSwitchMonitor.clear()
        assertEquals(DirSwitchPhase.IDLE, DirSwitchMonitor.status.value.phase)
        DirSwitchMonitor.begin("rime-wanxiang-base")
        assertEquals(DirSwitchPhase.SWITCHING, DirSwitchMonitor.status.value.phase)
        assertEquals("rime-wanxiang-base", DirSwitchMonitor.status.value.targetDir)
        DirSwitchMonitor.done()
        assertEquals(DirSwitchPhase.DONE, DirSwitchMonitor.status.value.phase)
        DirSwitchMonitor.begin("rime-wanxiang-base")
        DirSwitchMonitor.failed("切换目录失败，已回退到上一目录")
        assertEquals(DirSwitchPhase.FAILED, DirSwitchMonitor.status.value.phase)
        assertTrue(DirSwitchMonitor.status.value.errorMessage.isNotEmpty())
        DirSwitchMonitor.clear()
        assertEquals(DirSwitchPhase.IDLE, DirSwitchMonitor.status.value.phase)
    }
}
