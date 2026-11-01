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
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * images 类型走表：布局解析与其它类型同口径。
 * 本波只负责类型解析与高度；图片的上屏提交通路是并行
 * 分工的另一波，不在本文件断言。
 */
class KeyboardTypeImagesTest {

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
    fun imagesEntryResolvesLikeOtherTypes() {
        val entry = KeyboardTypeResolved(
            portrait = resolved("img-lay"),
            landscape = resolved("img-land"),
            portraitResolution = LayoutResolution.RESOLVED,
            landscapeResolution = LayoutResolution.RESOLVED,
            heightPercent = 55.0,
        )
        val snap = snapshot(mapOf("images" to entry))
        assertEquals(
            "custom:img-lay",
            CustomLayoutRuntime.resolveTypeTarget(snap, "images", "T9"),
        )
        assertEquals(
            "custom:img-land",
            CustomLayoutRuntime.resolveTypeTarget(snap, "images", "T9", landscape = true),
        )
        // 生效布局与其它类型同一装配器、无覆写时恒等。
        assertSame(
            entry.portrait!!.baseLayout,
            CustomLayoutRuntime.effectiveLayoutForType(snap, "images", emptyMap()),
        )
        assertEquals(55.0, entry.heightPercentFor(false))
    }
}
