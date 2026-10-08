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
        val rules = listOf(rule("恒星达", "恒星大"))
        val result = VoiceCorrector.correct("去恒星大签约", rules)
        assertEquals("去恒星达签约", result.text)
        assertEquals(1, result.applied.size)
        assertEquals("恒星大", result.applied[0].wrongForm)
        assertEquals("恒星达", result.applied[0].rightForm)
        // start 是纠正后文本中的位置
        assertEquals(1, result.applied[0].start)
    }

    @Test
    fun correct_generalizesAcrossLearnedPositions() {
        // 两条词对分别教了第 0 位（衡）和第 2 位（大）的同音字，
        // 组合出的新错形「衡星大」也应命中
        val rules = listOf(rule("恒星达", "恒星大", "衡星达"))
        assertEquals("恒星达", VoiceCorrector.correct("衡星大", rules).text)
    }

    @Test
    fun correct_unseenCharNotTouched() {
        val rules = listOf(rule("恒星达", "恒星大", "衡星达"))
        // 「答」从未在第 2 位被教过
        val result = VoiceCorrector.correct("恒星答", rules)
        assertEquals("恒星答", result.text)
        assertTrue(result.applied.isEmpty())
    }

    @Test
    fun correct_rightFormItselfUntouched() {
        val rules = listOf(rule("恒星达", "恒星大"))
        val result = VoiceCorrector.correct("去恒星达签约", rules)
        assertEquals("去恒星达签约", result.text)
        assertTrue(result.applied.isEmpty())
    }

    @Test
    fun correct_keepsSurroundingPunctuation() {
        val rules = listOf(rule("恒星达", "恒星大"))
        assertEquals("去恒星达。", VoiceCorrector.correct("去恒星大。", rules).text)
    }

    // ── 规则优先级与不重叠 ──

    @Test
    fun correct_longestSpanWins() {
        val rules = listOf(
            rule("星达", "星大"),
            rule("恒星达", "恒星大"),
        )
        assertEquals("恒星达", VoiceCorrector.correct("恒星大", rules).text)
    }

    @Test
    fun correct_multipleHitsInOneText() {
        val rules = listOf(
            rule("恒星达", "恒星大"),
            rule("陈晓峰", "陈小峰"),
        )
        val result = VoiceCorrector.correct("恒星大的陈小峰", rules)
        assertEquals("恒星达的陈晓峰", result.text)
        assertEquals(2, result.applied.size)
    }

    @Test
    fun correct_shorterWrongFormGrowsText() {
        // 识别漏字：错形 2 字、正形 3 字
        val rules = listOf(rule("恒星达", "恒达"))
        val result = VoiceCorrector.correct("找恒达签约", rules)
        assertEquals("找恒星达签约", result.text)
        assertEquals(1, result.applied[0].start)
    }

    // ── 词形合规 ──

    @Test
    fun validForm_constraints() {
        assertTrue(VoiceCorrector.isValidForm("恒星达"))
        assertTrue(!VoiceCorrector.isValidForm("恒"))
        assertTrue(!VoiceCorrector.isValidForm("恒星达科技有限公"))
        assertTrue(!VoiceCorrector.isValidForm("A星达"))
        assertTrue(!VoiceCorrector.isValidForm("恒星1"))
    }

    @Test
    fun correct_emptyInputs() {
        val rules = listOf(rule("恒星达", "恒星大"))
        assertEquals("", VoiceCorrector.correct("", rules).text)
        assertEquals("恒星大", VoiceCorrector.correct("恒星大", emptyList()).text)
    }
}
