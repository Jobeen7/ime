package com.jobeen.ime

import com.jobeen.ime.input.ImeInputConnection
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 码点 → UTF-16 单元换算的纯函数测试：deleteSurroundingTextInCodePoints
 * 的入参是码点数、deleteSurroundingText 的口径是 UTF-16 单元数，
 * 换算必须按选区两端逐码点行走：代理对（emoji）1 码点 = 2 单元、
 * 组合字符自成 1 码点、请求超界钳制到可用量、绝不越界抛异常。
 */
class ImeInputConnectionTest {

    private fun convert(
        text: String,
        selStart: Int,
        selEnd: Int,
        beforeCp: Int,
        afterCp: Int,
    ): Pair<Int, Int> =
        ImeInputConnection.codePointsToUtf16Counts(text, selStart, selEnd, beforeCp, afterCp)

    @Test
    fun asciiCountsMatchCodePoints() {
        // 纯 ASCII：码点数与 UTF-16 单元数 1:1
        assertEquals(2 to 3, convert("hello world", 5, 5, 2, 3))
        assertEquals(0 to 0, convert("hello", 2, 2, 0, 0))
    }

    @Test
    fun emojiSurrogatePairIsOneCodePointTwoUnits() {
        // "a😀b"：😀 = U+1F600，占 UTF-16 索引 1..2，全长 4
        val text = "a\uD83D\uDE00b"
        // 光标在 emoji 之后（索引 3）：向前 1 码点跨整个代理对 = 2 单元
        assertEquals(2 to 0, convert(text, 3, 3, 1, 0))
        // 向前 2 码点连 'a' 一起 = 3 单元
        assertEquals(3 to 0, convert(text, 3, 3, 2, 0))
        // 光标在 emoji 之前（索引 1）：向后 1 码点 = 2 单元
        assertEquals(0 to 2, convert(text, 1, 1, 0, 1))
    }

    @Test
    fun combiningMarkIsItsOwnCodePoint() {
        // "é" 分解式 = 'e' + U+0301 组合重音：2 个 UTF-16 单元、2 个码点
        val text = "e\u0301"
        // 向前 1 码点只跨组合符本身 = 1 单元（码点口径不合并字素簇）
        assertEquals(1 to 0, convert(text, 2, 2, 1, 0))
        assertEquals(2 to 0, convert(text, 2, 2, 2, 0))
    }

    @Test
    fun nonEmptySelectionWalksFromBothEnds() {
        // 选区非空时前后分别以选区左端/右端为原点行走
        assertEquals(1 to 1, convert("abcdef", 2, 4, 1, 1))
        // "a😀b😀c"（长 7），选区为中间的 'b'（3..4）：两侧各 1 码点都跨代理对
        val text = "a\uD83D\uDE00b\uD83D\uDE00c"
        assertEquals(2 to 2, convert(text, 3, 4, 1, 1))
    }

    @Test
    fun overRequestClampsToAvailable() {
        // 请求量超过两端可用码点数时钳制到全量，不抛异常
        assertEquals(1 to 1, convert("ab", 1, 1, 100, 100))
        assertEquals(2 to 0, convert("ab", 2, 2, 100, 0))
        assertEquals(0 to 2, convert("ab", 0, 0, 0, 100))
    }

    @Test
    fun emptyTextReturnsZero() {
        assertEquals(0 to 0, convert("", 0, 0, 3, 3))
    }

    @Test
    fun negativeRequestTreatedAsZero() {
        assertEquals(0 to 0, convert("abc", 1, 1, -5, -1))
    }
}
