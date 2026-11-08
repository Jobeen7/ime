// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime

import com.jobeen.ime.data.Kaomoji
import com.jobeen.ime.skin.symbol.KaomojiSwipe
import com.jobeen.ime.skin.symbol.KaomojiSwipe.Direction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 颜文字板左右滑（H2-7，D17）：纯方向判定＋内置表完整性。 */
class KaomojiSwipeTest {

    // ── 方向判定 ──

    @Test
    fun swipe_left_isNextCategory() {
        assertEquals(Direction.NEXT, KaomojiSwipe.directionFor(-120f, 5f, 48f))
    }

    @Test
    fun swipe_right_isPrevCategory() {
        assertEquals(Direction.PREV, KaomojiSwipe.directionFor(120f, -5f, 48f))
    }

    @Test
    fun swipe_belowMinDistance_isNone() {
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

    @Test
    fun swipe_zeroMovement_isNone() {
        assertNull(KaomojiSwipe.directionFor(0f, 0f, 48f))
    }

    // ── 内置表完整性 ──

    @Test
    fun data_hasSixCategories() {
        assertEquals(6, Kaomoji.Categories.size)
    }

    @Test
    fun data_labelsAreUniqueAndNonBlank() {
        val labels = Kaomoji.Categories.map { it.first.label }
        assertEquals(labels.size, labels.toSet().size)
        assertTrue(labels.all { it.isNotBlank() })
    }

    @Test
    fun data_everyCategoryHasTenNonBlankItems() {
        for ((category, items) in Kaomoji.Categories) {
            assertEquals("分类 ${category.label} 应有 10 条", 10, items.size)
            assertTrue("分类 ${category.label} 有空条目", items.all { it.isNotBlank() })
        }
    }

    @Test
    fun data_noDuplicateItemWithinCategory() {
        for ((category, items) in Kaomoji.Categories) {
            assertEquals(
                "分类 ${category.label} 有重复条目",
                items.size, items.toSet().size,
            )
        }
    }

    @Test
    fun data_itemsContainNoBackslashOrQuote() {
        for ((_, items) in Kaomoji.Categories) {
            assertTrue(items.all { !it.contains('\\') && !it.contains('"') })
        }
    }
}
