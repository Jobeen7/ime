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

    var progress: KeyboardDeployProgress =
        KeyboardDeployProgress(KeyboardDeployPhase.IDLE, null, null)
        private set

    var onProgressChanged: ((KeyboardDeployProgress) -> Unit)? = null

    private fun setProgress(next: KeyboardDeployProgress) {
        progress = next
        onProgressChanged?.invoke(next)
    }

    private val isRunning: Boolean get() =
        progress.phase == KeyboardDeployPhase.DEPLOYING ||
            progress.phase == KeyboardDeployPhase.SYNCING

    /**
     * 请求执行：IDLE／SUCCESS／FAILED → 有组字则 NEEDS_CONFIRM
     * （返回 false，未启动），无组字则 DEPLOYING（返回 true，调用
     * 方随后执行 execute）；NEEDS_CONFIRM／运行中重复请求返回
     * false（忽略，不排队）。
     */
    fun requestDeploy(): Boolean {
        return when (progress.phase) {
            KeyboardDeployPhase.NEEDS_CONFIRM -> false
            KeyboardDeployPhase.DEPLOYING, KeyboardDeployPhase.SYNCING -> false
            KeyboardDeployPhase.IDLE, KeyboardDeployPhase.SUCCESS, KeyboardDeployPhase.FAILED -> {
                if (hasPreedit()) {
                    setProgress(
                        KeyboardDeployProgress(KeyboardDeployPhase.NEEDS_CONFIRM, null, null),
                    )
                    false
                } else {
                    setProgress(
                        KeyboardDeployProgress(KeyboardDeployPhase.DEPLOYING, null, null),
                    )
                    true
                }
            }
        }
    }

    /** 确认待确认的部署：仅 NEEDS_CONFIRM → DEPLOYING（返回 true）。 */
    fun confirmPending(): Boolean {
        if (progress.phase != KeyboardDeployPhase.NEEDS_CONFIRM) return false
        setProgress(KeyboardDeployProgress(KeyboardDeployPhase.DEPLOYING, null, null))
        return true
    }

    /** 取消待确认的部署：仅 NEEDS_CONFIRM → IDLE（返回 true）。 */
    fun cancelPending(): Boolean {
        if (progress.phase != KeyboardDeployPhase.NEEDS_CONFIRM) return false
        setProgress(KeyboardDeployProgress(KeyboardDeployPhase.IDLE, null, null))
        return true
    }

    /** 消除终态显示：仅 SUCCESS／FAILED → IDLE（返回 true）。 */
    fun reset(): Boolean {
        if (progress.phase != KeyboardDeployPhase.SUCCESS &&
            progress.phase != KeyboardDeployPhase.FAILED
        ) {
            return false
        }
        setProgress(KeyboardDeployProgress(KeyboardDeployPhase.IDLE, null, null))
        return true
    }

    /** 运行中进度更新（仅运行中有效；越界钳制 0..1，非有限值 → null）。 */
    fun updateProgress(value: Float?) {
        if (!isRunning) return
        val clamped = value?.takeIf { it.isFinite() }?.coerceIn(0f, 1f)
        setProgress(progress.copy(progress = clamped))
    }

    /**
     * 执行部署（＋同步）：仅 DEPLOYING 态真正跑，返回是否跑完
     * （true = 到达 SUCCESS／FAILED 终态；false = 态不对，未跑）。
     * TIMEOUT_RUNNING 视为 FAILED（原因 deploy_timeout_running，
     * 后台部署继续、引擎守门阻止重入，直至作业收尾）。
     */
    suspend fun execute(): Boolean {
        if (progress.phase != KeyboardDeployPhase.DEPLOYING) return false
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
