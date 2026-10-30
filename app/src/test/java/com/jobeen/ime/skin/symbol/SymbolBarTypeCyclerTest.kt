// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.skin.symbol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 符号栏类型循环（接线轮 R5，纯函数）。 */
class SymbolBarTypeCyclerTest {

    private val labels = listOf("常规", "数字", "括号")

    @Test
    fun cyclesForward() {
        assertEquals("数字", SymbolBarTypeCycler.next(labels, "常规"))
        assertEquals("括号", SymbolBarTypeCycler.next(labels, "数字"))
    }

    @Test
    fun wrapsAtEnd() {
        assertEquals("常规", SymbolBarTypeCycler.next(labels, "括号"))
    }

    @Test
    fun unknownOrNullCurrentGoesToFirst() {
        assertEquals("常规", SymbolBarTypeCycler.next(labels, "不存在"))
        assertEquals("常规", SymbolBarTypeCycler.next(labels, null))
    }

    @Test
    fun singleLabelStays() {
        assertEquals("常规", SymbolBarTypeCycler.next(listOf("常规"), "常规"))
    }

    @Test
    fun emptyLabelsReturnsNull() {
        assertNull(SymbolBarTypeCycler.next(emptyList(), "常规"))
    }
}
