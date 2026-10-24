// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.data.manager

/**
 * 第一期方案切换编排（纯逻辑，不依赖 Android 框架，可 JVM 单测）。
 *
 * 约束来源（定稿文档，2026-10-24 修订版）：
 * - 切换复用现有 saveOrder / selectSchemas 机制：当前方案 = 已启用列表首位，
 *   点选切换 = 把目标移到首位并写回偏好，键盘侧偏好监听随后完成引擎切方案；
 * - 部署失败回上一方案：恢复切换前 schema_list（即已启用顺序）+ 重选上一方案
 *   + 重新部署，不做快照级回滚；
 * - 内置 Lite（shared 层）是任何时候可用的兜底，其 id 为 [BUILTIN_LITE_ID]；
 * - 维护期守卫：切换 / 部署进行中拒绝新的切换，绝不留半吊子状态。
 *
 * 本文件只做状态判定与顺序运算，不直接碰引擎与偏好：引擎动作（切方案、
 * 重新部署、读当前方案）由调用方按 [SwitchPlan]/[RollbackPlan] 执行，执行
 * 结果再经 resolve* 回填，状态机只记录“做到哪一步”。
 */
object SchemeSwitchPolicy {

    /** 内置 Lite 的 schema id（shared 层兜底）。 */
    const val BUILTIN_LITE_ID = "wanxiang_lite"

    /** 方案来源：内置（shared 层）或用户（user 层）。 */
    enum class SchemeSource {
        BUILTIN,
        USER,
    }

    /** 键盘标签：只按 layout 字段映射，未知 / 缺失一律全键盘。 */
    enum class SchemeKeyboardTag {
        T9,
        T15,
        FULL,
    }

    fun keyboardTagFor(layout: String): SchemeKeyboardTag = when (layout) {
        "T9" -> SchemeKeyboardTag.T9
        "T15" -> SchemeKeyboardTag.T15
        else -> SchemeKeyboardTag.FULL
    }

    /**
     * 来源判定：user 层有方案文件即算用户（用户覆盖优先）；否则一律算内置。
     * 两层都没有时回落内置——第一期列表只含已部署的内置方案，用户包
     * 在第二期才进 user 层；回落口径见验收报告。
     */
    fun classifySource(inUserDir: Boolean, inSharedDir: Boolean): SchemeSource =
        if (inUserDir) SchemeSource.USER else SchemeSource.BUILTIN

    /** 把目标移到已启用顺序首位；目标不在顺序里时先加入首位（启用并切换）。 */
    fun moveToTop(order: List<String>, targetId: String): List<String> {
        if (order.firstOrNull() == targetId) return order
        return listOf(targetId) + (order - targetId)
    }
}

/** 切换前快照：上一方案 + 切换前已启用顺序（即回退时的 schema_list）。 */
data class SchemeSwitchSnapshot(
    val previousId: String,
    val previousOrder: List<String>,
)

/** 点选切换的执行计划：调用方按 newOrder 写回偏好后等待引擎确认。 */
data class SwitchPlan(
    val targetId: String,
    val snapshot: SchemeSwitchSnapshot,
    val newOrder: List<String>,
)

/** 回退的执行计划：调用方按 restoredOrder 写回偏好并重新部署。 */
data class RollbackPlan(
    val previousId: String,
    val failedTargetId: String,
    val restoredOrder: List<String>,
)

/** 切换 / 部署 / 回退的状态。Failed 与 RolledBack 不是忙态，可重试或关闭。 */
sealed interface SchemeSwitchState {
    data object Idle : SchemeSwitchState

    data class Switching(
        val targetId: String,
        val snapshot: SchemeSwitchSnapshot,
    ) : SchemeSwitchState

    data class Deploying(
        val targetId: String,
        val snapshot: SchemeSwitchSnapshot,
    ) : SchemeSwitchState

    data class Failed(
        val targetId: String,
        val snapshot: SchemeSwitchSnapshot,
        val reason: String,
    ) : SchemeSwitchState

    data class RollingBack(
        val previousId: String,
        val failedTargetId: String,
        val snapshot: SchemeSwitchSnapshot,
    ) : SchemeSwitchState

    data class RolledBack(
        val previousId: String,
        val failedTargetId: String,
    ) : SchemeSwitchState
}

/** 切换确认结果：成功，或需走部署重试。 */
enum class SwitchVerification {
    SUCCESS,
    NEEDS_DEPLOY,
}

