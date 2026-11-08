package com.jobeen.ime.input.panel

import com.jobeen.ime.engine.data.EngineMessage
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 联想关闭（K-predict）：预测态边缘钮点按发 ClosePredictions，
 * 组字态（标志关）仍发展开／收起。hitTest 为纯几何，可单测。
 */
class PredictCloseTest {

    private val candidates = listOf(
        EngineMessage.Candidate(index = 0, text = "你好"),
    )

    private fun renderer(closeOnEdgeTap: Boolean, expandLeft: Boolean = false) =
        ComposingRenderer(
            candidates = candidates,
            expandDrawable = null,
            horizontalPaddingDp = 8f,
            expandLeft = expandLeft,
            edgeTapClosesPredictions = closeOnEdgeTap,
        )

    @Test
    fun edgeTapClosesPredictionsWhenFlagOn() {
        val result = renderer(closeOnEdgeTap = true)
            .hitTest(950f, 100f, 1000, 200, 0f, false, 2f)
        assertTrue(result is KawaiiPanel.TouchResult.ClosePredictions)
    }

    @Test
    fun edgeTapExpandsWhenFlagOff() {
        val result = renderer(closeOnEdgeTap = false)
            .hitTest(950f, 100f, 1000, 200, 0f, false, 2f)
        assertTrue(result is KawaiiPanel.TouchResult.ExpandCandidates)
    }

    @Test
    fun edgeTapClosesPredictionsOnLeftEdgeToo() {
        val result = renderer(closeOnEdgeTap = true, expandLeft = true)
            .hitTest(50f, 100f, 1000, 200, 0f, false, 2f)
        assertTrue(result is KawaiiPanel.TouchResult.ClosePredictions)
    }

    /**
     * H2-2 候选栏取消按钮：预测态边缘钮画 ×（取消），组字态
     * 仍画展开 chevron；图标抉择只看关预测标志，与图标是否
     * 传入无关（缺图时绘制期回落展开图，抉择本身不变）。
     */
    @Test
    fun edgeButtonShowsCancelIconInPredictionMode() {
        val kind = renderer(closeOnEdgeTap = true).edgeButtonIconKind()
        assertTrue(kind == ComposingRenderer.EdgeButtonIcon.CANCEL)
    }

    @Test
    fun edgeButtonShowsExpandIconInComposingMode() {
        val kind = renderer(closeOnEdgeTap = false).edgeButtonIconKind()
        assertTrue(kind == ComposingRenderer.EdgeButtonIcon.EXPAND)
    }
}
