package com.jobeen.ime

import com.jobeen.ime.input.panel.component.FloatingCandidatePosition
import com.jobeen.ime.input.panel.component.FloatingCandidatePositioning
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WO52 1.4 浮动候选窗位置计算：上下试放＋三位置＋回退（纯函数单测）。
 * 屏 1080×2400，键盘顶 1800、左右 0/1080，窗 1000×200，间隙 16。
 */
class FloatingCandidatePositioningTest {

    private fun resolve(
        preference: FloatingCandidatePosition,
        fallback: FloatingCandidatePosition = FloatingCandidatePosition.BOTTOM_LEFT,
        cursorLeft: Int? = 100,
        cursorTop: Int? = 1000,
        cursorRight: Int? = 300,
        cursorBottom: Int? = 1050,
        windowWidth: Int = 1000,
        windowHeight: Int = 200,
    ) = FloatingCandidatePositioning.resolve(
        preference = preference,
        fallback = fallback,
        cursorLeft = cursorLeft,
        cursorTop = cursorTop,
        cursorRight = cursorRight,
        cursorBottom = cursorBottom,
        keyboardLeft = 0,
        keyboardTop = 1800,
        keyboardRight = 1080,
        windowWidth = windowWidth,
        windowHeight = windowHeight,
        screenWidth = 1080,
        screenHeight = 2400,
        gapPx = 16,
    )

    @Test
    fun cursorAboveFits_staysAbove() {
        val pos = resolve(FloatingCandidatePosition.CURSOR)
        // x 取光标左，但夹进 0..(屏宽−窗宽)=0..80。
        assertEquals(80, pos.x)
        assertEquals(784, pos.y)
        assertTrue(pos.aboveCursor)
        assertFalse(pos.usedFallback)
    }

    @Test
    fun cursorNearTopAboveFails_flipsBelow() {
        val pos = resolve(
            FloatingCandidatePosition.CURSOR,
            cursorTop = 100, cursorBottom = 150,
        )
        assertEquals(80, pos.x)
        assertEquals(166, pos.y)
        assertFalse(pos.aboveCursor)
        assertFalse(pos.usedFallback)
    }

    @Test
    fun cursorUnavailable_fallsBackToBottomLeft() {
        val pos = resolve(
            FloatingCandidatePosition.CURSOR,
            cursorLeft = null, cursorTop = null, cursorRight = null, cursorBottom = null,
        )
        assertEquals(0, pos.x)
        assertEquals(1584, pos.y)
        assertTrue(pos.usedFallback)
    }

    @Test
    fun bottomRightAlignsToKeyboardRight() {
        val pos = resolve(FloatingCandidatePosition.BOTTOM_RIGHT)
        assertEquals(80, pos.x)
        assertEquals(1584, pos.y)
        assertFalse(pos.usedFallback)
    }

    @Test
    fun bottomLeftIgnoresCursor() {
        val pos = resolve(FloatingCandidatePosition.BOTTOM_LEFT)
        assertEquals(0, pos.x)
        assertEquals(1584, pos.y)
        assertFalse(pos.usedFallback)
    }

    @Test
    fun belowCursorCoveredByKeyboard_fallsBack() {
        // 光标近顶：上方放不下；下方窗底 1866 > 键盘顶 1800（会被键盘盖住）
        // → 不试下方，回退左下。
        val pos = resolve(
            FloatingCandidatePosition.CURSOR,
            cursorTop = 100, cursorBottom = 150,
            windowHeight = 1700,
        )
        assertTrue(pos.usedFallback)
        assertEquals(0, pos.x)
        assertEquals(84, pos.y)
    }

    @Test
    fun cursorLowStillFitsAbove() {
        // 光标贴键盘顶：上方够放仍放上方；下方会被键盘盖住不试。
        // 光标顶 1750：上方 y=1534（底 1734 ≤ 1800）放上方。
        val above = resolve(
            FloatingCandidatePosition.CURSOR,
            cursorTop = 1750, cursorBottom = 1790,
        )
        assertTrue(above.aboveCursor)
        assertFalse(above.usedFallback)
        // 光标顶 60 且窗高 2000：上下都放不下 → 回退夹取。
        val clamped = resolve(
            FloatingCandidatePosition.CURSOR,
            cursorTop = 60, cursorBottom = 100,
            windowHeight = 2000,
        )
        assertTrue(clamped.usedFallback)
        assertEquals(0, clamped.y)
    }

    @Test
    fun positionParsesUnknownAsCursor() {
        assertEquals(FloatingCandidatePosition.CURSOR, FloatingCandidatePosition.parse(null))
        assertEquals(FloatingCandidatePosition.CURSOR, FloatingCandidatePosition.parse(""))
        assertEquals(FloatingCandidatePosition.CURSOR, FloatingCandidatePosition.parse("cursor"))
        assertEquals(FloatingCandidatePosition.CURSOR, FloatingCandidatePosition.parse("weird"))
        assertEquals(FloatingCandidatePosition.BOTTOM_LEFT, FloatingCandidatePosition.parse("bottomLeft"))
        assertEquals(FloatingCandidatePosition.BOTTOM_RIGHT, FloatingCandidatePosition.parse("bottomRight"))
    }
}
