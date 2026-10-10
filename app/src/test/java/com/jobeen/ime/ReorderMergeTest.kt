package com.jobeen.ime

import com.jobeen.ime.data.manager.slotPreservingReorder
import org.junit.Assert.assertEquals
import org.junit.Test

/** 拖动排序落库的槽位保持合并：未显示条目原位不动，显示条目在原槽位集合内重排。 */
class ReorderMergeTest {

    private fun merge(current: List<String>, shown: List<String>) =
        slotPreservingReorder(current, shown) { it }

    @Test
    fun fullSetReordersCompletely() {
        assertEquals(
            listOf("c", "a", "b"),
            merge(listOf("a", "b", "c"), listOf("c", "a", "b")),
        )
    }

    @Test
    fun unshownItemsKeepTheirSlots() {
        // x、y 未显示（保留期外旧条目）：a、b 只在自己的槽位内互换
        assertEquals(
            listOf("x", "b", "a", "y"),
            merge(listOf("x", "a", "b", "y"), listOf("b", "a")),
        )
    }

    @Test
    fun newEntryAtHeadIsNotSunkByConcurrentReorder() {
        // 落库前刚复制的新条目 n 位于队首且未显示：重排其余条目后它仍在队首
        assertEquals(
            listOf("n", "c", "a", "b"),
            merge(listOf("n", "a", "b", "c"), listOf("c", "a", "b")),
        )
    }

    @Test
    fun emptyShownKeepsCurrent() {
        assertEquals(listOf("a", "b"), merge(listOf("a", "b"), emptyList()))
    }
}
