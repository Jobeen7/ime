package com.jobeen.ime

import com.jobeen.ime.engine.rime.data.userdict.formatHttpDate
import com.jobeen.ime.engine.rime.data.userdict.mergedStateMatchesCurrent
import com.jobeen.ime.engine.rime.data.userdict.postPutRecheckAdoptable
import com.jobeen.ime.engine.rime.data.userdict.putPrecondition
import com.jobeen.ime.engine.rime.data.userdict.remoteChangedDecision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WebDAV 同步基准决策的回归测试（第十二批 F1/F2/F3）。
 *
 * 覆盖层级：决策纯函数。F1 的「合并窗口」与 F3 的「无 ETag 降级」
 * 本质都是决策输入/口径问题，在这一层完整可判；F2 的修复本体是
 * downloadLocked 里基准提交点的后移（网络/Android 控制流，无桩
 * 不可直测），这里覆盖它的决策级后果：基准未被失败下载推进时，
 * 下一次上传面对未合并版本必须判「已变、先合并」。
 * 基准入参一律是 per-file 口径——其他词典的同步历史根本不作为
 * 入参出现，这正是 F3 的修复形态。
 */
class WebDavBaselineDecisionTest {

    // ---------- F3：无 ETag 时的 per-file 时间降级 ----------

    @Test
    fun f3_firstSyncOfDictB_noBaseline_mergesEvenIfRemoteIsOld() {
        // 词典 B 本机从未同步（它自己的 etag/时间基准全空），远端 B
        // 是他设备很早写下的。旧实现拿全局时间（其他词典的同步时
        // 刻）比较会判「未变」直接覆盖；新口径下 B 无基准 → 已变
        assertTrue(
            remoteChangedDecision(
                knownETag = null,
                stateETag = null,
                stateLastModifiedMs = 1_000L,
                lastUploadMs = 0L,
                lastDownloadMs = 0L,
            ),
        )
    }

    @Test
    fun f3_noETag_remoteNotNewerThanOwnBaseline_unchanged() {
        assertFalse(
            remoteChangedDecision(
                knownETag = null,
                stateETag = null,
                stateLastModifiedMs = 1_000L,
                lastUploadMs = 0L,
                lastDownloadMs = 2_000L,
            ),
        )
    }

    @Test
    fun f3_noETag_remoteNewerThanOwnBaseline_changed() {
        assertTrue(
            remoteChangedDecision(
                knownETag = null,
                stateETag = null,
                stateLastModifiedMs = 3_000L,
                lastUploadMs = 2_000L,
                lastDownloadMs = 1_000L,
            ),
        )
    }

    @Test
    fun f3_etagAppearsButKnownBaselineHasNone_changed() {
        assertTrue(
            remoteChangedDecision(
                knownETag = null,
                stateETag = "e1",
                stateLastModifiedMs = 0L,
                lastUploadMs = 9_999L,
                lastDownloadMs = 9_999L,
            ),
        )
    }

    @Test
    fun remoteChanged_etagEqual_unchanged_etagDiffers_changed() {
        assertFalse(
            remoteChangedDecision(
                knownETag = "e1",
                stateETag = "e1",
                stateLastModifiedMs = 0L,
                lastUploadMs = 0L,
                lastDownloadMs = 0L,
            ),
        )
        assertTrue(
            remoteChangedDecision(
                knownETag = "e1",
                stateETag = "e2",
                stateLastModifiedMs = 0L,
                lastUploadMs = 0L,
                lastDownloadMs = 0L,
            ),
        )
    }

    @Test
    fun remoteChanged_etagVanishedFromRemote_changed() {
        assertTrue(
            remoteChangedDecision(
                knownETag = "e1",
                stateETag = null,
                stateLastModifiedMs = 0L,
                lastUploadMs = 9_999L,
                lastDownloadMs = 9_999L,
            ),
        )
    }

    // ---------- F2 的决策级后果：失败下载不得推进基准 ----------

