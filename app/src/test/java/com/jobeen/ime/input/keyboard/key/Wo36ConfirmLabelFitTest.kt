// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.input.keyboard.key

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 文字标签适配字号（WO36 大字号边界，[confirmLabelFitSize]）：
 * 键宽固定、请求字号放不下时自动缩小到放得下为止，只缩不放、
 * 下限 10dp；仍放不下保持下限＋ellipsize 兜底。
 *
 * 键宽口径沿用 WO35 定稿：S24 Ultra 竖屏键盘 376dp（屏宽 384dp − 两侧内边距各 4dp），
 * T9 回车 0.18 → 67.68dp、Qwerty 回车 0.15 → 56.4dp；窄屏 360dp
 * 键盘 352dp，T9 → 63.36dp、Qwerty → 52.8dp。字宽按 1em 计。
 *
 * 状态空间（键宽 × 请求字号）：
 * - 放得下 → 原样；边界恰好放得下 → 原样；放不下 → 缩到恰好放得下。
 * - 极窄键 → 下限 10dp（仍放不下，ellipsize 兜底）；键宽未知 → 不缩。
 * - 永不放大；内边距／下限参数可配。
 * 视图层接线（ImageKeyView.showTextLabel 按实测宽调本函数）现无生产
 * 调用方（WO45 回车键删除后）；本测试只钉纯函数本身。
 */
class Wo36ConfirmLabelFitTest {

    private val delta = 0.01f

    // S24 Ultra 竖屏 376dp：放得下 → 原样。
    @Test
    fun fitsAtProductionSize() {
        assertEquals(16f, confirmLabelFitSize(67.68f, 16f), delta)
        assertEquals(16f, confirmLabelFitSize(56.4f, 16f), delta)
    }

    // S24 Ultra 竖屏：Qwerty 边界 24dp 恰好放得下（2×24＋8＝56 ≤ 56.4）。
    @Test
    fun qwertyBoundary24FitsUnchanged() {
        assertEquals(24f, confirmLabelFitSize(56.4f, 24f), delta)
        assertEquals(24.2f, confirmLabelFitSize(56.4f, 24.2f), delta)
    }

    // S24 Ultra 竖屏：Qwerty 26dp 放不下 → 缩到 24.2dp。
    @Test
    fun qwerty26ShrinksToFit() {
        val fitted = confirmLabelFitSize(56.4f, 26f)
        assertEquals(24.2f, fitted, delta)
        assertTrue("shrunk size must fit the 2em budget", 2f * fitted + 8f <= 56.4f)
    }

    // S24 Ultra 竖屏：T9 29dp 放得下，30dp → 缩到 29.84dp。
    @Test
    fun t9Boundary29Fits30Shrinks() {
        assertEquals(29f, confirmLabelFitSize(67.68f, 29f), delta)
        val fitted = confirmLabelFitSize(67.68f, 30f)
        assertEquals(29.84f, fitted, delta)
        assertTrue("shrunk size must fit the 2em budget", 2f * fitted + 8f <= 67.68f)
    }

    // 窄屏 360dp（键盘 352dp）：Qwerty 26dp → 22.4dp；T9 30dp → 27.68dp。
    @Test
    fun narrowScreenShrinksMore() {
        assertEquals(22.4f, confirmLabelFitSize(52.8f, 26f), delta)
        assertEquals(27.68f, confirmLabelFitSize(63.36f, 30f), delta)
        assertEquals(16f, confirmLabelFitSize(52.8f, 16f), delta)
        assertEquals(16f, confirmLabelFitSize(63.36f, 16f), delta)
    }

    // 极大字号也只缩到放得下，不截断：Qwerty 40dp → 24.2dp。
    @Test
    fun hugeSizeShrinksToFitInsteadOfEllipsis() {
        assertEquals(24.2f, confirmLabelFitSize(56.4f, 40f), delta)
        assertEquals(29.84f, confirmLabelFitSize(67.68f, 60f), delta)
    }

    // 极窄键：放得下限也放不下时保持下限 10dp（ellipsize 兜底，不再缩）。
    @Test
    fun extremelyNarrowKeyStopsAtMinSize() {
        assertEquals(10f, confirmLabelFitSize(20f, 30f), delta)
        assertEquals(10f, confirmLabelFitSize(16f, 30f), delta)
    }

    // 键宽未知（未布局）时不缩、沿用请求字号。
    @Test
    fun unknownKeyWidthKeepsRequestedSize() {
        assertEquals(26f, confirmLabelFitSize(0f, 26f), delta)
        assertEquals(26f, confirmLabelFitSize(-5f, 26f), delta)
    }

    // 永不放大：小字号在大键上保持原样。
    @Test
    fun neverEnlargesSmallSize() {
        assertEquals(12f, confirmLabelFitSize(67.68f, 12f), delta)
    }

    // 内边距参数参与预算：无内边距时 Qwerty 26dp → 28.2dp。
    @Test
    fun customPaddingChangesBudget() {
        assertEquals(28.2f, confirmLabelFitSize(56.4f, 30f, horizontalPaddingDp = 0f), delta)
    }

    // 下限参数可配：下限 12dp 时极窄键停在 12dp。
    @Test
    fun customMinSizeIsRespected() {
        assertEquals(12f, confirmLabelFitSize(20f, 30f, minTextSizeDp = 12f), delta)
    }
}
