// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.data.manager

import kotlinx.coroutines.CancellationException

/**
 * 键盘内执行部署／同步（重点候选 8-4）：
 *
 * 控制器本身不持有引擎，只通过注入的挂起函数调用现有部署路径
 * （deployAndAwait 的互斥＋守门仍在引擎侧生效；本控制器再加
 * 一层键盘侧单飞：运行中重复请求直接忽略，不排队、不重编）。
 *
 * 状态机：IDLE →（有组字）NEEDS_CONFIRM → DEPLOYING →（有同步）
 * SYNCING → SUCCESS／FAILED；SUCCESS／FAILED 可再次请求。
 * 组字保守原则：请求时组字非空 → 先停在 NEEDS_CONFIRM，调用方
 * 须向用户明示“部署将丢弃未提交的组字”，只有确认才继续；
 * 绝不静默丢字。确认／取消只在 NEEDS_CONFIRM 有效。
 * 失败原因只记结果名／异常类名（元数据），绝不记组字、候选、
 * 方案内容。部署无进度回调，progress 恒为 null（不定态），
 * 接线层可经 updateProgress 填入外部进度（仅运行中有效）。
 *
 * 调用约定：requestDeploy 返回 true（或 confirmPending 返回
 * true）后，调用方在自己的作用域内执行一次 execute()；execute
 * 只在 DEPLOYING 态真正跑，其他态直接返回 false。
 */
enum class KeyboardDeployPhase {
    IDLE,
    NEEDS_CONFIRM,
    DEPLOYING,
    SYNCING,
    SUCCESS,
    FAILED,
}

data class KeyboardDeployProgress(
    val phase: KeyboardDeployPhase,
    val progress: Float?,
    val failureReason: String?,
)

