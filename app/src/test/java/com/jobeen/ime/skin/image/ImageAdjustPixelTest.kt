package com.jobeen.ime.skin.image

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 背景图调节像素运算（参考项）：亮度与裁剪矩形。 */
class ImageAdjustPixelTest {

    private fun argb(a: Int, r: Int, g: Int, b: Int): Int =
        (a shl 24) or (r shl 16) or (g shl 8) or b

    @Test
    fun brightnessScalesRgbKeepsAlpha() {
        val pixels = intArrayOf(argb(128, 100, 150, 200))
        PixelOps.adjustBrightness(pixels, 0.5f)
        assertEquals(argb(128, 50, 75, 100), pixels[0])
    }

    @Test
    fun brightnessClampsAndIdentityIsNoop() {
        val pixels = intArrayOf(argb(255, 200, 200, 200))
        PixelOps.adjustBrightness(pixels, 2f)
        assertEquals(argb(255, 255, 255, 255), pixels[0])
        val same = intArrayOf(argb(255, 10, 20, 30))
        PixelOps.adjustBrightness(same, 1f)
        assertEquals(argb(255, 10, 20, 30), same[0])
        val dark = intArrayOf(argb(255, 100, 100, 100))
        PixelOps.adjustBrightness(dark, 0f)
        assertEquals(argb(255, 0, 0, 0), dark[0])
    }

    @Test
    fun cropRectFractions() {
        assertArrayEquals(
            intArrayOf(10, 5, 70, 85),
            PixelOps.cropRect(100, 100, 0.1f, 0.05f, 0.2f, 0.1f),
        )
        assertArrayEquals(
            intArrayOf(0, 0, 100, 100),
            PixelOps.cropRect(100, 100, null, null, null, null),
        )
    }

    @Test
    fun cropRectEmptyReturnsNull() {
        assertNull(PixelOps.cropRect(100, 100, 0.6f, null, 0.5f, null))
        assertNull(PixelOps.cropRect(100, 100, null, 1f, null, null))
    }
}
