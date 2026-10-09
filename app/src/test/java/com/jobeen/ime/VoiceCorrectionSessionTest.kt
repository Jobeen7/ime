package com.jobeen.ime

import com.jobeen.ime.base.speech.VoiceCorrectionLearner
import com.jobeen.ime.base.speech.VoiceCorrectionSessionLogic
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceCorrectionSessionTest {

    private fun change(start: Int, old: String, new: String) =
        VoiceCorrectionLearner.Change(start, old, new)

    private fun edit(start: Int, oldLength: Int, newLength: Int) =
        VoiceCorrectionSessionLogic.SegmentEdit(start, oldLength, newLength)

    // ── 段定位与补武装重试 ──

    @Test
    fun locate_atCursorEnd() {
        // 常态：光标正落在段尾
        val start = VoiceCorrectionSessionLogic.locateSegment(
            windowText = "前文星辰海",
            cursorPos = 5,
            committedText = "星辰海",
        )
        assertEquals(2, start)
    }

    @Test
    fun locate_fallbackLastIndex() {
        // 光标不在段尾（段后已有新文字）：取前文里最后一次出现
        val start = VoiceCorrectionSessionLogic.locateSegment(
            windowText = "星辰海在前文里",
            cursorPos = 7,
            committedText = "星辰海",
        )
        assertEquals(0, start)
    }

    @Test
    fun locate_notFoundReturnsNull() {
        assertNull(
            VoiceCorrectionSessionLogic.locateSegment(
                windowText = "完全无关的文字",
                cursorPos = 7,
                committedText = "星辰海",
            )
        )
    }

    @Test
    fun armRetry_withinWindowRetriesAndLocates() {
        // 30 秒重试窗内：允许重试，且编辑器落账后能定位成功
        val commitAt = 1_000_000L
        assertTrue(
            VoiceCorrectionSessionLogic.canRetryArm(
                commitAt, commitAt + 29_000L, retryMs = 30_000L
            )
        )
        assertEquals(
            2,
            VoiceCorrectionSessionLogic.locateSegment("前文星辰海", 5, "星辰海")
        )
    }

    @Test
    fun armRetry_beyondWindowGivesUp() {
        // 超过 30 秒仍未定位：放弃，上屏记录作废
        val commitAt = 1_000_000L
        assertFalse(
            VoiceCorrectionSessionLogic.canRetryArm(
                commitAt, commitAt + 31_000L, retryMs = 30_000L
            )
        )
        // 且定位本身失败时同样不武装
        assertNull(
            VoiceCorrectionSessionLogic.locateSegment("前文星辰", 4, "星辰海")
        )
    }

    // ── 过期判定 ──

    @Test
    fun expiry_fiveMinuteWindow() {
        val armedAt = 1_000_000L
        val expireMs = 5 * 60 * 1000L
        assertFalse(VoiceCorrectionSessionLogic.isExpired(armedAt, armedAt + expireMs, expireMs))
        assertTrue(VoiceCorrectionSessionLogic.isExpired(armedAt, armedAt + expireMs + 1, expireMs))
    }

    // ── 锚点维护 ──

    @Test
    fun anchor_editBeforeSegmentShifts() {
        val anchor = VoiceCorrectionSessionLogic.updateAnchor(
            segmentStart = 5,
            segmentText = "星辰海",
            change = change(0, "", "新"),
            newWindowText = "新前文星辰海",
        )!!
        assertEquals(6, anchor.segmentStart)
        assertEquals("星辰海", anchor.segmentText)
    }

    @Test
    fun anchor_editAfterSegmentUnchanged() {
        val anchor = VoiceCorrectionSessionLogic.updateAnchor(
            segmentStart = 2,
            segmentText = "星辰海",
            change = change(5, "", "新"),
            newWindowText = "前文星辰海新",
        )!!
        assertEquals(2, anchor.segmentStart)
        assertEquals("星辰海", anchor.segmentText)
    }

    @Test
    fun anchor_editInsideSegmentUpdatesSnapshotOnly() {
        // 段内把「星尘峰」删掉：演化快照同步缩短，锚点位置不动
        val anchor = VoiceCorrectionSessionLogic.updateAnchor(
            segmentStart = 2,
            segmentText = "联系人星尘峰明天到",
            change = change(5, "星尘峰", ""),
            newWindowText = "前文联系人明天到",
        )!!
        assertEquals(2, anchor.segmentStart)
        assertEquals("联系人明天到", anchor.segmentText)
    }

    @Test
    fun anchor_crossBoundaryReanchorsWhenSegmentFound() {
        // 跨界变更后段仍能在新窗口里原样找到：重新锚定到新位置
        val anchor = VoiceCorrectionSessionLogic.updateAnchor(
            segmentStart = 2,
            segmentText = "星辰海",
            change = change(0, "前文星", "改"),
            newWindowText = "改辰海星辰海",
        )!!
        assertEquals(3, anchor.segmentStart)
        assertEquals("星辰海", anchor.segmentText)
    }

    @Test
    fun anchor_crossBoundaryDropsWhenSegmentGone() {
        // 跨界变更且段已无从找起：放弃跟踪
        assertNull(
            VoiceCorrectionSessionLogic.updateAnchor(
                segmentStart = 2,
                segmentText = "星辰海",
                change = change(0, "前文星辰海", "全删了"),
                newWindowText = "全删了",
            )
        )
    }

    @Test
    fun segmentInternalChangeDetection() {
        assertTrue(
            VoiceCorrectionSessionLogic.isSegmentInternalChange(
                2, "联系人星尘峰明天到", change(5, "星尘峰", "")
            )
        )
        // 段前、段后、跨界编辑都不算段内编辑（不产生配对位置信号）
        assertFalse(
            VoiceCorrectionSessionLogic.isSegmentInternalChange(
                2, "星辰海", change(0, "", "新")
            )
        )
        assertFalse(
            VoiceCorrectionSessionLogic.isSegmentInternalChange(
                2, "星辰海", change(6, "", "新")
            )
        )
        assertFalse(
            VoiceCorrectionSessionLogic.isSegmentInternalChange(
                2, "星辰海", change(0, "前文星", "改")
            )
        )
    }

    // ── 编辑区间回映射到原文坐标 ──

    @Test
    fun originalRanges_singleDeletion() {
        // 在原文第 1 字起删掉 3 字错词：区间就是原文 [1, 4)
        val ranges = VoiceCorrectionSessionLogic.originalEditRanges(
            listOf(edit(start = 1, oldLength = 3, newLength = 0))
        )
        assertEquals(listOf(VoiceCorrectionLearner.EditRange(1, 4)), ranges)
    }

    @Test
    fun originalRanges_deleteThenInsertAtSameSpot() {
        // 先删错词、再在原位打正形：插入点回映射到原文区间尾端，
        // 与候选片段紧邻，仍可配对
        val ranges = VoiceCorrectionSessionLogic.originalEditRanges(
            listOf(
                edit(start = 1, oldLength = 3, newLength = 0),
                edit(start = 1, oldLength = 0, newLength = 3),
            )
        )
        assertEquals(VoiceCorrectionLearner.EditRange(1, 4), ranges[0])
        assertEquals(VoiceCorrectionLearner.EditRange(4, 4), ranges[1])
    }

    @Test
    fun originalRanges_charByCharDeletionCoversSpan() {
        // 逐字删除同一错词：三次删除回映射后恰好连成原文 [1, 4)
        val ranges = VoiceCorrectionSessionLogic.originalEditRanges(
            listOf(
                edit(start = 1, oldLength = 1, newLength = 0),
                edit(start = 1, oldLength = 1, newLength = 0),
                edit(start = 1, oldLength = 1, newLength = 0),
            )
        )
        assertEquals(
            listOf(
                VoiceCorrectionLearner.EditRange(1, 2),
                VoiceCorrectionLearner.EditRange(2, 3),
                VoiceCorrectionLearner.EditRange(3, 4),
            ),
            ranges,
        )
    }

    @Test
    fun originalRanges_replacementInsideSegment() {
        // 选中错词直接打正形替换（一次变更）：区间就是被替换的原文段
        val ranges = VoiceCorrectionSessionLogic.originalEditRanges(
            listOf(edit(start = 3, oldLength = 3, newLength = 3))
        )
        assertEquals(listOf(VoiceCorrectionLearner.EditRange(3, 6)), ranges)
    }
}
