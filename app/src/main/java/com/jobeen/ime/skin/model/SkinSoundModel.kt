package com.jobeen.ime.skin.model

import com.jobeen.ime.skin.media.AudioInfo

/**
 * 自定义音效接纳模型（定稿十二.2 / Batch 2 详设 §7.1）：
 * 接纳判定在快照构建期（冷路径）按清单顺序逐项执行，确定性
 * 只取决于（皮肤包， 内存档位）；播放期不补检查（详设 §7.2）。
 */

/** 资产事实（工厂注入）：内容寻址 sha + 文件事实 + 解码元信息。 */
data class AudioAssetFacts(
    val sha256: String,
    /** 扩展名（小写、无点）。 */
    val ext: String,
    val byteSize: Long,
    /** 解码元信息；不可解码为 null。 */
    val info: AudioInfo?,
)

/** 已接纳音效（快照携带，播放层按此解析文件）。 */
data class AdmittedSound(
    val assetId: String,
    val sha256: String,
    val ext: String,
    val pcmBytes: Long,
)

/** 音效剔除原因（详设 §八枚举）。 */
enum class SoundRejectReason {
    FORMAT,
    SIZE,
    DURATION,
    SAMPLE_RATE,
    CHANNELS,
    COUNT_LIMIT,
    TOTAL_BYTES,
    DECODE,
    MISSING,
}

data class RejectedSound(
    val assetId: String,
    val reason: SoundRejectReason,
)

/** 快照中的音效绑定结果。 */
data class SoundBindings(
    val admitted: Map<String, AdmittedSound>,
    /** audio.tap 解析结果（指向已接纳资产）；未接纳/未配置为 null。 */
    val defaultTapAssetId: String?,
    val rejected: List<RejectedSound>,
    /** 极低内存档整段跳过接纳（裁定七）：此时 admitted 恒空、rejected 恒空。 */
    val skippedByDegradation: Boolean,
) {
    companion object {
        val EMPTY = SoundBindings(
            admitted = emptyMap(),
            defaultTapAssetId = null,
            rejected = emptyList(),
            skippedByDegradation = false,
        )
    }
}

/** 接纳公式（详设 §7.1 定稿十二.2 + 裁定 B4 注记）。 */
object SkinSoundAdmission {

    const val MAX_SOUND_COUNT = 16
    const val MAX_FILE_BYTES = 250_000L
    const val MAX_TOTAL_BYTES = 2_000_000L
    const val MAX_DURATION_MS = 5_000L
    const val MAX_SAMPLE_RATE = 48_000
    const val MAX_CHANNELS = 2
    val SUPPORTED_EXTS: Set<String> = setOf("mp3", "ogg", "wav", "m4a")

    /**
     * @param referencedInOrder 被引用资产 id（清单顺序在前、未入清单的追加在后；
     *   未入清单者 facts 恒 null → MISSING）。
     * @param defaultTapAssetId audio.tap 指向的资产 id（可空）。
     * @param audioBudgetBytes 当前档位 PCM 预算（定稿十二.2；在现行
     *   上限组合下数学上不可达——16×5s×48kHz×2ch×2B = 15.36MB <
     *   最低档 16MiB——保留为防御项，触发时按 TOTAL_BYTES 剔除，
     *   裁定 B4 诚实口径）。
     * @param skipAll 极低档整段跳过（裁定七）。
     */
    fun admit(
        referencedInOrder: List<String>,
        defaultTapAssetId: String?,
        facts: (String) -> AudioAssetFacts?,
        audioBudgetBytes: Long,
        skipAll: Boolean,
    ): SoundBindings {
        if (skipAll) {
            return SoundBindings(
                admitted = emptyMap(),
                defaultTapAssetId = null,
                rejected = emptyList(),
                skippedByDegradation = true,
            )
        }
        val admitted = LinkedHashMap<String, AdmittedSound>()
        val rejected = mutableListOf<RejectedSound>()
        var totalBytes = 0L
        var totalPcm = 0L
        for (assetId in referencedInOrder) {
            fun reject(reason: SoundRejectReason) {
                rejected += RejectedSound(assetId, reason)
            }
            val assetFacts = facts(assetId)
            if (assetFacts == null) {
                reject(SoundRejectReason.MISSING)
                continue
            }
            if (assetFacts.ext !in SUPPORTED_EXTS) {
                reject(SoundRejectReason.FORMAT)
                continue
            }
            if (assetFacts.byteSize > MAX_FILE_BYTES) {
                reject(SoundRejectReason.SIZE)
                continue
            }
            val info = assetFacts.info
            if (info == null) {
                reject(SoundRejectReason.DECODE)
                continue
            }
            if (info.durationMs > MAX_DURATION_MS) {
                reject(SoundRejectReason.DURATION)
                continue
            }
            if (info.sampleRate > MAX_SAMPLE_RATE) {
                reject(SoundRejectReason.SAMPLE_RATE)
                continue
            }
            if (info.channels > MAX_CHANNELS) {
                reject(SoundRejectReason.CHANNELS)
                continue
            }
            if (admitted.size >= MAX_SOUND_COUNT) {
                reject(SoundRejectReason.COUNT_LIMIT)
                continue
            }
            if (totalBytes + assetFacts.byteSize > MAX_TOTAL_BYTES) {
                reject(SoundRejectReason.TOTAL_BYTES)
                continue
            }
            if (totalPcm + info.pcmBytes > audioBudgetBytes) {
                reject(SoundRejectReason.TOTAL_BYTES) // 防御项，见类注（裁定 B4）
                continue
            }
            admitted[assetId] = AdmittedSound(
                assetId = assetId,
                sha256 = assetFacts.sha256,
                ext = assetFacts.ext,
                pcmBytes = info.pcmBytes,
            )
            totalBytes += assetFacts.byteSize
            totalPcm += info.pcmBytes
        }
        return SoundBindings(
            admitted = admitted,
            defaultTapAssetId = defaultTapAssetId?.takeIf { it in admitted },
            rejected = rejected,
            skippedByDegradation = false,
        )
    }
}
