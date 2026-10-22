package com.jobeen.ime

import com.jobeen.ime.engine.rime.core.SyllableProto
import com.jobeen.ime.engine.rime.core.hasUnresolvedDigitSegment
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「无候选直出」收紧谓词（第十六批）的规格，源自
 * review16/verify-digit-commit 的主机实测形态：九宫格纯数字串
 * 打到候选变空的长度时引擎翻转——组字回落成原始数字、拼写为
 * 空（或全数字），此时 emitResponse 不再合成提交、保留组字。
 * lite / english 组字只有字母（数字在键盘层直提、不进组字），
 * 谓词恒假、原直出行为不变。逐例钉住边界形态。
 */
class UnresolvedDigitSegmentTest {

    private fun seg(raw: String, spelling: String, text: String = "") =
        SyllableProto(rawInput = raw, spelling = spelling, text = text)

    @Test
    fun flippedSingleSegment_spellingEmpty() {
        // 77777 / 444444 / 45745 的翻转形态：整段原始数字、无拼写
        assertTrue(hasUnresolvedDigitSegment(arrayOf(seg("77777", ""))))
    }

    @Test
    fun flippedSingleSegment_spellingAllDigits() {
        // 翻转的另一落形：拼写本身就是原始数字串
        assertTrue(hasUnresolvedDigitSegment(arrayOf(seg("77777", "77777"))))
    }

    @Test
    fun parsedHeadFlippedTail_afterSegmentation() {
        // 9633 分词后尾段翻转：前段拼写正常、尾段未解析数字
        assertTrue(
            hasUnresolvedDigitSegment(
                arrayOf(seg("96", "wo"), seg("33'", "de"), seg("77", ""))
            )
        )
    }

    @Test
    fun confirmedHeadFlippedTail() {
        // 已确认段（text 有值）不算未解析，但尾段翻转仍命中整串
        assertTrue(
            hasUnresolvedDigitSegment(
                arrayOf(seg("96", "wo", text = "我"), seg("77", ""))
            )
        )
    }

    @Test
    fun normalT9Composition_allSegmentsSpelled() {
        // 正常组字：T9 段原始输入本就是数字，但拼写已解析出字母
        assertFalse(
            hasUnresolvedDigitSegment(
                arrayOf(seg("96", "wo"), seg("33", "de"))
            )
        )
    }

    @Test
    fun allConfirmedSegments() {
        // 段全部已成词：即使原始输入是数字也不命中
        assertFalse(
            hasUnresolvedDigitSegment(
                arrayOf(seg("96", "wo", text = "我"), seg("33", "de", text = "的"))
            )
        )
    }

    @Test
    fun liteLetterStringNoCandidates() {
        // lite 字母原始串（无候选直出的保留场景）：无数字段
        assertFalse(hasUnresolvedDigitSegment(arrayOf(seg("gks", "gks"))))
        assertFalse(hasUnresolvedDigitSegment(arrayOf(seg("aaaa", ""))))
    }

    @Test
    fun englishWordNoCandidates() {
        // english 词典外单词：原始输入为字母，谓词不命中
        assertFalse(hasUnresolvedDigitSegment(arrayOf(seg("hello", "hello"))))
    }

    @Test
    fun separatorOnlyInput() {
        // 纯分隔符段：原始输入不含数字，不命中（原行为保留）
        assertFalse(hasUnresolvedDigitSegment(arrayOf(seg("'", ""))))
    }

    @Test
    fun emptySyllables() {
        assertFalse(hasUnresolvedDigitSegment(emptyArray()))
    }
}
