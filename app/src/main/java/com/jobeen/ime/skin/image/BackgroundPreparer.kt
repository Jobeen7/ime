package com.jobeen.ime.skin.image

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import kotlin.math.roundToInt

/**
 * 背景预处理（详设 §2.6，冷路径专用）：
 * 解码（先读边界、按目标采样）→ 缩放到目标尺寸 → 模糊预计算 →
 * 压平到底色并按 overlayAlpha 混合 → 落盘 prepared/。
 * 热路径（onDraw/首显）禁止调用本类。
 */
interface BackgroundPreparer {

    data class Request(
        val assetBytes: ByteArray,
        val assetSha256: String,
        /** 产物落盘的代次目录（staging 内或库内，产物写其 prepared/ 子目录）。 */
        val genDir: File,
        val targetWidthPx: Int,
        val targetHeightPx: Int,
        /** 生效模糊半径（dp，已含降级处理）；0 = 不模糊。 */
        val blurRadiusDp: Float,
        /** styles.background 合成值：压平底色与 overlay 混合目标。 */
        val overlayColorArgb: Int,
        val overlayAlpha: Float,
        /**
         * 产物键：由快照构建口径统一计算（SkinSnapshotBuilder.preparedKey），
         * 本接口只按键落盘，保证导入/应用/运行三处对同一产物同名。
         */
        val preparedKey: String,
    )

    sealed interface Result {
        data class Done(val preparedKey: String, val widthPx: Int, val heightPx: Int) : Result
        data class Failed(val reason: String) : Result
    }

    fun prepare(request: Request): Result
}

/**
 * Android 实现：模糊半径按 [density] 换算 px；产物键与快照构建
 * 同口径（[SkinSnapshotBuilder.preparedKey]），同键命中直接复用。
 */
class AndroidBackgroundPreparer(
    private val density: Float,
) : BackgroundPreparer {

    override fun prepare(request: BackgroundPreparer.Request): BackgroundPreparer.Result {
        val key = request.preparedKey
        val preparedDir = File(request.genDir, "prepared").apply { mkdirs() }
        val outFile = File(preparedDir, "$key.webp")
        if (outFile.isFile && outFile.length() > 0) {
            return BackgroundPreparer.Result.Done(key, request.targetWidthPx, request.targetHeightPx)
        }
        return runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(request.assetBytes, 0, request.assetBytes.size, bounds)
            require(bounds.outWidth > 0 && bounds.outHeight > 0) { "undecodable" }
            val sample = PixelOps.computeSampleSize(
                bounds.outWidth, bounds.outHeight, request.targetWidthPx, request.targetHeightPx,
            )
            val decodeOptions = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            val decoded = BitmapFactory.decodeByteArray(
                request.assetBytes, 0, request.assetBytes.size, decodeOptions,
            ) ?: error("decode_failed")
            val scaled = if (decoded.width == request.targetWidthPx &&
                decoded.height == request.targetHeightPx
            ) {
                decoded
            } else {
                Bitmap.createScaledBitmap(decoded, request.targetWidthPx, request.targetHeightPx, true)
                    .also { if (it !== decoded) decoded.recycle() }
            }
            val pixels = IntArray(scaled.width * scaled.height)
            scaled.getPixels(pixels, 0, scaled.width, 0, 0, scaled.width, scaled.height)
            val radiusPx = (request.blurRadiusDp * density).roundToInt()
            if (radiusPx > 0) {
                PixelOps.stackBlur(pixels, scaled.width, scaled.height, radiusPx)
            }
            PixelOps.flattenOver(pixels, request.overlayColorArgb)
            PixelOps.tintToward(pixels, request.overlayColorArgb, request.overlayAlpha)
            scaled.setPixels(pixels, 0, scaled.width, 0, 0, scaled.width, scaled.height)
            val format = if (android.os.Build.VERSION.SDK_INT >= 30) {
                Bitmap.CompressFormat.WEBP_LOSSY
            } else {
                @Suppress("DEPRECATION")
                Bitmap.CompressFormat.WEBP
            }
            outFile.outputStream().buffered().use { out ->
                check(scaled.compress(format, 88, out)) { "compress_failed" }
            }
            scaled.recycle()
            writePreparedIndex(preparedDir, key, request)
            BackgroundPreparer.Result.Done(key, request.targetWidthPx, request.targetHeightPx)
        }.getOrElse {
            BackgroundPreparer.Result.Failed(it.message ?: it.javaClass.simpleName)
        }
    }

    /** prepared.json：产物清单（调试与对账用；运行态按 key 直取文件）。 */
    private fun writePreparedIndex(
        preparedDir: File,
        key: String,
        request: BackgroundPreparer.Request,
    ) {
        val indexFile = File(preparedDir, "prepared.json")
        val entry = """{"key":"$key","asset":"${request.assetSha256}",""" +
            """"w":${request.targetWidthPx},"h":${request.targetHeightPx}}"""
        val existing = runCatching { indexFile.readText() }.getOrNull()
        val merged = if (existing.isNullOrBlank()) {
            """{"products":[$entry]}"""
        } else if (existing.contains("\"$key\"")) {
            existing
        } else {
            existing.replaceFirst("]}", ",$entry]}")
        }
        runCatching { indexFile.writeText(merged) }
    }
}
