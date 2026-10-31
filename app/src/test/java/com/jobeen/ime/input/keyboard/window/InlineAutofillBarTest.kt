package com.jobeen.ime.input.keyboard.window

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 内联自动填充状态机与分区（纯函数）：条区覆盖层本身是 ViewGroup，
 * 在 JVM 存根上无法实例化，故只测决策与分区；挂载／度量／布局走
 * assembleDebug 编译证明＋真机验证。
 */
class InlineAutofillBarTest {

    @Test
    fun show_requiresSuggestionsAndIdleAndActiveField() {
        assertTrue(inlineAutofillShouldShow(true, false, true))
        assertFalse(inlineAutofillShouldShow(false, false, true))
        assertFalse(inlineAutofillShouldShow(true, true, true))
        assertFalse(inlineAutofillShouldShow(true, false, false))
        assertFalse(inlineAutofillShouldShow(false, true, false))
    }

    @Test
    fun partition_splitsPinnedAndScrollableInOrder() {
        val (pinned, scrollable) = partitionInlineSuggestions(listOf(true, false, true, false))
        assertEquals(listOf(0, 2), pinned)
        assertEquals(listOf(1, 3), scrollable)
    }

    @Test
    fun partition_allPinned() {
        val (pinned, scrollable) = partitionInlineSuggestions(listOf(true, true))
        assertEquals(listOf(0, 1), pinned)
        assertEquals(emptyList<Int>(), scrollable)
    }

    @Test
    fun partition_nonePinned() {
        val (pinned, scrollable) = partitionInlineSuggestions(listOf(false, false))
        assertEquals(emptyList<Int>(), pinned)
        assertEquals(listOf(0, 1), scrollable)
    }

    @Test
    fun partition_empty() {
        val (pinned, scrollable) = partitionInlineSuggestions(emptyList())
        assertEquals(emptyList<Int>(), pinned)
        assertEquals(emptyList<Int>(), scrollable)
    }
}
