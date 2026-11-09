// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime

import com.jobeen.ime.data.Symbol
import com.jobeen.ime.skin.symbol.KaomojiSwipe
import com.jobeen.ime.skin.symbol.KaomojiSwipe.Direction
import com.jobeen.ime.skin.symbol.SubClassifiedBoardState
import com.jobeen.ime.skin.symbol.SubClassifiedSymbols
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 符号板左右滑翻页（施工单 18，与 emoji 板同款接线）：
 * 纯方向判定＋内置表页数＋板状态机翻页夹取。
 * 板级触摸拦截本身需 View/ MotionEvent，走真机验收。
 */
class SymbolSwipeTest {

    private fun state() = SubClassifiedBoardState(
        SubClassifiedSymbols.fromSingleLevel(Symbol.Symbol),
    )

    // ── 方向判定（复用 KaomojiSwipe.directionFor，48dp）──

    @Test
    fun swipe_left_isNextPage() {
        assertEquals(Direction.NEXT, KaomojiSwipe.directionFor(-120f, 5f, 48f))
    }

    @Test
    fun swipe_right_isPrevPage() {
        assertEquals(Direction.PREV, KaomojiSwipe.directionFor(120f, -5f, 48f))
    }

    @Test
    fun swipe_belowMinDistance_isNone() {
        // 短横滑不翻页，归点按。
        assertNull(KaomojiSwipe.directionFor(-47f, 0f, 48f))
        assertNull(KaomojiSwipe.directionFor(47f, 0f, 48f))
    }

    @Test
    fun swipe_atMinDistance_counts() {
        assertEquals(Direction.NEXT, KaomojiSwipe.directionFor(-48f, 0f, 48f))
        assertEquals(Direction.PREV, KaomojiSwipe.directionFor(48f, 0f, 48f))
    }

    @Test
    fun swipe_verticalDominant_isNone() {
        assertNull(KaomojiSwipe.directionFor(-120f, 130f, 48f))
        assertNull(KaomojiSwipe.directionFor(60f, -80f, 48f))
    }

    @Test
    fun swipe_equalAxes_isNone() {
        assertNull(KaomojiSwipe.directionFor(-100f, 100f, 48f))
    }

    // ── 内置表：7 分类全部超一页（25 格／页）──

    @Test
    fun builtinTable_everyCategoryExceedsOnePage() {
        val expected = mapOf(
            "常规" to 80,
            "数字" to 125,
            "序号" to 85,
            "括号" to 70,
            "箭头" to 85,
            "全角" to 55,
            "其他" to 110,
        )
        assertEquals(expected.keys.toList(), Symbol.Symbol.map { it.first.label })
        for ((label, count) in expected) {
            val actual = Symbol.Symbol.first { it.first.label == label }.second.size
            assertEquals("$label 项数", count, actual)
            assertTrue("$label 超一页", actual > 25)
        }
    }

    @Test
    fun builtinTable_pageCounts() {
        val s = state()
        val expectedPages = listOf(4, 5, 4, 3, 4, 3, 5)
        Symbol.Symbol.indices.forEach { index ->
            assertTrue(s.selectParent(index))
            assertEquals(expectedPages[index], s.pageCount)
        }
    }

    // ── 翻页夹取（pageSubCollection 的状态机口径）──

    @Test
    fun nextPage_advancesAndChangesVisibleItems() {
        val s = state()
        assertTrue(s.selectParent(1)) // 数字，125 项，5 页
        val firstPage = s.visibleItems.map { it.displayText }
        assertEquals(25, firstPage.size)
        assertTrue(s.nextPage())
        assertEquals(1, s.page)
        val secondPage = s.visibleItems.map { it.displayText }
        assertEquals(25, secondPage.size)
        assertTrue(firstPage != secondPage)
    }

    @Test
    fun nextPage_atLastPage_isNoOp() {
        val s = state()
        assertTrue(s.selectParent(1)) // 数字，5 页
        repeat(4) { assertTrue(s.nextPage()) }
        assertEquals(4, s.page)
        assertFalse(s.nextPage())
        assertEquals(4, s.page)
    }

    @Test
    fun prevPage_atFirstPage_isNoOp() {
        val s = state()
        assertFalse(s.prevPage())
        assertEquals(0, s.page)
    }

    @Test
    fun prevPage_returnsToPreviousPage() {
        val s = state()
        assertTrue(s.nextPage())
        assertEquals(1, s.page)
        assertTrue(s.prevPage())
        assertEquals(0, s.page)
    }

    @Test
    fun paging_doesNotChangeParent() {
        val s = state()
        assertTrue(s.nextPage())
        assertEquals(0, s.parentIndex)
        assertEquals("常规", s.currentParent?.label)
    }

    @Test
    fun selectParent_resetsPageToZero() {
        val s = state()
        assertTrue(s.nextPage())
        assertTrue(s.selectParent(1))
        assertEquals(0, s.page)
    }
}
