package com.jobeen.ime.skin.runtime

import com.jobeen.ime.data.keyboard.theme.KeyboardColors
import com.jobeen.ime.skin.model.KeyboardTypeResolved
import com.jobeen.ime.skin.model.KeyboardTypes
import com.jobeen.ime.skin.model.ResolvedLayout
import com.jobeen.ime.skin.model.SkinSnapshot
import com.jobeen.ime.skin.store.SkinGenRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 布局名 → 类型反查（Wave-4 布局切换键用，纯决策）。 */
class TypeForLayoutNameTest {

    private val scheme = KeyboardColors.ColorScheme(
        keyBackground = 1, keyPressed = 2, keyBorderStroke = 3,
        specialKeyBackground = 4, specialKeyPressed = 5, specialKeyBorderStroke = 6,
        accentKeyBackground = 7, accentKeyPressed = 8, accentKeyBorderStroke = 9,
        keyText = 10, specialKeyText = 11, accentKeyText = 12, altText = 13,
        background = 14,
        panel = KeyboardColors.ColorScheme.PanelColors(
            background = 20, toolbarText = 21, toolbarActived = 22, toolbarIcon = 23,
            candidateBackground = 24, candidateText = 25, candidateIndex = 26,
            candidateDivider = 27, toolbarPressed = 28,
        ),
        pinner = KeyboardColors.ColorScheme.PinnerColors(
            background = 30, textColor = 31, secondaryTextColor = 32,
        ),
    )

    private fun layout(id: String) = ResolvedLayout(
        layoutId = id, generation = 1, name = id, baseLayout = emptyList(),
    )

    private fun snapshot() = SkinSnapshot(
        ref = SkinGenRef("skin-1", "h".repeat(64), 1),
        name = "test",
        colorScheme = scheme,
        geometryPresence = emptySet(),
        effectiveGeometry = emptyMap(),
        background = null,
        degradations = emptySet(),
        layoutId = null,
        resolvedKeyboards = mapOf(
            KeyboardTypes.SYMBOLIC to KeyboardTypeResolved(portrait = layout("sym-layout")),
            KeyboardTypes.ALPHABETIC to KeyboardTypeResolved(portrait = layout("abc-layout")),
        ),
        resolvedLayout = layout("legacy-layout"),
    )

    @Test
    fun builtinNamesMapToTypes() {
        val snap = snapshot()
        assertEquals(
            KeyboardTypes.ALPHABETIC,
            CustomLayoutRuntime.typeForLayoutName(snap, "Qwerty"),
        )
        assertEquals(
            KeyboardTypes.PINYIN,
            CustomLayoutRuntime.typeForLayoutName(snap, "T9"),
        )
        assertEquals(
            KeyboardTypes.PINYIN,
            CustomLayoutRuntime.typeForLayoutName(snap, "T15"),
        )
        assertEquals(
            KeyboardTypes.SYMBOLIC,
            CustomLayoutRuntime.typeForLayoutName(snap, "Symbol"),
        )
        assertEquals(
            KeyboardTypes.EMOJIS,
            CustomLayoutRuntime.typeForLayoutName(snap, "Emoji"),
        )
        assertEquals(
            KeyboardTypes.NUMERIC,
            CustomLayoutRuntime.typeForLayoutName(snap, "Number"),
        )
        assertNull(CustomLayoutRuntime.typeForLayoutName(snap, "Nope"))
    }

    @Test
    fun tableAndLegacyCustomIdsResolve() {
        val snap = snapshot()
        assertEquals(
            KeyboardTypes.SYMBOLIC,
            CustomLayoutRuntime.typeForLayoutName(snap, "custom:sym-layout"),
        )
        assertEquals(
            KeyboardTypes.ALPHABETIC,
            CustomLayoutRuntime.typeForLayoutName(snap, "custom:abc-layout"),
        )
        assertEquals(
            KeyboardTypes.PINYIN,
            CustomLayoutRuntime.typeForLayoutName(snap, "custom:legacy-layout"),
        )
        assertNull(CustomLayoutRuntime.typeForLayoutName(snap, "custom:unknown-layout"))
        // 无快照时内置名仍可反查
        assertEquals(
            KeyboardTypes.ALPHABETIC,
            CustomLayoutRuntime.typeForLayoutName(null, "Qwerty"),
        )
        assertNull(CustomLayoutRuntime.typeForLayoutName(null, "custom:anything"))
    }
}
