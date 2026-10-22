package com.jobeen.ime

import com.jobeen.ime.engine.rime.data.userdict.formatHttpDate
import com.jobeen.ime.engine.rime.data.userdict.postPutRecheckAdoptable
import com.jobeen.ime.engine.rime.data.userdict.putPrecondition
import com.jobeen.ime.engine.rime.data.userdict.remoteChangedDecision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WebDAV 同步基准决策的回归测试（第十二批 F2/F3）。
 *
 * 覆盖层级：决策纯函数。F3 的「无 ETag 口径」本质是决策输入/口
 * 径问题，在这一层完整可判；F2 的修复本体是 downloadLocked 里
 * 基准提交点的后移（网络/Android 控制流，无桩不可直测），这
 * 里覆盖它的决策级后果：基准未被失败下载推进时，下一次上传面
 * 对未合并版本必须判「已变、先合并」。
 * 基准入参一律是 per-file 口径——其他词典的同步历史根本不作为
 * 入参出现，这正是 F3 的修复形态。
 * 第十八批：F1 的合并夹逼决策（mergedStateMatchesCurrent）已
 * 随夹逼本体删除（下载不再做一致性校验），其用例一并移除。
 * 第二十批：isUnprotectedOverwrite / unprotectedOverwriteMessage
 * 两个守卫纯函数随守卫本体一并删除（词典上传改无条件裸 PUT、
 * 删词链删除中止守卫），其用例一并移除，见下方删除说明。
 */
class WebDavBaselineDecisionTest {

    // ---------- F3：无 ETag 时只认 per-file ETag 基准，别词典的历史不入参 ----------

    @Test
    fun f3_firstSyncOfDictB_noETagBaseline_alwaysMerges() {
        // 词典 B 本机从未同步（它自己的 ETag 基准为空），远端 B 是
        // 他设备写下的。旧实现曾拿全局时间（其他词典的同步时刻）
        // 比较误判「未变」直接覆盖；per-file 化后别词典的历史根本
        // 不作为入参出现。第十六批（O2）后判据进一步收敛：两边无
        // ETag 即恒判已变、先合并，不再有任何时间入参
        assertTrue(
            remoteChangedDecision(knownETag = null, stateETag = null),
        )
    }

    @Test
    fun o2_noETag_localTimesNeverProveUnchanged() {
        // 主报告 O2 的重放形态：服务器全程不给 ETag。他设备在本机
        // PUT 落盘后、响应返回前同秒追加新词——远端修改时间（整
        // 秒）必然 ≤ 本机记录的上传完成时刻（真毫秒），旧时间支
        // 据此判「未变化」跳过 GET 合并，下轮 PUT 覆盖新词。新规
        // 格：本机时间不作内容身份，无 ETag 基准即恒判已变；旧签
        // 名下的 (state=1_000, up=0, down=2_000)「远端不新于本机基
        // 准」形态在此同样判已变
        assertTrue(
            remoteChangedDecision(knownETag = null, stateETag = null),
        )
    }

    @Test
    fun f3_etagAppearsButKnownBaselineHasNone_changed() {
        assertTrue(
            remoteChangedDecision(knownETag = null, stateETag = "e1"),
        )
    }

    @Test
    fun remoteChanged_etagEqual_unchanged_etagDiffers_changed() {
        assertFalse(
            remoteChangedDecision(knownETag = "e1", stateETag = "e1"),
        )
        assertTrue(
            remoteChangedDecision(knownETag = "e1", stateETag = "e2"),
        )
    }

    @Test
    fun remoteChanged_etagVanishedFromRemote_changed() {
        assertTrue(
            remoteChangedDecision(knownETag = "e1", stateETag = null),
        )
    }

    // ---------- F2 的决策级后果：失败下载不得推进基准 ----------

    @Test
    fun f2_failedDownloadLeavesBaseline_nextUploadMergesFirst() {
        // 下载 e1 在导入阶段失败：基准仍停在上一个成功版本 e0。
        // 下次上传面对 e1 必须判已变（先 GET 合并），而不是把
        // e1 当作已合并基准跳过合并直接 PUT
        assertTrue(
            remoteChangedDecision(knownETag = "e0", stateETag = "e1"),
        )
    }

