package com.jobeen.ime.skin.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 高度和规则：数字 + 'Nvh' 混写解析与相加。 */
class HeightSumTest {

    @Test
    fun mixedInputsParse() {
        assertEquals(HeightPart.Dp(48f), parseHeightPart(48))
        assertEquals(HeightPart.Dp(48.5f), parseHeightPart(48.5))
        assertEquals(HeightPart.Vh(10f), parseHeightPart("10vh"))
        assertEquals(HeightPart.Vh(7.5f), parseHeightPart(" 7.5VH "))
        assertEquals(HeightPart.Dp(48f), parseHeightPart("48"))
        assertNull(parseHeightPart(null))
        assertNull(parseHeightPart("abc"))
        assertNull(parseHeightPart("10px"))
        assertNull(parseHeightPart(true))
    }

    @Test
    fun sumResolvesVhAgainstViewport() {
        // 组字 40dp + 工具栏 48dp + 按键区 50vh（视口 800dp → 400dp）+ 底边距 12dp = 500dp。
        assertEquals(
            500f,
            sumHeights(
                800f,
                HeightPart.Dp(40f),
                HeightPart.Dp(48f),
                HeightPart.Vh(50f),
                HeightPart.Dp(12f),
            ),
            0.001f,
        )
        // null 分量跳过；负分量钳 0。
        assertEquals(
            100f,
            sumHeights(800f, null, HeightPart.Dp(100f), HeightPart.Vh(-5f)),
            0.001f,
        )
        assertEquals(0f, sumHeights(800f), 0.001f)
    }

    @Test
    fun parseThenSumRoundTrip() {
        val parts = listOf(40, "48", "50vh", 12).map { parseHeightPart(it) }
        assertEquals(500f, sumHeights(800f, *parts.toTypedArray()), 0.001f)
    }
}
