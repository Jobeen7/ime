package com.jobeen.ime.input.panel.toolbar

import android.content.Context
import com.jobeen.ime.data.manager.CandidateManager
import com.jobeen.ime.input.panel.PanelAction

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
        PanelAction.ToggleAsciiMode -> CandidateManager.isAsciiModeEnabled(context)
        else -> false
    }

}
