// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.skin.notify

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull

/**
 * 条件样式（重点候选 8-1，轻量变体）：只换样式不换动作。
 *
 * 皮肤顶层 `conditionStyles` 数组的一项：
 * - conditionKey：符号键盘锁定态（`symbolLock`）或 rime 选项
 *   （`rime$<选项名>`）；其他键名非法，整项丢弃；
 * - conditionValue：布尔（选项开关／锁定态）或整数数组
 *   （锁定态多值集合；空数组非法，整项丢弃）；
 * - styleName：单个样式名或样式名数组（空／全空非法，整项丢弃）。
 *
 * 语义：条件样式门控同名样式——键的 styleRef 被某项命名且该项
 * 条件成立时样式生效；被命名但条件不成立时样式不生效（键回退
 * 无样式渲染，见 KeyStyleResolver 的门控参数）。未被任何项命
 * 名的样式不受影响。条件值类型失配（布尔条件对整数现值等）
 * 视为不成立，绝不崩。
 */
sealed interface ConditionValue {
    data class Bool(val value: Boolean) : ConditionValue
    data class Ints(val values: Set<Int>) : ConditionValue
}

/** 条件现值（供给方提供）：布尔或单个整数。 */
sealed interface ConditionCurrent {
    data class Bool(val value: Boolean) : ConditionCurrent
    data class IntValue(val value: Int) : ConditionCurrent
}

data class ConditionStyle(
    val conditionKey: String,
    val conditionValue: ConditionValue,
    val styleNames: List<String>,
)

object ConditionEvaluator {

    /** 全部成立项的样式名集合（顺序无关，调用方只判成员）。 */
    fun activeStyleNames(
        entries: List<ConditionStyle>,
        current: Map<String, ConditionCurrent>,
    ): Set<String> {
        val out = LinkedHashSet<String>()
        for (entry in entries) {
            if (matches(entry, current[entry.conditionKey])) out += entry.styleNames
        }
        return out
    }

    fun matches(entry: ConditionStyle, current: ConditionCurrent?): Boolean {
        if (current == null) return false
        return when (val expected = entry.conditionValue) {
            is ConditionValue.Bool ->
                current is ConditionCurrent.Bool && current.value == expected.value
            is ConditionValue.Ints ->
                current is ConditionCurrent.IntValue && current.value in expected.values
        }
    }
}

object ConditionStyleParser {

    const val SYMBOL_LOCK_KEY = "symbolLock"
    const val RIME_OPTION_PREFIX = "rime$"

    /** @return 解析出的条件样式表与丢弃项数（坏项只记数，调用方转 issue）。 */
    fun parse(node: JsonArray): Pair<List<ConditionStyle>, Int> {
        val out = ArrayList<ConditionStyle>()
        var dropped = 0
        for (element in node) {
            val obj = element as? JsonObject ?: run { dropped++; continue }
            val parsed = parseEntry(obj) ?: run { dropped++; continue }
            out += parsed
        }
        return out to dropped
    }

    private fun parseEntry(obj: JsonObject): ConditionStyle? {
        val conditionKey = obj["conditionKey"].stringContentOrNull()
            ?.takeIf { it.isNotEmpty() } ?: return null
        if (conditionKey != SYMBOL_LOCK_KEY &&
            !(conditionKey.startsWith(RIME_OPTION_PREFIX) &&
                conditionKey.length > RIME_OPTION_PREFIX.length)
        ) {
            return null
        }
        val conditionValue = parseValue(obj["conditionValue"]) ?: return null
        val styleNames = parseStyleNames(obj["styleName"]) ?: return null
        return ConditionStyle(conditionKey, conditionValue, styleNames)
    }

    private fun parseValue(node: JsonElement?): ConditionValue? {
        if (node == null) return null
        if (node is JsonPrimitive) {
            val bool = node.booleanOrNull ?: return null
            return ConditionValue.Bool(bool)
        }
        if (node is JsonArray) {
            val ints = LinkedHashSet<Int>()
            for (element in node) {
                ints += (element as? JsonPrimitive)?.intOrNull ?: return null
            }
            if (ints.isEmpty()) return null
            return ConditionValue.Ints(ints)
        }
        return null
    }

    private fun parseStyleNames(node: JsonElement?): List<String>? {
        if (node == null) return null
        if (node is JsonPrimitive) {
            val name = node.stringContentOrNull()?.takeIf { it.isNotEmpty() } ?: return null
            return listOf(name)
        }
        if (node is JsonArray) {
            val names = node.mapNotNull { element ->
                element.stringContentOrNull()?.takeIf { it.isNotEmpty() }
            }
            if (names.isEmpty()) return null
            return names
        }
        return null
    }

    private fun JsonElement?.stringContentOrNull(): String? {
        val primitive = this as? JsonPrimitive ?: return null
        return if (primitive.isString) primitive.content else null
    }
}
