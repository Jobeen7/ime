package com.jobeen.ime.input.keyboard.window

import com.jobeen.ime.data.keyboard.theme.KeyboardColors
import com.jobeen.ime.skin.model.KeyboardTypeResolved
import com.jobeen.ime.skin.model.SkinSnapshot
import com.jobeen.ime.skin.store.SkinGenRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 类型高度取值：表项方向值优先于既有高度链，缺失时回落（不插手既有链）。 */
class KeyboardTypeHeightTest {

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
    fun typeHeightPerDirection() {
        val snap = snapshot(
            mapOf(
                "pinyin" to KeyboardTypeResolved(
                    heightPercent = 42.0, heightPercentLandscape = 36.0,
                ),
                "emojis" to KeyboardTypeResolved(heightPercent = 55.0),
            ),
        )
        assertEquals(42, KeyboardWindowView.typeHeightPercent(snap, "pinyin", false))
        assertEquals(36, KeyboardWindowView.typeHeightPercent(snap, "pinyin", true))
        // 横屏无专属值 → 竖屏值。
        assertEquals(55, KeyboardWindowView.typeHeightPercent(snap, "emojis", true))
    }

    @Test
    fun missingTypeOrHeightIsNull() {
        val snap = snapshot(
            mapOf("pinyin" to KeyboardTypeResolved()),
        )
        assertNull(KeyboardWindowView.typeHeightPercent(snap, "pinyin", false))
        assertNull(KeyboardWindowView.typeHeightPercent(snap, "symbolic", false))
        assertNull(KeyboardWindowView.typeHeightPercent(snap, null, false))
        assertNull(KeyboardWindowView.typeHeightPercent(null, "pinyin", false))
    }

    @Test
    fun typeHeightWinsOverExistingChain() {
        // 既有链本身不变（布局 50 仍决议 50）；contentHeight 内
        // 类型值以 ?: 置于链首，故类型值 42 会覆盖布局值 50。
        // 此处只 pin 两端：类型取值 42、既有链决议 50。
        val snap = snapshot(
            mapOf("pinyin" to KeyboardTypeResolved(heightPercent = 42.0)),
        )
        assertEquals(42, KeyboardWindowView.typeHeightPercent(snap, "pinyin", false))
        assertEquals(
            50,
            KeyboardWindowView.resolveHeightPercent(
                layoutPercent = 50, skinPercent = null, skinLandscapePercent = null,
                isLandscape = false, appPortraitPercent = 40, appLandscapePercent = 40,
            ),
        )
    }
}
