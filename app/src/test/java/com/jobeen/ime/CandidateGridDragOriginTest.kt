package com.jobeen.ime

import com.jobeen.ime.input.panel.component.dragOrigin
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * CandidateGridView 拖动图起点计算（dragOrigin）的边界规格。
 *
 * 规格来源（F7 已核实缺陷）：旧写法 (finger - dragW/2).coerceIn(0f,
 * width - dragW) 在拖动图宽于视口时上限为负、区间为空，onDraw 必抛
 * IllegalArgumentException。新口径：有效尺寸先钳到视口大小，区间恒
 * 非空；尺寸不超过视口时与旧公式逐值一致（正常候选拖动位置不变）。
 */
class CandidateGridDragOriginTest {

    @Test
    fun oversizeReturnsZeroAndNeverThrows() {
        // 旧版必抛的形态：候选宽 612 > 视口宽 300，手指任意位置
        for (finger in listOf(-100f, 0f, 1f, 150f, 299f, 300f, 10_000f)) {
            assertEquals(0f, dragOrigin(finger, 612f, 300f), 0f)
        }
    }

    @Test
    fun sizeEqualsViewportAlwaysZero() {
        for (finger in listOf(0f, 75f, 150f, 300f)) {
            assertEquals(0f, dragOrigin(finger, 300f, 300f), 0f)
        }
    }

    @Test
    fun normalCenteringUnchanged() {
        // 视口 300、图宽 100：手指居中时起点 = 手指 - 50
        assertEquals(100f, dragOrigin(150f, 100f, 300f), 0f)
        assertEquals(70f, dragOrigin(120f, 100f, 300f), 0f)
    }

    @Test
    fun fingerAtEndsClampsToViewport() {
        // 左端：居中值负数钳到 0；恰好 0 的边界不偏移
        assertEquals(0f, dragOrigin(0f, 100f, 300f), 0f)
        assertEquals(0f, dragOrigin(-30f, 100f, 300f), 0f)
        assertEquals(0f, dragOrigin(50f, 100f, 300f), 0f)
        // 右端：上限 = 视口 - 图宽 = 200；恰好命中的边界不偏移
        assertEquals(200f, dragOrigin(250f, 100f, 300f), 0f)
        assertEquals(200f, dragOrigin(300f, 100f, 300f), 0f)
        assertEquals(200f, dragOrigin(10_000f, 100f, 300f), 0f)
    }

    @Test
    fun rowTallerThanViewportReturnsZeroAndNeverThrows() {
        // 纵向同类防御：行高 120 > 视口高 90（旧 dragTop 上限为负）
        for (finger in listOf(0f, 45f, 90f, 500f)) {
            assertEquals(0f, dragOrigin(finger, 120f, 90f), 0f)
        }
    }

    @Test
    fun zeroSizeFollowsFinger() {
        assertEquals(150f, dragOrigin(150f, 0f, 300f), 0f)
        assertEquals(0f, dragOrigin(-5f, 0f, 300f), 0f)
    }

    @Test
    fun matchesLegacyFormulaWheneverSizeFitsViewport() {
        // 与旧公式 (finger - size/2).coerceIn(0f, viewport - size) 对照：
        // 尺寸不超过视口的全部组合必须逐值一致
        val viewports = listOf(90f, 300f, 1080f)
        val sizes = listOf(0f, 1f, 44f, 100f, 299f)
        val fingers = listOf(-50f, 0f, 23f, 150f, 540f, 1080f, 5000f)
        for (viewport in viewports) for (size in sizes) {
            if (size > viewport) continue
            for (finger in fingers) {
                val legacy = (finger - size / 2f).coerceIn(0f, viewport - size)
                assertEquals(
                    "viewport=$viewport size=$size finger=$finger",
                    legacy, dragOrigin(finger, size, viewport), 0f
                )
            }
        }
    }
}
