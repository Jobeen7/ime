// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.skin.symbol

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull

/**
 * 横向符号条（审计 J horizontalSymbols）：
 *
 * - maxColumns：每页列数，缺省 5；域外/非法值回缺省，
 *   不丢弃整条（列数是布局参数，不是数据合法性）。
 * - contentRightToLeft：true 时条内顺序反向（从右往左读）。
 * - dataSource：符号项数组（复用 SymbolItem 三形态）。
 * - cellStyle：单元格样式名，只原样携带，样式层按名取用。
 * 条视图复用既有符号栏行（SymbolKeyboard 的 bar 行），由
 * 后续接线轮挂接；本层只产规格与分页，不碰视图文件。
 */
data class HorizontalSymbolsSpec(
    val maxColumns: Int = 5,
    val contentRightToLeft: Boolean = false,
    val dataSource: List<SymbolItem>,
    val cellStyle: String? = null,
)

object HorizontalSymbolsParser {

    /** @return 规格（数据源为空时为 null）与丢弃的符号项数。 */
    fun parse(node: JsonElement?): Pair<HorizontalSymbolsSpec?, Int> {
        val obj = node as? JsonObject ?: return null to 0
        val maxColumns = (obj["maxColumns"] as? JsonPrimitive)?.intOrNull
            ?.takeIf { it in 1..12 } ?: 5
        // 布尔字面与字符串 "true" 两种写法都接受。
        val rtlNode = obj["contentRightToLeft"] as? JsonPrimitive
        val rtl = when {
            rtlNode == null -> false
            rtlNode.isString -> rtlNode.content == "true"
            else -> rtlNode.booleanOrNull ?: false
        }
        val (items, dropped) = SymbolItemParser.parseDataSource(obj["dataSource"])
        if (items.isEmpty()) return null to dropped
        return HorizontalSymbolsSpec(
            maxColumns = maxColumns,
            contentRightToLeft = rtl,
            dataSource = items,
            cellStyle = (obj["cellStyle"] as? JsonPrimitive)
                ?.takeIf { it.isString }?.content?.takeIf { it.isNotEmpty() },
        ) to dropped
    }
}

/** 横向条纯布局：顺序（RTL 反向）与按列分页。 */
object HorizontalSymbolLayout {

    /** 展示顺序：RTL 时整条反向（页内顺序亦反向）。 */
    fun orderedItems(spec: HorizontalSymbolsSpec): List<SymbolItem> =
        if (spec.contentRightToLeft) spec.dataSource.asReversed() else spec.dataSource

    /** 按 maxColumns 切页；空数据源为 0 页。 */
    fun pages(spec: HorizontalSymbolsSpec): List<List<SymbolItem>> =
        orderedItems(spec).chunked(spec.maxColumns)

    fun pageCount(spec: HorizontalSymbolsSpec): Int = pages(spec).size

    /** 越界页码夹取到首/末页（调用方翻页不判界）。 */
    fun visibleItems(spec: HorizontalSymbolsSpec, page: Int): List<SymbolItem> {
        val all = pages(spec)
        if (all.isEmpty()) return emptyList()
        return all[page.coerceIn(0, all.lastIndex)]
    }
}

/** 横向条翻页状态：页码恒夹取有效范围。 */
class HorizontalSymbolStripState(
    val spec: HorizontalSymbolsSpec,
) {
    var page: Int = 0
        private set

    val pageCount: Int get() = HorizontalSymbolLayout.pageCount(spec)
    val visibleItems: List<SymbolItem>
        get() = HorizontalSymbolLayout.visibleItems(spec, page)

    fun nextPage(): Boolean {
        if (page + 1 >= pageCount) return false
        page++
        return true
    }

    fun prevPage(): Boolean {
        if (page == 0) return false
        page--
        return true
    }
}
