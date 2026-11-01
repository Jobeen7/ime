package com.jobeen.ime.input.panel.toolbar

import android.content.Context
import com.jobeen.ime.data.manager.CandidateManager
import com.jobeen.ime.input.panel.PanelAction
import com.jobeen.ime.input.keyboard.window.KeyboardStateManager

/**
 * 工具栏开关态（E6）：对端工具栏钮以条件前景区分开关态；
 * Jime 侧菜单网格已有同口径实现（isToggleAction / isEnabled），
 * 此处为工具栏池钮行抽出同一份判定，供 ToolbarRenderer 注入用。
 * 用户中央区（用户层）不走此着色，只池钮行用。
 */
object ToolbarToggleState {

    fun isToggle(action: PanelAction): Boolean =
        action == PanelAction.TogglePrediction ||
            action == PanelAction.ToggleShowComment ||
            action == PanelAction.ToggleTraditionalChinese ||
            action == PanelAction.ToggleEmojiInput ||
            action == PanelAction.ToggleAsciiMode

    fun isActive(context: Context, action: PanelAction): Boolean = when (action) {
        PanelAction.TogglePrediction -> CandidateManager.isPredictionEnabled(context)
        PanelAction.ToggleShowComment -> CandidateManager.isShowComment(context)
        PanelAction.ToggleTraditionalChinese -> CandidateManager.isTraditionalChineseEnabled(context)
        PanelAction.ToggleEmojiInput -> CandidateManager.isEmojiEnabled(context)
        PanelAction.ToggleAsciiMode -> KeyboardStateManager.isMainTypeAlphabetic(context)
        else -> false
    }

    /**
     * 绘制用着色判定（纯函数，可单测）：开关且启用 → 启用色，
     * 其余（非开关／开关未启用）→ 常态色；禁用态由调用方
     * 先行变暗（禁用优先于开关着色）。
     */
    fun tintFor(
        action: PanelAction,
        active: Boolean,
        activedColor: Int,
        normalColor: Int,
    ): Int = if (isToggle(action) && active) activedColor else normalColor
}
