package com.jobeen.ime.input.panel.component

import com.jobeen.ime.skin.model.VerticalBottomRowSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 竖网格底部钮行钮表：规范顺序、仅真钮入表、返回文案双名同认。 */
class CandidateGridBottomRowTest {

    @Test
    fun fullSpecYieldsFourButtonsInOrder() {
        val buttons = bottomRowButtons(
            VerticalBottomRowSpec(
                pageUp = true, pageDown = true,
                returnOrCollapse = true, backspace = true,
                returnLabel = "收起",
            ),
        )
        assertEquals(
            listOf(
                BottomRowButton("▲", BottomRowButtonKind.PAGE_UP),
                BottomRowButton("▼", BottomRowButtonKind.PAGE_DOWN),
                BottomRowButton("收起", BottomRowButtonKind.RETURN_OR_COLLAPSE),
                BottomRowButton("⌫", BottomRowButtonKind.BACKSPACE),
            ),
            buttons,
        )
    }

    @Test
    fun onlyDeclaredButtonsAppear() {
        val buttons = bottomRowButtons(
            VerticalBottomRowSpec(pageDown = true, backspace = true),
        )
        assertEquals(
            listOf(
                BottomRowButton("▼", BottomRowButtonKind.PAGE_DOWN),
                BottomRowButton("⌫", BottomRowButtonKind.BACKSPACE),
            ),
            buttons,
        )
    }

    @Test
    fun returnLabelFallsBackWhenNotReturnOrCollapse() {
        val buttons = bottomRowButtons(
            VerticalBottomRowSpec(returnOrCollapse = true, returnLabel = "关闭"),
        )
        assertEquals(
            listOf(BottomRowButton("返回", BottomRowButtonKind.RETURN_OR_COLLAPSE)),
            buttons,
        )
    }

    @Test
    fun emptySpecYieldsNoButtons() {
        assertTrue(bottomRowButtons(null).isEmpty())
        assertTrue(bottomRowButtons(VerticalBottomRowSpec()).isEmpty())
    }
}
