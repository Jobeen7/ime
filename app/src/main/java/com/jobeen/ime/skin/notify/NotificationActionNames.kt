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
        is KeyboardAction.ToggleAsciiModeAction -> "ToggleAsciiModeAction"
        is KeyboardAction.BoardCommitReturnAction -> "BoardCommitReturnAction"
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
        is KeyboardAction.HostUndoAction -> "HostUndoAction"
        is KeyboardAction.HostRedoAction -> "HostRedoAction"
        is KeyboardAction.LiteralCommitAction -> "LiteralCommitAction"
        is KeyboardAction.DeleteForwardAction -> "DeleteForwardAction"
        is KeyboardAction.DeleteTextAction -> "DeleteTextAction"
        is KeyboardAction.OpenSystemImePickerAction -> "OpenSystemImePickerAction"
        is KeyboardAction.HideKeyboardAction -> "HideKeyboardAction"
        is KeyboardAction.SendKeysAction -> "SendKeysAction"
        is KeyboardAction.AppKeyEventAction -> "AppKeyEventAction"
        is KeyboardAction.CombineAction -> "CombineAction"
        is KeyboardAction.OpenUrlAction -> "OpenUrlAction"
        is KeyboardAction.NoOpAction -> "NoOpAction"
        is KeyboardAction.SettingsAction -> "SettingsAction"
        is KeyboardAction.SystemSettingsAction -> "SystemSettingsAction"
        is KeyboardAction.RunScriptAction -> "RunScriptAction"
        is KeyboardAction.OpenScriptsAction -> "OpenScriptsAction"
        is KeyboardAction.ReturnPrimaryAction -> "ReturnPrimaryAction"
        is KeyboardAction.ReturnLastAction -> "ReturnLastAction"
        is KeyboardAction.ClipboardAction -> "ClipboardAction"
        is KeyboardAction.ToggleTraditionalAction -> "ToggleTraditionalAction"
        is KeyboardAction.ToggleEmbeddedInputAction -> "ToggleEmbeddedInputAction"
        is KeyboardAction.ToggleScriptViewAction -> "ToggleScriptViewAction"
        is KeyboardAction.SelectCandidateIndexAction -> "SelectCandidateIndexAction"
        is KeyboardAction.ShowPhraseViewAction -> "ShowPhraseViewAction"
        is KeyboardAction.CandidatesBarToggleAction -> "CandidatesBarToggleAction"
        is KeyboardAction.MomentaryShiftAction -> "MomentaryShiftAction"
        is KeyboardAction.PanelShortcutAction -> "PanelShortcutAction"
        is KeyboardAction.SwitchKeyboardTypeAction -> "SwitchKeyboardTypeAction"
        is KeyboardAction.KeyboardMenuAction -> "KeyboardMenuAction"
        is KeyboardAction.ToggleSymbolBarAction -> "ToggleSymbolBarAction"
        is KeyboardAction.ToggleCommandViewAction -> "ToggleCommandViewAction"
        is KeyboardAction.ToggleRimeOptionAction -> "ToggleRimeOptionAction"
        is KeyboardAction.SnippetAction -> "SnippetAction"
        is KeyboardAction.DelayAction -> "DelayAction"
        is KeyboardAction.SelectAllAction -> "SelectAllAction"
        is KeyboardAction.ShowPasteboardAction -> "ShowPasteboardAction"
        is KeyboardAction.ShowPerformanceAction -> "ShowPerformanceAction"
        is KeyboardAction.SubCollectionPageUpAction -> "SubCollectionPageUpAction"
        is KeyboardAction.SubCollectionPageDownAction -> "SubCollectionPageDownAction"
    }

    /**
     * 参数化动作的区分值（A5）：模式切换类动作以参数值参与通知
     * 匹配（切方案按方案 ID、切类型按类型名、rime 选项按选项名）；
     * 其余动作无区分值（匹配只判种类，value 须空）。
     */
    fun valueOf(action: KeyboardAction): String? = when (action) {
        is KeyboardAction.SwitchKeyboardTypeAction -> action.type
        is KeyboardAction.SwitchSchemaByValue -> action.value
        is KeyboardAction.ToggleRimeOptionAction -> action.option
        else -> null
    }

    /**
     * 组合键（P3：send_keys／send_key_event 的 value 格式）：
     * 逗号分隔多个组合，＋连接修饰键与键名／键码
     * （如 "ctrl+a,shift+enter"）；任一 token 非法即整值无效。
     * 键名用 Android KeyEvent 常量（字母 a–z＝29–54、数字 0–9＝
     * 7–16，另见 KEY_NAMES）；纯数字 token 直接作键码。
     */
    data class ParsedCombo(
        val keyCode: Int,
        val ctrl: Boolean = false,
        val shift: Boolean = false,
        val alt: Boolean = false,
        val meta: Boolean = false,
    )

    fun parseKeyCombo(value: String): ParsedCombo? {
        val tokens = value.split("+").map { it.trim().lowercase() }
        if (tokens.any { it.isEmpty() }) return null
        val keyToken = tokens.last()
        val keyCode = keyToken.toIntOrNull() ?: KEY_NAMES[keyToken] ?: return null
        var ctrl = false
        var shift = false
        var alt = false
        var meta = false
        for (mod in tokens.dropLast(1)) {
            when (mod) {
                "ctrl", "control" -> ctrl = true
                "shift" -> shift = true
                "alt" -> alt = true
                "meta" -> meta = true
                else -> return null
            }
        }
        if (tokens.size == 1 && (ctrl || shift || alt || meta)) return null
        return ParsedCombo(keyCode, ctrl, shift, alt, meta)
    }

    fun parseSendKeysValue(value: String): List<ParsedCombo>? {
        if (value.isBlank()) return null
        val combos = value.split(",").map { parseKeyCombo(it) }
        if (combos.any { it == null }) return null
        return combos.filterNotNull().takeIf { it.isNotEmpty() }
    }

    private val KEY_NAMES: Map<String, Int> = buildMap {
        for (c in 'a'..'z') put(c.toString(), 29 + (c - 'a'))
        for (c in '0'..'9') put(c.toString(), 7 + (c - '0'))
        put("enter", 66)
        put("tab", 61)
        put("esc", 111)
        put("escape", 111)
        put("space", 62)
        put("del", 67)
        put("delete", 67)
        put("backspace", 67)
        put("forward_del", 112)
        put("up", 19)
        put("down", 20)
        put("left", 21)
        put("right", 22)
        put("home", 122)
        put("end", 123)
        put("page_up", 92)
        put("page_down", 93)
        for (i in 1..12) put("f$i", 130 + i)
    }
}
