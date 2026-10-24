// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.data.manager

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 目录切换进程级状态：切换一旦开始就不再属于某个页面实例。
 *
 * 背景（2026-10-25 Base 导入 stranded）：switchDirectory 此前跑在
 * SchemaSettingsScreen 的 rememberCoroutineScope 里，用户中途离开页面
 * 即取消协程，catch 对 CancellationException 直接重抛、跳过回滚——
 * 指针已指新目录、引擎丢在新目录部署中途、无方案、无恢复，重进页
 * 面又只读引擎表，stranded 状态稳定存在。
 * 此后切换一律跑应用级作用域（appScope），状态写这里，任何页面实
 * 例重进都能读到进行中 / 失败，失败提示不再依赖已销毁的组合状态。
 */
enum class DirSwitchPhase { IDLE, SWITCHING, DONE, FAILED }

data class DirSwitchStatus(
    val phase: DirSwitchPhase = DirSwitchPhase.IDLE,
    val targetDir: String = "",
    val errorMessage: String = "",
)

object DirSwitchMonitor {
    private val _status = MutableStateFlow(DirSwitchStatus())
    val status: StateFlow<DirSwitchStatus> = _status.asStateFlow()

    fun begin(targetDir: String) {
        _status.value = DirSwitchStatus(DirSwitchPhase.SWITCHING, targetDir)
    }

    fun done() {
        _status.value = _status.value.copy(phase = DirSwitchPhase.DONE)
    }

    fun failed(message: String) {
        _status.value = _status.value.copy(phase = DirSwitchPhase.FAILED, errorMessage = message)
    }

    fun clear() {
        _status.value = DirSwitchStatus()
    }
}

/**
 * 切换退出口径：取消与失败一样必须回滚。
 * 此前取消直接重抛、跳过回滚，是 stranded 的直接成因。
 */
object DirSwitchOutcome {
    enum class Exit { SUCCESS, FAILED, CANCELLED }

    fun needsRollback(exit: Exit): Boolean = exit != Exit.SUCCESS
}

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
 * 重进页面续切判定（纯逻辑）：引擎方案表空 + 磁盘有方案 + 无切换在跑
 * + 无部署在跑 → 续切（把引擎重同步到指针目录）；否则只读，不盲目重部署。
 * 部署在跑时表空是正常态，续切 reload 会打断编译，必须排除。
 */
object DirSwitchResumePolicy {
    fun shouldResume(
        engineSchemasEmpty: Boolean,
        diskHasSchemes: Boolean,
        switchRunning: Boolean,
        deployRunning: Boolean,
    ): Boolean = engineSchemasEmpty && diskHasSchemes && !switchRunning && !deployRunning
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
