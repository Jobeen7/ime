package com.jobeen.ime.skin.image

/**
 * 像素级纯函数（背景预处理的数学部分，与 Bitmap 解耦以便单测）：
 * 叠加合成与 stack blur 均在 ARGB IntArray 上原地运算。
 */
object PixelOps {

    /** 把图片按自身 alpha 压平到纯色底上（结果不透明），消除透明像素的未定义透出。 */
    fun flattenOver(pixels: IntArray, bgArgb: Int) {
        val bgR = bgArgb shr 16 and 0xFF
        val bgG = bgArgb shr 8 and 0xFF
        val bgB = bgArgb and 0xFF
        for (i in pixels.indices) {
            val p = pixels[i]
            val a = p ushr 24
            if (a == 0xFF) continue
            val inv = 255 - a
            val r = ((p shr 16 and 0xFF) * a + bgR * inv) / 255
            val g = ((p shr 8 and 0xFF) * a + bgG * inv) / 255
            val b = ((p and 0xFF) * a + bgB * inv) / 255
            pixels[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
    }

    /** 向底色方向混合 alpha 比例（overlayAlpha 的冷路径实现，详设 §2.6）。 */
    fun tintToward(pixels: IntArray, argb: Int, alpha: Float) {
        if (alpha <= 0f) return
        val tR = argb shr 16 and 0xFF
        val tG = argb shr 8 and 0xFF
        val tB = argb and 0xFF
        val keep = 1f - alpha
        for (i in pixels.indices) {
            val p = pixels[i]
            val r = ((p shr 16 and 0xFF) * keep + tR * alpha).toInt()
            val g = ((p shr 8 and 0xFF) * keep + tG * alpha).toInt()
            val b = ((p and 0xFF) * keep + tB * alpha).toInt()
            pixels[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
    }

    /**
     * Stack blur（Klingemann 算法，单通道独立、alpha 不参与）：
     * radius 为像素半径（1..254），两次迭代近似高斯。原地修改。
     */
    fun stackBlur(pixels: IntArray, width: Int, height: Int, radius: Int) {
        if (radius < 1 || width <= 0 || height <= 0) return
        val r = radius.coerceAtMost(254)
        val div = 2 * r + 1
        val tmp = IntArray(maxOf(width, height))

        // 水平方向
        for (y in 0 until height) {
            val row = y * width
            var sumR = 0
            var sumG = 0
            var sumB = 0
            // 初始窗口：左界外以首像素填充
            val first = pixels[row]
            sumR = (first shr 16 and 0xFF) * (r + 1)
            sumG = (first shr 8 and 0xFF) * (r + 1)
            sumB = (first and 0xFF) * (r + 1)
            for (i in 1..r) {
                val p = pixels[row + minOf(i, width - 1)]
                sumR += p shr 16 and 0xFF
                sumG += p shr 8 and 0xFF
                sumB += p and 0xFF
            }
            for (x in 0 until width) {
                tmp[x] = (0xFF shl 24) or ((sumR / div) shl 16) or
                    ((sumG / div) shl 8) or (sumB / div)
                val outIdx = x - r
                val inIdx = x + r + 1
                val outP = pixels[row + if (outIdx < 0) 0 else outIdx]
                val inP = pixels[row + if (inIdx >= width) width - 1 else inIdx]
                sumR += (inP shr 16 and 0xFF) - (outP shr 16 and 0xFF)
                sumG += (inP shr 8 and 0xFF) - (outP shr 8 and 0xFF)
                sumB += (inP and 0xFF) - (outP and 0xFF)
            }
            System.arraycopy(tmp, 0, pixels, row, width)
        }
        // 垂直方向
        for (x in 0 until width) {
            var sumR = 0
            var sumG = 0
            var sumB = 0
            val first = pixels[x]
            sumR = (first shr 16 and 0xFF) * (r + 1)
            sumG = (first shr 8 and 0xFF) * (r + 1)
            sumB = (first and 0xFF) * (r + 1)
            for (i in 1..r) {
                val p = pixels[minOf(i, height - 1) * width + x]
                sumR += p shr 16 and 0xFF
                sumG += p shr 8 and 0xFF
                sumB += p and 0xFF
            }
            for (y in 0 until height) {
                tmp[y] = (0xFF shl 24) or ((sumR / div) shl 16) or
                    ((sumG / div) shl 8) or (sumB / div)
                val outIdy = y - r
                val inIdy = y + r + 1
                val outP = pixels[(if (outIdy < 0) 0 else outIdy) * width + x]
                val inP = pixels[(if (inIdy >= height) height - 1 else inIdy) * width + x]
                sumR += (inP shr 16 and 0xFF) - (outP shr 16 and 0xFF)
                sumG += (inP shr 8 and 0xFF) - (outP shr 8 and 0xFF)
                sumB += (inP and 0xFF) - (outP and 0xFF)
            }
            for (y in 0 until height) {
                pixels[y * width + x] = tmp[y]
            }
        }
    }

    /**
     * 采样率计算（定稿十一.3：先读边界再按目标采样）：
     * 返回 2 的幂采样率，使采样后尺寸仍不小于目标（标准 BitmapFactory 口径）。
     */
    fun computeSampleSize(srcWidth: Int, srcHeight: Int, targetWidth: Int, targetHeight: Int): Int {
        if (srcWidth <= 0 || srcHeight <= 0 || targetWidth <= 0 || targetHeight <= 0) return 1
        var sample = 1
        while (srcWidth / (sample * 2) >= targetWidth && srcHeight / (sample * 2) >= targetHeight) {
            sample *= 2
        }
        return sample
    }
}
