package com.jobeen.ime.skin.runtime

import com.jobeen.ime.engine.event.KeyModifier
import com.jobeen.ime.engine.event.KeyModifiers
import com.jobeen.ime.input.keyboard.key.KeyboardAction
import com.jobeen.ime.skin.model.DynamicTextSource
import com.jobeen.ime.skin.model.SkinAction
import com.jobeen.ime.skin.model.WhitelistAction
import com.jobeen.ime.skin.notify.NotificationActionNames

/**
 * 键动作映射（自 KeyOverrideApplier.kt 迁出的活声明；
 * 其余装配逻辑（apply／解析／音效）随皮肤 v3 死壳删除）。
 * T9／数字符号集合常量与 toRuntime 的值一字未改。
 */
object KeyActionMapping {

    /**
     * t9Symbols 集合键（Bin 皮肤左竖栏 tall 键，源包 .cskin 的
     * t9Symbols 类型节点）：转换器只产一个空键（label ""、tap none、
     * rowSpan 3），集合内容是宿主定义、对端包里没有符号表。
     * 本键的符号取 demo（Bin v1.9）所示的宿主默认 `,.?!`，纵向排布、
     * 抬手按 Y 分区提交（Rime 按标点模式自行转全角，与九宫格逗号键同口径）。
     * 皮肤若自带前景层（覆写写了 foregroundLayers）则以皮肤为准，不合成。
     */
    const val T9_SYMBOLS_COLLECTION_STABLE_ID = "t9SymbolsCollection"
    val T9_SYMBOLS_COLLECTION_DEFAULT = listOf(",", ".", "?", "!")

    /**
     * numericSymbols 集合键（Bin 皮肤数字键盘左竖栏 tall 键，源包
     * .cskin 的 numericSymbols 类型节点）：转换器只产一个空键
     * （label ""、tap none、rowSpan 3），集合内容在 skin.json 顶层
     * numericSymbols（转换器 A③补发，App SkinValidator 解析进快照）。
     * 有皮肤内容为准；皮肤未声明时按源包 13 项（"()" 是一整项，
     * "*" 的提交字符就是 "*"，显示 "×" 只是源包前景写法）。
     * 皮肤若自带前景层（覆写写了 foregroundLayers）则以皮肤为准，不合成。
     */
    const val NUMERIC_SYMBOLS_COLLECTION_STABLE_ID = "numericSymbolsCollection"
    val NUMERIC_SYMBOLS_COLLECTION_DEFAULT = listOf(
        "+", "-", "*", "/", "()", ".", "@", ",", "#", ":", "_", "?", "￥",
    )

    /**
     * 动态文字刷新取值（纯函数）：动态值非空取动态值，否则回退
     * 静态文本；动态值本身绝不记日志（调用方只传值、不记日志）。
     */
    fun resolveDynamicDisplayText(
        fallback: String,
        source: String?,
        values: Map<String, String>,
    ): String {
        val value = source?.let { values[it] }?.takeIf { it.isNotEmpty() }
        return value ?: fallback
    }

    /**
     * 前景层实时变量解析（纯函数，审计 K 余项）：模板中的对端
     * 特殊变量按 [DynamicTextSource.fromPeerVariable] 映射到
     * 本地动态文字来源，以 values（来源 json 名 → 值）求值；
     * 无值/空值时替为空串（实时变量无静态回退，空候选即空白，
     * 永不原样露出记号）；未知记号原样保留（调用方再走
     * substitute，占位口径记 WARN）。值本身绝不记日志。
     */
    fun resolveForegroundLiveText(
        template: String,
        values: Map<String, String>,
    ): String =
        TOKEN.replace(template) { match ->
            val source = DynamicTextSource.fromPeerVariable(match.value) ?: return@replace match.value
            values[source.jsonName]?.takeIf { it.isNotEmpty() } ?: ""
        }

    private val TOKEN = Regex("\\$[A-Za-z][A-Za-z0-9]*")

