package com.jobeen.ime

import com.jobeen.ime.base.speech.VoiceUndoLedger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 语音段撤销账本的纯判定测试：只有光标前文本以账本条目整段结尾
 * 才返回应删长度，任何偏差都返回 0（不接管、回落 Ctrl+Z）。
 * 长度口径是 UTF-16 单元数，与 deleteSurroundingText 一致。
 */
class VoiceUndoLedgerTest {

    @Test
    fun suffixHitReturnsEntryLength() {
        assertEquals(2, VoiceUndoLedger.decideUndoDelete("前面一句话你好", "你好"))
    }

    @Test
    fun exactWholeTextHits() {
        assertEquals(4, VoiceUndoLedger.decideUndoDelete("语音输入", "语音输入"))
    }

    @Test
    fun suffixMismatchReturnsZero() {
        assertEquals(0, VoiceUndoLedger.decideUndoDelete("你好呀", "你好"))
    }

    @Test
    fun entryOnlyInMiddleReturnsZero() {
        // 条目出现但不在结尾（其后还有字）不算命中
        assertEquals(0, VoiceUndoLedger.decideUndoDelete("你好吗", "你好"))
    }

    @Test
    fun nullBeforeReturnsZero() {
        assertEquals(0, VoiceUndoLedger.decideUndoDelete(null, "你好"))
    }

    @Test
    fun emptyEntryReturnsZero() {
        assertEquals(0, VoiceUndoLedger.decideUndoDelete("你好", ""))
        assertEquals(0, VoiceUndoLedger.decideUndoDelete("", ""))
    }

    @Test
    fun beforeShorterThanEntryReturnsZero() {
        assertEquals(0, VoiceUndoLedger.decideUndoDelete("好", "你好"))
        assertEquals(0, VoiceUndoLedger.decideUndoDelete("", "你好"))
    }

    @Test
    fun surrogatePairCountsUtf16Units() {
        // 表情是 1 个码点 = 2 个 UTF-16 单元，删除长度必须按 2 算
        assertEquals(2, VoiceUndoLedger.decideUndoDelete("看😀", "😀"))
    }

    // ---- decideUndoDeleteMulti：一次删完连续多段 ----

    @Test
    fun multi_twoSegmentsConcatenated_matchBoth() {
        // 编辑器里是 旧段＋新段 相邻，条目最新在前传入
        val span = VoiceUndoLedger.decideUndoDeleteMulti(
            "我昨天跟他讲了。他今天也休息。", listOf("他今天也休息。", "我昨天跟他讲了。")
        )
        assertEquals(2 to 15, span)
    }

    @Test
    fun multi_onlyNewestMatches_stopsAtMismatch() {
        // 新段吻合、旧段前被改过（拼接在旧段处断开）：只删新段
        val span = VoiceUndoLedger.decideUndoDeleteMulti(
            "被改过的旧文。他今天也休息。", listOf("他今天也休息。", "我昨天跟他讲了。")
        )
        assertEquals(1 to 7, span)
    }

    @Test
    fun multi_newestMismatch_returnsNull() {
        val span = VoiceUndoLedger.decideUndoDeleteMulti(
            "光标前是别的内容", listOf("他今天也休息。", "我昨天跟他讲了。")
        )
        assertNull(span)
    }

    @Test
    fun multi_threeSegments_matchAll() {
        val span = VoiceUndoLedger.decideUndoDeleteMulti(
            "前缀无关。一。二。三。", listOf("三。", "二。", "一。")
        )
        assertEquals(3 to 6, span)
    }

    @Test
    fun multi_typedTextBetweenSegments_breaksChain() {
        // 两段之间夹了手打字：拼接串与原文不符，旧段不连带
        val span = VoiceUndoLedger.decideUndoDeleteMulti(
            "一。手动二。", listOf("二。", "一。")
        )
        assertEquals(1 to 2, span)
    }

    @Test
    fun multi_nullOrEmpty_returnsNull() {
        assertNull(VoiceUndoLedger.decideUndoDeleteMulti(null, listOf("一。")))
        assertNull(VoiceUndoLedger.decideUndoDeleteMulti("一。", emptyList()))
    }

    // ---- decideRedoApply：恢复前的前文核对 ----

    @Test
    fun redo_shortFieldExactPrefix_applies() {
        // 字段里只有锚点前文（删除后原样），窗口恰等于 prefix
        assertEquals(true, VoiceUndoLedger.decideRedoApply("你好", "你好", 3))
    }

    @Test
    fun redo_longFieldWindowEndsWithPrefix_applies() {
        // 长锚点（锚点长 ≥ 被删串长）：更早的内容在锚点之前，窗口
        // 落在 [锚点长, 锚点长＋被删串长] 区间内且以锚点结尾
        assertEquals(true, VoiceUndoLedger.decideRedoApply("更早的一二三四五", "一二三四五", 3))
    }

    @Test
    fun redo_typedAfterUndo_refuses() {
        // 撤销后又打了字：窗口尾部不再是 prefix
        assertEquals(false, VoiceUndoLedger.decideRedoApply("你好呀", "你好", 3))
    }

