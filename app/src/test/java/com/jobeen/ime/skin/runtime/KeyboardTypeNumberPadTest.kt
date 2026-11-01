package com.jobeen.ime.skin.runtime

import com.jobeen.ime.data.keyboard.theme.KeyboardColors
import com.jobeen.ime.input.keyboard.key.KeyDef
import com.jobeen.ime.input.keyboard.key.KeyboardAction
import com.jobeen.ime.skin.model.KeyboardTypeResolved
import com.jobeen.ime.skin.model.LayoutResolution
import com.jobeen.ime.skin.model.ResolvedLayout
import com.jobeen.ime.skin.model.SkinSnapshot
import com.jobeen.ime.skin.store.SkinGenRef
import org.junit.Assert.assertEquals
import org.junit.Test

/** numberPad 类型走表：自定义布局、方向、高度与 numeric 相互独立。 */
class KeyboardTypeNumberPadTest {

    private val scheme = KeyboardColors.ColorScheme(
        keyBackground = 100, keyPressed = 101, keyBorderStroke = 102,
        specialKeyBackground = 200, specialKeyPressed = 201, specialKeyBorderStroke = 202,
        accentKeyBackground = 300, accentKeyPressed = 301, accentKeyBorderStroke = 302,
        keyText = 400, specialKeyText = 401, accentKeyText = 402, altText = 403,
        background = 500, cornerRadius = 5f, keyHMargin = 3f, keyVMargin = 4f,
        panel = KeyboardColors.ColorScheme.PanelColors(
            background = 600, toolbarText = 601, toolbarActived = 602, toolbarIcon = 603,
            candidateBackground = 604, candidateText = 605, candidateIndex = 606,
            candidateDivider = 607, toolbarPressed = 608,
        ),
        pinner = KeyboardColors.ColorScheme.PinnerColors(
            background = 700, textColor = 701, secondaryTextColor = 702,
        ),
        toastBackground = 999, toastText = 998,
    )

    private fun keyDef(id: String): KeyDef = KeyDef(
        appearance = KeyDef.Appearance.Text(
            displayText = id, textSize = 18f, percentWidth = 1f,
        ),
        behaviors = setOf(
            KeyDef.Behavior.Press(KeyboardAction.KeySequenceAction(id)),
        ),
        id = id,
    )

    private fun resolved(id: String) = ResolvedLayout(
        layoutId = id, generation = 1, name = id,
        baseLayout = listOf(listOf(keyDef("$id:K-1"))),
    )

    private fun snapshot(table: Map<String, KeyboardTypeResolved>): SkinSnapshot = SkinSnapshot(
        ref = SkinGenRef("skin-1", "h", 1),
        name = "S",
        colorScheme = scheme,
        geometryPresence = emptySet(),
        effectiveGeometry = emptyMap(),
        background = null,
        degradations = emptySet(),
        layoutId = null,
        resolvedKeyboards = table,
    )

    @Test
    fun numberPadEntryResolvesPerDirection() {
        val snap = snapshot(
            mapOf(
                "numberPad" to KeyboardTypeResolved(
                    portrait = resolved("pad-lay"),
                    landscape = resolved("pad-land"),
                    portraitResolution = LayoutResolution.RESOLVED,
                    landscapeResolution = LayoutResolution.RESOLVED,
                    heightPercent = 48.0,
                ),
            ),
        )
        assertEquals(
            "custom:pad-lay",
            CustomLayoutRuntime.resolveTypeTarget(snap, "numberPad", "T9"),
        )
        assertEquals(
            "custom:pad-land",
            CustomLayoutRuntime.resolveTypeTarget(snap, "numberPad", "T9", landscape = true),
        )
        assertEquals(
            48.0,
            snap.resolvedKeyboards.getValue("numberPad").heightPercentFor(false),
        )
    }

    @Test
    fun numberPadAndNumericAreIndependentEntries() {
        // 两者内置回退同为数字键盘实现，但表项互不干扰。
        val snap = snapshot(
            mapOf(
                "numberPad" to KeyboardTypeResolved(
                    portrait = resolved("pad-lay"),
                    portraitResolution = LayoutResolution.RESOLVED,
                    heightPercent = 48.0,
                ),
                "numeric" to KeyboardTypeResolved(
                    portrait = resolved("num-lay"),
                    portraitResolution = LayoutResolution.RESOLVED,
                    heightPercent = 60.0,
                ),
            ),
        )
        assertEquals(
            "custom:pad-lay",
            CustomLayoutRuntime.resolveTypeTarget(snap, "numberPad", "T9"),
        )
        assertEquals(
            "custom:num-lay",
            CustomLayoutRuntime.resolveTypeTarget(snap, "numeric", "T9"),
        )
        assertEquals(
            60.0,
            snap.resolvedKeyboards.getValue("numeric").heightPercentFor(false),
        )
    }
}