    @Test
    fun f2_failedDownloadLeavesBaseline_nextUploadMergesFirst() {
        // 下载 e1 在导入阶段失败：基准仍停在上一个成功版本 e0。
        // 下次上传面对 e1 必须判已变（先 GET 合并），而不是把
        // e1 当作已合并基准跳过合并直接 PUT
        assertTrue(
            remoteChangedDecision(
                knownETag = "e0",
                stateETag = "e1",
                stateLastModifiedMs = 0L,
                lastUploadMs = 0L,
                lastDownloadMs = 0L,
            ),
        )
    }

    // ---------- F1：合并后二次校验，只认合并内容自身的验证器 ----------

    @Test
    fun f1_window_otherDeviceWroteAfterMerge_mismatchAborts() {
        // GET 合并的是 e1；GET→二次 PROPFIND 之间他设备写入 e2。
        // 校验必须判不一致 → 上传中止，绝不锚定 e2 覆盖
        assertFalse(
            mergedStateMatchesCurrent(
                mergedETag = "e1",
                mergedLastModifiedMs = 1_000L,
                currentETag = "e2",
                currentLastModifiedMs = 2_000L,
            ),
        )
    }

    @Test
    fun f1_remoteUntouchedSinceMerge_matches() {
        assertTrue(
            mergedStateMatchesCurrent(
                mergedETag = "e1",
                mergedLastModifiedMs = 1_000L,
                currentETag = "e1",
                currentLastModifiedMs = 1_000L,
            ),
        )
    }

    @Test
    fun f1_remoteLostETagAfterMerge_mismatch() {
        assertFalse(
            mergedStateMatchesCurrent(
                mergedETag = "e1",
                mergedLastModifiedMs = 1_000L,
                currentETag = null,
                currentLastModifiedMs = 1_000L,
            ),
        )
    }

    @Test
    fun f1_noETagServer_fallsBackToLastModifiedEquality() {
        assertTrue(
            mergedStateMatchesCurrent(
                mergedETag = null,
                mergedLastModifiedMs = 1_000L,
                currentETag = null,
                currentLastModifiedMs = 1_000L,
            ),
        )
        assertFalse(
            mergedStateMatchesCurrent(
                mergedETag = null,
                mergedLastModifiedMs = 1_000L,
                currentETag = null,
                currentLastModifiedMs = 2_000L,
            ),
        )
    }

    @Test
    fun r3_noValidatorsAtAll_cannotVerify_treatedAsMismatch() {
        // 第十三批 R3 改规格（原用例断言 true、名为 treatedAsMatch，
        // 已按新规格改判并更名）：双验证器皆无、或任一侧时间缺失时
        // 没有任何材料能证明期间未被改动，按「无法验证即已变」判
        // 不一致，上层据此中止/不采纳，而不是无条件放行裸覆盖
        assertFalse(
            mergedStateMatchesCurrent(
                mergedETag = null,
                mergedLastModifiedMs = 0L,
                currentETag = null,
                currentLastModifiedMs = 2_000L,
            ),
        )
        assertFalse(
            mergedStateMatchesCurrent(
                mergedETag = null,
                mergedLastModifiedMs = 0L,
                currentETag = null,
                currentLastModifiedMs = 0L,
            ),
        )
        assertFalse(
            mergedStateMatchesCurrent(
                mergedETag = null,
                mergedLastModifiedMs = 1_000L,
                currentETag = null,
                currentLastModifiedMs = 0L,
            ),
        )
    }

    // ---------- H1：夹逼只比 PROPFIND 与 PROPFIND，GET 头表示不进决策 ----------

    @Test
    fun h1_bracket_propfindStatesEqual_matches_regardlessOfGetHeaderRepresentation() {
        // Apache mod_deflate 会把 GET 响应 ETag 改写为 "abc-gzip"，
        // 与 PROPFIND 的 "abc" 永不相等——旧实现拿 GET 头与 PROPFIND
        // 全等比对，这类服务器上上传永久失败。新口径下 GET 头根本
        // 不进 mergedStateMatchesCurrent：夹逼比的是 GET 前后两次
        // PROPFIND 的状态（同口径），相等即一致、不相等即中止
        assertTrue(
            mergedStateMatchesCurrent(
                mergedETag = "abc",
                mergedLastModifiedMs = 1_000L,
                currentETag = "abc",
                currentLastModifiedMs = 1_000L,
            ),
        )
        assertFalse(
            mergedStateMatchesCurrent(
                mergedETag = "abc",
                mergedLastModifiedMs = 1_000L,
                currentETag = "abd",
                currentLastModifiedMs = 1_000L,
            ),
        )
    }

