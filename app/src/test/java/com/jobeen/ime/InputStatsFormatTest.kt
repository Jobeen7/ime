// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime

import com.jobeen.ime.input.stats.InputStats
import com.jobeen.ime.ui.screen.categoryDistribution
import com.jobeen.ime.ui.screen.accuracyRating
import com.jobeen.ime.ui.screen.formatAccuracy
import com.jobeen.ime.ui.screen.formatMistouchRate
import com.jobeen.ime.ui.screen.formatSpeed
import com.jobeen.ime.ui.screen.formatTypingMinutes
import com.jobeen.ime.ui.screen.longStatsReport
import com.jobeen.ime.ui.screen.rankCaption
import com.jobeen.ime.ui.screen.shortStatsReport
import com.jobeen.ime.ui.screen.speedRating
import com.jobeen.ime.ui.screen.stageExpText
import com.jobeen.ime.ui.screen.segmentLabel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 输入统计屏展示格式化：分段标签、时长、速度、准确率、段位说明、类别分布。 */
class InputStatsFormatTest {

    @Test
    fun segmentLabels_matchReferenceSegments() {
        assertEquals("今日", segmentLabel(InputStats.Period.DAY))
        assertEquals("本周", segmentLabel(InputStats.Period.WEEK))
        assertEquals("本月", segmentLabel(InputStats.Period.MONTH))
        assertEquals("今年", segmentLabel(InputStats.Period.YEAR))
    }

    @Test
    fun typingMinutes_zeroIsEmptyNotZero() {
        assertEquals("—", formatTypingMinutes(0L))
        assertEquals("—", formatTypingMinutes(-1000L))
    }

    @Test
    fun typingMinutes_minutesAndHours() {
        assertEquals("5分钟", formatTypingMinutes(5L * 60000L))
        assertEquals("1小时", formatTypingMinutes(60L * 60000L))
        assertEquals("1小时30分钟", formatTypingMinutes(90L * 60000L))
    }

    @Test
    fun speedAndAccuracy_nullIsEmpty() {
        assertEquals("—", formatSpeed(null))
        assertEquals("123 字／分钟", formatSpeed(123.4))
        assertEquals("—", formatAccuracy(null))
        assertEquals("86%", formatAccuracy(0.856))
    }

    @Test
    fun rankCaption_fullAndNext() {
        val full = InputStats.Rank("传奇", 6, 1.0, 150000L, null)
        assertEquals("已满级", rankCaption(full))
        val next = InputStats.Rank("萌新", 0, 0.2, 100L, 500L)
        assertEquals("距下一段位还差 400 字", rankCaption(next))
    }

    @Test
    fun categoryDistribution_percentsAndEmpty() {
        assertTrue(categoryDistribution(emptyMap()).isEmpty())
        val shares = categoryDistribution(mapOf("INPUT" to 3L, "SPACE" to 1L))
        assertEquals(2, shares.size)
        val total = shares.sumOf { it.percent }
        assertEquals(1.0, total, 1e-9)
    }

    @Test
    fun stageExpText_fiveStagesAndFull() {
        // 炼气期：段内 1000 / 差距 2000（不是图示 0 / 100）。
        assertEquals(
            "1000 / 2000 EXP",
            stageExpText(InputStats.Rank("炼气期", 0, 0.5, 1000L, 2000L)),
        )
        // 筑基期：段内＝终身 − 下限（3000 − 2000＝1000），差距 6000。
        assertEquals(
            "1000 / 6000 EXP",
            stageExpText(InputStats.Rank("筑基期", 1, 0.0, 3000L, 8000L)),
        )
        assertEquals(
            "已满级",
            stageExpText(InputStats.Rank("化神期", 4, 1.0, 150000L, null)),
        )
    }

    @Test
    fun ratings_thresholdsAndZeroStateDefaults() {
        // 2026-10-31 Jobeen 定：评级与段位统一用段位名，无数据为炼气期。
        assertEquals("炼气期", speedRating(null))
        assertEquals("炼气期", accuracyRating(null))
        assertEquals("化神期", speedRating(400.0))
        assertEquals("元婴期", speedRating(300.0))
        assertEquals("金丹期", speedRating(200.0))
        assertEquals("筑基期", speedRating(120.0))
        assertEquals("炼气期", speedRating(60.0))
        assertEquals("炼气期", speedRating(59.9))
        assertEquals("化神期", accuracyRating(0.99))
        assertEquals("元婴期", accuracyRating(0.97))
        assertEquals("金丹期", accuracyRating(0.95))
        assertEquals("筑基期", accuracyRating(0.90))
        assertEquals("炼气期", accuracyRating(0.80))
        assertEquals("炼气期", accuracyRating(0.79))
    }

    @Test
    fun shareReports_shortAndLongContainAggregatesOnly() {
        val rank = InputStats.Rank("炼气期", 0, 0.5, 1000L, 2000L)
        val total = InputStats.Counters(
            commits = 10, chars = 25, keypresses = 40, deletes = 5,
            typingMs = 60000, wordLen1 = 4, wordLen2 = 3, wordLen3 = 2,
            wordLen4plus = 1, corrections = 2,
        )
        val stats = InputStats.PeriodStats(
            total = total, schemes = emptyMap(), hours = List(24) { 0L },
            categories = emptyMap(), avgSpeed = 25.0, accuracy = 25.0 / 30.0,
            heat = mapOf("q" to 6L, "w" to 2L),
        )
        val short = shortStatsReport(rank, stats, 120.0)
        assertTrue(short.contains("炼气期 Lv.1"))
        assertTrue(short.contains("累计 1000 字"))
        assertTrue(short.contains("120 字／分钟"))
        val long = longStatsReport("今日", rank, stats, 120.0)
        assertTrue(long.contains("分段：今日"))
        // 多字词＝三字＋四字以上＝3／10＝30%。
        assertTrue(long.contains("多字词 30%"))
        assertTrue(long.contains("误触率：5.0%"))
        assertTrue(long.contains("键盘热力：q 75%"))
    }

    @Test
    fun mistouchRate_correctionsOverKeypressesAndZeroIsEmpty() {
        assertEquals("—", formatMistouchRate(0L, 0L))
        assertEquals("0.0%", formatMistouchRate(0L, 50L))
        assertEquals("1.0%", formatMistouchRate(1L, 100L))
        assertEquals("33.3%", formatMistouchRate(1L, 3L))
    }
}
