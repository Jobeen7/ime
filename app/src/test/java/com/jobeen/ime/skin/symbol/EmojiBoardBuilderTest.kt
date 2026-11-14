// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.skin.symbol

import com.jobeen.ime.data.Symbol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** WO52 7.1：最近使用拼首组（纯函数）；7.4 自定义分组优先于皮肤／内置。 */
class EmojiBoardBuilderTest {

    private fun base() = listOf(
        Symbol.Category("😀") to arrayOf("😀", "😃", "😄"),
        Symbol.Category("👋") to arrayOf("👋", "🤚"),
    )

    @Test
    fun build_noRecents_builtinGroupsUnchanged() {
        val board = EmojiBoardBuilder.build(emptyList(), baseCategories = base())
        assertEquals(listOf("😀", "👋"), board.parents.map { it.label })
        assertEquals(
            listOf("😀", "😃", "😄"),
            board.parents[0].subCategories[0].items.map { it.displayText },
        )
    }

    @Test
    fun build_recentsPrependedToFirstGroup_deduped() {
        val board = EmojiBoardBuilder.build(
            listOf("😄", "🥰"),
            baseCategories = base(),
        )
        assertEquals(
            listOf("😄", "🥰", "😀", "😃"),
            board.parents[0].subCategories[0].items.map { it.displayText },
        )
        // 第二组不动。
        assertEquals(
            listOf("👋", "🤚"),
            board.parents[1].subCategories[0].items.map { it.displayText },
        )
    }

    @Test
    fun build_skinBoard_recentsPrependedToSmileysParent() {
        val skin = SubClassifiedSymbols.fromSingleLevel(base())
        val board = EmojiBoardBuilder.build(listOf("🥰"), skinBoard = skin)
        assertEquals(
            listOf("🥰", "😀", "😃", "😄"),
            board.parents[0].subCategories[0].items.map { it.displayText },
        )
    }

    @Test
    fun build_skinBoardWithoutSmileys_recentsPrependedToFirstParent() {
        val skin = SubClassifiedSymbols(
            listOf(
                SymbolParentCategory(
                    "动物",
                    listOf(SymbolSubCategory("全部", listOf(SymbolItem.Plain("🐶")))),
                ),
            ),
        )
        val board = EmojiBoardBuilder.build(listOf("🥰"), skinBoard = skin)
        assertEquals(
            listOf("🥰", "🐶"),
            board.parents[0].subCategories[0].items.map { it.displayText },
        )
    }

    @Test
    fun build_customGroups_winOverSkinAndBuiltin() {
        val skin = SubClassifiedSymbols.fromSingleLevel(base())
        val board = EmojiBoardBuilder.build(
            emptyList(),
            skinBoard = skin,
            customGroups = listOf("我的" to listOf("😀", "😂")),
        )
        assertEquals(listOf("我的"), board.parents.map { it.label })
        assertEquals(
            listOf("😀", "😂"),
            board.parents[0].subCategories[0].items.map { it.displayText },
        )
    }

    @Test
    fun build_customGroups_recentsPrependedToFirstCustomGroup() {
        val board = EmojiBoardBuilder.build(
            listOf("🥰"),
            customGroups = listOf(
                "我的" to listOf("😀", "😂"),
                "动物" to listOf("🐶"),
            ),
        )
        assertEquals(
            listOf("🥰", "😀", "😂"),
            board.parents[0].subCategories[0].items.map { it.displayText },
        )
        assertEquals(
            listOf("🐶"),
            board.parents[1].subCategories[0].items.map { it.displayText },
        )
    }

    @Test
    fun prependRecents_emptyRecents_returnsSameBoard() {
        val skin = SubClassifiedSymbols.fromSingleLevel(base())
        assertTrue(EmojiBoardBuilder.prependRecents(skin, emptyList()) === skin)
    }
}
