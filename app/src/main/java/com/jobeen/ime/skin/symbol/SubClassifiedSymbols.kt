// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.skin.symbol

import com.jobeen.ime.data.Symbol
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 二级分类符号（审计 J subClassifiedSymbols，两级 + 角标）：
 *
 * - 一级（父类）→ 二级（子类）→ 符号项三级结构；既有
 *   Symbol.Symbol/Emoji 的一级表是其特例（见 [fromSingleLevel]）。
 * - 角标（badge）：父类角标显示其全部子类符号总数，子类角标
 *   显示自身符号数；显式 badge 字段优先于派生数。角标的
 *   字号/颜色属于样式层字段（badgeFontSize 等），本层只产
 *   角标文本，不碰样式文件。
 * - 两级面板的状态机见 [SubClassifiedBoardState]：选父类即
 *   回到其首个子类（对端两级面板口径）；面板视图本身复用
 *   既有侧栏 + 网格，由后续接线轮挂接（侧栏视图文件属
 *   渲染侧，本波不改）。
 */
data class SymbolSubCategory(
    val label: String,
    val items: List<SymbolItem>,
    val badge: String? = null,
) {
    /** 子类角标：显式值优先，否则为自身符号数（0 项时无角标）。 */
    val badgeText: String? get() = badge ?: items.size.takeIf { it > 0 }?.toString()
}

data class SymbolParentCategory(
    val label: String,
    val subCategories: List<SymbolSubCategory>,
    val badge: String? = null,
) {
    /** 父类角标：显式值优先，否则为全部子类符号总数。 */
    val badgeText: String?
        get() = badge ?: subCategories.sumOf { it.items.size }
            .takeIf { it > 0 }?.toString()
}

data class SubClassifiedSymbols(
    val parents: List<SymbolParentCategory>,
) {
    /** 既有一级表转二级：每类成一父类，旗下仅一子类“全部”。 */
    companion object {
        fun fromSingleLevel(
            categories: List<Pair<Symbol.Category, Array<String>>>,
        ): SubClassifiedSymbols = SubClassifiedSymbols(
            parents = categories.map { (category, symbols) ->
                SymbolParentCategory(
                    label = category.label,
                    subCategories = listOf(
                        SymbolSubCategory(
                            label = "全部",
                            items = symbols.map { SymbolItem.Plain(it) },
                        ),
                    ),
                )
            },
        )
    }
}

object SubClassifiedSymbolsParser {

    /**
     * 解析：顶层数组或 {parents: [...]}；父项 {label,
     * subCategories: [{label, symbols|dataSource: [...], badge?}],
     * badge?}。无有效父项时返回 null（调用方走既有一级表）；
     * 非法父/子类逐项丢弃，只记数。
     */
    fun parse(node: JsonElement?): Pair<SubClassifiedSymbols?, Int> {
        val arr = when (node) {
            is JsonArray -> node
            is JsonObject -> node["parents"] as? JsonArray ?: return null to 0
            else -> return null to 0
        }
        val parents = ArrayList<SymbolParentCategory>()
        var dropped = 0
        for (element in arr) {
            val (parent, subDropped) = parseParent(element)
            dropped += subDropped
            if (parent == null) {
                dropped++
                continue
            }
            parents += parent
        }
        if (parents.isEmpty()) return null to dropped
        return SubClassifiedSymbols(parents) to dropped
    }

    /** @return 父类（null = 该父项非法）与该父项内丢弃的子类数。 */
    private fun parseParent(element: JsonElement): Pair<SymbolParentCategory?, Int> {
        val obj = element as? JsonObject ?: return null to 0
        val label = obj.stringField("label")?.takeIf { it.isNotEmpty() } ?: return null to 0
        val subsNode = obj["subCategories"] as? JsonArray ?: return null to 0
        var subDropped = 0
        val subs = ArrayList<SymbolSubCategory>()
        for (subElement in subsNode) {
            val sub = parseSub(subElement) ?: run { subDropped++; continue }
            subs += sub
        }
        if (subs.isEmpty()) return null to subDropped
        return SymbolParentCategory(
            label = label,
            subCategories = subs,
            badge = obj.stringField("badge")?.takeIf { it.isNotEmpty() },
        ) to subDropped
    }

