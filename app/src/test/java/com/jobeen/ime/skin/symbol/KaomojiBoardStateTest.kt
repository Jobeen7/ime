// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.skin.symbol

import com.jobeen.ime.data.Kaomoji
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 颜文字板分类切换（H2-7，D17）：父类前进／后退夹取＋提交自动返回。 */
class KaomojiBoardStateTest {

    private fun state() = SubClassifiedBoardState(
        SubClassifiedSymbols.fromSingleLevel(Kaomoji.Categories),
    )

    @Test
    fun nextParent_movesForwardOneCategory() {
        val s = state()
        assertTrue(s.nextParent())
        assertEquals(1, s.parentIndex)
        assertEquals("难过", s.currentParent?.label)
    }

    @Test
    fun prevParent_atFirstCategory_isNoOp() {
        val s = state()
        assertFalse(s.prevParent())
        assertEquals(0, s.parentIndex)
    }

    @Test
    fun nextParent_atLastCategory_isNoOp() {
        val s = state()
        repeat(Kaomoji.Categories.size - 1) { assertTrue(s.nextParent()) }
        assertEquals(Kaomoji.Categories.size - 1, s.parentIndex)
        assertFalse(s.nextParent())
        assertEquals(Kaomoji.Categories.size - 1, s.parentIndex)
    }

    @Test
    fun parentSwitch_resetsSubAndPage() {
        val s = state()
        assertTrue(s.nextParent())
        assertEquals(0, s.subIndex)
        assertEquals(0, s.page)
    }

    @Test
    fun parentSwitch_showsNextCategoryItems() {
        val s = state()
        assertTrue(s.nextParent())
        assertTrue(s.visibleItems.any { it.displayText == "(T_T)" })
        assertTrue(s.nextParent())
        assertTrue(s.visibleItems.any { it.displayText == "(｡･ω･｡)" })
    }

    @Test
    fun swipeRoundTrip_returnsToFirstCategory() {
        val s = state()
        assertTrue(s.nextParent())
        assertTrue(s.nextParent())
        assertTrue(s.prevParent())
        assertTrue(s.prevParent())
        assertEquals(0, s.parentIndex)
    }
}
