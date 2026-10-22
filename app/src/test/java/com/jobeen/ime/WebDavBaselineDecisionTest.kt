package com.jobeen.ime

import com.jobeen.ime.engine.rime.data.userdict.formatHttpDate
import com.jobeen.ime.engine.rime.data.userdict.mergedStateMatchesCurrent
import com.jobeen.ime.engine.rime.data.userdict.isUnprotectedOverwrite
import com.jobeen.ime.engine.rime.data.userdict.postPutRecheckAdoptable
import com.jobeen.ime.engine.rime.data.userdict.putPrecondition
import com.jobeen.ime.engine.rime.data.userdict.remoteChangedDecision
import com.jobeen.ime.engine.rime.data.userdict.unprotectedOverwriteMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WebDAV 同步基准决策的回归测试（第十二批 F1/F2/F3）。
 *
 * 覆盖层级：决策纯函数。F1 的「合并窗口」与 F3 的「无 ETag 口径」
 * 本质都是决策输入/口径问题，在这一层完整可判；F2 的修复本体是
 * downloadLocked 里基准提交点的后移（网络/Android 控制流，无桩
 * 不可直测），这里覆盖它的决策级后果：基准未被失败下载推进时，
 * 下一次上传面对未合并版本必须判「已变、先合并」。
 * 基准入参一律是 per-file 口径——其他词典的同步历史根本不作为
 * 入参出现，这正是 F3 的修复形态。
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
        // 双无时纯函数层面前置条件为空（是否中止由调用方按
        // isUnprotectedOverwrite 判，见下方 R2 用例）
        val bare = putPrecondition(null, etagWeak = false, lastModifiedMs = 0L)
        assertNull(bare.ifMatch)
        assertNull(bare.ifUnmodifiedSince)
    }

    // ---------- R2（.17 复核）＋O1（第十六批）：无 If-Match 的前置条件不得覆盖已存在远端文件 ----------

    @Test
    fun r2_weakETagNoDate_remoteExists_abort() {
        // 弱 ETag 且无 Last-Modified：putPrecondition 双空，远端
        // 文件已存在 → 无条件覆盖风险成立，调用方须中止上传
        val pre = putPrecondition("e1", etagWeak = true, lastModifiedMs = 0L)
        assertNull(pre.ifMatch)
        assertNull(pre.ifUnmodifiedSince)
        assertTrue(isUnprotectedOverwrite(pre, remoteExists = true))
    }

    @Test
    fun r2_doubleNull_remoteMissing_allowed() {
        // 远端文件不存在（首次新建）：没有可被覆盖的既有内容，
        // 双空放行，新建另有 If-None-Match 限定
        val pre = putPrecondition("e1", etagWeak = true, lastModifiedMs = 0L)
        assertFalse(isUnprotectedOverwrite(pre, remoteExists = false))
    }

    @Test
    fun o1_weakETagWithDate_dateOnlyPrecondition_abortsOnExistingRemote() {
        // 第十六批 O1 改规格（原用例名为 …stillSendsIfUnmodifiedSince、
        // 末段断言「不触发中止」）：putPrecondition 的构造不变——弱
        // ETag＋有修改时间仍产出 If-Unmodified-Since；但日期只有秒
        // 精度，同秒并发写对它不可见，覆盖已存在文件时不再算并发
        // 保护 → 守卫判无保护，调用方中止上传
        val lm = 1_700_000_000_000L
        val pre = putPrecondition("e1", etagWeak = true, lastModifiedMs = lm)
        assertNull(pre.ifMatch)
        assertEquals(formatHttpDate(lm), pre.ifUnmodifiedSince)
        assertTrue(isUnprotectedOverwrite(pre, remoteExists = true))
        // 强 ETag 的 If-Match 是唯一受保护形态，不触发
        val strong = putPrecondition("e1", etagWeak = false, lastModifiedMs = 0L)
        assertFalse(isUnprotectedOverwrite(strong, remoteExists = true))
    }

    @Test
    fun o1_noETagWithDate_abortsOnExistingRemote() {
        // 无 ETag＋有修改时间：与弱 ETag 同形——只有日期条件，
        // 覆盖已存在文件视为无并发保护
        val lm = 1_700_000_000_000L
        val pre = putPrecondition(null, etagWeak = false, lastModifiedMs = lm)
        assertNull(pre.ifMatch)
        assertEquals(formatHttpDate(lm), pre.ifUnmodifiedSince)
        assertTrue(isUnprotectedOverwrite(pre, remoteExists = true))
    }

    @Test
    fun o1_dateOnlyPrecondition_remoteMissing_allowed() {
        // 远端文件不存在（首次新建）：没有可被覆盖的既有内容，
        // 日期-only 前置条件不触发中止，新建另有 If-None-Match 限定
        val lm = 1_700_000_000_000L
        val pre = putPrecondition(null, etagWeak = false, lastModifiedMs = lm)
        assertFalse(isUnprotectedOverwrite(pre, remoteExists = false))
    }

    @Test
    fun o1_unprotectedAbort_messages_pinnedVerbatim() {
        // 中止话术两形态全文钉住：日期-only 点明同秒不可见，
        // 双空沿用第十四批 R2 原文
        val lm = 1_700_000_000_000L
        assertEquals(
            "该服务器只能提供秒精度的修改时间校验（无可用的 ETag 强校验），" +
                "同一秒内其他设备刚写入的内容无法被察觉，继续上传可能将其覆盖，本次上传已中止",
            unprotectedOverwriteMessage(
                putPrecondition(null, etagWeak = false, lastModifiedMs = lm),
            ),
        )
        assertEquals(
            "该服务器无法提供并发保护（无可用的 ETag / 修改时间校验），" +
                "继续上传可能覆盖其他设备刚写入的内容，本次上传已中止",
            unprotectedOverwriteMessage(
                putPrecondition(null, etagWeak = false, lastModifiedMs = 0L),
            ),
        )
    }

    // ---------- 删词同步归一（第十四批回修）：与词典上传同一守卫 ----------

    @Test
    fun dw_weakETagNoDate_remoteExists_abort() {
        // 删词链与 R2 同形：弱 ETag＋无修改时间，GET 已证明远端
        // 删词文件存在 → putPrecondition 双空、无条件覆盖风险成立，
        // syncDeletedWords 须按同一判据中止本次同步
        val pre = putPrecondition("e1", etagWeak = true, lastModifiedMs = 0L)
        assertNull(pre.ifMatch)
        assertNull(pre.ifUnmodifiedSince)
        assertTrue(isUnprotectedOverwrite(pre, remoteExists = true))
    }

    @Test
    fun dw_noETagNoDate_remoteExists_abort() {
        // 旧删词链该分支根本不算前置条件、无条件裸 PUT；归一后
        // 双空＋文件已存在同样中止
        val pre = putPrecondition(null, etagWeak = false, lastModifiedMs = 0L)
        assertTrue(isUnprotectedOverwrite(pre, remoteExists = true))
    }

    @Test
    fun o1_dw_noETagWithDate_dateOnlyPrecondition_aborts() {
        // 第十六批 O1 对齐（原用例名为 …usesIfUnmodifiedSince、末段
        // 断言「不触发中止」）：构造不变——无 ETag＋有修改时间走
        // putPrecondition 产出 If-Unmodified-Since（旧分支连可用的
        // 时间条件也不发）；但删词链与词典上传同一守卫，日期-only
        // 覆盖已存在的删词文件同样视为无并发保护 → 中止
        val lm = 1_700_000_000_000L
        val pre = putPrecondition(null, etagWeak = false, lastModifiedMs = lm)
        assertNull(pre.ifMatch)
        assertEquals(formatHttpDate(lm), pre.ifUnmodifiedSince)
        assertTrue(isUnprotectedOverwrite(pre, remoteExists = true))
    }

    @Test
    fun o1_dw_weakETagWithDate_aborts() {
        // 删词链弱 ETag＋有修改时间：同一守卫，覆盖已存在文件
        // 时日期-only 不算保护
        val lm = 1_700_000_000_000L
        val pre = putPrecondition("e1", etagWeak = true, lastModifiedMs = lm)
        assertTrue(isUnprotectedOverwrite(pre, remoteExists = true))
    }

    @Test
    fun dw_remoteMissing_doubleNull_allowed() {
        // 删词文件远端不存在（首次同步）：没有可被覆盖的既有
        // 内容，双空放行，新建另有 If-None-Match 限定
        val pre = putPrecondition(null, etagWeak = false, lastModifiedMs = 0L)
        assertFalse(isUnprotectedOverwrite(pre, remoteExists = false))
    }
}
