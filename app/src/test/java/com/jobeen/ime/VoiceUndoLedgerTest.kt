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
}
