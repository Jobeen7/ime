package com.jobeen.ime.skin.image

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File

/**
 * 带上限的文件位图两遍式解码（共用 helper，后续批次复用）：
 *
 * 先 bounds 探尺寸，再按目标尺寸采样（[PixelOps.computeSampleSize]，
 * 2 的幂、采样后仍不小于目标），绝不以原尺寸解码超大图。
 * 任一失败（文件无效、尺寸非法、解码 null、OOM）返回 null，
 * 调用方按原有回落口径处理（颜色键面／渐变／纯色），绝不抛、
 * 绝不留空白。冷路径专用，禁止进绘制热路径。
 *
 * 注意上限语义：上限是“解码目标尺寸”，不是固定 512——背景
 * 产物目标就是屏幕宽 × 键盘高，用 512 会把正常背景糊掉；
 * 小图调用方自行传 512 之类的小目标即可（如键图／图标口径）。
 */
object BoundedBitmapDecoder {

    /**
     * 两遍式解码文件；目标尺寸任一 <=0 时返回 null（调用方传非法目标）。
     * 施工单 5 第 7 条：超限图不再直接拒收——采样率取目标采样与
     * 上限采样（边长 ≤ [ImageDecodePolicy.MAX_DECODE_SIDE]、像素
     * ≤ [ImageDecodePolicy.MAX_DECODE_PIXELS]）两者较大值，解码
     * 结果恒在上限内；背景产物已是降采样后文件，不受影响。
     */
    fun decodeFile(file: File, targetWidthPx: Int, targetHeightPx: Int): Bitmap? {
        if (!file.isFile || targetWidthPx <= 0 || targetHeightPx <= 0) return null
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            val options = BitmapFactory.Options().apply {
                inSampleSize = maxOf(
                    PixelOps.computeSampleSize(
                        bounds.outWidth, bounds.outHeight, targetWidthPx, targetHeightPx,
                    ),
                    PixelOps.computeCappedSampleSize(
                        bounds.outWidth, bounds.outHeight,
                        ImageDecodePolicy.MAX_DECODE_SIDE, ImageDecodePolicy.MAX_DECODE_PIXELS,
                    ),
                )
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            BitmapFactory.decodeFile(file.absolutePath, options)
        } catch (error: OutOfMemoryError) {
            null
        } catch (error: Exception) {
            null
        }
    }
}
