// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.skin.runtime

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 背景重建请求现势判定（W17-4）纯决策单测：解码前按代次＋安装
 * token 双重核对，任一过期即已被取代——旧请求不再整张解完才丢。
 * 与提交段裁决同口径。不依赖 Android 框架。
 */
class SkinSnapshotProviderBackgroundRequestTest {

    @Test
    fun current_whenGenerationAndTokenMatch() {
        assertTrue(SkinSnapshotProvider.isBackgroundRequestCurrent(5L, 5L, 9L, 9L))
    }

    @Test
    fun stale_whenGenerationSuperseded() {
        assertFalse(SkinSnapshotProvider.isBackgroundRequestCurrent(4L, 5L, 9L, 9L))
    }

    @Test
    fun stale_whenInstallTokenSuperseded() {
        assertFalse(SkinSnapshotProvider.isBackgroundRequestCurrent(5L, 5L, 8L, 9L))
    }

    @Test
    fun stale_whenBothSuperseded() {
        assertFalse(SkinSnapshotProvider.isBackgroundRequestCurrent(4L, 5L, 8L, 9L))
    }

    @Test
    fun stale_whenGenerationAheadOfLatest() {
        // 代次只应单调递增、请求代次不可能超前最新值；相等以外一律按过期计
        assertFalse(SkinSnapshotProvider.isBackgroundRequestCurrent(6L, 5L, 9L, 9L))
    }
}
