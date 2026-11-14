package com.jobeen.ime

import com.jobeen.ime.engine.CandidatePagingMode
import com.jobeen.ime.input.panel.component.FloatingCandidateController
import com.jobeen.ime.skin.model.FloatingCandidateStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WO52 1.4 浮动候选窗控制器：激活条件＋无内容关闭＋翻页钮可见性
 * ＋光标失败回退（纯状态单测，不碰 View／PopupWindow）。
 */
class FloatingCandidateControllerTest {

    private fun controller() = FloatingCandidateController()

    @Test
    fun showsOnlyWhenEmbeddedAndHasCandidates() {
        val c = controller()
        assertFalse(c.shouldShow(embeddedModeEnabled = true))
        c.updateCandidates(
            isEmpty = false, pageNo = 0, isLastPage = false,
            mode = CandidatePagingMode.NATIVE,
        )
        assertTrue(c.shouldShow(embeddedModeEnabled = true))
        assertFalse(c.shouldShow(embeddedModeEnabled = false))
    }

    @Test
    fun emptyCandidatesCloses() {
        val c = controller()
        c.updateCandidates(
            isEmpty = false, pageNo = 0, isLastPage = false,
            mode = CandidatePagingMode.NATIVE,
        )
        assertTrue(c.shouldShow(embeddedModeEnabled = true))
        c.updateCandidates(
            isEmpty = true, pageNo = 0, isLastPage = false,
            mode = CandidatePagingMode.NATIVE,
        )
        assertFalse(c.shouldShow(embeddedModeEnabled = true))
    }

    @Test
    fun pagingButtonsFollowPageAndLastPage() {
        val c = controller()
        c.updateCandidates(
            isEmpty = false, pageNo = 0, isLastPage = false,
            mode = CandidatePagingMode.NATIVE,
        )
        assertEquals(false to true, c.pagingButtonsVisible())
        c.updateCandidates(
            isEmpty = false, pageNo = 1, isLastPage = true,
            mode = CandidatePagingMode.NATIVE,
        )
        assertEquals(true to false, c.pagingButtonsVisible())
    }

    @Test
    fun pagingButtonsHiddenWhenSkinKeyOff() {
        val c = controller()
        c.style = FloatingCandidateStyle(pagingButtonDisplayed = false)
        c.updateCandidates(
            isEmpty = false, pageNo = 1, isLastPage = false,
            mode = CandidatePagingMode.NATIVE,
        )
        assertEquals(false to false, c.pagingButtonsVisible())
    }

    @Test
    fun cursorUnavailableResolvesToFallback() {
        val c = controller()
        c.updateCursorAnchor(FloatingCandidateController.AnchorRect(100, 1000, 300, 1050))
        c.markCursorUnavailable()
        val pos = c.resolvePlacement(
            keyboardLeft = 0, keyboardTop = 1800, keyboardRight = 1080,
            windowWidth = 1000, windowHeight = 200,
            screenWidth = 1080, screenHeight = 2400, gapPx = 16,
        )
        assertTrue(pos.usedFallback)
        assertEquals(0, pos.x)
        assertEquals(1584, pos.y)
    }

    @Test
    fun cursorAnchorResolvesAboveCursor() {
        val c = controller()
        c.updateCursorAnchor(FloatingCandidateController.AnchorRect(100, 1000, 300, 1050))
        val pos = c.resolvePlacement(
            keyboardLeft = 0, keyboardTop = 1800, keyboardRight = 1080,
            windowWidth = 1000, windowHeight = 200,
            screenWidth = 1080, screenHeight = 2400, gapPx = 16,
        )
        assertFalse(pos.usedFallback)
        assertTrue(pos.aboveCursor)
        assertEquals(784, pos.y)
    }
}
