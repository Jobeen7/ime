// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.data.manager

/**
 * 键盘内部署阶段提示文案（接线轮 R7，纯函数）：
 * 只说部署，绝不声称同步（本轮同步恒为 null，未接）。
 * IDLE／NEEDS_CONFIRM／SYNCING 无提示（确认走弹窗、
 * 同步不可达）。
 */
object KeyboardDeployTexts {

    fun toastFor(progress: KeyboardDeployProgress): String? = when (progress.phase) {
        KeyboardDeployPhase.DEPLOYING -> "正在部署…"
        KeyboardDeployPhase.SUCCESS -> "部署完成"
        KeyboardDeployPhase.FAILED -> when (progress.failureReason) {
            "deploy_timeout_running" -> "部署超时（后台部署继续）"
            "deploy_failed", null -> "部署失败"
            // 失败原因只会是结果名／异常类名（元数据），可直接展示
            else -> "部署失败：${progress.failureReason}"
        }
        else -> null
    }
}
