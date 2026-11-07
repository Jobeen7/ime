package com.jobeen.ime.ui.screen

import com.jobeen.ime.skin.store.ApplyTransaction
import com.jobeen.ime.skin.store.SkinGenRef

/**
 * 皮肤应用结果 → 用户可读提示（2026-10-28 修复轮）。
 *
 * 旧文案直接拼 Result 对象（`应用结果：FirstRenderFailedRolledBack(...)`），
 * 回滚时不报实际生效的皮肤；新文案一律报实际结果：回滚类结果必须
 * 说出回退到的皮肤（restoredRef 为 null = 内置默认键盘）。
 * 纯函数，不依赖 Compose，便于单测。
 * 延迟路径 AppliedPending 文案（2026-11-02 用户定）：点下即报“已切换到”
 *（快照暂存时已装好，下次键盘必用新皮肤），不再报“已暂存”；
 * PENDING→VERIFIED 晋升与画失败回滚逻辑本身不动。
 */
fun applyResultMessage(
    result: ApplyTransaction.Result,
    displayName: (String) -> String?,
): String {
    fun name(ref: SkinGenRef): String = displayName(ref.skinId)?.ifBlank { null } ?: ref.skinId
    fun effective(restored: SkinGenRef?): String = restored?.let { name(it) } ?: "内置键盘"
    return when (result) {
        is ApplyTransaction.Result.Applied ->
            "已应用：${name(result.ref)}"
        is ApplyTransaction.Result.AppliedPending ->
            "已切换到：${name(result.ref)}"
        is ApplyTransaction.Result.BuildFailed ->
            "应用失败：${name(result.ref)}（构建失败）"
        is ApplyTransaction.Result.SwitchFailedRolledBack ->
            "切换失败，已回退到：${effective(result.restoredRef)}"
        is ApplyTransaction.Result.FirstRenderFailedRolledBack ->
            "首次渲染失败，已回退到：${effective(result.restoredRef)}"
        is ApplyTransaction.Result.RollbackFailed ->
            // 2026-11-05 审计 #1 项 2：回滚本身失败时运行态与记录
            // 均未获确认，不得按默认报“已回退到内置键盘”。
            "回滚失败：皮肤状态不确定，请重启键盘"
        is ApplyTransaction.Result.TargetInvalid ->
            "皮肤无效，无法应用"
        is ApplyTransaction.Result.Superseded ->
            "已被更新的应用取代"
        is ApplyTransaction.Result.RolledBackToLastGood ->
            "已回退到：${name(result.ref)}"
        is ApplyTransaction.Result.RevertedToDefault ->
            "已恢复内置键盘"
        is ApplyTransaction.Result.RollbackUnavailable ->
            "回退不可用：${rollbackReasonText(result.reason)}"
    }
}

private fun rollbackReasonText(reason: String): String = when (reason) {
    "no_last_good" -> "没有可回退的皮肤"
    "last_good_target_invalid" -> "上个皮肤已不可用"
    "build_failed" -> "构建失败"
    "switch_failed" -> "切换失败"
    "first_render_failed" -> "首次渲染失败"
    "active_write_failed" -> "记录写入失败"
    else -> reason
}
