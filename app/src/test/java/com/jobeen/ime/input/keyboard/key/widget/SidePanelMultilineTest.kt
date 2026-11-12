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
}
