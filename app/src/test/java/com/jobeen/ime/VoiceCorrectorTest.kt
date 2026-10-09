package com.jobeen.ime

import com.jobeen.ime.base.speech.VoiceCorrector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceCorrectorTest {

    private fun rule(right: String, vararg wrongs: String) =
        VoiceCorrector.buildRule(right, wrongs.toList())!!

    // ── 基本纠错 ──

    @Test
    fun correct_replacesLearnedWrongForm() {
        val rules = listOf(rule("星辰海", "星辰大"))
        val result = VoiceCorrector.correct("去星辰大签约", rules)
        assertEquals("去星辰海签约", result.text)
        assertEquals(1, result.applied.size)
        assertEquals("星辰大", result.applied[0].wrongForm)
        assertEquals("星辰海", result.applied[0].rightForm)
        // start 是纠正后文本中的位置
        assertEquals(1, result.applied[0].start)
    }

    @Test
    fun correct_generalizesAcrossLearnedPositions() {
        // 两条词对分别教了第 1 位（尘）和第 2 位（嗨）的同音字，
        // 组合出的新错形「星尘嗨」也应命中
        val rules = listOf(rule("星辰海", "星辰嗨", "星尘海"))
        assertEquals("星辰海", VoiceCorrector.correct("星尘嗨", rules).text)
        // 正形保护：已学正形本身一字不差出现时永不改写
        val untouched = VoiceCorrector.correct("星辰海", rules)
        assertEquals("星辰海", untouched.text)
        assertTrue(untouched.applied.isEmpty())
    }

    @Test
    fun correct_learnedRightFormProtectedFromOtherRules() {
        // 「星辰海」与「星尘海」互为对方的错形：两条规则的字集都能
        // 命中对方的正形；正形保护下，两个已学正形都不许被改写
        val rules = listOf(
            rule("星辰海", "星尘海"),
            rule("星尘海", "星辰海"),
        )
        assertEquals("星辰海", VoiceCorrector.correct("星辰海", rules).text)
        assertEquals("星尘海", VoiceCorrector.correct("星尘海", rules).text)
    }

    @Test
    fun correct_unseenCharNotTouched() {
        val rules = listOf(rule("星辰海", "星辰大", "衡辰海"))
        // 「答」从未在第 2 位被教过
        val result = VoiceCorrector.correct("星辰答", rules)
        assertEquals("星辰答", result.text)
        assertTrue(result.applied.isEmpty())
    }

    @Test
    fun correct_rightFormItselfUntouched() {
        val rules = listOf(rule("星辰海", "星辰大"))
        val result = VoiceCorrector.correct("去星辰海签约", rules)
        assertEquals("去星辰海签约", result.text)
        assertTrue(result.applied.isEmpty())
    }

    @Test
    fun correct_keepsSurroundingPunctuation() {
        val rules = listOf(rule("星辰海", "星辰大"))
        assertEquals("去星辰海。", VoiceCorrector.correct("去星辰大。", rules).text)
    }

    // ── 规则优先级与不重叠 ──

    @Test
    fun correct_longestSpanWins() {
        val rules = listOf(
            rule("辰海", "辰大"),
            rule("星辰海", "星辰大"),
        )
        assertEquals("星辰海", VoiceCorrector.correct("星辰大", rules).text)
    }

    @Test
    fun correct_multipleHitsInOneText() {
        val rules = listOf(
            rule("星辰海", "星辰大"),
            rule("星辰峰", "星尘峰"),
        )
        val result = VoiceCorrector.correct("星辰大的星尘峰", rules)
        assertEquals("星辰海的星辰峰", result.text)
        assertEquals(2, result.applied.size)
    }

    @Test
    fun correct_shorterWrongFormGrowsText() {
        // 识别漏字：错形 2 字、正形 3 字
        val rules = listOf(rule("星辰海", "星海"))
        val result = VoiceCorrector.correct("找星海签约", rules)
        assertEquals("找星辰海签约", result.text)
        assertEquals(1, result.applied[0].start)
    }

    // ── 词形合规 ──

    @Test
    fun validForm_constraints() {
        assertTrue(VoiceCorrector.isValidForm("星辰海"))
        assertTrue(!VoiceCorrector.isValidForm("星"))
        assertTrue(!VoiceCorrector.isValidForm("星辰海科技有限公"))
        assertTrue(!VoiceCorrector.isValidForm("A星海"))
        assertTrue(!VoiceCorrector.isValidForm("星辰1"))
    }

    @Test
    fun correct_emptyInputs() {
        val rules = listOf(rule("星辰海", "星辰大"))
        assertEquals("", VoiceCorrector.correct("", rules).text)
        assertEquals("星辰大", VoiceCorrector.correct("星辰大", emptyList()).text)
    }
}
