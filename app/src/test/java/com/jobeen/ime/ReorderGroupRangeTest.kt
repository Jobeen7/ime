package com.jobeen.ime

import com.jobeen.ime.input.panel.component.reorderGroupRange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 剪贴板拖动排序的同组区间：置顶段与未置顶段各自成组、拖动不跨组。 */
class ReorderGroupRangeTest {

    @Test
    fun pinnedSegmentIsOwnGroup() {
        val pinned = listOf(true, true, false, false, false)
        assertEquals(0..1, reorderGroupRange(pinned, 0))
        assertEquals(0..1, reorderGroupRange(pinned, 1))
    }

    @Test
    fun unpinnedSegmentIsOwnGroup() {
        val pinned = listOf(true, true, false, false, false)
        assertEquals(2..4, reorderGroupRange(pinned, 2))
        assertEquals(2..4, reorderGroupRange(pinned, 4))
    }

    @Test
    fun singleGroupAndEdges() {
        assertEquals(0..2, reorderGroupRange(listOf(false, false, false), 1))
        assertEquals(0..0, reorderGroupRange(listOf(true), 0))
        assertTrue(reorderGroupRange(emptyList(), 0).isEmpty())
        assertTrue(reorderGroupRange(listOf(true), 5).isEmpty())
    }
}
