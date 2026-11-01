package com.jobeen.ime.skin.model

import com.jobeen.ime.data.keyboard.theme.KeyboardColors
import com.jobeen.ime.input.keyboard.key.KeyDef
import com.jobeen.ime.input.keyboard.key.KeyboardAction
import com.jobeen.ime.skin.store.SkinGenRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 快照类型表透传与方向取值：构建器只透传工厂解析结果，不改旧字段。 */
class SkinSnapshotKeyboardTableTest {

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

    private fun build(table: Map<String, KeyboardTypeResolved>): SkinSnapshot {
        val tree = kotlinx.serialization.json.Json.parseToJsonElement(
            """{"formatVersion": 3, "styles": {"keyBackground": "#FFFFFFFF"}}""",
        ) as kotlinx.serialization.json.JsonObject
        val validated = SkinValidator.validate(
            tree, 157, GeometryFallbacks(12f, 6f, 5f, 10f), assetKind = { null },
        ) as SkinValidator.Result.Valid
        return SkinSnapshotBuilder.build(
            validated.skin, SkinGenRef("skin-1", "h", 1), scheme,
            MemoryTier.NORMAL, { null }, 1000, 400,
            resolvedKeyboards = table,
        )
    }

    @Test
    fun builderPassesTableThroughUnchanged() {
        val table = mapOf(
            "pinyin" to KeyboardTypeResolved(
                portrait = resolved("pin-lay"), portraitResolution = LayoutResolution.RESOLVED,
                heightPercent = 42.0,
            ),
            "emojis" to KeyboardTypeResolved(heightPercent = 55.0),
        )
        val snapshot = build(table)
        assertEquals(table, snapshot.resolvedKeyboards)
        // 旧字段不受表影响：无顶层绑定时仍为空。
        assertNull(snapshot.resolvedLayout)
        assertEquals(LayoutResolution.NOT_SET, snapshot.layoutResolution)
    }

    @Test
    fun resolvedForLandscapeFallsBackToPortraitNotViceVersa() {
        val onlyPortrait = KeyboardTypeResolved(portrait = resolved("pin-lay"))
        assertEquals("pin-lay", onlyPortrait.resolvedFor(true)?.layoutId)
        assertEquals("pin-lay", onlyPortrait.resolvedFor(false)?.layoutId)
        val onlyLandscape = KeyboardTypeResolved(landscape = resolved("land-lay"))
        assertEquals("land-lay", onlyLandscape.resolvedFor(true)?.layoutId)
        assertNull(onlyLandscape.resolvedFor(false))
    }

    @Test
    fun heightPercentForLandscapeFallsBackToPortrait() {
        val entry = KeyboardTypeResolved(heightPercent = 42.0)
        assertEquals(42.0, entry.heightPercentFor(false))
        assertEquals(42.0, entry.heightPercentFor(true))
        assertEquals(
            50.0,
            entry.copy(heightPercentLandscape = 50.0).heightPercentFor(true),
        )
    }

    @Test
    fun emptyTableByDefault() {
        val snapshot = build(emptyMap())
        assertEquals(emptyMap<String, KeyboardTypeResolved>(), snapshot.resolvedKeyboards)
    }
}
