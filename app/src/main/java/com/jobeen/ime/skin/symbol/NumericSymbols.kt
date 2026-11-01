// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.skin.symbol

import com.jobeen.ime.data.Symbol
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * 数字符号集（审计 J numericSymbols，独立集合类型）：
 *
 * - 身份：类型 id 恒为 "numericSymbols"，与三者都不同——
 *   通用 数字分类（仅是符号键盘内一分类）、NumberKeyboard
 *   （数字输入框的功能垫：数字 + ,/./@/回车，随 inputType
 *   出现）、numeric 类型键盘（主键盘类型表一项）。
 * - 内容：缺省取内置 数字分类的全部符号（同一字形、不同
 *   身份）；皮肤节点提供 dataSource 时以节点内容为准。
 * - 用途：作为独立数据源挂给符号板/横向条时，调用方按
 *   类型 id 取集，不再从分类表中按 label 捞取。
 */
data class NumericSymbolsCollection(
    val type: String = "numericSymbols",
    val items: List<SymbolItem>,
)

object NumericSymbols {

    /** 内置数字符号集：取 数字分类（缺失时为空集，不虚构字形）。 */
    fun builtIn(): NumericSymbolsCollection {
        val glyphs = Symbol.Symbol.firstOrNull { it.first.label == "数字" }?.second
        return NumericSymbolsCollection(
            items = glyphs?.map { SymbolItem.Plain(it) }.orEmpty(),
        )
    }

    /**
     * 节点解析：{dataSource: [...]} 有有效项时用节点内容，
     * 否则（无节点/空数据源）回落内置集。
     */
    fun parse(node: JsonElement?): NumericSymbolsCollection {
        val obj = node as? JsonObject
        val (items, _) = SymbolItemParser.parseDataSource(obj?.get("dataSource"))
        if (items.isNotEmpty()) return NumericSymbolsCollection(items = items)
        return builtIn()
    }
}
