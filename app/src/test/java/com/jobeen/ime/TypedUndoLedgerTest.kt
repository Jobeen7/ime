package com.jobeen.ime

import com.jobeen.ime.input.TypedUndoLedger
import com.jobeen.ime.input.TypedUndoLedger.UndoFallback
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 打字上屏段撤销账本的纯判定测试（与 VoiceUndoLedgerTest 同口径：
 * 只测纯函数，不碰 Android 类）。
 *
 * 核心是下滑撤销落点真值表：污染会话内两账本都不命中时必须空
 * 操作（NOOP），绝不能回落 Ctrl+Z——那正是内嵌组字清除后被编
 * 辑器撤销栈以已上屏文本复活的路径；未污染会话保持正式版原
 * 样（SEND_CTRL_Z）。
 */
class TypedUndoLedgerTest {

    // ---- decideTypedUndoDelete：单段后缀判定 ----

    @Test
    fun typedUndo_suffixHitReturnsEntryLength() {
        assertEquals(2, TypedUndoLedger.decideTypedUndoDelete("前面一句话你好", "你好"))
    }

    @Test
    fun typedUndo_exactWholeTextHits() {
        assertEquals(4, TypedUndoLedger.decideTypedUndoDelete("你好明天", "你好明天"))
    }

    @Test
    fun typedUndo_suffixMismatchReturnsZero() {
        assertEquals(0, TypedUndoLedger.decideTypedUndoDelete("你好呀", "你好"))
    }

    @Test
    fun typedUndo_entryOnlyInMiddleReturnsZero() {
        assertEquals(0, TypedUndoLedger.decideTypedUndoDelete("你好吗", "你好"))
    }

    @Test
    fun typedUndo_nullBeforeReturnsZero() {
        assertEquals(0, TypedUndoLedger.decideTypedUndoDelete(null, "你好"))
    }

    @Test
    fun typedUndo_emptyEntryReturnsZero() {
        assertEquals(0, TypedUndoLedger.decideTypedUndoDelete("你好", ""))
        assertEquals(0, TypedUndoLedger.decideTypedUndoDelete("", ""))
    }

    @Test
    fun typedUndo_beforeShorterThanEntryReturnsZero() {
        assertEquals(0, TypedUndoLedger.decideTypedUndoDelete("好", "你好"))
    }

    @Test
    fun typedUndo_surrogatePairCountsUtf16Units() {
        assertEquals(2, TypedUndoLedger.decideTypedUndoDelete("看😀", "😀"))
    }

    // ---- decideUndoFallback：下滑撤销落点真值表 ----

    @Test
    fun fallback_voiceHitAlwaysDone() {
        assertEquals(UndoFallback.DONE, TypedUndoLedger.decideUndoFallback(true, false, false))
        assertEquals(UndoFallback.DONE, TypedUndoLedger.decideUndoFallback(true, false, true))
        assertEquals(UndoFallback.DONE, TypedUndoLedger.decideUndoFallback(true, true, false))
        assertEquals(UndoFallback.DONE, TypedUndoLedger.decideUndoFallback(true, true, true))
    }

    @Test
    fun fallback_typedHitAlwaysDone() {
        assertEquals(UndoFallback.DONE, TypedUndoLedger.decideUndoFallback(false, true, false))
        assertEquals(UndoFallback.DONE, TypedUndoLedger.decideUndoFallback(false, true, true))
    }

    @Test
    fun fallback_poisonedWithoutHitIsNoop() {
        // 关键行：污染会话内两账本都不命中时空操作，绝不发 Ctrl+Z
        assertEquals(UndoFallback.NOOP, TypedUndoLedger.decideUndoFallback(false, false, true))
    }

    @Test
    fun fallback_unpoisonedWithoutHitSendsCtrlZ() {
        // 未污染会话保持正式版原样
        assertEquals(UndoFallback.SEND_CTRL_Z, TypedUndoLedger.decideUndoFallback(false, false, false))
    }

    // ---- decideTypedRedoApply：恢复前的前文核对 ----

    @Test
    fun typedRedo_shortFieldExactPrefix_applies() {
        assertEquals(true, TypedUndoLedger.decideTypedRedoApply("你好", "你好", 3))
    }

    @Test
    fun typedRedo_typedAfterUndo_refuses() {
        assertEquals(false, TypedUndoLedger.decideTypedRedoApply("你好呀", "你好", 3))
    }

    @Test
    fun typedRedo_emptyPrefixRequiresEmptyWindow() {
        assertEquals(true, TypedUndoLedger.decideTypedRedoApply("", "", 3))
        assertEquals(false, TypedUndoLedger.decideTypedRedoApply("别的内容", "", 4))
    }

    @Test
    fun typedRedo_fullAnchor_applies() {
        val anchor = "一二三四五六七八九十甲乙丙丁戊己"
        assertEquals(true, TypedUndoLedger.decideTypedRedoApply("前文" + anchor, anchor, 17))
    }

    @Test
    fun typedRedo_windowEditedTail_refuses() {
        val anchor = "一二三四五六七八九十甲乙丙丁戊己"
        assertEquals(false, TypedUndoLedger.decideTypedRedoApply("一二三四五六七八九十甲乙丙丁戊改", anchor, 3))
    }

    @Test
    fun typedRedo_nullBefore_refuses() {
        assertEquals(false, TypedUndoLedger.decideTypedRedoApply(null, "你好", 3))
    }
}