    private fun parseSub(element: JsonElement): SymbolSubCategory? {
        val obj = element as? JsonObject ?: return null
        val label = obj.stringField("label")?.takeIf { it.isNotEmpty() } ?: return null
        val itemsNode = obj["symbols"] ?: obj["dataSource"] ?: return null
        val (items, _) = SymbolItemParser.parseDataSource(itemsNode)
        if (items.isEmpty()) return null
        return SymbolSubCategory(
            label = label,
            items = items,
            badge = obj.stringField("badge")?.takeIf { it.isNotEmpty() },
        )
    }

    private fun JsonObject.stringField(name: String): String? {
        val primitive = this[name] as? JsonPrimitive ?: return null
        return if (primitive.isString) primitive.content else null
    }
}

/**
 * 二级面板纯状态机：父类与子类各一索引；切父类时子类
 * 恒回首项；越界选择为空操作（索引不动）。
 *
 * 翻页（P1-5）：当前子类的符号项按 [pageSize] 切页（与板
 * 网格 5 列 × 5 行一致）；切父类／子类时页码恒回 0；页内
 * 无更多时 nextPage/prevPage 返回 false（调用方空操作，
 * 不换父类、不循环绕回）。
 */
class SubClassifiedBoardState(
    val symbols: SubClassifiedSymbols,
    val pageSize: Int = DEFAULT_PAGE_SIZE,
) {
    var parentIndex: Int = 0
        private set
    var subIndex: Int = 0
        private set

    /** 当前页（0 起）；切父类／子类时恒回 0。 */
    var page: Int = 0
        private set

    val currentParent: SymbolParentCategory? get() = symbols.parents.getOrNull(parentIndex)
    val currentSub: SymbolSubCategory? get() = currentParent?.subCategories?.getOrNull(subIndex)
    val currentItems: List<SymbolItem> get() = currentSub?.items.orEmpty()
    val parentLabels: List<String> get() = symbols.parents.map { it.label }
    val subLabels: List<String>
        get() = currentParent?.subCategories?.map { it.label }.orEmpty()

    /** 当前子类总页数（0 项时为 0 页）。 */
    val pageCount: Int
        get() {
            val size = pageSize.coerceAtLeast(1)
            return (currentItems.size + size - 1) / size
        }

    /** 当前页可见项（页码恒夹取有效范围）。 */
    val visibleItems: List<SymbolItem>
        get() {
            if (currentItems.isEmpty()) return emptyList()
            val size = pageSize.coerceAtLeast(1)
            val clamped = page.coerceIn(0, (pageCount - 1).coerceAtLeast(0))
            val from = clamped * size
            return currentItems.subList(from, minOf(from + size, currentItems.size))
        }

    fun selectParent(index: Int): Boolean {
        if (symbols.parents.getOrNull(index) == null) return false
        parentIndex = index
        subIndex = 0
        page = 0
        return true
    }

    /**
     * 下一父类（H2-7 颜文字左右滑）：已是末类时返回 false
     * （索引不动，不循环绕回；与 nextPage 同口径）。
     */
    fun nextParent(): Boolean = selectParent(parentIndex + 1)

    /**
     * 上一父类（H2-7 颜文字左右滑）：已是首类时返回 false
     * （索引不动；与 prevPage 同口径）。
     */
    fun prevParent(): Boolean = selectParent(parentIndex - 1)

    fun selectSub(index: Int): Boolean {
        if (currentParent?.subCategories?.getOrNull(index) == null) return false
        subIndex = index
        page = 0
        return true
    }

    /** 下一页：已是末页时返回 false（页码不动）。 */
    fun nextPage(): Boolean {
        if (page + 1 >= pageCount) return false
        page++
        return true
    }

    /** 上一页：已是首页时返回 false（页码不动）。 */
    fun prevPage(): Boolean {
        if (page == 0) return false
        page--
        return true
    }

    companion object {
        /** 每页格数：板网格 5 列 × 5 行。 */
        const val DEFAULT_PAGE_SIZE = 25
    }
}