    @Test
    fun redo_deletedIntoPrefix_refuses() {
        // 撤销后又删了前文的字：窗口与 prefix 不符
        assertEquals(false, VoiceUndoLedger.decideRedoApply("你", "你好", 3))
    }

    @Test
    fun redo_emptyPrefixRequiresEmptyWindow() {
        // 语音段原本就是字段全部内容：只认光标前全空
        assertEquals(true, VoiceUndoLedger.decideRedoApply("", "", 3))
        assertEquals(false, VoiceUndoLedger.decideRedoApply("别的内容", "", 4))
    }

    @Test
    fun redo_nullBefore_refuses() {
        assertEquals(false, VoiceUndoLedger.decideRedoApply(null, "你好", 3))
    }

    // ---- decideRedoApply 区域矩阵：统一后缀判定（锚点只存尾 16 字） ----

    @Test
    fun redo_partialWindowInBlindZone_applies() {
        // 盲区形态：撤销窗口之外还残留 2 字前文（窗口未读满、也
        // 不恰等于锚点），未做任何编辑。此前只认两端点会误拒
        val anchor = "一二三四五六七八九十甲乙丙丁戊己"
        assertEquals(16, anchor.length)
        // 前文共 18 字（窗口外 2 字＋锚点 16 字），被删串长 3：
        // 现读 16+3=19 只能读回 18，落在 （锚点长， 锚点长＋被删长） 之间
        assertEquals(true, VoiceUndoLedger.decideRedoApply("更早" + anchor, anchor, 3))
    }

    @Test
    fun redo_windowShorterThanAnchor_refuses() {
        // 窗口比锚点还短：锚点已被删改动过，不接管
        assertEquals(false, VoiceUndoLedger.decideRedoApply("你好", "你们好", 3))
    }

    @Test
    fun redo_windowBeyondCombinedLength_refuses() {
        // 窗口长于「锚点＋被删串」：删除点之后多出内容（形态不合）
        assertEquals(false, VoiceUndoLedger.decideRedoApply("更早的内容你好世界", "你好", 3))
    }

    @Test
    fun redo_typedInsideAnchorTail_refuses() {
        // 锚点尾部被改：后缀不再以完整锚点结尾
        val anchor = "一二三四五六七八九十甲乙丙丁戊己"
        assertEquals(false, VoiceUndoLedger.decideRedoApply("一二三四五六七八九十甲乙丙丁戊改", anchor, 3))
    }

    // ---- buildRedoPrefix：锚点只存尾 16 字 ----

    @Test
    fun buildRedoPrefix_longPreamble_truncatesToAnchor() {
        // 窗口 = 18 字前文＋3 字被删串：锚点只取前文尾 16 字
        val window = "更早一二三四五六七八九十甲乙丙丁戊己被删串"
        assertEquals(
            "一二三四五六七八九十甲乙丙丁戊己",
            VoiceUndoLedger.buildRedoPrefix(window, 3),
        )
    }

    @Test
    fun buildRedoPrefix_shortPreamble_keepsAll() {
        // 前文不足 16 字时全量保留（短字段恢复靠恰等判定）
        assertEquals("你好", VoiceUndoLedger.buildRedoPrefix("你好被删串", 3))
    }

    @Test
    fun buildRedoPrefix_segmentAtFieldStart_emptyAnchor() {
        // 被删串就是字段全部内容：锚点为空
        assertEquals("", VoiceUndoLedger.buildRedoPrefix("被删串", 3))
    }

    // ---- decideRedoApply 短锚点严判（锚点长 < 被删串长） ----

    @Test
    fun redo_shortAnchorExactWindow_applies() {
        // 短锚点：窗口恰为锚点全长（删除点前文完整读回、未被动过）
        assertEquals(true, VoiceUndoLedger.decideRedoApply("你好", "你好", 5))
    }

    @Test
    fun redo_shortAnchorWindowWithinOldInterval_refuses() {
        // 旧区间判定会放行（窗口 7 落在 [2, 2+5]、后缀吻合），短锚点
        // 严判拒绝：这正是光标移走后写错位置的形态
        assertEquals(false, VoiceUndoLedger.decideRedoApply("更早的内容你好", "你好", 5))
    }

    @Test
    fun redo_shortAnchorWindowOneCharBeyond_refuses() {
        // 窗口只比锚点多 1 字（删除后在删除点后打了一个字又删掉
        // 之类无法区分的状态一律从严）：不恰等即拒绝
        assertEquals(false, VoiceUndoLedger.decideRedoApply("好你好", "你好", 5))
    }

    @Test
    fun redo_shortAnchorSuffixMismatch_refuses() {
        // 窗口恰为锚点长但内容不是锚点
        assertEquals(false, VoiceUndoLedger.decideRedoApply("您好", "你好", 5))
    }

    @Test
    fun redo_anchorEqualsCombinedLength_usesIntervalRule() {
        // 锚点长 == 被删串长属长锚点：窗口在区间内且后缀吻合即接管，
        // 不受短锚点恰等严判影响
        assertEquals(true, VoiceUndoLedger.decideRedoApply("更早一二三", "一二三", 3))
        assertEquals(true, VoiceUndoLedger.decideRedoApply("一二三", "一二三", 3))
    }
}
