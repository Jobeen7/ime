package com.jobeen.ime.skin.model

import com.jobeen.ime.skin.media.AudioInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 音效接纳公式（定稿十二.2 / 详设 §7.1）：逐项上限、静默跳过
 * 继续接纳、极低档整段跳过；PCM 预算在现行上限下不可达（B4），
 * 不设预算耗尽用例。
 */
class SkinSoundAdmissionTest {

    private fun facts(
        ext: String = "mp3",
        byteSize: Long = 100_000,
        durationMs: Long = 800,
        sampleRate: Int = 44_100,
        channels: Int = 2,
        decodable: Boolean = true,
    ) = AudioAssetFacts(
        sha256 = "s".repeat(64),
        ext = ext,
        byteSize = byteSize,
        info = if (decodable) AudioInfo(durationMs, sampleRate, channels) else null,
    )

    private fun admit(
        ids: List<String>,
        factsById: Map<String, AudioAssetFacts?>,
        tap: String? = null,
        budget: Long = 32L * 1024 * 1024,
        skipAll: Boolean = false,
    ) = SkinSoundAdmission.admit(ids, tap, { factsById[it] }, budget, skipAll)

    @Test
    fun validSoundAdmittedWithPcmAccounting() {
        val result = admit(listOf("snd_a"), mapOf("snd_a" to facts()), tap = "snd_a")
        val admitted = result.admitted.getValue("snd_a")
        assertEquals(44_100L * 2 * 2 * 800 / 1000, admitted.pcmBytes)
        assertEquals("snd_a", result.defaultTapAssetId)
        assertTrue(result.rejected.isEmpty())
    }

    @Test
    fun eachLimitRejectsWithItsReason() {
        val cases: List<Pair<AudioAssetFacts, SoundRejectReason>> = listOf(
            facts(ext = "flac") to SoundRejectReason.FORMAT,
            facts(byteSize = SkinSoundAdmission.MAX_FILE_BYTES + 1) to SoundRejectReason.SIZE,
            facts(decodable = false) to SoundRejectReason.DECODE,
            facts(durationMs = SkinSoundAdmission.MAX_DURATION_MS + 1) to SoundRejectReason.DURATION,
            facts(sampleRate = SkinSoundAdmission.MAX_SAMPLE_RATE + 1) to SoundRejectReason.SAMPLE_RATE,
            facts(channels = 3) to SoundRejectReason.CHANNELS,
        )
        for ((assetFacts, reason) in cases) {
            val result = admit(listOf("snd_x"), mapOf("snd_x" to assetFacts))
            assertTrue("$reason 应剔除", result.admitted.isEmpty())
            assertEquals(listOf(RejectedSound("snd_x", reason)), result.rejected)
        }
        // 清单外引用 → MISSING
        val missing = admit(listOf("ghost"), emptyMap())
        assertEquals(
            listOf(RejectedSound("ghost", SoundRejectReason.MISSING)),
            missing.rejected,
        )
    }

    @Test
    fun boundaryValuesAdmitted() {
        val edge = facts(
            byteSize = SkinSoundAdmission.MAX_FILE_BYTES,
            durationMs = SkinSoundAdmission.MAX_DURATION_MS,
            sampleRate = SkinSoundAdmission.MAX_SAMPLE_RATE,
            channels = SkinSoundAdmission.MAX_CHANNELS,
        )
        val result = admit(listOf("snd_edge"), mapOf("snd_edge" to edge))
        assertTrue(result.admitted.containsKey("snd_edge"))
    }

    @Test
    fun countLimitRejectsBeyondSixteen() {
        val ids = (1..17).map { "snd_$it" }
        val result = admit(ids, ids.associateWith { facts(byteSize = 10_000) })
        assertEquals(SkinSoundAdmission.MAX_SOUND_COUNT, result.admitted.size)
        assertEquals(
            listOf(RejectedSound("snd_17", SoundRejectReason.COUNT_LIMIT)),
            result.rejected,
        )
    }

    @Test
    fun totalBytesSkipsOverflowAndContinues() {
        // 8 × 240KB = 1.92MB；第 9 个大文件将超 2MB 总额被跳过，
        // 其后的小文件仍可接纳（静默跳过口径，详设 §7.1）
        val big = facts(byteSize = 240_000, durationMs = 100)
        val ids = (1..9).map { "big_$it" } + listOf("small_1")
        val factsById = ids.associateWith { if (it == "small_1") facts(byteSize = 1_000) else big }
        val result = admit(ids, factsById)
        assertEquals(9, result.admitted.size)
        assertTrue(result.admitted.containsKey("small_1"))
        assertEquals(
            listOf(RejectedSound("big_9", SoundRejectReason.TOTAL_BYTES)),
            result.rejected,
        )
    }

    @Test
    fun extremeTierSkipsAllAdmission() {
        val result = admit(
            listOf("snd_a"), mapOf("snd_a" to facts()), tap = "snd_a", skipAll = true,
        )
        assertTrue(result.skippedByDegradation)
        assertTrue(result.admitted.isEmpty())
        assertTrue(result.rejected.isEmpty())
        assertNull(result.defaultTapAssetId)
    }

    @Test
    fun defaultTapFallsBackWhenItsAssetRejected() {
        val result = admit(
            listOf("snd_bad"),
            mapOf("snd_bad" to facts(ext = "flac")),
            tap = "snd_bad",
        )
        assertNull(result.defaultTapAssetId)
    }
}
