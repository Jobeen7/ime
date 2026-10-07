package com.jobeen.ime

import com.jobeen.ime.base.speech.FinalTextNormalizer
import org.junit.Assert.assertEquals
import org.junit.Test

class FinalTextNormalizerTest {

    // ── 间距规整（原 normalizeCjkSpacing 行为保持不变） ──

    @Test
    fun spacing_removesSpaceBetweenCjk() {
        assertEquals("你好世界", FinalTextNormalizer.normalizeSpacing("你好 世界"))
    }

    @Test
    fun spacing_keepsSpaceBetweenAsciiWords() {
        assertEquals("hello world", FinalTextNormalizer.normalizeSpacing("hello world"))
    }

    @Test
    fun spacing_removesSpaceBeforeAsciiPunctuation() {
        assertEquals("hello, world", FinalTextNormalizer.normalizeSpacing("hello , world"))
    }

    @Test
    fun spacing_trimsAndHandlesEmpty() {
        assertEquals("", FinalTextNormalizer.normalizeSpacing("   "))
        assertEquals("你好", FinalTextNormalizer.normalizeSpacing("  你好  "))
    }

    // ── 标点合并 ──

    @Test
    fun final_mergesRepeatedSentencePeriods() {
        assertEquals("好的。", FinalTextNormalizer.normalizeFinal("好的。。"))
        assertEquals("好的。", FinalTextNormalizer.normalizeFinal("好的。。。"))
    }

    @Test
    fun final_mergesRepeatedCommas() {
        assertEquals("我，我来了。", FinalTextNormalizer.normalizeFinal("我，，我来了"))
    }

    @Test
    fun final_keepsEmphasisAndEllipsisUntouched() {
        // 「！」不在合并集合：感叹强调不被吞
        assertEquals("太好了！！", FinalTextNormalizer.normalizeFinal("太好了！！"))
        // 省略号原样保留；结尾是「…」不补句号
        assertEquals("我想想……", FinalTextNormalizer.normalizeFinal("我想想……"))
    }

    // ── 句末补句号 ──

    @Test
    fun final_appendsPeriodForCjkEnding() {
        assertEquals("我们明天见。", FinalTextNormalizer.normalizeFinal("我们明天见"))
    }

    @Test
    fun final_noAppendWhenAlreadyTerminated() {
        assertEquals("我们明天见。", FinalTextNormalizer.normalizeFinal("我们明天见。"))
        assertEquals("真的吗？", FinalTextNormalizer.normalizeFinal("真的吗？"))
        assertEquals("太好了！", FinalTextNormalizer.normalizeFinal("太好了！"))
    }

    @Test
    fun final_noAppendForAsciiOrDigitEnding() {
        assertEquals("see you tomorrow", FinalTextNormalizer.normalizeFinal("see you tomorrow"))
        assertEquals("一共 123", FinalTextNormalizer.normalizeFinal("一共 123"))
        assertEquals("版本号 v2", FinalTextNormalizer.normalizeFinal("版本号 v2"))
    }

    @Test
    fun final_noAppendForShortOrEmpty() {
        assertEquals("", FinalTextNormalizer.normalizeFinal(""))
        assertEquals("好", FinalTextNormalizer.normalizeFinal("好"))
    }
}
