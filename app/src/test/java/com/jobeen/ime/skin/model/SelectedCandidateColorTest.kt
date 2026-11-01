package com.jobeen.ime.skin.model

import com.jobeen.ime.data.keyboard.theme.KeyboardColors
import com.jobeen.ime.data.keyboard.theme.KeyboardTheme
import com.jobeen.ime.data.theme.CompactTheme
import com.jobeen.ime.data.theme.ReadableTheme
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 选中候选文字色槽（panel.selectedCandidateText）：
 * 缺省跟随未选中文字色；皮肤 styles 可独立覆盖；紧凑/可读主题
 * 往返不丢该槽。
 */
class SelectedCandidateColorTest {

    private val green = 0xFF23C891.toInt()

    private fun scheme(selectedCandidateText: Int? = null) =
        KeyboardColors.ColorScheme(
            keyBackground = 100, keyPressed = 101, keyBorderStroke = 102,
            specialKeyBackground = 200, specialKeyPressed = 201, specialKeyBorderStroke = 202,
            accentKeyBackground = 300, accentKeyPressed = 301, accentKeyBorderStroke = 302,
            keyText = 400, specialKeyText = 401, accentKeyText = 402, altText = 403,
            background = 500,
            panel = if (selectedCandidateText == null) {
                KeyboardColors.ColorScheme.PanelColors(
                    background = 600, toolbarText = 601, toolbarActived = 602, toolbarIcon = 603,
                    candidateBackground = 604, candidateText = 605, candidateIndex = 606,
                    candidateDivider = 607, toolbarPressed = 608,
                )
            } else {
                KeyboardColors.ColorScheme.PanelColors(
                    background = 600, toolbarText = 601, toolbarActived = 602, toolbarIcon = 603,
                    candidateBackground = 604, candidateText = 605, candidateIndex = 606,
                    candidateDivider = 607, toolbarPressed = 608,
                    selectedCandidateText = selectedCandidateText,
                )
            },
            pinner = KeyboardColors.ColorScheme.PinnerColors(
                background = 700, textColor = 701, secondaryTextColor = 702,
            ),
        )

    @Test
    fun panelDefaultFollowsCandidateText() {
        val panel = KeyboardColors.ColorScheme.PanelColors(
            background = 600, toolbarText = 601, toolbarActived = 602, toolbarIcon = 603,
            candidateBackground = 604, candidateText = 605, candidateIndex = 606,
            candidateDivider = 607, toolbarPressed = 608,
        )
        assertEquals(605, panel.selectedCandidateText)
    }

    @Test
    fun styleFieldValueInReadsSlot() {
        val s = scheme(green)
        assertEquals(green, StyleField.PANEL_SELECTED_CANDIDATE_TEXT.valueIn(s))
        assertEquals("panel.selectedCandidateText", StyleField.PANEL_SELECTED_CANDIDATE_TEXT.path)
    }

    @Test
    fun withStyleOverridesSelectedOnly() {
        val base = scheme()
        val updated = SkinSnapshotBuilder.run {
            base.withStyle(StyleField.PANEL_SELECTED_CANDIDATE_TEXT, green)
        }
        assertEquals(green, updated.panel.selectedCandidateText)
        assertEquals(605, updated.panel.candidateText)
        assertEquals(base.panel.background, updated.panel.background)
    }

    @Test
    fun compactThemeRoundTripPreservesSlot() {
        val s = scheme(green)
        val restored = CompactTheme.from(KeyboardTheme("t", "T", s)).toKeyboardTheme().colors
        assertEquals(green, restored.panel.selectedCandidateText)
    }

    @Test
    fun readableThemeRoundTripPreservesSlot() {
        val s = scheme(green)
        val restored = ReadableTheme.from(KeyboardTheme("t", "T", s)).toKeyboardTheme().colors
        assertEquals(green, restored.panel.selectedCandidateText)
    }
}
