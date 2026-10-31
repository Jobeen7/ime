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

    /**
     * 超时孤儿部署进行中：等待方超时后部署作业仍在后台跑，
     * 此时新部署一律以忙失败（deployAndAwait 入口守门），直至
     * 作业收尾复位。只由 deployAndAwait 置位/复位。
     */
    private val _orphanRunning = MutableStateFlow(false)
    val orphanRunning: StateFlow<Boolean> = _orphanRunning.asStateFlow()

    fun isOrphanRunning(): Boolean = _orphanRunning.value

    fun setOrphanRunning(running: Boolean) {
        _orphanRunning.value = running
    }
}

/**
 * 部署期按键门：部署进行中（running）或超时孤儿仍在跑（orphan）
 * 时，打字键一律丢弃，不入动作队列。否则键会排在部署作业之后、
 * 以新方案执行（旧键进新方案），或在部署 Start 消息处理后被
 * initialized 检查静默丢掉——两种结果全凭时序。
 * 口径与 deployGateAllows 的部署忙判断一致（running 或 orphan 即忙）；
 * 孤儿由部署作业收尾或 finalize 复位，不会钉死，故门不会长关。
 * 判定本身可单测；接线在 RimeEngine.processKey 入口。
 */
internal object DeployKeyGate {
    fun shouldDrop(): Boolean =
        EngineDeployState.isRunning() || EngineDeployState.isOrphanRunning()
}

/** deployAndAwait 的三态结果：完成 / 失败 / 超时但部署仍在后台继续。 */
enum class DeployAwaitResult { COMPLETED, FAILED, TIMEOUT_RUNNING }

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
