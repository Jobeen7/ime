package com.jobeen.ime.base.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** E2：下载前余量判定与镜像 URL 候选都是纯函数，在此钉住。 */
class DownloadGuardsTest {

    @Test
    fun space_exactFit_allowed() {
        assertTrue(hasEnoughSpace(300, 200, 100))
        assertTrue(hasEnoughSpace(200, 200, 0))
    }

    @Test
    fun space_oneByteShort_refused() {
        assertFalse(hasEnoughSpace(299, 200, 100))
        assertFalse(hasEnoughSpace(199, 200, 0))
        assertFalse(hasEnoughSpace(0, 100, 0))
    }

    @Test
    fun space_unknownSizeOrFreeSpace_notBlocking() {
        // StatFs 失败：判不出不拦，拦了会把可下的也拒了
        assertTrue(hasEnoughSpace(-1, 200, 100))
        // 分块传输：总量未知不拦
        assertTrue(hasEnoughSpace(100, -1, 100))
        assertTrue(hasEnoughSpace(100, 0, 100))
    }

    @Test
    fun space_negativeReserve_refused() {
        assertFalse(hasEnoughSpace(1000, 100, -1))
    }

    @Test
    fun space_hugeValues_noOverflow() {
        assertTrue(hasEnoughSpace(Long.MAX_VALUE, Long.MAX_VALUE - 1, 1))
        assertFalse(hasEnoughSpace(Long.MAX_VALUE - 1, Long.MAX_VALUE - 1, 1))
    }

    @Test
    fun mirror_primaryProxy_rewrittenToDomesticMirror() {
        assertEquals(
            "https://gh-proxy.com/https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/x.tar.bz2",
            mirrorDownloadUrl(
                "https://gh-proxy.org/https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/x.tar.bz2",
            ),
        )
    }

    @Test
    fun mirror_directGithub_rewrittenToDomesticMirror() {
        assertEquals(
            "https://gh-proxy.com/https://github.com/amzxyz/RIME-LMDG/releases/download/LTS/wanxiang-lts-zh-hans.gram",
            mirrorDownloadUrl(
                "https://github.com/amzxyz/RIME-LMDG/releases/download/LTS/wanxiang-lts-zh-hans.gram",
            ),
        )
    }

    @Test
    fun mirror_otherShapes_noCandidate() {
        // 已是镜像地址：不来回倒
        assertNull(
            mirrorDownloadUrl(
                "https://gh-proxy.com/https://github.com/k2-fsa/sherpa-onnx/x.tar.bz2",
            ),
        )
        // 非 GitHub 形态：不猜
        assertNull(mirrorDownloadUrl("https://example.com/model.bin"))
        assertNull(mirrorDownloadUrl("http://github.com/k2-fsa/x.tar.bz2"))
        assertNull(mirrorDownloadUrl(""))
        assertNull(mirrorDownloadUrl("https://github.com/"))
        assertNull(mirrorDownloadUrl("https://gh-proxy.org/"))
    }
}
