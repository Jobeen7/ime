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
 *   未知动作名回落提交 label（点按永不丢动作）。
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
            else -> KeyboardAction.CommitAction(item.label)
        }
    }
}
