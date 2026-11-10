package com.jobeen.ime.input.keyboard.key

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 可见字形居中（W19-1）：alignVisibleTextCenter 曾按 left＋字形 bounds 算中心，
 * 没计 paddingLeft；副文字／顶部小字的注解留白加在 TextView padding 上，
 * 居中多算一个留白量、整体推右约 4px（key-sublabel-not-centered-20261110.md）。
 */
class VisibleTextCenterTest {

    private val delta = 0.001f

    @Test
    fun noPaddingCentersGlyph() {
        // 容器 100，视图左缘 0，无留白，字形 [10, 30] → 中心 20，位移 30。
        assertEquals(
            30f,
            visibleTextCenterOffset(100f, 0f, 0f, 10f, 30f),
            delta,
        )
    }

    @Test
    fun paddingLeftShiftsGlyphCenterRight() {
        // 同上，留白 4：字形中心 = 0 + 4 + 20 = 24，位移 26——
        // 旧算法（不计 paddingLeft）会算成 30，正好偏右一个留白量。
        assertEquals(
            26f,
            visibleTextCenterOffset(100f, 0f, 4f, 10f, 30f),
            delta,
        )
    }

    @Test
    fun offsetDiffersFromOldFormulaByExactlyPaddingLeft() {
        val containerWidth = 100f
        val viewLeft = 6f
        val paddingLeft = 4f
        val boundsLeft = -2f
        val boundsRight = 30f
        val oldOffset = containerWidth / 2f - (viewLeft + (boundsLeft + boundsRight) / 2f)
        val newOffset = visibleTextCenterOffset(
            containerWidth, viewLeft, paddingLeft, boundsLeft, boundsRight,
        )
        assertEquals(paddingLeft, oldOffset - newOffset, delta)
    }

    @Test
    fun asymmetricBoundsUseGlyphCenterNotAdvance() {
        // 字形带左负 bearing：[-2, 30] → 中心 14，留白 4，位移 50 − 18 = 32。
        assertEquals(
            32f,
            visibleTextCenterOffset(100f, 0f, 4f, -2f, 30f),
            delta,
        )
    }

    @Test
    fun offsetAlwaysPutsGlyphCenterOnContainerCenter() {
        val cases = listOf(
            floatArrayOf(100f, 0f, 0f, 10f, 30f),
            floatArrayOf(100f, 0f, 4f, 10f, 30f),
            floatArrayOf(85f, 3f, 4f, 0f, 22f),
            floatArrayOf(39f, 0f, 4f, 2f, 14f),
        )
        for ((containerWidth, viewLeft, paddingLeft, boundsLeft, boundsRight) in cases) {
            val offset = visibleTextCenterOffset(
                containerWidth, viewLeft, paddingLeft, boundsLeft, boundsRight,
            )
            val glyphCenter = viewLeft + paddingLeft + (boundsLeft + boundsRight) / 2f + offset
            assertEquals(containerWidth / 2f, glyphCenter, delta)
        }
    }
}
