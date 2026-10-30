package com.jobeen.ime.skin.image

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 背景预处理像素运算（详设 §2.6 的数学部分）。 */
class PixelOpsTest {

    private fun argb(a: Int, r: Int, g: Int, b: Int): Int =
        (a shl 24) or (r shl 16) or (g shl 8) or b

    @Test
    fun flattenTransparentBecomesBackground() {
        val pixels = intArrayOf(argb(0, 200, 100, 50))
        PixelOps.flattenOver(pixels, argb(255, 10, 20, 30))
        assertEquals(argb(255, 10, 20, 30), pixels[0])
    }

    @Test
    fun flattenOpaqueUnchanged() {
        val pixels = intArrayOf(argb(255, 200, 100, 50))
        PixelOps.flattenOver(pixels, argb(255, 10, 20, 30))
        assertEquals(argb(255, 200, 100, 50), pixels[0])
    }

    @Test
    fun tintExtremes() {
        val pixels = intArrayOf(argb(255, 100, 100, 100))
        PixelOps.tintToward(pixels, argb(255, 0, 0, 0), 0f)
        assertEquals(argb(255, 100, 100, 100), pixels[0])
        PixelOps.tintToward(pixels, argb(255, 0, 0, 0), 1f)
        assertEquals(argb(255, 0, 0, 0), pixels[0])
    }

    @Test
    fun blurUniformStaysUniform() {
        val pixels = IntArray(9) { argb(255, 80, 90, 100) }
        PixelOps.stackBlur(pixels, 3, 3, 2)
        assertTrue(pixels.all { it == argb(255, 80, 90, 100) })
    }

    @Test
    fun blurSoftensImpulse() {
        // 3x3 中心白点，模糊后中心变暗、四邻变亮
        val pixels = IntArray(9) { argb(255, 0, 0, 0) }
        pixels[4] = argb(255, 255, 255, 255)
        PixelOps.stackBlur(pixels, 3, 3, 1)
        val center = pixels[4] and 0xFF
        val neighbor = pixels[3] and 0xFF
        assertTrue(center in 1..254)
        assertTrue(neighbor > 0)
    }

    @Test
    fun sampleSizeCalc() {
        assertEquals(4, PixelOps.computeSampleSize(4000, 2000, 1000, 500))
        assertEquals(1, PixelOps.computeSampleSize(200, 100, 1000, 500))
        assertEquals(2, PixelOps.computeSampleSize(2000, 1000, 1000, 500))
        assertEquals(1, PixelOps.computeSampleSize(0, 0, 100, 100))
    }
}
