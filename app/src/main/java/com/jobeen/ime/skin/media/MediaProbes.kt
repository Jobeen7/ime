package com.jobeen.ime.skin.media

import android.graphics.BitmapFactory
import android.media.MediaDataSource
import android.media.MediaExtractor
import android.media.MediaFormat
import kotlin.math.roundToInt

/** 图片元信息（只解码元信息，不解码像素；定稿十一.3）。 */
data class ImageSize(val width: Int, val height: Int) {
    val pixels: Long get() = width.toLong() * height.toLong()
}

interface ImageInfoProbe {
    /** 不可解码返回 null。 */
    fun bounds(bytes: ByteArray): ImageSize?
}

object AndroidImageInfoProbe : ImageInfoProbe {
    override fun bounds(bytes: ByteArray): ImageSize? {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        if (options.outWidth <= 0 || options.outHeight <= 0) return null
        return ImageSize(options.outWidth, options.outHeight)
    }
}

/** 音效元信息（定稿十二.2 的解码校验口径）。 */
data class AudioInfo(val durationMs: Long, val sampleRate: Int, val channels: Int) {
    /** 解码 PCM 占用（采样率 × 声道 × 2 字节 × 时长，定稿十二.2 核算式）。 */
    val pcmBytes: Long get() = sampleRate.toLong() * channels * 2 * durationMs / 1000
}

interface AudioInfoProbe {
    /** 不可解码返回 null。 */
    fun probe(bytes: ByteArray): AudioInfo?
}

object AndroidAudioInfoProbe : AudioInfoProbe {
    override fun probe(bytes: ByteArray): AudioInfo? = runCatching {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(object : MediaDataSource() {
                override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
                    if (position >= bytes.size) return -1
                    val count = minOf(size.toLong(), bytes.size - position).toInt()
                    System.arraycopy(bytes, position.toInt(), buffer, offset, count)
                    return count
                }

                override fun getSize(): Long = bytes.size.toLong()
                override fun close() = Unit
            })
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                if (!mime.startsWith("audio/")) continue
                val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) {
                    format.getLong(MediaFormat.KEY_DURATION)
                } else {
                    0L
                }
                val sampleRate = if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                    format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                } else {
                    0
                }
                val channels = if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                    format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                } else {
                    0
                }
                return AudioInfo(
                    durationMs = (durationUs / 1000.0).roundToInt().toLong(),
                    sampleRate = sampleRate,
                    channels = channels,
                )
            }
            null
        } finally {
            extractor.release()
        }
    }.getOrNull()
}
