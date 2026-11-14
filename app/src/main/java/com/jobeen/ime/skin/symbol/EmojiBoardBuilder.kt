// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.skin.symbol

import com.jobeen.ime.data.Symbol

/**
 * Emoji 板数据源装配（WO52 7.1／7.4，纯函数）：
 *
 * 优先级：用户自定义分组（7.4，有存档时）＞ 皮肤 `subClassifiedSymbols`
 * ＞ 内置 `Symbol.Emoji` 9 组。最近使用一律拼到 `smileysAndPeople`
 * 组前面——内置表即首组 😀；皮肤板／自定义板里有标签为 😀 的父类
 * 时拼进该父类，没有时拼进首个父类（首组即其“常用”位置）。
 * 拼时组内去重（最近使用的项不再在组内重复出现）。
 */
object EmojiBoardBuilder {

    /** 内置 `smileysAndPeople` 组标签（首组 😀）。 */
    const val SMILEYS_LABEL = "😀"

    /**
     * @param baseCategories 内置一级表（缺省 `Symbol.Emoji`）。
     * @param skinBoard 皮肤二级表（null＝无皮肤覆盖）。
     * @param customGroups 用户自定义分组（null＝无自定义；每组＝标签＋符号列）。
     * @param recents 最近使用（最新在前，已按上限截断）。
     */
    fun build(
        recents: List<String>,
        skinBoard: SubClassifiedSymbols? = null,
        customGroups: List<Pair<String, List<String>>>? = null,
        baseCategories: List<Pair<Symbol.Category, Array<String>>> = Symbol.Emoji,
    ): SubClassifiedSymbols {
        if (!customGroups.isNullOrEmpty()) {
            val parents = customGroups.map { (label, symbols) ->
                SymbolParentCategory(
                    label = label,
                    subCategories = listOf(
                        SymbolSubCategory(
                            label = "全部",
                            items = symbols.map { SymbolItem.Plain(it) },
                        ),
                    ),
                )
            }
            return prependRecents(SubClassifiedSymbols(parents), recents)
        }
        if (skinBoard != null) return prependRecents(skinBoard, recents)
        val categories = if (recents.isEmpty()) {
            baseCategories
        } else {
            baseCategories.mapIndexed { index, (category, symbols) ->
                if (index == 0) {
                    val merged = (recents + symbols.filter { it !in recents })
                    category to merged.toTypedArray()
                } else {
                    category to symbols
                }
            }
        }
        return SubClassifiedSymbols.fromSingleLevel(categories)
    }

    /**
     * 最近使用拼进 😀 父类首个子类前面（组内去重）；无 😀 父类时拼进
     * 首个父类（皮肤／自定义板的首组即其“常用”位置）；空表时原样返回。
     */
    fun prependRecents(
        board: SubClassifiedSymbols,
        recents: List<String>,
    ): SubClassifiedSymbols {
        if (recents.isEmpty() || board.parents.isEmpty()) return board
        val target = board.parents.indexOfFirst { it.label == SMILEYS_LABEL }
            .takeIf { it >= 0 } ?: 0
        val parents = board.parents.mapIndexed { index, parent ->
            if (index != target) return@mapIndexed parent
            val first = parent.subCategories.firstOrNull() ?: return@mapIndexed parent
            val merged = (recents + first.items.map { it.displayText }
                .filter { it !in recents }).map { SymbolItem.Plain(it) }
            parent.copy(
                subCategories = listOf(first.copy(items = merged)) +
                    parent.subCategories.drop(1),
            )
        }
        return board.copy(parents = parents)
    }
}
