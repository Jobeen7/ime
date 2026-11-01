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
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/** 类型目标解析（多主槽一期）：表项 → pinyin 旧绑定 → 内置回退，永不空白。 */
class KeyboardTypeResolveTest {

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

    private fun snapshot(
        table: Map<String, KeyboardTypeResolved> = emptyMap(),
        legacy: ResolvedLayout? = null,
        legacyLandscape: ResolvedLayout? = null,
    ): SkinSnapshot = SkinSnapshot(
        ref = SkinGenRef("skin-1", "h", 1),
        name = "S",
        colorScheme = scheme,
        geometryPresence = emptySet(),
        effectiveGeometry = emptyMap(),
        background = null,
        degradations = emptySet(),
        layoutId = legacy?.layoutId,
        resolvedLayout = legacy,
        layoutResolution = if (legacy != null) {
            LayoutResolution.RESOLVED
        } else {
            LayoutResolution.NOT_SET
        },
        resolvedLayoutLandscape = legacyLandscape,
        landscapeLayoutResolution = if (legacyLandscape != null) {
            LayoutResolution.RESOLVED
        } else {
            LayoutResolution.NOT_SET
        },
        resolvedKeyboards = table,
    )

    private fun typeEntry(
        portrait: String? = null,
        landscape: String? = null,
    ) = KeyboardTypeResolved(
        portrait = portrait?.let { resolved(it) },
        landscape = landscape?.let { resolved(it) },
        portraitResolution = if (portrait != null) {
            LayoutResolution.RESOLVED
        } else {
            LayoutResolution.NOT_SET
        },
        landscapeResolution = if (landscape != null) {
            LayoutResolution.RESOLVED
        } else {
            LayoutResolution.NOT_SET
        },
    )

    @Test
    fun tableEntryWinsPerDirectionWithPortraitFallback() {
        val snap = snapshot(
            table = mapOf(
                "pinyin" to typeEntry(portrait = "pin-lay", landscape = "pin-land"),
                "alphabetic" to typeEntry(portrait = "alpha-lay"),
            ),
        )
        assertEquals("custom:pin-lay", CustomLayoutRuntime.resolveTypeTarget(snap, "pinyin", "T9"))
        assertEquals(
            "custom:pin-land",
            CustomLayoutRuntime.resolveTypeTarget(snap, "pinyin", "T9", landscape = true),
        )
        // 横屏无专属绑定 → 竖屏布局。
        assertEquals(
            "custom:alpha-lay",
            CustomLayoutRuntime.resolveTypeTarget(snap, "alphabetic", "T9", landscape = true),
        )
    }

    @Test
    fun pinyinWithoutTableFallsBackToLegacyBinding() {
        val snap = snapshot(
            legacy = resolved("old-lay"), legacyLandscape = resolved("old-land"),
        )
        assertEquals("custom:old-lay", CustomLayoutRuntime.resolveTypeTarget(snap, "pinyin", "T9"))
        assertEquals(
            "custom:old-land",
            CustomLayoutRuntime.resolveTypeTarget(snap, "pinyin", "T9", landscape = true),
        )
        // 横屏无旧绑定 → 竖屏旧布局。
        val portraitOnly = snapshot(legacy = resolved("old-lay"))
        assertEquals(
            "custom:old-lay",
            CustomLayoutRuntime.resolveTypeTarget(portraitOnly, "pinyin", "T9", landscape = true),
        )
    }

    @Test
    fun builtinFallbacksNeverBlank() {
        val snap = snapshot()
        assertEquals("Qwerty", CustomLayoutRuntime.resolveTypeTarget(snap, "alphabetic", "T9"))
        assertEquals("T9", CustomLayoutRuntime.resolveTypeTarget(snap, "pinyin", "T9"))
        assertEquals("Emoji", CustomLayoutRuntime.resolveTypeTarget(snap, "emojis", "T9"))
        // images 暂无实现：过渡期以表情键盘兜底，绝不空白。
        assertEquals("Emoji", CustomLayoutRuntime.resolveTypeTarget(snap, "images", "T9"))
        assertEquals("Number", CustomLayoutRuntime.resolveTypeTarget(snap, "numeric", "T9"))
        assertEquals("Number", CustomLayoutRuntime.resolveTypeTarget(snap, "numberPad", "T9"))
        assertEquals("Symbol", CustomLayoutRuntime.resolveTypeTarget(snap, "symbolic", "T9"))
        // 未声明的自定义类型 → 方案默认布局。
        assertEquals("T9", CustomLayoutRuntime.resolveTypeTarget(snap, "myBoard", "T9"))
        assertEquals("Qwerty", CustomLayoutRuntime.resolveTypeTarget(null, "alphabetic", "T9"))
    }

