// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime

import com.jobeen.ime.data.manager.CandidateLinePolicy
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 长候选切分（W17-5）纯函数单测：切分点不得拆开代理对——星号平面
 * 字符（如 emoji）拦腰切断会留孤代理项，渲染成 �。测量按 UTF-16
 * 码元计（每码元 1.0），切分点完全由宽度决定，与真实字宽无关。
 * 均不依赖 Android 框架。
 */
class CandidateLinePolicyTest {

    private val measure: (String) -> Float = { it.length.toFloat() }

    @Test
    fun truncate_emojiAtCutPoint_excludesWholePair() {
        // "ab😀cd"（6 码元）：宽 4 只够 3 码元＋省略号，二分得 lo=3，
        // 正落在 😀 的高／低代理之间——旧实现返回 "ab\uD83D…"（孤代理）。
        val result = CandidateLinePolicy.truncateWithEllipsis("ab\uD83D\uDE00cd", 4f, measure)
        assertEquals("ab…", result)
    }

    @Test
    fun truncate_emojiAtStart_keptWhole() {
        // "😀abcdef"（8 码元）：宽 3，lo=2 恰为整对 😀，不退格。
        val result = CandidateLinePolicy.truncateWithEllipsis("\uD83D\uDE00abcdef", 3f, measure)
        assertEquals("\uD83D\uDE00…", result)
    }

    @Test
    fun truncate_asciiUnchanged() {
        assertEquals("abc…", CandidateLinePolicy.truncateWithEllipsis("abcdef", 4f, measure))
        assertEquals("hello", CandidateLinePolicy.truncateWithEllipsis("hello", 5f, measure))
    }

    @Test
    fun layout_emojiAtLineSplit_notSplit() {
        // "ab😀cdefgh"（10 码元），宽 2.5、下限字号 0.7：第一行二分
        // 得 3 码元，正拆 😀——旧实现首行 "ab\uD83D"；新实现退一格
        // 为 "ab"，第二行整对 😀 起首再截断。
        val layout = CandidateLinePolicy.layoutLongCandidate("ab\uD83D\uDE00cdefgh", 2.5f, measure)
        assertEquals(listOf("ab", "\uD83D\uDE00…"), layout.lines)
        assertEquals(0.7f, layout.scale, 0f)
    }

    @Test
    fun layout_asciiTwoLinesUnchanged() {
        val layout = CandidateLinePolicy.layoutLongCandidate("abcdefghij", 4f, measure)
        assertEquals(listOf("abcde", "fghij"), layout.lines)
        assertEquals(0.7f, layout.scale, 0f)
    }
}
