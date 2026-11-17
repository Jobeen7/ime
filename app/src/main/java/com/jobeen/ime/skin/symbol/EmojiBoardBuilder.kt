// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.skin.symbol

import com.jobeen.ime.data.Symbol

/**
 * Emoji 板数据源装配（纯函数）：
 *
 * 优先级：皮肤 `subClassifiedSymbols`
 * ＞ 内置 `Symbol.Emoji` 9 组。
 */
object EmojiBoardBuilder {

    /**
     * @param baseCategories 内置一级表（缺省 `Symbol.Emoji`）。
     * @param skinBoard 皮肤二级表（null＝无皮肤覆盖）。
     */
    fun build(
        skinBoard: SubClassifiedSymbols? = null,
        baseCategories: List<Pair<Symbol.Category, Array<String>>> = Symbol.Emoji,
    ): SubClassifiedSymbols {
        if (skinBoard != null) return skinBoard
        return SubClassifiedSymbols.fromSingleLevel(baseCategories)
    }
}