class KeyboardDeployController(
    private val deploy: suspend () -> DeployAwaitResult,
    private val sync: (suspend () -> Boolean)?,
    private val hasPreedit: () -> Boolean,
) {

    /**
     * 状态机锁（D-M7）：旧实现是裸 var，检查与置位分两步——请求／
     * 确认／执行可在两线程上交错（两路 requestDeploy 同时看到 IDLE
     * 各置 DEPLOYING、两路 execute 同时跑部署）。全部检查—置位在
     * 同一把锁内原子完成；通知回调在锁外发（onProgressChanged 是
     * 调用方注入的回调，不得在锁内调用）。
     */
    private val stateLock = Any()
    private var _progress: KeyboardDeployProgress =
        KeyboardDeployProgress(KeyboardDeployPhase.IDLE, null, null)

    /** 当前状态（锁下读数）：调用方读到的是某一时刻的一致快照。 */
    val progress: KeyboardDeployProgress
        get() = synchronized(stateLock) { _progress }

    /** execute() 的双跑占位（与 phase 同锁，见 [execute]）。 */
    private var executing = false

    var onProgressChanged: ((KeyboardDeployProgress) -> Unit)? = null

    private fun setProgress(next: KeyboardDeployProgress) {
        synchronized(stateLock) { _progress = next }
        onProgressChanged?.invoke(next)
    }

    /**
     * 原子状态迁移：compute 在锁内对当前状态算出下一状态（返回 null
     * 表示不变、不通知），置位与通知分离——置位在锁内，通知在锁外。
     * 返回迁移后的状态（不变时返回 null）。
     */
    private fun transition(
        compute: (KeyboardDeployProgress) -> KeyboardDeployProgress?,
    ): KeyboardDeployProgress? {
        val next = synchronized(stateLock) {
            compute(_progress)?.also { _progress = it }
        }
        if (next != null) onProgressChanged?.invoke(next)
        return next
    }

    /**
     * 请求执行：IDLE／SUCCESS／FAILED → 有组字则 NEEDS_CONFIRM
     * （返回 false，未启动），无组字则 DEPLOYING（返回 true，调用
     * 方随后执行 execute）；NEEDS_CONFIRM／运行中重复请求返回
     * false（忽略，不排队）。
     */
    fun requestDeploy(): Boolean {
        var started = false
        transition { cur ->
            when (cur.phase) {
                KeyboardDeployPhase.NEEDS_CONFIRM,
                KeyboardDeployPhase.DEPLOYING,
                KeyboardDeployPhase.SYNCING,
                -> null

                KeyboardDeployPhase.IDLE,
                KeyboardDeployPhase.SUCCESS,
                KeyboardDeployPhase.FAILED,
                -> {
                    // hasPreedit 是外部状态读数，与单线程版同一时点
                    // 语义、在锁内读（它不回调本控制器，无锁序问题）。
                    if (hasPreedit()) {
                        KeyboardDeployProgress(KeyboardDeployPhase.NEEDS_CONFIRM, null, null)
                    } else {
                        started = true
                        KeyboardDeployProgress(KeyboardDeployPhase.DEPLOYING, null, null)
                    }
                }
            }
        }
        return started
    }

    /** 确认待确认的部署：仅 NEEDS_CONFIRM → DEPLOYING（返回 true）。 */
    fun confirmPending(): Boolean {
        return transition { cur ->
            if (cur.phase == KeyboardDeployPhase.NEEDS_CONFIRM) {
                KeyboardDeployProgress(KeyboardDeployPhase.DEPLOYING, null, null)
            } else {
                null
            }
        } != null
    }

    /** 取消待确认的部署：仅 NEEDS_CONFIRM → IDLE（返回 true）。 */
    fun cancelPending(): Boolean {
        return transition { cur ->
            if (cur.phase == KeyboardDeployPhase.NEEDS_CONFIRM) {
                KeyboardDeployProgress(KeyboardDeployPhase.IDLE, null, null)
            } else {
                null
            }
        } != null
    }

    /** 消除终态显示：仅 SUCCESS／FAILED → IDLE（返回 true）。 */
    fun reset(): Boolean {
        return transition { cur ->
            if (cur.phase == KeyboardDeployPhase.SUCCESS ||
                cur.phase == KeyboardDeployPhase.FAILED
            ) {
                KeyboardDeployProgress(KeyboardDeployPhase.IDLE, null, null)
            } else {
                null
            }
        } != null
    }

    /** 运行中进度更新（仅运行中有效；越界钳制 0..1，非有限值 → null）。 */
    fun updateProgress(value: Float?) {
        val clamped = value?.takeIf { it.isFinite() }?.coerceIn(0f, 1f)
        transition { cur ->
            if (cur.phase == KeyboardDeployPhase.DEPLOYING ||
                cur.phase == KeyboardDeployPhase.SYNCING
            ) {
                cur.copy(progress = clamped)
            } else {
                null
            }
        }
    }

    /**
     * 执行部署（＋同步）：仅 DEPLOYING 态真正跑，返回是否跑完
     * （true = 到达 SUCCESS／FAILED 终态；false = 态不对，未跑）。
     * TIMEOUT_RUNNING 视为 FAILED（原因 deploy_timeout_running，
     * 后台部署继续、引擎守门阻止重入，直至作业收尾）。
     * 取消（CancellationException）时 phase 复位到 IDLE 再重抛：
     * 不复位会钉死 DEPLOYING、requestDeploy 恒 false，再也发不
     * 起部署。
     */
    suspend fun execute(): Boolean {
        // 双跑守门：两路并发 execute() 都会在入口看到 DEPLOYING，
        // 必须原子占位——executing 标志与 phase 同锁，否则部署跑两遍。
        // 占位在 finally 里释放（取消／异常／各返回路径都经过）。
        synchronized(stateLock) {
            if (_progress.phase != KeyboardDeployPhase.DEPLOYING || executing) return false
            executing = true
        }
        try {
            return executeLocked()
        } finally {
            synchronized(stateLock) { executing = false }
        }
    }

    /** execute() 本体（调用方已占位，见 [execute]）。 */
    private suspend fun executeLocked(): Boolean {
        try {
            when (deploy()) {
                DeployAwaitResult.COMPLETED -> {
                    // 部署完成
                }
                DeployAwaitResult.FAILED -> {
                    setProgress(
                        KeyboardDeployProgress(
                            KeyboardDeployPhase.FAILED, null, "deploy_failed",
                        ),
                    )
                    return true
                }
                DeployAwaitResult.TIMEOUT_RUNNING -> {
                    setProgress(
                        KeyboardDeployProgress(
                            KeyboardDeployPhase.FAILED, null, "deploy_timeout_running",
                        ),
                    )
                    return true
                }
            }
            val syncFn = sync
            if (syncFn != null) {
                setProgress(KeyboardDeployProgress(KeyboardDeployPhase.SYNCING, null, null))
                val synced = syncFn()
                if (!synced) {
                    setProgress(
                        KeyboardDeployProgress(
                            KeyboardDeployPhase.FAILED, null, "sync_failed",
                        ),
                    )
                    return true
                }
            }
            setProgress(KeyboardDeployProgress(KeyboardDeployPhase.SUCCESS, null, null))
            return true
        } catch (error: CancellationException) {
            setProgress(KeyboardDeployProgress(KeyboardDeployPhase.IDLE, null, null))
            throw error
        } catch (error: Throwable) {
            setProgress(
                KeyboardDeployProgress(
                    KeyboardDeployPhase.FAILED, null,
                    error.javaClass.simpleName,
                ),
            )
            return true
        }
    }
}
