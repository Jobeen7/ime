package com.jobeen.ime.base.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * B-M2：实时清单点名新资产但摘要无处可得时，resolveLiveTarget 返回
 * null（调用方 fail-closed），绝不静默回落装钉死旧包。
 */
class ModelDownloaderResolveTargetTest {

    private val newAsset =
        "sherpa-onnx-x-asr-160ms-streaming-zipformer-transducer-zh-en-punct-int8-2026-07-01.tar.bz2"
    private val pinnedAsset =
        "sherpa-onnx-x-asr-160ms-streaming-zipformer-transducer-zh-en-punct-int8-2026-06-05.tar.bz2"

    @Test
    fun liveAssetWithRegisteredDigest_returnsLiveTarget() {
        val target = ModelDownloader.resolveLiveTarget(
            listOf(newAsset to "a".repeat(64)),
        )
        assertEquals(newAsset, target?.name)
        assertEquals("a".repeat(64), target?.sha256)
        assertTrue(target?.fromLiveApi == true)
        assertFalse(target?.shaFromPinned == true)
    }

    @Test
    fun liveAssetWithoutDigest_butPinnedForSameName_returnsLiveTargetFromPinned() {
        val target = ModelDownloader.resolveLiveTarget(
            listOf(pinnedAsset to null),
        )
        assertEquals(pinnedAsset, target?.name)
        assertTrue(target?.fromLiveApi == true)
        assertTrue(target?.shaFromPinned == true)
    }

    @Test
    fun liveAssetWithoutDigestAndNotPinned_returnsNull_noSilentFallback() {
        // 新资产无登记摘要、钉死表里也没有它：不能回落旧包
        assertNull(ModelDownloader.resolveLiveTarget(listOf(newAsset to null)))
        assertNull(ModelDownloader.resolveLiveTarget(listOf(newAsset to "")))
    }

    @Test
    fun noLiveAsset_returnsNull() {
        assertNull(ModelDownloader.resolveLiveTarget(emptyList()))
        assertNull(
            ModelDownloader.resolveLiveTarget(
                listOf("unrelated-asset.tar.bz2" to "b".repeat(64)),
            ),
        )
    }

    @Test
    fun newestLiveAssetWins() {
        val target = ModelDownloader.resolveLiveTarget(
            listOf(
                pinnedAsset to "c".repeat(64),
                newAsset to "d".repeat(64),
            ),
        )
        assertEquals(newAsset, target?.name)
        assertEquals("d".repeat(64), target?.sha256)
    }
}
