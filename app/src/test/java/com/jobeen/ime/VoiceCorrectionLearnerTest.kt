package com.jobeen.ime

import com.jobeen.ime.base.speech.AppliedCorrection
import com.jobeen.ime.base.speech.VoiceCorrectionLearner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceCorrectionLearnerTest {

    // ── extractChange ──

    @Test
    fun extract_sameReturnsNull() {
        assertNull(VoiceCorrectionLearner.extractChange("你好世界", "你好世界"))
    }

    @Test
    fun extract_middleSubstitution() {
        val change = VoiceCorrectionLearner.extractChange("去恒星大签约", "去恒星达签约")!!
        assertEquals(3, change.start)
        assertEquals("大", change.oldText)
        assertEquals("达", change.newText)
    }

    @Test
    fun extract_insertionAndDeletion() {
        val ins = VoiceCorrectionLearner.extractChange("去签约", "去恒星达签约")!!
        assertEquals("", ins.oldText)
        assertEquals("恒星达", ins.newText)
        val del = VoiceCorrectionLearner.extractChange("去恒星达签约", "去签约")!!
        assertEquals("恒星达", del.oldText)
        assertEquals("", del.newText)
    }

    // ── decidePairing：基本配对 ──

    @Test
    fun pairing_learnsHomophoneSpan() {
        // 段里是错形「陈小峰」，打出正形「陈晓峰」：同位 2/3 相同
        val pairing = VoiceCorrectionLearner.decidePairing(
            segment = "联系人陈小峰明天到",
            applied = emptyList(),
            word = "陈晓峰",
        )!!
        assertEquals(3, pairing.start)
        assertEquals("陈小峰", pairing.span)
        val action = pairing.action as VoiceCorrectionLearner.PairingAction.Learn
        assertEquals("陈小峰", action.wrongForm)
        assertEquals("陈晓峰", action.rightForm)
    }

    @Test
    fun pairing_twoCharWordNeedsOneSameChar() {
        val pairing = VoiceCorrectionLearner.decidePairing(
            segment = "去星大开会",
            applied = emptyList(),
            word = "星达",
        )!!
        assertEquals("星大", pairing.span)
    }

    @Test
    fun pairing_exactSameSpanIsNotLearned() {
        // 段里本来就是正形：不学（全同不配对）
        assertNull(
            VoiceCorrectionLearner.decidePairing("联系人陈晓峰明天到", emptyList(), "陈晓峰")
        )
    }

    @Test
    fun pairing_unrelatedWordNeverPairs() {
        // 改述式编辑换上的词与段内任何片段字位重合都不足：不学
        assertNull(
            VoiceCorrectionLearner.decidePairing("明天去公司开会", emptyList(), "企业")
        )
        assertNull(
            VoiceCorrectionLearner.decidePairing("衡星大明天到", emptyList(), "陈晓峰")
        )
    }

    @Test
    fun pairing_ambiguousCandidatesAreDropped() {
        // 「陈小峰」（对 2 字）与「陈晓风」（对 2 字）都够格：歧义，放弃
        assertNull(
            VoiceCorrectionLearner.decidePairing("陈小峰和陈晓风都到", emptyList(), "陈晓峰")
        )
    }

    @Test
    fun pairing_invalidWordIgnored() {
        assertNull(VoiceCorrectionLearner.decidePairing("恒星大", emptyList(), "达"))
        assertNull(VoiceCorrectionLearner.decidePairing("恒星大", emptyList(), "A星达"))
        assertNull(VoiceCorrectionLearner.decidePairing("短", emptyList(), "恒星达"))
    }

    // ── decidePairing：与自动纠正的互动 ──

    private val applied = listOf(
        AppliedCorrection(start = 1, wrongForm = "恒星大", rightForm = "恒星达")
    )

    @Test
    fun pairing_revertDisables() {
        // 自动纠成「恒星达」，他打回「恒星大」：停用
        val pairing = VoiceCorrectionLearner.decidePairing("找恒星达签约", applied, "恒星大")!!
        val action = pairing.action as VoiceCorrectionLearner.PairingAction.Disable
        assertEquals("恒星达", action.rightForm)
    }

    @Test
    fun pairing_replaceCorrectionLearnsAndDisables() {
        // 自动纠成「恒星达」，他打出「衡星达」：学新对并停用旧正形
        val pairing = VoiceCorrectionLearner.decidePairing("找恒星达签约", applied, "衡星达")!!
        val action = pairing.action as VoiceCorrectionLearner.PairingAction.LearnAndDisable
        assertEquals("恒星达", action.wrongForm)
        assertEquals("衡星达", action.rightForm)
        assertEquals("恒星达", action.disableRightForm)
    }

    @Test
    fun pairing_identicalSpansAmbiguousDropped() {
        // 段内有两处相同片段都够格配对：无法确定他指的是哪一处，
        // 按歧义放弃（不学也不停用）
        val pairing = VoiceCorrectionLearner.decidePairing(
            segment = "恒星达与恒星达合作",
            applied = listOf(AppliedCorrection(0, "恒星大", "恒星达")),
            word = "恒星大",
        )
        assertNull(pairing)
    }
}
