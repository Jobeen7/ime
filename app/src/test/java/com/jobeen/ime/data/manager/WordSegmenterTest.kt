// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.data.manager

import java.text.BreakIterator
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WO52 1.9 分词多选：切分＋锚点区间选择＋边缘自动滚动。
 *
 * 单测用 java.text 分词（与 android.icu 同一切分核心；见 WordSegmenter
 * 注释：java.text 中文切分较粗，连续汉字常为一段，真机 ICU 更细）。
 */
class WordSegmenterTest {

    private fun segment(text: String): List<WordSegmenter.Word> =
        WordSegmenter.segment(text, BreakIterator.getWordInstance(Locale.CHINESE))

    @Test
    fun mixedChineseEnglishDigits() {
        val words = segment("Hello world 你好")
        assertEquals(listOf("Hello", "world", "你好"), words.map { it.text })
    }

    @Test
    fun codeMessageSegments() {
        val words = segment("您的验证码是123456，请勿泄露")
        assertEquals(
            listOf("您的验证码是", "123456", "，", "请勿泄露"),
            words.map { it.text },
        )
    }

    @Test
    fun digitsSplitFromChinese() {
        val words = segment("复制这个123456粘贴")
        assertEquals(listOf("复制这个", "123456", "粘贴"), words.map { it.text })
    }

    @Test
    fun dateSegments() {
        val words = segment("2026年10月29日会议")
        assertEquals(
            listOf("2026", "年", "10", "月", "29", "日会议"),
            words.map { it.text },
        )
    }

    @Test
    fun emptyAndBlankYieldNoWords() {
        assertTrue(segment("").isEmpty())
        assertTrue(segment("   ").isEmpty())
    }

    @Test
    fun wordSpansCoverOriginal() {
        val text = "Hello world 你好"
        val words = segment(text)
        // 词格按原串下标连续：相邻两格之间只差空白
        for (i in 0 until words.size - 1) {
            val gap = text.substring(words[i].end, words[i + 1].start)
            assertTrue(gap.isBlank())
        }
        assertEquals(text, text.substring(words.first().start, words.last().end))
    }

    @Test
    fun selectionRangeAnchorOrder() {
        assertEquals(1..3, WordSegmenter.selectionRange(1, 3, 5))
        assertEquals(1..3, WordSegmenter.selectionRange(3, 1, 5))
        assertEquals(2..2, WordSegmenter.selectionRange(2, 2, 5))
        assertEquals(0..4, WordSegmenter.selectionRange(-2, 9, 5))
        assertTrue(WordSegmenter.selectionRange(0, 0, 0).isEmpty())
    }

    @Test
    fun selectedTextKeepsSeparators() {
        val text = "Hello world 你好"
        val words = segment(text)
        assertEquals("Hello world", WordSegmenter.selectedText(text, words, 0..1))
        assertEquals("world 你好", WordSegmenter.selectedText(text, words, 1..2))
        assertEquals(text, WordSegmenter.selectedText(text, words, 0..2))
        assertEquals("", WordSegmenter.selectedText(text, words, IntRange.EMPTY))
    }

    @Test
    fun autoScrollTriggersNearEdgesOnly() {
        // 顶沿：越近越快，16dp 处归零
        assertEquals(-12f, WordSegmenter.autoScrollDeltaDp(0f, 400f))
        assertEquals(-6f, WordSegmenter.autoScrollDeltaDp(8f, 400f))
        assertEquals(0f, WordSegmenter.autoScrollDeltaDp(16f, 400f))
        // 底沿：对称
        assertEquals(12f, WordSegmenter.autoScrollDeltaDp(400f, 400f))
        assertEquals(6f, WordSegmenter.autoScrollDeltaDp(392f, 400f))
        assertEquals(0f, WordSegmenter.autoScrollDeltaDp(384f, 400f))
        // 中间不滚动
        assertEquals(0f, WordSegmenter.autoScrollDeltaDp(200f, 400f))
    }
}
