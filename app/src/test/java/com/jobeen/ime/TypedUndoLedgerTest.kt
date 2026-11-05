package com.jobeen.ime

import com.jobeen.ime.input.TypedUndoLedger
import com.jobeen.ime.input.TypedUndoLedger.HostUndoFallback
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
        // 口径驱动的测试改动（本批）：decideUndoFallback 由 3 参
        // （voiceHit、typedHit、poisoned）改为 4 参，新增 clearedHit
        // （上滑清字账本命中）；以下断言按新签名重写，旧语义不变。
        assertEquals(UndoFallback.DONE, TypedUndoLedger.decideUndoFallback(true, false, false, false))
        assertEquals(UndoFallback.DONE, TypedUndoLedger.decideUndoFallback(true, false, false, true))
        assertEquals(UndoFallback.DONE, TypedUndoLedger.decideUndoFallback(true, true, false, false))
        assertEquals(UndoFallback.DONE, TypedUndoLedger.decideUndoFallback(true, true, false, true))
    }

    @Test
    fun fallback_typedHitAlwaysDone() {
        assertEquals(UndoFallback.DONE, TypedUndoLedger.decideUndoFallback(false, true, false, false))
        assertEquals(UndoFallback.DONE, TypedUndoLedger.decideUndoFallback(false, true, false, true))
    }

    @Test
    fun fallback_clearedHitAlwaysDone() {
        // 上滑清字账本命中：清掉的已上屏文字已原样写回，任何会话
        // 状态下都不再发 Ctrl+Z（污染会话内发了会让组字复活）
        assertEquals(UndoFallback.DONE, TypedUndoLedger.decideUndoFallback(false, false, true, false))
        assertEquals(UndoFallback.DONE, TypedUndoLedger.decideUndoFallback(false, false, true, true))
    }

    @Test
    fun fallback_poisonedWithoutHitIsNoop() {
        // 关键行：污染会话内三账本都不命中时空操作，绝不发 Ctrl+Z
        assertEquals(UndoFallback.NOOP, TypedUndoLedger.decideUndoFallback(false, false, false, true))
    }

    @Test
    fun fallback_unpoisonedWithoutHitSendsCtrlZ() {
        // 未污染会话保持正式版原样
        assertEquals(UndoFallback.SEND_CTRL_Z, TypedUndoLedger.decideUndoFallback(false, false, false, false))
    }

    // ---- decideHostUndoFallback：皮肤 #undo 改道落点真值表 ----
    // 与下滑表同一账本顺序（已清除→语音→打字），区别只在未污染
    // 无命中时的回落：下滑发 Ctrl+Z，#undo 发宿主上下文菜单撤销。

    @Test
    fun hostUndo_anyLedgerHitIsDone() {
        assertEquals(HostUndoFallback.DONE, TypedUndoLedger.decideHostUndoFallback(true, false, false, false, false))
        assertEquals(HostUndoFallback.DONE, TypedUndoLedger.decideHostUndoFallback(false, true, false, false, false))
        assertEquals(HostUndoFallback.DONE, TypedUndoLedger.decideHostUndoFallback(false, false, true, false, false))
        assertEquals(HostUndoFallback.DONE, TypedUndoLedger.decideHostUndoFallback(true, true, true, true, false))
    }

    @Test
    fun hostUndo_poisonedWithoutHitIsNoopNeverHost() {
        // 关键行：污染会话内三账本都不命中时空操作，绝不回退宿主撤销
        assertEquals(HostUndoFallback.NOOP, TypedUndoLedger.decideHostUndoFallback(false, false, false, true, false))
        assertEquals(HostUndoFallback.NOOP, TypedUndoLedger.decideHostUndoFallback(false, false, false, true, true))
    }

    @Test
    fun hostUndo_unpoisonedWithoutHitFallsBackToHost() {
        // 未污染无命中时保持 #undo 原有行为：宿主上下文菜单撤销
        assertEquals(HostUndoFallback.HOST_UNDO, TypedUndoLedger.decideHostUndoFallback(false, false, false, false, false))
        assertEquals(HostUndoFallback.HOST_UNDO, TypedUndoLedger.decideHostUndoFallback(false, false, false, false, true))
    }

    @Test
    fun hostUndo_composingGateBlocksLedgerHits() {
        // 组字中门：组字中账本不查——即便传入账本命中，污染门
        // 之外的结果也不得为 DONE（污染→NOOP，未污染→HOST_UNDO），
        // 与下滑 .17 的 !composing 门同一语义
        assertEquals(HostUndoFallback.NOOP, TypedUndoLedger.decideHostUndoFallback(true, true, true, true, true))
        assertEquals(HostUndoFallback.HOST_UNDO, TypedUndoLedger.decideHostUndoFallback(true, true, true, false, true))
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

    // ---- decideComposingSwipeDown：组字中下滑藏 span 真值表 ----
    // 口径（2026-11-05 藏 span 解）：组字中＋污染时下滑先藏 span，
    // 藏态下再下滑走账本撤销；其余一律移交引擎（.17 原样）。

    @Test
    fun composingSwipeDown_composingPoisonedNotHidden_hidesSpan() {
        assertEquals(
            TypedUndoLedger.ComposingSwipeDown.HIDE_SPAN,
            TypedUndoLedger.decideComposingSwipeDown(true, true, false),
        )
    }

    @Test
    fun composingSwipeDown_composingPoisonedHidden_undoesHidden() {
        assertEquals(
            TypedUndoLedger.ComposingSwipeDown.UNDO_HIDDEN,
            TypedUndoLedger.decideComposingSwipeDown(true, true, true),
        )
    }

    @Test
    fun composingSwipeDown_notComposing_delegates() {
        assertEquals(
            TypedUndoLedger.ComposingSwipeDown.DELEGATE,
            TypedUndoLedger.decideComposingSwipeDown(false, true, false),
        )
        assertEquals(
            TypedUndoLedger.ComposingSwipeDown.DELEGATE,
            TypedUndoLedger.decideComposingSwipeDown(false, true, true),
        )
    }

    @Test
    fun composingSwipeDown_notPoisoned_delegates() {
        // 未污染会话组字中下滑仍走引擎（Ctrl+Z 格），藏 span
        // 只接管污染会话的 .17 空操作格。
        assertEquals(
            TypedUndoLedger.ComposingSwipeDown.DELEGATE,
            TypedUndoLedger.decideComposingSwipeDown(true, false, false),
        )
        assertEquals(
            TypedUndoLedger.ComposingSwipeDown.DELEGATE,
            TypedUndoLedger.decideComposingSwipeDown(true, false, true),
        )
    }

    // ---- 藏态生命周期：置位／复位／随输入框结束作废 ----

    @Test
    fun composingSpanHidden_markClearAndClearAll() {
        TypedUndoLedger.clearAll()
        assertEquals(false, TypedUndoLedger.isComposingSpanHidden())
        TypedUndoLedger.markComposingSpanHidden()
        assertEquals(true, TypedUndoLedger.isComposingSpanHidden())
        TypedUndoLedger.clearComposingSpanHidden()
        assertEquals(false, TypedUndoLedger.isComposingSpanHidden())
        TypedUndoLedger.markComposingSpanHidden()
        TypedUndoLedger.clearAll()
        assertEquals(false, TypedUndoLedger.isComposingSpanHidden())
    }
}
