// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.input.keyboard.key.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 多行标签各行基线（I-⊕：T9 左竖栏“⊕\n自定义”两行居中）。 */
class SidePanelMultilineTest {

    private val delta = 0.001f

    @Test
    fun singleLineIsVerticallyCentered() {
        val baselines = multiLineBaselines(1, 100f, 20f, -14f, 4f)
        assertEquals(1, baselines.size)
        // 基线＝中心 Y － (ascent＋descent)/2。
        assertEquals(105f, baselines[0], delta)
    }

    @Test
    fun twoLinesAreSymmetricAroundCenter() {
        val baselines = multiLineBaselines(2, 100f, 20f, -14f, 4f)
        assertEquals(2, baselines.size)
        assertEquals(95f, baselines[0], delta)
        assertEquals(115f, baselines[1], delta)
        // 行距＝行高；两行中点即垂直居中点。
        assertEquals(20f, baselines[1] - baselines[0], delta)
        assertEquals(105f, (baselines[0] + baselines[1]) / 2f, delta)
    }

    @Test
    fun threeLinesMiddleSitsOnCenter() {
        val baselines = multiLineBaselines(3, 100f, 20f, -14f, 4f)
        assertEquals(3, baselines.size)
        assertEquals(105f, baselines[1], delta)
        assertEquals(20f, baselines[1] - baselines[0], delta)
        assertEquals(20f, baselines[2] - baselines[1], delta)
    }

    @Test
    fun zeroLinesIsEmpty() {
        assertTrue(multiLineBaselines(0, 100f, 20f, -14f, 4f).isEmpty())
    }

    // ---------- fitTextSizePx：多行标签随格高缩放（I-L5） ----------

    @Test
    fun fitTextSize_fitsUnchanged() {
        // 两行 12sp、行距 14.06dp：行块 28.1dp，格高 40dp、格宽 100dp 均放得下。
        assertEquals(36f, fitTextSizePx(36f, 2, 14.06f, 40f, 36f, 100f), delta)
    }

    @Test
    fun fitTextSize_shortCellShrinksByHeight() {
        // 横屏 30% 格高约 12.6dp：两行需 28.1dp，等比缩到 12.6/28.1。
        assertEquals(36f * 12.6f / 28.12f, fitTextSizePx(36f, 2, 14.06f, 12.6f, 36f, 100f), 0.01f)
    }

    @Test
    fun fitTextSize_narrowCellShrinksByWidth() {
        // 格高够、格宽不够：按最宽行缩放。
        assertEquals(36f * 50f / 72f, fitTextSizePx(36f, 2, 14.06f, 40f, 72f, 50f), 0.01f)
    }

    @Test
    fun fitTextSize_takesSmallerOfHeightAndWidth() {
        // 高、宽都超：取更小的那个比例。
        val byHeight = 36f * 12.6f / 28.12f
        val byWidth = 36f * 50f / 72f
        assertEquals(minOf(byHeight, byWidth), fitTextSizePx(36f, 2, 14.06f, 12.6f, 72f, 50f), 0.01f)
    }

    @Test
    fun fitTextSize_zeroLineCountKeepsBase() {
        assertEquals(36f, fitTextSizePx(36f, 0, 14.06f, 12.6f, 36f, 100f), delta)
    }
}