    // ---------- R2：PUT 无响应 ETag 时，补查基准的采纳判据 ----------

    @Test
    fun r2_recheck_otherDeviceWroteAfterPut_notAdopted() {
        // 他设备在本机 PUT 完成之后、补查之前写入新版本：补查修改
        // 时间晚于 PUT 完成时刻 → 证明不了是本机 PUT 的内容，不写
        // 基准，下次上传先合并，不会跳过合并覆盖它
        assertFalse(
            postPutRecheckAdoptable(
                anchorETag = "e3",
                anchorETagWeak = false,
                anchorLastModifiedMs = 1_000L,
                recheckETag = "e4",
                recheckLastModifiedMs = 5_000L,
                putFinishedAtMs = 4_000L,
            ),
        )
    }

    @Test
    fun r2_recheck_withinPutWindow_adopted() {
        // 补查修改时间落在「锚点之后、PUT 完成之前」：就是本次 PUT
        // 落下的内容，可采纳为新基准
        assertTrue(
            postPutRecheckAdoptable(
                anchorETag = "e3",
                anchorETagWeak = false,
                anchorLastModifiedMs = 1_000L,
                recheckETag = "e3next",
                recheckLastModifiedMs = 3_000L,
                putFinishedAtMs = 4_000L,
            ),
        )
    }

    @Test
    fun r2_recheck_sameETagAsAnchor_adopted_staleOrNoMaterial_notAdopted() {
        // 补查 ETag 与强锚点全等：远端仍是已合并的那一份
        assertTrue(
            postPutRecheckAdoptable(
                anchorETag = "e3",
                anchorETagWeak = false,
                anchorLastModifiedMs = 1_000L,
                recheckETag = "e3",
                recheckLastModifiedMs = 1_000L,
                putFinishedAtMs = 4_000L,
            ),
        )
        // 修改时间不晚于锚点且 ETag 已变：无法证明是本次 PUT
        assertFalse(
            postPutRecheckAdoptable(
                anchorETag = "e3",
                anchorETagWeak = false,
                anchorLastModifiedMs = 2_000L,
                recheckETag = "eX",
                recheckLastModifiedMs = 2_000L,
                putFinishedAtMs = 4_000L,
            ),
        )
        // 无 ETag / 无时间：无从证明，一律不采纳
        assertFalse(
            postPutRecheckAdoptable(
                anchorETag = "e3",
                anchorETagWeak = false,
                anchorLastModifiedMs = 1_000L,
                recheckETag = null,
                recheckLastModifiedMs = 3_000L,
                putFinishedAtMs = 4_000L,
            ),
        )
        assertFalse(
            postPutRecheckAdoptable(
                anchorETag = null,
                anchorETagWeak = false,
                anchorLastModifiedMs = 0L,
                recheckETag = "e1",
                recheckLastModifiedMs = 0L,
                putFinishedAtMs = 4_000L,
            ),
        )
    }

    // ---------- R3：无 ETag 有时间时，前置条件降级为时间条件 ----------

    @Test
    fun r3_precondition_noETagWithTimestamp_usesIfUnmodifiedSince() {
        val lm = 1_700_000_000_000L
        val pre = putPrecondition(null, etagWeak = false, lastModifiedMs = lm)
        assertNull(pre.ifMatch)
        assertEquals(formatHttpDate(lm), pre.ifUnmodifiedSince)
        // 与弱 ETag 分支同口径（同一时间入参得同一结果）
        assertEquals(
            putPrecondition("abc123", etagWeak = true, lastModifiedMs = lm),
            pre,
        )
        // 双无才裸 PUT
        val bare = putPrecondition(null, etagWeak = false, lastModifiedMs = 0L)
        assertNull(bare.ifMatch)
        assertNull(bare.ifUnmodifiedSince)
    }
}
