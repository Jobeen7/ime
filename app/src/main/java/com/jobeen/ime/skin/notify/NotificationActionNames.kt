// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.skin.notify

import com.jobeen.ime.input.keyboard.key.KeyboardAction

/**
 * 键盘动作稳定名（接线轮 R1）：
 *
 * 通知源 keyboard_action 的 matchKey 取值——与 KeyboardPopup
 * 的动作标签同口径硬编码（不用 simpleName：R8 混淆后无意义）。
 * 参数化动作只取类型名（值不进匹配：匹配只判动作种类）。
 */
object NotificationActionNames {

    fun nameOf(action: KeyboardAction): String = when (action) {
        is KeyboardAction.KeyCodeAction -> "KeyCodeAction"
        is KeyboardAction.KeySequenceAction -> "KeySequenceAction"
        is KeyboardAction.ClearAction -> "ClearAction"
        is KeyboardAction.CommitAction -> "CommitAction"
        is KeyboardAction.SelectCandidatePinYin -> "SelectCandidatePinYin"
        is KeyboardAction.CapsAction -> "CapsAction"
        is KeyboardAction.LayoutSwitchAction -> "LayoutSwitchAction"
        is KeyboardAction.ResumeAction -> "ResumeAction"
        is KeyboardAction.BackspaceAction -> "BackspaceAction"
        is KeyboardAction.ReturnAction -> "ReturnAction"
        is KeyboardAction.SpaceAction -> "SpaceAction"
        is KeyboardAction.CursorMoveAction -> "CursorMoveAction"
        is KeyboardAction.LangSwitchAction -> "LangSwitchAction"
        is KeyboardAction.RotateSchema -> "RotateSchema"
        is KeyboardAction.SwitchSchemaByValue -> "SwitchSchemaByValue"
        is KeyboardAction.SelectSchema -> "SelectSchema"
        is KeyboardAction.ShowInputMethodPickerAction -> "ShowInputMethodPickerAction"
        is KeyboardAction.VoiceInputAction -> "VoiceInputAction"
        is KeyboardAction.StopVoiceInputAction -> "StopVoiceInputAction"
        is KeyboardAction.VoiceDragPosition -> "VoiceDragPosition"
        is KeyboardAction.VoiceDragUp -> "VoiceDragUp"
        is KeyboardAction.MultiReturnAction -> "MultiReturnAction"
        is KeyboardAction.UndoAction -> "UndoAction"
        is KeyboardAction.RedoAction -> "RedoAction"
        is KeyboardAction.LiteralCommitAction -> "LiteralCommitAction"
        is KeyboardAction.DeleteForwardAction -> "DeleteForwardAction"
        is KeyboardAction.OpenSystemImePickerAction -> "OpenSystemImePickerAction"
        is KeyboardAction.HideKeyboardAction -> "HideKeyboardAction"
    }
}
