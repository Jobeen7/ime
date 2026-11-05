// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.skin.symbol

import com.jobeen.ime.input.keyboard.key.KeyboardAction
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 符号项（审计 J 数据源，三形态）：
 *
 * - Plain：纯字符串，显示即提交文本（既有内置表口径）；
 * - LabelValue：{label, value}，显示 label、提交 value
 *   （显示与提交分离，如显示“空格”提交实际字符）；
 * - LabelActionStyle：{label, action, styleName?}，点按分发
 *   action 而非提交；styleName 可选，缺省为空（空 = 取条目
 *   所在格的默认文字样式）；action 恒取自数据项本身，永不
 *   取自 styleName 所指的样式（样式只定渲染，不定行为）。
 * 显示文本统一经 [displayText] 取，调用方不得再猜形态。
 */
sealed interface SymbolItem {
    val displayText: String

    data class Plain(val text: String) : SymbolItem {
        override val displayText: String get() = text
    }

    data class LabelValue(val label: String, val value: String) : SymbolItem {
        override val displayText: String get() = label
    }

    data class LabelActionStyle(
        val label: String,
        val action: String,
        val styleName: String = "",
    ) : SymbolItem {
        override val displayText: String get() = label
    }
}

object SymbolItemParser {

    /** 解析数据源数组：非法项逐项丢弃，只记数。 */
    fun parseDataSource(node: JsonElement?): Pair<List<SymbolItem>, Int> {
        val arr = node as? JsonArray ?: return emptyList<SymbolItem>() to 0
        val out = ArrayList<SymbolItem>()
        var dropped = 0
        for (element in arr) {
            val item = parseItem(element) ?: run { dropped++; continue }
            out += item
        }
        return out to dropped
    }

    /** 解析单项：null = 非法项（调用方记 dropped）。 */
    fun parseItem(element: JsonElement?): SymbolItem? {
        if (element is JsonPrimitive && element.isString) {
            val text = element.content.takeIf { it.isNotEmpty() } ?: return null
            return SymbolItem.Plain(text)
        }
        val obj = element as? JsonObject ?: return null
        val label = obj.stringField("label")?.takeIf { it.isNotEmpty() } ?: return null
        val value = obj.stringField("value")
        if (value != null) {
            if (value.isEmpty()) return null
            return SymbolItem.LabelValue(label, value)
        }
        val action = obj.stringField("action")?.takeIf { it.isNotEmpty() } ?: return null
        // styleName 可选：缺省或空串均归一为空（取格默认文字样式），
        // 不再整项丢弃；action 恒取自本数据项，与样式名无关。
        val styleName = obj.stringField("styleName")?.takeIf { it.isNotEmpty() } ?: ""
        return SymbolItem.LabelActionStyle(label, action, styleName)
    }

    private fun JsonObject.stringField(name: String): String? {
        val primitive = this[name] as? JsonPrimitive ?: return null
        return if (primitive.isString) primitive.content else null
    }
}

/**
 * 符号项点按动作解析（纯函数）：显示与提交/动作分离后，
 * 板内 keyDef 不再一律 CommitAction——
 * - Plain → 提交原文；LabelValue → 提交 value；
 * - LabelActionStyle → 动作名映射：resume/return 回主键盘，
 *   symbolic_lock_toggle / emoji_lock_toggle 翻对应板锁，
 *   对端 `#` 快捷指令（与 WhitelistAction 的 `#` 项同名表，
 *   见 SkinKeyModel）按键动作同口径分发；
 * - 未知 `#` 快捷指令 → 安全默认 NoOpAction（绝不提交 label
 *   文本上屏：对端 `{"shortcut": "#copy", "label": "复制"}` 这类
 *   项此前会误上屏“复制”二字）；未知非 `#` 动作名沿旧口径
 *   回落提交 label（点按永不丢动作）。
 * 动作恒取自数据项的 action 字段：本解析不查任何样式表，
 * styleName 取值（存在、缺省、未知）皆不影响分发结果。
 */
object SymbolItemActionResolver {

    fun resolve(item: SymbolItem): KeyboardAction = when (item) {
        is SymbolItem.Plain -> KeyboardAction.CommitAction(item.text)
        is SymbolItem.LabelValue -> KeyboardAction.CommitAction(item.value)
        is SymbolItem.LabelActionStyle -> when (item.action) {
            "resume", "return" -> KeyboardAction.ResumeAction
            "symbolic_lock_toggle" -> KeyboardAction.ToggleSymbolicLockAction
            "emoji_lock_toggle" -> KeyboardAction.ToggleEmojiLockAction
            "#cut" -> KeyboardAction.ClipboardAction(KeyboardAction.ClipboardAction.Kind.CUT)
            "#copy" -> KeyboardAction.ClipboardAction(KeyboardAction.ClipboardAction.Kind.COPY)
            "#paste" -> KeyboardAction.ClipboardAction(KeyboardAction.ClipboardAction.Kind.PASTE)
            "#clearSystemPasteboard" ->
                KeyboardAction.ClipboardAction(KeyboardAction.ClipboardAction.Kind.CLEAR)
            "#selectText" -> KeyboardAction.SelectAllAction
            "#deleteText" -> KeyboardAction.DeleteTextAction
            "#undo" -> KeyboardAction.HostUndoAction
            "#redo" -> KeyboardAction.HostRedoAction
            "#showPasteboardView" -> KeyboardAction.ShowPasteboardAction
            "#keyboardPerformance" -> KeyboardAction.ShowPerformanceAction
            "#showPhraseView" -> KeyboardAction.ShowPhraseViewAction
            "#keyboardMenu" -> KeyboardAction.KeyboardMenuAction
            "#toggleSymbolBar" -> KeyboardAction.ToggleSymbolBarAction
            "#toggleCommandView" -> KeyboardAction.ToggleCommandViewAction
            "#subCollectionPageUp" -> KeyboardAction.SubCollectionPageUpAction
            "#subCollectionPageDown" -> KeyboardAction.SubCollectionPageDownAction
            "#简繁切换" -> KeyboardAction.ToggleTraditionalAction
            "#中英切换" -> KeyboardAction.ToggleAsciiModeAction
            "#次选上屏" -> KeyboardAction.SelectCandidateIndexAction(1)
            "#三选上屏" -> KeyboardAction.SelectCandidateIndexAction(2)
            "#方案切换" -> KeyboardAction.RotateSchema
            "#RimeSwitcher" -> KeyboardAction.ShowInputMethodPickerAction
            "#行首" -> KeyboardAction.AppKeyEventAction(122)
            "#行尾" -> KeyboardAction.AppKeyEventAction(123)
            "#换行" -> KeyboardAction.CommitAction("\n")
            "#Enter" -> KeyboardAction.ReturnAction()
            "#重输" -> KeyboardAction.ClearAction
            "#candidatesBarStateToggle" -> KeyboardAction.CandidatesBarToggleAction
            "#rimePreviousPage", "#verticalCandidatesPageUp" ->
                KeyboardAction.KeyCodeAction(92)
            "#rimeNextPage", "#verticalCandidatesPageDown" ->
                KeyboardAction.KeyCodeAction(93)
            "#toggleEmbeddedInputMode" -> KeyboardAction.ToggleEmbeddedInputAction
            "#toggleScriptView" -> KeyboardAction.ToggleScriptViewAction
            else -> if (item.action.startsWith("#")) {
                // 未知快捷指令：安全默认（不提交 label 文本）。
                KeyboardAction.NoOpAction
            } else {
                KeyboardAction.CommitAction(item.label)
            }
        }
    }
}
