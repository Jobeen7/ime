package com.jobeen.ime

import com.jobeen.ime.base.update.WanxiangUpdateManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 方案更新误报修复的判定纯函数测试：判定从「只看下载记录」改为
 * 记录 + 内容指纹/内置版本兜底，记录为空不再直接误判有更新。
 */
class WanxiangUpdateManagerTest {

    private fun info(
        schemaLocal: String? = null,
        gramLocal: String? = null,
        schemaRemote: String = "v18.1.2",
        gramRemotePub: String = REMOTE_PUB,
    ) = WanxiangUpdateManager.UpdateInfo(
        schemaRemoteVersion = schemaRemote,
        schemaLocalVersion = schemaLocal,
        gramRemotePublishedAt = gramRemotePub,
        gramLocalPublishedAt = gramLocal,
        dictRemoteFingerprint = null,
        gramRemoteFingerprint = null,
        gramSha256 = SHA,
    )

    // ---- 语法模型：内容兜底 ----

    @Test
    fun gram_recordMissingButFingerprintMatches_noUpdateAndBackfill() {
        val decision = WanxiangUpdateManager.resolveGramUpdate(
            recordedPublishedAt = null,
            localFingerprint = SHA,
            remotePublishedAt = REMOTE_PUB,
            remoteSha256 = SHA,
        )
        assertEquals(REMOTE_PUB, decision.effectiveLocalPublishedAt)
        assertTrue(decision.backfill)
        assertFalse(info(gramLocal = decision.effectiveLocalPublishedAt).gramUpdateAvailable)
    }

    @Test
    fun gram_fingerprintDiffers_updateAvailable() {
        val decision = WanxiangUpdateManager.resolveGramUpdate(
            recordedPublishedAt = null,
            localFingerprint = OTHER_SHA,
            remotePublishedAt = REMOTE_PUB,
            remoteSha256 = SHA,
        )
        assertNull(decision.effectiveLocalPublishedAt)
        assertFalse(decision.backfill)
        assertTrue(info(gramLocal = decision.effectiveLocalPublishedAt).gramUpdateAvailable)
    }

    @Test
    fun gram_staleRecordButContentMatches_backfilledToRemote() {
        val decision = WanxiangUpdateManager.resolveGramUpdate(
            recordedPublishedAt = "2026-01-01T00:00:00Z",
            localFingerprint = SHA,
            remotePublishedAt = REMOTE_PUB,
            remoteSha256 = SHA,
        )
        assertEquals(REMOTE_PUB, decision.effectiveLocalPublishedAt)
        assertTrue(decision.backfill)
        assertFalse(info(gramLocal = decision.effectiveLocalPublishedAt).gramUpdateAvailable)
    }

    @Test
    fun gram_recordAlreadyCurrent_noBackfill() {
        val decision = WanxiangUpdateManager.resolveGramUpdate(
            recordedPublishedAt = REMOTE_PUB,
            localFingerprint = SHA,
            remotePublishedAt = REMOTE_PUB,
            remoteSha256 = SHA,
        )
        assertEquals(REMOTE_PUB, decision.effectiveLocalPublishedAt)
        assertFalse(decision.backfill)
    }

    @Test
    fun gram_noRemoteDigest_fallsBackToRecord() {
        val decision = WanxiangUpdateManager.resolveGramUpdate(
            recordedPublishedAt = null,
            localFingerprint = SHA,
            remotePublishedAt = REMOTE_PUB,
            remoteSha256 = "",
        )
        assertNull(decision.effectiveLocalPublishedAt)
        assertFalse(decision.backfill)
        assertTrue(info(gramLocal = decision.effectiveLocalPublishedAt).gramUpdateAvailable)
    }

    @Test
    fun gram_noLocalFile_fallsBackToRecord() {
        val decision = WanxiangUpdateManager.resolveGramUpdate(
            recordedPublishedAt = null,
            localFingerprint = null,
            remotePublishedAt = REMOTE_PUB,
            remoteSha256 = SHA,
        )
        assertNull(decision.effectiveLocalPublishedAt)
        assertFalse(decision.backfill)
        assertTrue(info(gramLocal = decision.effectiveLocalPublishedAt).gramUpdateAvailable)
    }

    // ---- 方案：内置版本兜底 ----

    @Test
    fun schema_recordMissingUsesBundled_localNewerNoUpdate() {
        val local = WanxiangUpdateManager.resolveLocalSchemaVersion(
            recordedVersion = null,
            bundledVersion = "v18.1.2",
        )
        assertEquals("v18.1.2", local)
        // 本地（内置）高于远端：不提示更新、不降级
        assertFalse(info(schemaLocal = local, schemaRemote = "v18.1.0").schemaUpdateAvailable)
        // 与远端相同：不提示更新（升版后误报的正是这一情形）
        assertFalse(info(schemaLocal = local, schemaRemote = "v18.1.2").schemaUpdateAvailable)
        // 远端确实更高时仍能检出
        assertTrue(info(schemaLocal = local, schemaRemote = "v18.2.0").schemaUpdateAvailable)
    }

    @Test
    fun schema_recordWinsOverBundled() {
        val local = WanxiangUpdateManager.resolveLocalSchemaVersion(
            recordedVersion = "v18.3.0",
            bundledVersion = "v18.1.2",
        )
        assertEquals("v18.3.0", local)
        assertFalse(info(schemaLocal = local, schemaRemote = "v18.1.2").schemaUpdateAvailable)
    }

    @Test
    fun schema_noRecordNoBundled_staysUnknown() {
        val local = WanxiangUpdateManager.resolveLocalSchemaVersion(
            recordedVersion = null,
            bundledVersion = null,
        )
        assertNull(local)
        assertTrue(info(schemaLocal = local).schemaUpdateAvailable)
    }

    private companion object {
        const val REMOTE_PUB = "2026-10-09T00:00:00Z"
        const val SHA =
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
        const val OTHER_SHA =
            "fedcba9876543210fedcba9876543210fedcba9876543210fedcba9876543210"
    }
}
