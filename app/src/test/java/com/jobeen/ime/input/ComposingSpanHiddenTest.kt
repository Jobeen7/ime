// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.input

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 藏 span 效果判定（I-S4，上一位代理引入、本轮验证补测）：
 * setComposingText("", 1) 的返回值不可信，藏没藏住须凭光标前
 * 文本复核；证实不了时按未藏处理（fail-closed）。
 */
class ComposingSpanHiddenTest {

    @Test
    fun emptyComposingIsTriviallyGone() {
        // 组字串为空：屏上无物可护，恒判已藏。
        assertTrue(TypedUndoLedger.isComposingSpanGone(null, ""))
        assertTrue(TypedUndoLedger.isComposingSpanGone("abc", ""))
    }

    @Test
    fun unreadableBeforeTextIsNotGone() {
        // 读回为空（编辑器不支持该调用）：无法证实，按未藏处理。
        assertFalse(TypedUndoLedger.isComposingSpanGone(null, "ni"))
    }

    @Test
    fun composingStillBeforeCursorIsNotGone() {
        assertFalse(TypedUndoLedger.isComposingSpanGone("ni", "ni"))
        assertFalse(TypedUndoLedger.isComposingSpanGone("abni", "ni"))
    }

    @Test
    fun composingNoLongerBeforeCursorIsGone() {
        assertTrue(TypedUndoLedger.isComposingSpanGone("ab", "ni"))
        assertTrue(TypedUndoLedger.isComposingSpanGone("", "ni"))
        assertTrue(TypedUndoLedger.isComposingSpanGone("n", "ni"))
    }
}
