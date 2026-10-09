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
        val change = VoiceCorrectionLearner.extractChange("去星辰大签约", "去星辰海签约")!!
        assertEquals(3, change.start)
        assertEquals("大", change.oldText)
        assertEquals("海", change.newText)
    }

    @Test
    fun extract_insertionAndDeletion() {
        val ins = VoiceCorrectionLearner.extractChange("去签约", "去星辰海签约")!!
        assertEquals("", ins.oldText)
        assertEquals("星辰海", ins.newText)
        val del = VoiceCorrectionLearner.extractChange("去星辰海签约", "去签约")!!
        assertEquals("星辰海", del.oldText)
        assertEquals("", del.newText)
    }

    // ── decidePairing：基本配对 ──

    @Test
    fun pairing_learnsHomophoneSpan() {
        // 段里是错形「星尘峰」，打出正形「星辰峰」：同位 2/3 相同
        val pairing = VoiceCorrectionLearner.decidePairing(
            segment = "联系人星尘峰明天到",
            applied = emptyList(),
            word = "星辰峰",
        )!!
        assertEquals(3, pairing.start)
        assertEquals("星尘峰", pairing.span)
        val action = pairing.action as VoiceCorrectionLearner.PairingAction.Learn
        assertEquals("星尘峰", action.wrongForm)
        assertEquals("星辰峰", action.rightForm)
    }

    @Test
    fun pairing_twoCharWordNeedsOneSameChar() {
        val pairing = VoiceCorrectionLearner.decidePairing(
            segment = "去星大开会",
            applied = emptyList(),
            word = "星海",
        )!!
        assertEquals("星大", pairing.span)
    }

    @Test
    fun pairing_exactSameSpanIsNotLearned() {
        // 段里本来就是正形：不学（全同不配对）
        assertNull(
            VoiceCorrectionLearner.decidePairing("联系人星辰峰明天到", emptyList(), "星辰峰")
        )
    }

    @Test
    fun pairing_unrelatedWordNeverPairs() {
        // 改述式编辑换上的词与段内任何片段字位重合都不足：不学
        assertNull(
            VoiceCorrectionLearner.decidePairing("明天去公司开会", emptyList(), "企业")
        )
        assertNull(
            VoiceCorrectionLearner.decidePairing("衡星大明天到", emptyList(), "星辰峰")
        )
    }

    @Test
    fun pairing_ambiguousCandidatesAreDropped() {
        // 「星尘峰」（对 2 字）与「星辰风」（对 2 字）都够格：歧义，放弃
        assertNull(
            VoiceCorrectionLearner.decidePairing("星尘峰和星辰风都到", emptyList(), "星辰峰")
        )
    }

    @Test
    fun pairing_invalidWordIgnored() {
        assertNull(VoiceCorrectionLearner.decidePairing("星辰大", emptyList(), "海"))
        assertNull(VoiceCorrectionLearner.decidePairing("星辰大", emptyList(), "A星海"))
        assertNull(VoiceCorrectionLearner.decidePairing("短", emptyList(), "星辰海"))
    }

    // ── decidePairing：与自动纠正的互动 ──

    private val applied = listOf(
        AppliedCorrection(start = 1, wrongForm = "星辰大", rightForm = "星辰海")
    )

    @Test
    fun pairing_revertDisables() {
        // 自动纠成「星辰海」，他打回「星辰大」：停用
        val pairing = VoiceCorrectionLearner.decidePairing("找星辰海签约", applied, "星辰大")!!
        val action = pairing.action as VoiceCorrectionLearner.PairingAction.Disable
        assertEquals("星辰海", action.rightForm)
    }

    @Test
    fun pairing_replaceCorrectionLearnsAndDisables() {
        // 自动纠成「星辰海」，他打出「衡辰海」：学新对并停用旧正形
        val pairing = VoiceCorrectionLearner.decidePairing("找星辰海签约", applied, "衡辰海")!!
        val action = pairing.action as VoiceCorrectionLearner.PairingAction.LearnAndDisable
        assertEquals("星辰海", action.wrongForm)
        assertEquals("衡辰海", action.rightForm)
        assertEquals("星辰海", action.disableRightForm)
    }

    @Test
    fun pairing_identicalSpansAmbiguousDropped() {
        // 段内有两处相同片段都够格配对：无法确定他指的是哪一处，
        // 按歧义放弃（不学也不停用）
        val pairing = VoiceCorrectionLearner.decidePairing(
            segment = "星辰海与星辰海合作",
            applied = listOf(AppliedCorrection(0, "星辰大", "星辰海")),
            word = "星辰大",
        )
        assertNull(pairing)
    }

    // ── decidePairing：区间配对（位置约束） ──

    @Test
    fun pairing_outsideEditRangeNotPaired() {
        // 候选片段在段首，但编辑发生在段尾另一处：不参与配对
        val pairing = VoiceCorrectionLearner.decidePairing(
            segment = "星尘峰明天去远山",
            applied = emptyList(),
            word = "星辰峰",
            editRanges = listOf(VoiceCorrectionLearner.EditRange(7, 8)),
        )
        assertNull(pairing)
    }

    @Test
    fun pairing_emptyEditRangesNeverPairs() {
        // 没有任何段内编辑信号：哪怕片段再像也不配对
        val pairing = VoiceCorrectionLearner.decidePairing(
            segment = "联系人星尘峰明天到",
            applied = emptyList(),
            word = "星辰峰",
            editRanges = emptyList(),
        )
        assertNull(pairing)
    }

    @Test
    fun pairing_overlappingEditRangePairs() {
        // 编辑区间与候选片段重叠：正常配对
        val pairing = VoiceCorrectionLearner.decidePairing(
            segment = "联系人星尘峰明天到",
            applied = emptyList(),
            word = "星辰峰",
            editRanges = listOf(VoiceCorrectionLearner.EditRange(3, 6)),
        )!!
        assertEquals("星尘峰", pairing.span)
        assertEquals(VoiceCorrectionLearner.EditRange(3, 6), pairing.spanRange)
    }

    @Test
    fun pairing_adjacentToDeletedRangePairs() {
        // 先删错词、再在删除点打正形：编辑点（空区间）恰落在候选
        // 片段的尾端，紧邻也算相关
        val pairing = VoiceCorrectionLearner.decidePairing(
            segment = "联系人星尘峰明天到",
            applied = emptyList(),
            word = "星辰峰",
            editRanges = listOf(VoiceCorrectionLearner.EditRange(6, 6)),
        )!!
        assertEquals("星尘峰", pairing.span)
    }

    @Test
    fun pairing_ambiguityJudgedWithinEditRangeOnly() {
        // 段内两处都够格，但只有编辑区间内的那处参与配对：不再歧义
        val pairing = VoiceCorrectionLearner.decidePairing(
            segment = "星尘峰和星辰风都到",
            applied = emptyList(),
            word = "星辰峰",
            editRanges = listOf(VoiceCorrectionLearner.EditRange(0, 3)),
        )!!
        assertEquals("星尘峰", pairing.span)
    }

    // ── decidePairing：已消费区间 ──

    @Test
    fun pairing_consumedSpanNotPairedAgain() {
        // 同一原文区间已学成一条：后续词段不得再对它配对
        val pairing = VoiceCorrectionLearner.decidePairing(
            segment = "联系人星尘峰明天到",
            applied = emptyList(),
            word = "星辰峰",
            editRanges = listOf(VoiceCorrectionLearner.EditRange(3, 6)),
            consumedRanges = listOf(VoiceCorrectionLearner.EditRange(3, 6)),
        )
        assertNull(pairing)
    }

    @Test
    fun pairing_distinctSpanStillPairsAfterOtherConsumed() {
        // 消费的是另一处不重叠区间时，本区间真实改词仍可各学一条
        val first = VoiceCorrectionLearner.decidePairing(
            segment = "星尘峰与星辰锋同行",
            applied = emptyList(),
            word = "星辰峰",
            editRanges = listOf(VoiceCorrectionLearner.EditRange(0, 3)),
        )!!
        val second = VoiceCorrectionLearner.decidePairing(
            segment = "星尘峰与星辰锋同行",
            applied = emptyList(),
            word = "星尘锋",
            editRanges = listOf(VoiceCorrectionLearner.EditRange(4, 7)),
            consumedRanges = listOf(first.spanRange),
        )!!
        assertEquals("星辰锋", second.span)
        assertTrue(second.start == 4)
    }
}
