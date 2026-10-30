// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.skin.pkg

import android.media.MediaMetadataRetriever
import com.jobeen.ime.skin.diag.DiagLog
import java.io.File

/**
 * 主题视频资产校验（2026-10-29 外观轮）。
 *
 * 三重门（导入期，任一失败整包拒绝——不把不可播的大文件
 * 装进库占配额）：
 * 1. 格式白名单：mp4／webm（清单扩展名判定，见
 *    [com.jobeen.ime.skin.model.PackageManifest.VIDEO_EXTENSIONS]）；
 * 2. 数量上限：每包至多 [MAX_VIDEO_COUNT] 个视频资产；
 * 3. 单文件上限 [MAX_VIDEO_FILE_BYTES] ＋解码维度：
 *    宽高＞0 且总像素 ≤ [MAX_VIDEO_PIXELS]（4K）。
 *
 * 维度取自 [MediaMetadataRetriever]（只认文件路径，故校验期
 * 把包内字节写 cache 暂存，finally 内删除——“失败删文件”即此；
 * 导入成功后的视频文件走库内容寻址资产存储，不另复制全局路径）。
 * 校验器以暂存目录注入（不依赖 Context），JVM 单测可用替身。
 */
interface VideoAssetValidator {
    fun check(bytes: ByteArray, tmpDir: File): VideoCheck

    sealed interface VideoCheck {
        data class Ok(val width: Int, val height: Int) : VideoCheck
        data class Failed(val reason: String) : VideoCheck
    }

    companion object {
        /** 每包视频资产上限（1 个生效位＋1 个备用位，防配额滥用）。 */
        const val MAX_VIDEO_COUNT = 2

        /** 单视频文件上限 128MB（参考实现复制上限同值）。 */
        const val MAX_VIDEO_FILE_BYTES = 128L * 1024 * 1024

        /** 视频总像素上限 4K（3840×2160）。 */
        const val MAX_VIDEO_PIXELS = 8_294_400L

        /** 纯数量门（可单测）。 */
        fun countOk(count: Int): Boolean = count <= MAX_VIDEO_COUNT

        /** 纯大小门（可单测）。 */
        fun sizeOk(sizeBytes: Long): Boolean = sizeBytes <= MAX_VIDEO_FILE_BYTES

        /** 纯维度门（可单测）：宽高＞0 且总像素 ≤4K。 */
        fun dimensionsOk(width: Int, height: Int): Boolean =
            width > 0 && height > 0 &&
                width.toLong() * height.toLong() <= MAX_VIDEO_PIXELS
    }
}

/** 真实校验器：retriever 读暂存文件维度；异常只记元数据。 */
class AndroidVideoAssetValidator : VideoAssetValidator {
    override fun check(bytes: ByteArray, tmpDir: File): VideoAssetValidator.VideoCheck {
        val tmp = File(tmpDir, "video-check-${System.nanoTime()}.tmp")
        try {
            tmp.writeBytes(bytes)
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(tmp.absolutePath)
                val width = retriever.extractMetadata(
                    MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH,
                )?.toIntOrNull()
                val height = retriever.extractMetadata(
                    MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT,
                )?.toIntOrNull()
                if (width == null || height == null) {
                    return VideoAssetValidator.VideoCheck.Failed("no_dimensions")
                }
                return VideoAssetValidator.VideoCheck.Ok(width, height)
            } finally {
                runCatching { retriever.release() }
            }
        } catch (e: Exception) {
            DiagLog.w(
                "store", "video_check_failed",
                mapOf("reason" to (e.javaClass.simpleName ?: "unknown")),
            )
            return VideoAssetValidator.VideoCheck.Failed("retriever_failed")
        } finally {
            runCatching { tmp.delete() }
        }
    }
}
