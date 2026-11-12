// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.data.manager

import android.os.SystemClock
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

    /**
     * 原子抢占部署位：仅当未运行时置位并返回 true，已在跑时返回 false
     * （不改状态）。部署入口应走本函数而非先查 [isRunning] 再
     * [setRunning]——两步之间另一路可同时抢进，双部署并跑。
     */
    fun tryAcquireRunning(): Boolean = _running.compareAndSet(false, true)

    /** 释放部署位：仅由抢到部署位的持有方调用（见 [tryAcquireRunning]）。 */
    fun releaseRunning() {
        _running.value = false
    }

    @Deprecated(
        message = "部署位只由 deployAndAwait 持有：抢占走 tryAcquireRunning，释放走 releaseRunning；" +
            "任意置位会误清标志、使 DeployKeyGate 在部署中途放行按键",
        level = DeprecationLevel.WARNING,
    )
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

    /**
     * 原子抢占孤儿部署位：仅当未置位时置位并返回 true（与
     * [tryAcquireRunning] 同口径，供 deployAndAwait 的超时孤儿收尾用）。
     */
    fun tryAcquireOrphanRunning(): Boolean = _orphanRunning.compareAndSet(false, true)

    /** 释放孤儿部署位：仅由置位方调用（见 [tryAcquireOrphanRunning]）。 */
    fun releaseOrphanRunning() {
        _orphanRunning.value = false
    }

    @Deprecated(
        message = "孤儿部署位只由 deployAndAwait 置位／复位：抢占走 tryAcquireOrphanRunning，" +
            "释放走 releaseOrphanRunning；任意置位会误清标志、使 DeployKeyGate 放行按键",
        level = DeprecationLevel.WARNING,
    )
    fun setOrphanRunning(running: Boolean) {
        _orphanRunning.value = running
    }
}

/**
 * 部署窗口：部署进行中（running）或超时孤儿仍在跑（orphan）。
 * 口径与 deployGateAllows 的部署忙判断一致（running 或 orphan 即忙）；
 * 孤儿由部署作业收尾或 finalize 复位，不会钉死。
 */
internal fun isDeployWindow(): Boolean =
    EngineDeployState.isRunning() || EngineDeployState.isOrphanRunning()

/**
 * 部署期按键门：部署窗口内打字键一律丢弃，不入动作队列。否则键会
 * 排在部署作业之后、以新方案执行（旧键进新方案），或在部署 Start
 * 消息处理后被 initialized 检查静默丢掉——两种结果全凭时序。
 * 判定本身可单测；接线在 RimeEngine.processKey 入口。
 */
internal object DeployKeyGate {
    fun shouldDrop(): Boolean = isDeployWindow()
}

/** deployAndAwait 的三态结果：完成 / 失败 / 超时但部署仍在后台继续。 */
enum class DeployAwaitResult { COMPLETED, FAILED, TIMEOUT_RUNNING }

/**
 * 等引擎方案表就绪：每 intervalMs 轮询一次，直到 poll 为真或超时。
 * 用于 FAB 部署“真实完成”判定：deploy() 返回只代表引擎刚启动，
 * 不代表部署编完（日志实证：启动后 7ms 即有 Depoly 消息，方案
 * apply 却要 20 秒后才来，Base 全量更久）。
 *
 * @param nowMs 单调时钟读数（毫秒）。默认走 [SystemClock.elapsedRealtime]：
 * 截止时间走单调时钟，墙钟拨快会提前判失败、拨慢会超期等待，超时口径
 * 必须与系统时间设置无关（D-L6）。JVM 单测里 android.os.SystemClock
 * 是未 mock 的桩，直接调会抛，故测试注入假时钟（如 System.nanoTime）。
 */
suspend fun awaitSchemasReady(
    poll: suspend () -> Boolean,
    timeoutMs: Long = 600_000L,
    intervalMs: Long = 2000L,
    nowMs: () -> Long = SystemClock::elapsedRealtime,
): Boolean {
    // 截止时间走单调时钟：墙钟拨快会提前判失败、拨慢会超期等待，
    // 超时口径必须与系统时间设置无关。
    val deadline = nowMs() + timeoutMs
    while (true) {
        if (runCatching { poll() }.getOrDefault(false)) return true
        if (nowMs() >= deadline) return false
        delay(intervalMs)
    }
}
