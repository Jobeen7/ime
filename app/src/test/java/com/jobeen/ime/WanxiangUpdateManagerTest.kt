package com.jobeen.ime

import com.jobeen.ime.base.update.WanxiangUpdateManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 方案更新判定纯函数测试：方案侧记录 + 内置版本兜底；语法模型侧
 * 双方哈希都非空时以哈希为准（原地换包 published_at 不变也判得出），
 * 哈希缺失回落记录语义。全矩阵见各 gram_* 用例。
 */
class WanxiangUpdateManagerTest {

    private fun info(
        schemaLocal: String? = null,
        gramLocal: String? = null,
        schemaRemote: String = "v18.1.2",
        gramRemotePub: String = REMOTE_PUB,
        gramUpdate: Boolean = false,
        gramSha256: String = SHA,
    ) = WanxiangUpdateManager.UpdateInfo(
        schemaRemoteVersion = schemaRemote,
        schemaLocalVersion = schemaLocal,
        gramRemotePublishedAt = gramRemotePub,
        gramLocalPublishedAt = gramLocal,
        dictRemoteFingerprint = null,
        gramRemoteFingerprint = null,
        gramSha256 = gramSha256,
        gramUpdateAvailable = gramUpdate,
    )

    /** 判定三元组与 UpdateInfo 字段的一致性：检查流程就是这样填的 */
    private fun assertDecision(
        decision: WanxiangUpdateManager.GramUpdateDecision,
        effective: String?,
        backfill: Boolean,
        update: Boolean,
    ) {
        assertEquals(effective, decision.effectiveLocalPublishedAt)
        assertEquals(backfill, decision.backfill)
        assertEquals(update, decision.updateAvailable)
        assertEquals(
            update,
            info(
                gramLocal = decision.effectiveLocalPublishedAt,
                gramUpdate = decision.updateAvailable,
            ).gramUpdateAvailable,
        )
    }

    // ---- 语法模型：哈希为准（双方哈希都非空） ----

    @Test
    fun gram_recordMissingButFingerprintMatches_noUpdateAndBackfill() {
        val decision = WanxiangUpdateManager.resolveGramUpdate(
            recordedPublishedAt = null,
            localFingerprint = SHA,
            remotePublishedAt = REMOTE_PUB,
            remoteSha256 = SHA,
        )
        assertDecision(decision, effective = REMOTE_PUB, backfill = true, update = false)
    }

    @Test
    fun gram_fingerprintDiffers_noRecord_updateAvailable() {
        val decision = WanxiangUpdateManager.resolveGramUpdate(
            recordedPublishedAt = null,
            localFingerprint = OTHER_SHA,
            remotePublishedAt = REMOTE_PUB,
            remoteSha256 = SHA,
        )
        assertDecision(decision, effective = null, backfill = false, update = true)
    }

    @Test
    fun gram_fingerprintDiffers_recordCurrent_updateAvailable() {
        // 上游原地换包的实测形态：published_at 与记录相同、只有摘要变。
        // 旧的纯时间判定会判无更新（真更新永不下发），哈希判定必须判有
        val decision = WanxiangUpdateManager.resolveGramUpdate(
            recordedPublishedAt = REMOTE_PUB,
            localFingerprint = OTHER_SHA,
            remotePublishedAt = REMOTE_PUB,
            remoteSha256 = SHA,
        )
        assertDecision(decision, effective = REMOTE_PUB, backfill = false, update = true)
    }

    @Test
    fun gram_fingerprintDiffers_staleRecord_updateAvailable() {
        val decision = WanxiangUpdateManager.resolveGramUpdate(
            recordedPublishedAt = OLD_PUB,
            localFingerprint = OTHER_SHA,
            remotePublishedAt = REMOTE_PUB,
            remoteSha256 = SHA,
        )
        assertDecision(decision, effective = OLD_PUB, backfill = false, update = true)
    }

    @Test
    fun gram_staleRecordButContentMatches_backfilledToRemote() {
        val decision = WanxiangUpdateManager.resolveGramUpdate(
            recordedPublishedAt = OLD_PUB,
            localFingerprint = SHA,
            remotePublishedAt = REMOTE_PUB,
            remoteSha256 = SHA,
        )
        assertDecision(decision, effective = REMOTE_PUB, backfill = true, update = false)
    }

    @Test
    fun gram_recordAlreadyCurrent_noBackfill() {
        val decision = WanxiangUpdateManager.resolveGramUpdate(
            recordedPublishedAt = REMOTE_PUB,
            localFingerprint = SHA,
            remotePublishedAt = REMOTE_PUB,
            remoteSha256 = SHA,
        )
        assertDecision(decision, effective = REMOTE_PUB, backfill = false, update = false)
    }

    // ---- 语法模型：哈希缺失回落记录语义 ----