    // ---------- F1 夹逼决策已删除（第十八批） ----------
    // mergedStateMatchesCurrent 及其用例（f1_window / f1_remoteUntouched /
    // f1_remoteLostETag / f1_noETagServer_timeEqualityCannotProveMatch /
    // r3_noValidatorsAtAll / h1_bracket）随下载夹逼本体一并删除：
    // 第十八批按 Jobeen 决定回退强校验语义，下载 GET＋合并成功即
    // 成功、不再有合并前后一致性判定点。GET 期间并发写的残余处
    // 理回到流程层：上传先合并后无条件裸 PUT 写回（第二十批）。

    // ---------- 补查基准采纳判据（.17 复核 R1：时间窗支已删除） ----------

    @Test
    fun r2_recheck_otherDeviceWroteAfterPut_notAdopted() {
        // 他设备在本机 PUT 完成之后、补查之前写入新版本：补查
        // ETag 已不是锚点那一份 → 证明不了是本机 PUT 的内容，
        // 不写基准，下次上传先合并，不会跳过合并覆盖它
        assertFalse(
            postPutRecheckAdoptable(
                anchorETag = "e3",
                anchorETagWeak = false,
                recheckETag = "e4",
            ),
        )
    }

    @Test
    fun r1_recheck_sameSecondConcurrentWrite_notAdopted() {
        // 同秒并发写形态（.17 复核 R1 的重放）：他设备在本机 PUT
        // 落盘后、响应返回前同秒追加新词，ETag 变 e4、修改时间取
        // 当前整秒——旧时间窗判据（锚点时间 < 补查整秒 ≤ PUT 完
        // 成时刻）会误采纳 e4 为基准，下次上传跳过合并合法覆盖
        // 丢词。新判据只认与强锚点全等，e4 ≠ e3 → 不采纳
        assertFalse(
            postPutRecheckAdoptable(
                anchorETag = "e3",
                anchorETagWeak = false,
                recheckETag = "e4",
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
                recheckETag = "e3",
            ),
        )
        // ETag 已变：无法证明是本次 PUT
        assertFalse(
            postPutRecheckAdoptable(
                anchorETag = "e3",
                anchorETagWeak = false,
                recheckETag = "eX",
            ),
        )
        // 无 ETag：无从证明，一律不采纳
        assertFalse(
            postPutRecheckAdoptable(
                anchorETag = "e3",
                anchorETagWeak = false,
                recheckETag = null,
            ),
        )
        // PUT 前无锚点（首次新建）：补查 ETag 无可比对的锚点，不采纳
        assertFalse(
            postPutRecheckAdoptable(
                anchorETag = null,
                anchorETagWeak = false,
                recheckETag = "e1",
            ),
        )
        // 锚点为弱 ETag：弱值不作强比较锚点，纵使同值也不采纳
        assertFalse(
            postPutRecheckAdoptable(
                anchorETag = "e3",
                anchorETagWeak = true,
                recheckETag = "e3",
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
        // 双无时纯函数层面前置条件为空（调用方裸发，见第二十批
        // 删除说明：已无任何「无保护即中止」的判据）
        val bare = putPrecondition(null, etagWeak = false, lastModifiedMs = 0L)
        assertNull(bare.ifMatch)
        assertNull(bare.ifUnmodifiedSince)
    }

    // ---------- R2/O1 守卫判据与删词链同守卫用例已删除（第二十批） ----------
    // isUnprotectedOverwrite / unprotectedOverwriteMessage 随守卫本
    // 体一并删除（生产调用方归零：词典上传改无条件裸 PUT、删词链
    // 删除中止守卫——见 fix20），原用例（r2_weakETagNoDate /
    // r2_doubleNull / o1_weakETagWithDate / o1_noETagWithDate /
    // o1_dateOnly_remoteMissing / o1_unprotectedAbort_messages /
    // dw_weakETagNoDate / dw_noETagNoDate / o1_dw_noETagWithDate /
    // o1_dw_weakETagWithDate / dw_remoteMissing_doubleNull）断言的
    // 均为这两函数的输出，函数既删、用例无落点。其中涉及
    // putPrecondition 构造形态的断言（弱/无 ETag＋时间 → 只有日期
    // 条件、强 ETag → If-Match、双无 → 双空）由上方 R3 用例与
    // SyncProtocolFixesTest 的 precondition_* 用例继续覆盖，
    // putPrecondition 本体与既有断言本批不动。
}
