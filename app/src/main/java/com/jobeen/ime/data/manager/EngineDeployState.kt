// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.data.manager

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 引擎部署进行中（进程级）：Action.Reload 的部署作业与引擎启动作业持有，
 * 覆盖 deploy() + joinMaintenanceThread() 全程。部署直通引擎作业队列串行
 * 执行；部署进行中引擎方案表读回空是正常态（作业队列被部署作业占住，
 * awaitJob 2 秒超时读回空表；native 表也要等部署 Finish 才就绪），不是
 * stranded——此时任何 reload 都等于把进行中的全量编译推倒重来。
 * 2026-10-25 Base 导入实证：切换部署进行中一次旁路部署打断后，引擎再无
 * Finish 消息、方案表恒空，页内重试/FAB 部署只会让编译永远从头开始。
 */
object EngineDeployState {
    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    fun isRunning(): Boolean = _running.value

    fun setRunning(running: Boolean) {
        _running.value = running
    }
}

/**
 * 等引擎方案表就绪：每 intervalMs 轮询一次，直到 poll 为真或超时。
 * 用于 FAB 部署“真实完成”判定：deploy() 返回只代表引擎刚启动，
 * 不代表部署编完（日志实证：启动后 7ms 即有 Depoly 消息，方案
 * apply 却要 20 秒后才来，Base 全量更久）。
 */
suspend fun awaitSchemasReady(
    poll: suspend () -> Boolean,
    timeoutMs: Long = 600_000L,
    intervalMs: Long = 2000L,
): Boolean {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (true) {
        if (runCatching { poll() }.getOrDefault(false)) return true
        if (System.currentTimeMillis() >= deadline) return false
        delay(intervalMs)
    }
}