    /** 强类型动作 → 运行时动作（详设 §3.2 映射表；占位类型不得到达此处）。 */
    fun toRuntime(action: SkinAction): KeyboardAction = when (action) {
        is SkinAction.Text -> KeyboardAction.KeySequenceAction(action.value)
        is SkinAction.SendKeys -> KeyboardAction.SendKeysAction(action.keys)
        is SkinAction.SendKeyEvent -> KeyboardAction.AppKeyEventAction(
            action.keyCode, action.ctrl, action.shift, action.alt, action.meta,
        )
        is SkinAction.Combine -> KeyboardAction.CombineAction(action.actions.map { toRuntime(it) })
        is SkinAction.KeyboardType ->
            KeyboardAction.SwitchKeyboardTypeAction(action.type)
        is SkinAction.RimeOption -> KeyboardAction.ToggleRimeOptionAction(action.name)
        is SkinAction.Snippet -> KeyboardAction.SnippetAction(action.template)
        is SkinAction.Delay -> KeyboardAction.DelayAction(action.ms)
        is SkinAction.Command -> when (action.action) {
            WhitelistAction.COMMIT_DIRECT ->
                KeyboardAction.LiteralCommitAction(requireNotNull(action.value))
            WhitelistAction.LITERAL_NEWLINE -> KeyboardAction.CommitAction("\n")
            WhitelistAction.ENTER -> KeyboardAction.ReturnAction()
            WhitelistAction.DELETE -> KeyboardAction.BackspaceAction
            WhitelistAction.DELETE_FORWARD -> KeyboardAction.DeleteForwardAction
            WhitelistAction.SPACE -> KeyboardAction.SpaceAction
            WhitelistAction.CURSOR_LEFT -> KeyboardAction.CursorMoveAction(-1)
            WhitelistAction.CURSOR_RIGHT -> KeyboardAction.CursorMoveAction(1)
            WhitelistAction.ROTATE_SCHEMA -> KeyboardAction.RotateSchema
            WhitelistAction.SWITCH_SCHEMA ->
                KeyboardAction.SwitchSchemaByValue(requireNotNull(action.value))
            WhitelistAction.OPEN_SCHEMA_PICKER -> KeyboardAction.ShowInputMethodPickerAction
            WhitelistAction.SWITCH_SYSTEM_IME -> KeyboardAction.LangSwitchAction
            WhitelistAction.TOGGLE_ASCII_MODE -> KeyboardAction.ToggleAsciiModeAction
            WhitelistAction.OPEN_SYSTEM_IME_PICKER -> KeyboardAction.OpenSystemImePickerAction
            WhitelistAction.HIDE_KEYBOARD -> KeyboardAction.HideKeyboardAction
            WhitelistAction.CAPS -> KeyboardAction.CapsAction
            WhitelistAction.SWITCH_LAYOUT ->
                KeyboardAction.LayoutSwitchAction(requireNotNull(action.value))
            WhitelistAction.RESUME -> KeyboardAction.ResumeAction
            WhitelistAction.CLEAR -> KeyboardAction.ClearAction
            WhitelistAction.UNDO -> KeyboardAction.UndoAction
            WhitelistAction.REDO -> KeyboardAction.RedoAction
            WhitelistAction.VOICE_INPUT -> KeyboardAction.VoiceInputAction()
            // 审计 E 新增命令。
            WhitelistAction.SYMBOL ->
                KeyboardAction.LiteralCommitAction(requireNotNull(action.value))
            WhitelistAction.TAB -> KeyboardAction.AppKeyEventAction(61)
            WhitelistAction.ESCAPE -> KeyboardAction.AppKeyEventAction(111)
            WhitelistAction.HOME -> KeyboardAction.AppKeyEventAction(122)
            WhitelistAction.END -> KeyboardAction.AppKeyEventAction(123)
            // 修饰键单按（WO52 8.3）：键码与 SkinKeyCodes 键名表一致。
            WhitelistAction.COMMAND -> KeyboardAction.AppKeyEventAction(117)
            WhitelistAction.CONTROL -> KeyboardAction.AppKeyEventAction(113)
            WhitelistAction.OPTION -> KeyboardAction.AppKeyEventAction(57)
            WhitelistAction.SETTINGS -> KeyboardAction.SettingsAction
            WhitelistAction.SYSTEM_SETTINGS -> KeyboardAction.SystemSettingsAction
            WhitelistAction.NONE -> KeyboardAction.NoOpAction
            WhitelistAction.DISMISS_KEYBOARD -> KeyboardAction.HideKeyboardAction
            WhitelistAction.OPEN_URL ->
                KeyboardAction.OpenUrlAction(requireNotNull(action.value))
            WhitelistAction.RETURN_PRIMARY_KEYBOARD -> KeyboardAction.ReturnPrimaryAction
            WhitelistAction.RETURN_LAST_KEYBOARD -> KeyboardAction.ReturnLastAction
            WhitelistAction.MOMENTARY_SHIFT -> KeyboardAction.MomentaryShiftAction
            // 审计 E # 快捷指令集。
            WhitelistAction.HASH_TRADITIONAL -> KeyboardAction.ToggleTraditionalAction
            WhitelistAction.HASH_RIME_SWITCHER -> KeyboardAction.ShowInputMethodPickerAction
            WhitelistAction.HASH_SECOND_CANDIDATE -> KeyboardAction.SelectCandidateIndexAction(1)
            WhitelistAction.HASH_THIRD_CANDIDATE -> KeyboardAction.SelectCandidateIndexAction(2)
            WhitelistAction.HASH_SWITCH_SCHEME -> KeyboardAction.RotateSchema
            WhitelistAction.HASH_LINE_START -> KeyboardAction.AppKeyEventAction(122)
            WhitelistAction.HASH_LINE_END -> KeyboardAction.AppKeyEventAction(123)
            WhitelistAction.HASH_NEWLINE -> KeyboardAction.CommitAction("\n")
            WhitelistAction.HASH_ENTER -> KeyboardAction.ReturnAction()
            WhitelistAction.HASH_RETYPE -> KeyboardAction.ClearAction
            WhitelistAction.HASH_CUT ->
                KeyboardAction.ClipboardAction(KeyboardAction.ClipboardAction.Kind.CUT)
            WhitelistAction.HASH_COPY ->
                KeyboardAction.ClipboardAction(KeyboardAction.ClipboardAction.Kind.COPY)
            WhitelistAction.HASH_SELECT_TEXT -> KeyboardAction.SelectAllAction
            WhitelistAction.HASH_SHOW_PASTEBOARD_VIEW -> KeyboardAction.ShowPasteboardAction
            WhitelistAction.HASH_SUB_COLLECTION_PAGE_UP ->
                KeyboardAction.SubCollectionPageUpAction
            WhitelistAction.HASH_SUB_COLLECTION_PAGE_DOWN ->
                KeyboardAction.SubCollectionPageDownAction
            WhitelistAction.HASH_KEYBOARD_PERFORMANCE -> KeyboardAction.ShowPerformanceAction
            WhitelistAction.HASH_PASTE ->
                KeyboardAction.ClipboardAction(KeyboardAction.ClipboardAction.Kind.PASTE)
            WhitelistAction.HASH_DELETE_TEXT -> KeyboardAction.DeleteTextAction
            WhitelistAction.HASH_UNDO -> KeyboardAction.HostUndoAction
            WhitelistAction.HASH_REDO -> KeyboardAction.HostRedoAction
            WhitelistAction.HASH_VERTICAL_PAGE_UP -> KeyboardAction.KeyCodeAction(92)
            WhitelistAction.HASH_VERTICAL_PAGE_DOWN -> KeyboardAction.KeyCodeAction(93)
            WhitelistAction.HASH_SHOW_PHRASE_VIEW -> KeyboardAction.ShowPhraseViewAction
            WhitelistAction.HASH_CANDIDATES_BAR_TOGGLE -> KeyboardAction.CandidatesBarToggleAction
            WhitelistAction.HASH_RIME_PREV_PAGE -> KeyboardAction.PageCandidatesAction(true)
            WhitelistAction.HASH_RIME_NEXT_PAGE -> KeyboardAction.PageCandidatesAction(false)
            WhitelistAction.HASH_RIME_DEPLOY -> KeyboardAction.RimeDeployAction
            WhitelistAction.HASH_RIME_SYNC -> KeyboardAction.RimeSyncAction
            WhitelistAction.HASH_TOGGLE_EMBEDDED_INPUT -> KeyboardAction.ToggleEmbeddedInputAction
            WhitelistAction.HASH_CLEAR_CLIPBOARD ->
                KeyboardAction.ClipboardAction(KeyboardAction.ClipboardAction.Kind.CLEAR)
            WhitelistAction.HASH_TOGGLE_SYMBOL_BAR -> KeyboardAction.ToggleSymbolBarAction
            WhitelistAction.HASH_KEYBOARD_MENU -> KeyboardAction.KeyboardMenuAction
            WhitelistAction.HASH_TOGGLE_COMMAND_VIEW -> KeyboardAction.ToggleCommandViewAction
            // 通知动作补齐（P3；值已在通知解析期校验，此处非法只
            // 作空操作，绝不因皮肤数据崩键盘）。
            WhitelistAction.CHARACTER ->
                KeyboardAction.KeySequenceAction(requireNotNull(action.value))
            WhitelistAction.SEND_KEYS -> {
                val combos = NotificationActionNames.parseSendKeysValue(
                    action.value ?: "",
                ) ?: emptyList()
                KeyboardAction.SendKeysAction(combos.map { combo ->
                    val mods = mutableListOf<KeyModifier>()
                    if (combo.ctrl) mods += KeyModifier.Control
                    if (combo.shift) mods += KeyModifier.Shift
                    if (combo.alt) mods += KeyModifier.Alt
                    if (combo.meta) mods += KeyModifier.Meta
                    KeyboardAction.SendKeyCombo(combo.keyCode, KeyModifiers(*mods.toTypedArray()))
                })
            }
            WhitelistAction.SEND_KEY_EVENT -> {
                val combo = NotificationActionNames.parseKeyCombo(action.value ?: "")
                if (combo == null) {
                    KeyboardAction.NoOpAction
                } else {
                    KeyboardAction.AppKeyEventAction(
                        combo.keyCode, combo.ctrl, combo.shift, combo.alt, combo.meta,
                    )
                }
            }
            WhitelistAction.RIME_OPTION ->
                KeyboardAction.ToggleRimeOptionAction(requireNotNull(action.value))
            WhitelistAction.DELAY ->
                KeyboardAction.DelayAction(action.value?.toIntOrNull() ?: 0)
            WhitelistAction.SNIPPET ->
                KeyboardAction.SnippetAction(requireNotNull(action.value))
        }
        SkinAction.SequencePlaceholder ->
            error("sequence 占位不得转运行时动作（详设 §3.6）")
    }
}
