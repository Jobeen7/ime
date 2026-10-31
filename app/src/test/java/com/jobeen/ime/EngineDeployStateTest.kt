// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime

import com.jobeen.ime.data.manager.DeployKeyGate
import com.jobeen.ime.data.manager.EngineDeployState
import com.jobeen.ime.data.manager.awaitSchemasReady
import com.jobeen.ime.data.manager.isDeployWindow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 引擎部署状态与方案表就绪轮询的纯逻辑部分。
 * 旧目录切换状态机（DirSwitchMonitor/Outcome/ResumePolicy）已随
 * 多目录改写退场，其用例一并删除。
 */
class EngineDeployStateTest {

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
    fun deployKeyGate_dropsWhenRunning() {
        try {
            EngineDeployState.setRunning(true)
            EngineDeployState.setOrphanRunning(false)
            assertTrue(DeployKeyGate.shouldDrop())
        } finally {
            EngineDeployState.setRunning(false)
        }
    }

    @Test
    fun deployKeyGate_dropsWhenOrphan() {
        try {
            EngineDeployState.setRunning(false)
            EngineDeployState.setOrphanRunning(true)
            assertTrue(DeployKeyGate.shouldDrop())
        } finally {
            EngineDeployState.setOrphanRunning(false)
        }
    }

    @Test
    fun deployKeyGate_passesWhenIdle() {
        try {
            EngineDeployState.setRunning(false)
            EngineDeployState.setOrphanRunning(false)
            assertFalse(DeployKeyGate.shouldDrop())
        } finally {
            EngineDeployState.setRunning(false)
            EngineDeployState.setOrphanRunning(false)
        }
    }

    @Test
    fun deployWindow_truthTable() {
        try {
            EngineDeployState.setRunning(false)
            EngineDeployState.setOrphanRunning(false)
            assertFalse(isDeployWindow())
            EngineDeployState.setRunning(true)
            assertTrue(isDeployWindow())
            EngineDeployState.setRunning(false)
            EngineDeployState.setOrphanRunning(true)
            assertTrue(isDeployWindow())
            EngineDeployState.setRunning(true)
            assertTrue(isDeployWindow())
        } finally {
            EngineDeployState.setRunning(false)
            EngineDeployState.setOrphanRunning(false)
        }
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
}
