// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime

import com.jobeen.ime.data.manager.CandidateLinePolicy
import com.jobeen.ime.data.manager.ClipboardManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 批量第 4 项纯策略单测：粘贴输出变换、展开候选网格行高、截断省略号。
 * 均不依赖 Android 框架（measure 以字符计数替身传入）。
 */
class Batch4PolicyTest {

    @Test
    fun outputRule_asIs_unchanged() {
        assertEquals(
            "  a\nb  ",
            ClipboardManager.applyOutputRule("  a\nb  ", ClipboardManager.OutputRule.AS_IS),
        )
    }

    @Test
    fun outputRule_trim_edgesOnly() {
        assertEquals(
            "a\nb",
            ClipboardManager.applyOutputRule("  a\nb  ", ClipboardManager.OutputRule.TRIM),
        )
    }

    @Test
    fun outputRule_newlineToSpace_allBreaks() {
        assertEquals(
            "a b c d",
            ClipboardManager.applyOutputRule(
                "a\nb\r\nc\rd",
                ClipboardManager.OutputRule.NEWLINE_TO_SPACE,
            ),
        )
    }

    @Test
    fun gridHeight_shrinkToContent() {
        assertEquals(
            192,
            CandidateLinePolicy.resolveGridHeight(
                contentRows = 2, maxLines = 5, limitEnabled = true,
                autoShrink = true, naturalRowH = 96, fullHeight = 960,
            ),
        )
    }

    @Test
    fun gridHeight_contentOverMax_fullHeight() {
        assertEquals(
            960,
            CandidateLinePolicy.resolveGridHeight(
                contentRows = 8, maxLines = 5, limitEnabled = true,
                autoShrink = true, naturalRowH = 96, fullHeight = 960,
            ),
        )
    }

    @Test
    fun gridHeight_noShrink_fullHeight() {
        assertEquals(
            960,
            CandidateLinePolicy.resolveGridHeight(
                contentRows = 2, maxLines = 5, limitEnabled = true,
                autoShrink = false, naturalRowH = 96, fullHeight = 960,
            ),
        )
    }

    @Test
    fun gridHeight_noLimit_naturalRows() {
        // 不限行：视口可放 10 行，内容 8 行 + 收缩 → 8 行高
        assertEquals(
            768,
            CandidateLinePolicy.resolveGridHeight(
                contentRows = 8, maxLines = 5, limitEnabled = false,
                autoShrink = true, naturalRowH = 96, fullHeight = 960,
            ),
        )
    }

    @Test
    fun gridHeight_emptyContent_fullHeight() {
        assertEquals(
            960,
            CandidateLinePolicy.resolveGridHeight(
                contentRows = 0, maxLines = 5, limitEnabled = true,
                autoShrink = true, naturalRowH = 96, fullHeight = 960,
            ),
        )
    }

    @Test
    fun truncate_fits_unchanged() {
        val measure: (String) -> Float = { it.length.toFloat() }
        assertEquals(
            "hello",
            CandidateLinePolicy.truncateWithEllipsis("hello", 5f, measure),
        )
    }

    @Test
    fun truncate_tooLong_ellipsis() {
        val measure: (String) -> Float = { it.length.toFloat() }
        assertEquals(
            "hel…",
            CandidateLinePolicy.truncateWithEllipsis("hello", 4f, measure),
        )
    }

    @Test
    fun truncate_empty_unchanged() {
        val measure: (String) -> Float = { it.length.toFloat() }
        assertEquals("", CandidateLinePolicy.truncateWithEllipsis("", 10f, measure))
    }

    @Test
    fun truncate_zeroWidth_ellipsisOnly() {
        val measure: (String) -> Float = { it.length.toFloat() }
        assertEquals("…", CandidateLinePolicy.truncateWithEllipsis("hello", 0f, measure))
    }

    @Test
    fun longCandidate_fits_unchanged() {
        val measure: (String) -> Float = { it.length.toFloat() }
        val layout = CandidateLinePolicy.layoutLongCandidate("hello", 5f, measure)
        assertEquals(listOf("hello"), layout.lines)
        assertEquals(1f, layout.scale)
    }

    @Test
    fun longCandidate_shrinkOneLine() {
        val measure: (String) -> Float = { it.length.toFloat() }
        // 宽 10，可用 8：缩到 0.8 一行放下（不低于下限 0.7）
        val layout = CandidateLinePolicy.layoutLongCandidate("0123456789", 8f, measure)
        assertEquals(listOf("0123456789"), layout.lines)
        assertEquals(0.8f, layout.scale, 0.001f)
    }

    @Test
    fun longCandidate_twoLines_atFloor() {
        val measure: (String) -> Float = { it.length.toFloat() }
        // 宽 10，可用 6：缩到 0.6 < 0.7，改两行（0.7 字号下每行 ≤ 8 字）
        val layout = CandidateLinePolicy.layoutLongCandidate("0123456789", 6f, measure)
        assertEquals(listOf("01234567", "89"), layout.lines)
        assertEquals(0.7f, layout.scale, 0.001f)
    }

    @Test
    fun longCandidate_twoLines_secondLineEllipsis() {
        val measure: (String) -> Float = { it.length.toFloat() }
        // 20 字，可用 4：第一行 0.7 字号下 5 字，第二行剩 15 字仍超长 → 截断加省略号
        val layout = CandidateLinePolicy.layoutLongCandidate("01234567890123456789", 4f, measure)
        assertEquals(2, layout.lines.size)
        assertEquals("01234", layout.lines[0])
        assertEquals("5678…", layout.lines[1])
        assertEquals(0.7f, layout.scale, 0.001f)
    }

    @Test
    fun longCandidate_spaceSplit_preferred() {
        val measure: (String) -> Float = { it.length.toFloat() }
        // "hello world" 宽 11，可用 6：贪心断点 8（"hello wo"），空格在 5，改在空格断
        val layout = CandidateLinePolicy.layoutLongCandidate("hello world", 6f, measure)
        assertEquals(listOf("hello", "world"), layout.lines)
        assertEquals(0.7f, layout.scale, 0.001f)
    }

    @Test
    fun longCandidate_empty_unchanged() {
        val measure: (String) -> Float = { it.length.toFloat() }
        val layout = CandidateLinePolicy.layoutLongCandidate("", 10f, measure)
        assertEquals(listOf(""), layout.lines)
        assertEquals(1f, layout.scale)
    }

    @Test
    fun longCandidate_tinyWidth_ellipsis() {
        val measure: (String) -> Float = { it.length.toFloat() }
        val layout = CandidateLinePolicy.layoutLongCandidate("hello", 0f, measure)
        assertEquals(listOf("…"), layout.lines)
        assertTrue(layout.scale >= 0.7f)
    }
}
