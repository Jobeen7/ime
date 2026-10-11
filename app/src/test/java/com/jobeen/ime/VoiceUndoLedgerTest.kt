package com.jobeen.ime

import com.jobeen.ime.base.speech.VoiceUndoLedger
import org.junit.Assert.assertEquals
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
}
