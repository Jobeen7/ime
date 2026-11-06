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

    // 施工单 5 第 7 条：超限图降采样到上限内（边长 8192、像素 64M），
    // 不直接拒收；采样后尺寸按 ceil 计。
    @Test
    fun cappedSampleSizeWithinCapsIsOne() {
        val side = 8192
        val pixels = 64L * 1024 * 1024
        assertEquals(1, PixelOps.computeCappedSampleSize(6000, 4000, side, pixels))
        // 恰在上限：8192 边、8192×8192＝64M 像素，仍为 1。
        assertEquals(1, PixelOps.computeCappedSampleSize(8192, 8192, side, pixels))
        assertEquals(1, PixelOps.computeCappedSampleSize(100, 100, side, pixels))
    }

    @Test
    fun cappedSampleSizeSideOver() {
        val side = 8192
        val pixels = 64L * 1024 * 1024
        assertEquals(2, PixelOps.computeCappedSampleSize(8192, 8193, side, pixels))
        assertEquals(2, PixelOps.computeCappedSampleSize(16384, 16384, side, pixels))
        // ceil 计：16385/2＝8193 仍超边，须到 4。
        assertEquals(4, PixelOps.computeCappedSampleSize(16385, 100, side, pixels))
    }

    @Test
    fun cappedSampleSizePixelOverAndHuge() {
        val side = 8192
        val pixels = 64L * 1024 * 1024
        // 边在上限内、像素超：4000×4000＝16M＞8M，采 2 后 4M。
        assertEquals(2, PixelOps.computeCappedSampleSize(4000, 4000, side, 8L * 1024 * 1024))
        // int 溢出安全：80000×80000＝6.4G 像素，采 16 后 5000×5000＝25M。
        assertEquals(16, PixelOps.computeCappedSampleSize(80000, 80000, side, pixels))
    }

    @Test
    fun cappedSampleSizeInvalidInputIsOne() {
        val side = 8192
        val pixels = 64L * 1024 * 1024
        assertEquals(1, PixelOps.computeCappedSampleSize(0, 100, side, pixels))
        assertEquals(1, PixelOps.computeCappedSampleSize(100, 0, side, pixels))
        assertEquals(1, PixelOps.computeCappedSampleSize(100, 100, 0, pixels))
        assertEquals(1, PixelOps.computeCappedSampleSize(100, 100, side, 0))
    }
}
