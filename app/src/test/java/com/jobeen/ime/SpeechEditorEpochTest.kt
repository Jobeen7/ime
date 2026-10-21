package com.jobeen.ime

import com.jobeen.ime.base.speech.EditorEpoch
import com.jobeen.ime.base.speech.InputLifecycleCallback
import com.jobeen.ime.base.speech.advancesEditorEpoch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 输入框纪元（F4 写屏属主判据）的规格：会话开始捕获纪元，结算尾
 * 写屏前复检——纪元未推进才放行，推进过即判过期整段丢弃。
 */
class SpeechEditorEpochTest {

    @Test
    fun capturedWithoutAdvanceStaysCurrent() {
        val epoch = EditorEpoch()
        val captured = epoch.current()
        assertTrue(epoch.isCurrent(captured))
    }

    @Test
    fun capturedThenAdvanceIsStale() {
        val epoch = EditorEpoch()
        val captured = epoch.current()
        epoch.advance()
        assertFalse(epoch.isCurrent(captured))
    }

    @Test
    fun captureAfterAdvanceIsCurrent() {
        val epoch = EditorEpoch()
        epoch.advance()
        val captured = epoch.current()
        assertTrue(epoch.isCurrent(captured))
    }

    @Test
    fun multipleAdvancesKeepOlderCapturesStale() {
        val epoch = EditorEpoch()
        val first = epoch.current()
        epoch.advance()
        val second = epoch.current()
        epoch.advance()
        assertFalse(epoch.isCurrent(first))
        assertFalse(epoch.isCurrent(second))
        assertTrue(epoch.isCurrent(epoch.current()))
    }

    @Test
    fun advanceReturnsStrictlyIncreasingValues() {
        val epoch = EditorEpoch()
        val a = epoch.advance()
        val b = epoch.advance()
        assertTrue(b > a)
        assertEquals(b, epoch.current())
    }

    @Test
    fun freshEpochStartsAtZero() {
        // 初值 0 与「从未推进」同义：进程内首个会话捕获 0、未换框时放行
        val epoch = EditorEpoch()
        assertEquals(0L, epoch.current())
        assertTrue(epoch.isCurrent(0L))
    }

    // ── S1：作废投递判定（回调序列 → 是否推进纪元）──
    // 事件以 (回调, finishingInput) 表示；onStartInputView 的
    // restarting 取值对判定无影响（恒不推进），序列里以
    // finishingInput 位借代标注

    private fun advancesFor(
        events: List<Pair<InputLifecycleCallback, Boolean>>,
    ): Int = events.count { (callback, finishing) ->
        advancesEditorEpoch(callback, finishing)
    }

    @Test
    fun hideThenShowSameEditorDoesNotAdvance() {
        // 同框收起再打开：hide 走 onFinishInputView(false)，show
        // 走 onStartInputView(restarting=false)。旧实现 show 时
        // 误推进纪元、把同框等 DONE 的整句整段丢弃（S1）；收窄
        // 投递后整串不得推进
        val events = listOf(
            InputLifecycleCallback.FINISH_INPUT_VIEW to false,
            InputLifecycleCallback.START_INPUT_VIEW to false,
        )
        assertEquals(0, advancesFor(events))
    }

    @Test
    fun trueEditorSwitchAdvancesOnce() {
        // 真换框路径一：旧会话 onFinishInput（doFinishInput）后，
        // 新框 onStartInputView 开始——推进恰一次
        val viaFinishInput = listOf(
            InputLifecycleCallback.FINISH_INPUT to false,
            InputLifecycleCallback.START_INPUT_VIEW to false,
        )
        assertEquals(1, advancesFor(viaFinishInput))
        // 真换框路径二：onFinishInputView(true) 后新框开始
        val viaFinishView = listOf(
            InputLifecycleCallback.FINISH_INPUT_VIEW to true,
            InputLifecycleCallback.START_INPUT_VIEW to false,
        )
        assertEquals(1, advancesFor(viaFinishView))
    }

    @Test
    fun restartInputDoesNotAdvance() {
        // 同框 restartInput：只有 onStartInputView(restarting=true)
        val events = listOf(InputLifecycleCallback.START_INPUT_VIEW to true)
        assertEquals(0, advancesFor(events))
    }

    @Test
    fun startInputViewNeverAdvancesRegardlessOfRestarting() {
        assertFalse(advancesEditorEpoch(InputLifecycleCallback.START_INPUT_VIEW, false))
        assertFalse(advancesEditorEpoch(InputLifecycleCallback.START_INPUT_VIEW, true))
        assertTrue(advancesEditorEpoch(InputLifecycleCallback.FINISH_INPUT))
        assertTrue(advancesEditorEpoch(InputLifecycleCallback.FINISH_INPUT_VIEW, true))
        assertFalse(advancesEditorEpoch(InputLifecycleCallback.FINISH_INPUT_VIEW, false))
    }

    // ── R1：迟到 FINAL 对账的武装纪元门控 ──

    @Test
    fun armedEpochGateClosesAfterAdvance() {
        // 对账在 settle 武装时记下所属会话的发起框纪元
        // （lateFinalArmedEpoch）；对账协程飞行中换框（纪元推进）
        // 后，以武装纪元复检必须判过期、整段放弃——协程持的是局
        // 部快照，换框清窗清不到它，只有这道门控挡得住
        val epoch = EditorEpoch()
        val armedEpoch = epoch.current()
        assertTrue(epoch.isCurrent(armedEpoch))
        epoch.advance()
        assertFalse(epoch.isCurrent(armedEpoch))
    }

    @Test
    fun armedEpochGateStaysOpenWithinSameEditor() {
        // 同框接管（N12 语义）：武装后纪元未推进，对账照常放行
        val epoch = EditorEpoch()
        val armedEpoch = epoch.current()
        assertTrue(epoch.isCurrent(armedEpoch))
    }
}
