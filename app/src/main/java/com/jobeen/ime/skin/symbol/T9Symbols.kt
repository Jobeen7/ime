// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.skin.symbol

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * 九宫格符号板（审计 J t9Symbols / t9HorizontalSymbols）：
 *
 * - t9Symbols：3×3 网格页，每页 9 格；行内不足 3 格时以
 *   空位补齐（固定 3 列网格的占位口径，空位不建键）。
 * - t9HorizontalSymbols：横向九键条，复用横向条布局，
 *   仅缺省列数不同（9 而非 5）；显式 maxColumns 优先。
 * 本层只产规格与分页；3 列网格视图复用 GridKeyboardView
 * （columns=3），由后续接线轮建板挂接。
 */
object T9Symbols {
    const val COLUMNS = 3
    const val CELLS_PER_PAGE = 9
}

data class T9SymbolsSpec(
    val dataSource: List<SymbolItem>,
)

object T9SymbolsParser {

    /** @return 规格（数据源为空时为 null）与丢弃的符号项数。 */
    fun parse(node: JsonElement?): Pair<T9SymbolsSpec?, Int> {
        val obj = node as? JsonObject ?: return null to 0
        val (items, dropped) = SymbolItemParser.parseDataSource(obj["dataSource"])
        if (items.isEmpty()) return null to dropped
        return T9SymbolsSpec(items) to dropped
    }
}

/** 九宫格纯布局：按 9 格分页，每页按 3 列成行（末行空位补 null）。 */
object T9SymbolLayout {

    fun pages(spec: T9SymbolsSpec): List<List<SymbolItem>> =
        spec.dataSource.chunked(T9Symbols.CELLS_PER_PAGE)

    fun pageCount(spec: T9SymbolsSpec): Int = pages(spec).size

    fun rowsForPage(pageItems: List<SymbolItem>): List<List<SymbolItem?>> {
        val rows = pageItems.chunked(T9Symbols.COLUMNS).map { row ->
            row + List(T9Symbols.COLUMNS - row.size) { null }
        }
        return rows
    }

    fun visiblePage(spec: T9SymbolsSpec, page: Int): List<SymbolItem> {
        val all = pages(spec)
        if (all.isEmpty()) return emptyList()
        return all[page.coerceIn(0, all.lastIndex)]
    }
}

object T9HorizontalSymbolsParser {

    /**
     * 横向九键条解析：复用横向条解析，仅缺省列数改为 9；
     * 显式 maxColumns（即使为 5）一律尊重，不改写。
     */
    fun parse(node: JsonElement?): Pair<HorizontalSymbolsSpec?, Int> {
        val obj = node as? JsonObject ?: return null to 0
        val (parsed, dropped) = HorizontalSymbolsParser.parse(node)
        if (parsed == null) return null to dropped
        if (obj["maxColumns"] != null) return parsed to dropped
        return parsed.copy(maxColumns = 9) to dropped
    }
}
