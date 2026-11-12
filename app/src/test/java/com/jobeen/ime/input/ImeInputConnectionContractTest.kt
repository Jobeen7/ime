// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.input

import org.junit.Assert.assertEquals
import org.junit.Test

/** 虚拟连接的 InputConnection 契约修复（I-L1）：钳制、光标落位、粘贴守卫。 */
class ImeInputConnectionContractTest {

    @Test
    fun clampComposingRegionKeepsValidRange() {
        assertEquals(2 to 5, clampComposingRegion(2, 5, 10))
        assertEquals(0 to 10, clampComposingRegion(0, 10, 10))
    }

    @Test
    fun clampComposingRegionClampsOutOfBounds() {
        assertEquals(0 to 5, clampComposingRegion(-3, 5, 10))
        assertEquals(2 to 10, clampComposingRegion(2, 20, 10))
        assertEquals(0 to 10, clampComposingRegion(-5, 20, 10))
        assertEquals(10 to 10, clampComposingRegion(12, 20, 10))
    }

    @Test
    fun clampComposingRegionCollapsesReversedRange() {
        // 起止颠倒：钳后空区间，只清 span、不建新区。
        assertEquals(8 to 8, clampComposingRegion(8, 3, 10))
        assertEquals(0 to 0, clampComposingRegion(0, 0, 0))
    }

    @Test
    fun cursorAfterCommitHonorsPositionOne() {
        // newCursorPosition = 1：紧贴插入文本末尾（与旧实现一致）。
        assertEquals(7, cursorAfterCommit(5, 2, 1, 10))
        assertEquals(10, cursorAfterCommit(8, 2, 1, 10))
    }

    @Test
    fun cursorAfterCommitHonorsPositiveOffset() {
        assertEquals(8, cursorAfterCommit(5, 2, 2, 10))
        // 超出文本末尾时钳回末尾。
        assertEquals(10, cursorAfterCommit(9, 2, 5, 10))
    }

    @Test
    fun cursorAfterCommitHonorsZeroAndNegative() {
        // 0：紧贴插入文本开头；-1：开头前一格（成对符号的括号中间）。
        assertEquals(5, cursorAfterCommit(5, 2, 0, 10))
        assertEquals(4, cursorAfterCommit(5, 2, -1, 10))
        // 开头前无处可退时钳回 0。
        assertEquals(0, cursorAfterCommit(2, 1, -5, 10))
        assertEquals(0, cursorAfterCommit(0, 3, 0, 10))
    }

    @Test
    fun pasteItemIndexGuardsEmptyClipboard() {
        assertEquals(-1, pasteItemIndex(0))
        assertEquals(0, pasteItemIndex(1))
        assertEquals(0, pasteItemIndex(5))
    }
}
