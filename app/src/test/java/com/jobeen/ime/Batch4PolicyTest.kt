// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime

import com.jobeen.ime.data.manager.CandidateLinePolicy
import com.jobeen.ime.data.manager.ClipboardManager
import org.junit.Assert.assertEquals
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
}