    @Test
    fun gram_noRemoteDigest_noRecord_updateAvailable() {
        val decision = WanxiangUpdateManager.resolveGramUpdate(
            recordedPublishedAt = null,
            localFingerprint = SHA,
            remotePublishedAt = REMOTE_PUB,
            remoteSha256 = "",
        )
        assertDecision(decision, effective = null, backfill = false, update = true)
    }

    @Test
    fun gram_noRemoteDigest_staleRecord_updateAvailable() {
        val decision = WanxiangUpdateManager.resolveGramUpdate(
            recordedPublishedAt = OLD_PUB,
            localFingerprint = SHA,
            remotePublishedAt = REMOTE_PUB,
            remoteSha256 = "",
        )
        assertDecision(decision, effective = OLD_PUB, backfill = false, update = true)
    }

    @Test
    fun gram_noRemoteDigest_recordCurrent_noUpdate() {
        val decision = WanxiangUpdateManager.resolveGramUpdate(
            recordedPublishedAt = REMOTE_PUB,
            localFingerprint = SHA,
            remotePublishedAt = REMOTE_PUB,
            remoteSha256 = "",
        )
        assertDecision(decision, effective = REMOTE_PUB, backfill = false, update = false)
    }

    @Test
    fun gram_noLocalFile_noRecord_updateAvailable() {
        val decision = WanxiangUpdateManager.resolveGramUpdate(
            recordedPublishedAt = null,
            localFingerprint = null,
            remotePublishedAt = REMOTE_PUB,
            remoteSha256 = SHA,
        )
        assertDecision(decision, effective = null, backfill = false, update = true)
    }

    @Test
    fun gram_noLocalFile_staleRecord_updateAvailable() {
        val decision = WanxiangUpdateManager.resolveGramUpdate(
            recordedPublishedAt = OLD_PUB,
            localFingerprint = null,
            remotePublishedAt = REMOTE_PUB,
            remoteSha256 = SHA,
        )
        assertDecision(decision, effective = OLD_PUB, backfill = false, update = true)
    }

    @Test
    fun gram_noLocalFile_recordCurrent_noUpdate() {
        val decision = WanxiangUpdateManager.resolveGramUpdate(
            recordedPublishedAt = REMOTE_PUB,
            localFingerprint = null,
            remotePublishedAt = REMOTE_PUB,
            remoteSha256 = SHA,
        )
        assertDecision(decision, effective = REMOTE_PUB, backfill = false, update = false)
    }

    @Test
    fun gram_bothHashesMissing_recordCurrent_noUpdate() {
        val decision = WanxiangUpdateManager.resolveGramUpdate(
            recordedPublishedAt = REMOTE_PUB,
            localFingerprint = "",
            remotePublishedAt = REMOTE_PUB,
            remoteSha256 = "",
        )
        assertDecision(decision, effective = REMOTE_PUB, backfill = false, update = false)
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

    // ---- 整包下载 client 不得带总时长上限（4.1 守护） ----

    @Test
    fun downloadClient_hasNoCallTimeout() {
        // callTimeout 是整条调用（含读完响应体）的总时长上限：整包
        // 下载走带 20 秒封顶的检查用 client 时，大文件必然在读流
        // 中途被掐断（第十一批回归）。下载 client 必须为 0（无上限）
        assertEquals(0, WanxiangUpdateManager.downloadClient.callTimeoutMillis)
    }

    // ---- 版本切换清理清单（data M1） ----

    @Test
    fun housekeeping_clearsSchemaVersionRecord_keepsGramPublishedAt() {
        val keys = WanxiangUpdateManager.VERSION_HOUSEKEEPING_CLEARED_KEYS
        // 方案版本记录必须清：升级重解压内置包后旧记录会让本地版本
        // 脱离盘上内容、误判「已是最新」
        assertTrue(keys.contains(WanxiangUpdateManager.KEY_SCHEMA_VERSION))
        // 语法模型发布时间记录不清：gram 文件不被内置包覆盖，记录
        // 与文件同在自洽
        assertFalse(keys.contains(WanxiangUpdateManager.KEY_GRAM_PUBLISHED_AT))
    }

    // ---- 远端内容核实口径（V7b） ----

    @Test
    fun gramContentVerified_onlyWithRegisteredDigest() {
        // 摘要拿到才算核实；摘要接口失败得空串时模型侧结论只是
        // 记录兜底，界面不得宣称「已是最新」
        assertTrue(info(gramSha256 = SHA).gramContentVerified)
        assertFalse(info(gramSha256 = "").gramContentVerified)
    }

    private companion object {
        const val REMOTE_PUB = "2026-10-09T00:00:00Z"
        const val OLD_PUB = "2026-01-01T00:00:00Z"
        const val SHA =
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
        const val OTHER_SHA =
            "fedcba9876543210fedcba9876543210fedcba9876543210fedcba9876543210"
    }
}