    @Test
    fun declaredButUnresolvedEntryFallsBackLikeUndeclared() {
        val snap = snapshot(
            table = mapOf(
                // 声明了高度但无有效绑定（解析失败口径）。
                "emojis" to KeyboardTypeResolved(heightPercent = 55.0),
                "pinyin" to KeyboardTypeResolved(
                    portraitResolution = LayoutResolution.MISSING,
                ),
            ),
            legacy = resolved("old-lay"),
        )
        assertEquals("Emoji", CustomLayoutRuntime.resolveTypeTarget(snap, "emojis", "T9"))
        // pinyin 声明失败 → 旧顶层绑定。
        assertEquals("custom:old-lay", CustomLayoutRuntime.resolveTypeTarget(snap, "pinyin", "T9"))
        val noLegacy = snapshot(
            table = mapOf(
                "pinyin" to KeyboardTypeResolved(
                    portraitResolution = LayoutResolution.INVALID,
                ),
            ),
        )
        assertEquals("T9", CustomLayoutRuntime.resolveTypeTarget(noLegacy, "pinyin", "T9"))
    }

    @Test
    fun oldNameBasedResolutionIgnoresTable() {
        // 旧按名解析冻结语义：只认旧单布局字段，表项不影响它。
        val snap = snapshot(table = mapOf("pinyin" to typeEntry(portrait = "pin-lay")))
        assertEquals("T9", CustomLayoutRuntime.resolveSwitchTarget(snap, "T9", "T9"))
        assertEquals("T9", CustomLayoutRuntime.resolveSwitchTarget(snap, "custom:pin-lay", "T9"))
    }

    @Test
    fun effectiveLayoutForTypeUsesTableEntry() {
        val entry = typeEntry(portrait = "pin-lay")
        val snap = snapshot(table = mapOf("pinyin" to entry))
        val effective = CustomLayoutRuntime.effectiveLayoutForType(snap, "pinyin", emptyMap())
        // 无覆写无通知：装配器恒等纪律 → 同一引用。
        assertSame(entry.portrait!!.baseLayout, effective)
        assertNull(CustomLayoutRuntime.effectiveLayoutForType(snap, "emojis", emptyMap()))
        assertNull(CustomLayoutRuntime.effectiveLayoutForType(null, "pinyin", emptyMap()))
    }

    @Test
    fun effectiveLayoutForLayoutIdFindsTableAndLegacy() {
        val snap = snapshot(
            table = mapOf("emojis" to typeEntry(portrait = "emo-lay")),
            legacy = resolved("old-lay"),
        )
        assertEquals(
            "emo-lay:K-1",
            (CustomLayoutRuntime.effectiveLayoutForLayoutId(
                snap, "emo-lay", emptyMap(),
            )!![0][0].appearance as KeyDef.Appearance.Text).displayText,
        )
        assertEquals(
            "old-lay:K-1",
            (CustomLayoutRuntime.effectiveLayoutForLayoutId(
                snap, "old-lay", emptyMap(),
            )!![0][0].appearance as KeyDef.Appearance.Text).displayText,
        )
        assertNull(
            CustomLayoutRuntime.effectiveLayoutForLayoutId(snap, "nope", emptyMap()),
        )
    }

    @Test
    fun toolTypesFallBackToToolBoardsNotSchemaDefault() {
        val snap = snapshot()
        // 无绑定工具类型不再回落方案默认主键盘，而是内置工具板。
        assertEquals(
            "layoutSwitch",
            CustomLayoutRuntime.resolveTypeTarget(snap, "layoutSwitch", "T9"),
        )
        assertEquals(
            "returnPath",
            CustomLayoutRuntime.resolveTypeTarget(snap, "returnPath", "T9"),
        )
        assertEquals(
            "floatPanel",
            CustomLayoutRuntime.resolveTypeTarget(snap, "floatPanel", "T9"),
        )
    }

    @Test
    fun toolTypeBoundLayoutWinsOverToolBoard() {
        val snap = snapshot(
            table = mapOf("layoutSwitch" to typeEntry(portrait = "switch-lay")),
        )
        assertEquals(
            "custom:switch-lay",
            CustomLayoutRuntime.resolveTypeTarget(snap, "layoutSwitch", "T9"),
        )
    }
}