/**
 * 方案切换状态机。调用顺序：
 * beginSwitch →（引擎确认）resolveSwitch → NEEDS_DEPLOY 时 markDeploying →
 * （重新部署后确认）resolveDeploy → 仍失败时 beginRollback →
 * （恢复顺序 + 重新部署后确认）resolveRollback。
 * 任何一步的偏好写回都由调用方执行，状态机只判定、不写盘。
 */
class SchemeSwitchOrchestrator {

    var state: SchemeSwitchState = SchemeSwitchState.Idle
        private set

    /** 维护期守卫：切换 / 部署 / 回退进行中拒绝新的切换。 */
    fun isBusy(): Boolean = state is SchemeSwitchState.Switching ||
        state is SchemeSwitchState.Deploying ||
        state is SchemeSwitchState.RollingBack

    /**
     * 开始切换：忙态或目标已是当前时返回 null（调用方不做任何事）。
     * 否则快照上一方案与顺序，返回把目标移到首位的执行计划。
     */
    fun beginSwitch(
        currentId: String,
        targetId: String,
        enabledOrder: List<String>,
    ): SwitchPlan? {
        if (isBusy()) return null
        if (targetId == currentId) return null
        val snapshot = SchemeSwitchSnapshot(currentId, enabledOrder)
        val plan = SwitchPlan(
            targetId = targetId,
            snapshot = snapshot,
            newOrder = SchemeSwitchPolicy.moveToTop(enabledOrder, targetId),
        )
        state = SchemeSwitchState.Switching(targetId, snapshot)
        return plan
    }

    /**
     * 切换确认：仅在 Switching 态调用。引擎当前方案已是目标则成功，
     * 否则进入部署重试。非 Switching 态调用时返回 NEEDS_DEPLOY 但不
     * 转移状态——调用方必须以 state 是否为 Deploying 为准决定是否
     * 真正部署，不得只看返回值就部署。
     */
    fun resolveSwitch(verified: Boolean): SwitchVerification {
        val switching = state as? SchemeSwitchState.Switching
            ?: return SwitchVerification.NEEDS_DEPLOY
        if (verified) {
            state = SchemeSwitchState.Idle
            return SwitchVerification.SUCCESS
        }
        state = SchemeSwitchState.Deploying(switching.targetId, switching.snapshot)
        return SwitchVerification.NEEDS_DEPLOY
    }

    /** 部署后确认：成功回空闲，失败进 Failed（等待回退）。 */
    fun resolveDeploy(verified: Boolean): Boolean {
        val deploying = state as? SchemeSwitchState.Deploying ?: return false
        if (verified) {
            state = SchemeSwitchState.Idle
            return true
        }
        state = SchemeSwitchState.Failed(
            targetId = deploying.targetId,
            snapshot = deploying.snapshot,
            reason = "deploy_failed",
        )
        return false
    }

    /** 开始回退：仅 Failed 态可进，返回恢复切换前顺序的执行计划。 */
    fun beginRollback(): RollbackPlan? {
        val failed = state as? SchemeSwitchState.Failed ?: return null
        state = SchemeSwitchState.RollingBack(
            previousId = failed.snapshot.previousId,
            failedTargetId = failed.targetId,
            snapshot = failed.snapshot,
        )
        return RollbackPlan(
            previousId = failed.snapshot.previousId,
            failedTargetId = failed.targetId,
            restoredOrder = failed.snapshot.previousOrder,
        )
    }

    /**
     * 回退确认：成功则 RolledBack（页内明示已回退）；失败仍停在 Failed，
     * 但偏好顺序已恢复为切换前——不再有半吊子顺序，引擎侧状态留待
     * 真机复核（见验收报告），调用方不得再自动重试回退。
     */
    fun resolveRollback(verified: Boolean): Boolean {
        val rollingBack = state as? SchemeSwitchState.RollingBack ?: return false
        if (verified) {
            state = SchemeSwitchState.RolledBack(
                previousId = rollingBack.previousId,
                failedTargetId = rollingBack.failedTargetId,
            )
            return true
        }
        state = SchemeSwitchState.Failed(
            targetId = rollingBack.failedTargetId,
            snapshot = rollingBack.snapshot,
            reason = "rollback_failed",
        )
        return false
    }

    /** 关闭失败 / 已回退提示横幅，回到空闲（不改变已写回的顺序）。 */
    fun dismiss() {
        if (state is SchemeSwitchState.Failed ||
            state is SchemeSwitchState.RolledBack
        ) {
            state = SchemeSwitchState.Idle
        }
    }
}
