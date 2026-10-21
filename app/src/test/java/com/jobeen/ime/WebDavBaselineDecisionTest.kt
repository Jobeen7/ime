package com.jobeen.ime

import com.jobeen.ime.engine.rime.data.userdict.mergedStateMatchesCurrent
import com.jobeen.ime.engine.rime.data.userdict.remoteChangedDecision
import org.junit.Assert.assertFalse
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
    fun f1_noETagServer_noTimestampMaterial_treatedAsMatch() {
        // GET 未带 Last-Modified：没有可信材料可比，按一致处理，
        // 不因校验材料不足让无 ETag 服务器上的同步永远失败
        assertTrue(
            mergedStateMatchesCurrent(
                mergedETag = null,
                mergedLastModifiedMs = 0L,
                currentETag = null,
                currentLastModifiedMs = 2_000L,
            ),
        )
    }
}
