package com.jobeen.ime.skin.runtime

import com.jobeen.ime.data.keyboard.theme.KeyboardColors
import com.jobeen.ime.input.keyboard.key.KeyDef
import com.jobeen.ime.input.keyboard.key.KeyboardAction
import com.jobeen.ime.skin.model.DynamicTextSource
import com.jobeen.ime.skin.model.KeyForegroundLayer
import com.jobeen.ime.skin.model.KeyGeometryOverride
import com.jobeen.ime.skin.model.KeyOverride
import com.jobeen.ime.skin.model.KeyStyleRole
import com.jobeen.ime.skin.model.KeyTextRole
import com.jobeen.ime.skin.model.SkinAction
import com.jobeen.ime.skin.model.SkinSnapshot
import com.jobeen.ime.skin.model.SoundBindings
import com.jobeen.ime.skin.model.WhitelistAction
import com.jobeen.ime.skin.store.SkinGenRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** G1 装配器：左右滑、宽归一化、副文字、图片接纳、字号档、前景层、动态文字、图标染色。 */
class KeyModelG1ApplierTest {

    private val scheme = KeyboardColors.ColorScheme(
        keyBackground = 1, keyPressed = 2, keyBorderStroke = 3,
        specialKeyBackground = 4, specialKeyPressed = 5, specialKeyBorderStroke = 6,
        accentKeyBackground = 7, accentKeyPressed = 8, accentKeyBorderStroke = 9,
        keyText = 10, specialKeyText = 11, accentKeyText = 12, altText = 13,
        background = 14, cornerRadius = 5f, keyHMargin = 3f, keyVMargin = 4f,
        panel = KeyboardColors.ColorScheme.PanelColors(
            background = 20, toolbarText = 21, toolbarActived = 22, toolbarIcon = 23,
            candidateBackground = 24, candidateText = 25, candidateIndex = 26,
            candidateDivider = 27, toolbarPressed = 28,
        ),
        pinner = KeyboardColors.ColorScheme.PinnerColors(
            background = 30, textColor = 31, secondaryTextColor = 32,
        ),
        toastBackground = 40, toastText = 41,
    )

    private fun snapshot(
        overrides: Map<String, KeyOverride> = emptyMap(),
        keyStyles: Map<String, Map<KeyStyleRole, String>> = emptyMap(),
        textSizes: Map<KeyTextRole, Float> = emptyMap(),
        imageAssets: Map<String, String> = emptyMap(),
    ) = SkinSnapshot(
        ref = SkinGenRef("skin-1", "h".repeat(64), 1),
        name = "test",
        colorScheme = scheme,
        geometryPresence = emptySet(),
        effectiveGeometry = emptyMap(),
        background = null,
        degradations = emptySet(),
        layoutId = null,
        keyOverrides = overrides,
        keyStyles = keyStyles,
        textSizes = textSizes,
        imageAssets = imageAssets,
        soundBindings = SoundBindings.EMPTY,
    )

    private fun textKey(
        id: String,
        text: String,
        width: Float,
        size: Float = 18f,
        rowSpan: Int = 1,
    ) = KeyDef(
        appearance = KeyDef.Appearance.Text(
            displayText = text, textSize = size, percentWidth = width, rowSpan = rowSpan,
        ),
        behaviors = setOf(
            KeyDef.Behavior.Press(KeyboardAction.KeySequenceAction(text)),
            KeyDef.Behavior.Swipe(
                upAction = KeyboardAction.KeySequenceAction("up"),
                downAction = KeyboardAction.KeySequenceAction("down"),
            ),
        ),
        id = id,
    )

    private fun apply(
        rows: List<List<KeyDef>>,
        snap: SkinSnapshot,
        dynamicTexts: Map<DynamicTextSource, String> = emptyMap(),
    ) = KeyOverrideApplier.apply("TestLayout", rows, snap, emptyMap(), dynamicTexts = dynamicTexts)

    private fun swipeOf(def: KeyDef): KeyDef.Behavior.Swipe =
        def.behaviors.filterIsInstance<KeyDef.Behavior.Swipe>().single()

    @Test
    fun swipeLeftRightMergedWithoutTouchingUpDown() {
        val rows = listOf(
            listOf(
                textKey("K1", "a", 0.5f),
                textKey("K2", "b", 0.5f),
            ),
        )
        val snap = snapshot(
            overrides = mapOf(
                "K1" to KeyOverride(
                    swipeLeft = SkinAction.Command(WhitelistAction.CURSOR_LEFT),
                    swipeRight = SkinAction.Command(WhitelistAction.CURSOR_RIGHT),
                ),
            ),
        )
        val out = apply(rows, snap)
        val swipe = swipeOf(out[0][0])
        assertEquals(KeyboardAction.CursorMoveAction(-1), swipe.leftAction)
        assertEquals(KeyboardAction.CursorMoveAction(1), swipe.rightAction)
        // 出厂上下滑原样保留
        assertEquals(KeyboardAction.KeySequenceAction("up"), swipe.upAction)
        assertEquals(KeyboardAction.KeySequenceAction("down"), swipe.downAction)
        // 未命中键恒等引用
        assertSame(rows[0][1], out[0][1])
    }

    @Test
    fun widthRenormalizationKeepsRowSum() {
        val rows = listOf(
            listOf(
                textKey("K1", "a", 0.25f),
                textKey("K2", "b", 0.25f),
                textKey("K3", "c", 0.5f),
            ),
        )
        val snap = snapshot(
            overrides = mapOf(
                "K1" to KeyOverride(geometry = KeyGeometryOverride(widthFactor = 2f)),
            ),
        )
        val out = apply(rows, snap)
        val widths = out[0].map { it.appearance.percentWidth }
        // 缩放后行和仍为 1.0（0.5+0.25+0.5=1.25 → 归一化：0.4/0.2/0.4）
        assertEquals(1.0f, widths.sum(), 0.0001f)
        assertEquals(0.4f, widths[0], 0.0001f)
        assertEquals(0.2f, widths[1], 0.0001f)
        assertEquals(0.4f, widths[2], 0.0001f)
    }

    @Test
    fun spanKeyWidthOverrideIgnoredButOtherFieldsApply() {
        val rows = listOf(
            listOf(
                textKey("K1", "a", 0.5f, rowSpan = 2),
                textKey("K2", "b", 0.5f),
            ),
        )
        val snap = snapshot(
            overrides = mapOf(
                "K1" to KeyOverride(
                    geometry = KeyGeometryOverride(widthFactor = 2f),
                    label = "A",
                ),
            ),
        )
        val out = apply(rows, snap)
        assertEquals(0.5f, out[0][0].appearance.percentWidth, 0.0001f)
        assertEquals("A", (out[0][0].appearance as KeyDef.Appearance.Text).displayText)
    }

    @Test
    fun subLabelConvertsTextKeyToAltText() {
        val rows = listOf(listOf(textKey("K1", "a", 1f)))
        val snap = snapshot(
            overrides = mapOf("K1" to KeyOverride(subLabel = "小字")),
        )
        val out = apply(rows, snap)
        val appearance = out[0][0].appearance as KeyDef.Appearance.AltText
        assertEquals("a", appearance.displayText)
        assertEquals("小字", appearance.altText)
    }

    @Test
    fun imageAdmittedOnly() {
        val rows = listOf(
            listOf(textKey("K1", "a", 0.5f), textKey("K2", "b", 0.5f)),
        )
        val snap = snapshot(
            overrides = mapOf(
                "K1" to KeyOverride(image = "img_ok"),
                "K2" to KeyOverride(image = "img_missing"),
            ),
            imageAssets = mapOf("img_ok" to "sha"),
        )
        val out = apply(rows, snap)
        assertEquals("img_ok", out[0][0].appearance.skinImageAssetId)
        assertNull(out[0][1].appearance.skinImageAssetId)
    }

    @Test
    fun tierSizesApplyToAllTextKeysAndPerKeyWins() {
        val rows = listOf(
            listOf(
                textKey("K1", "a", 0.25f),
                textKey("K2", "，", 0.25f), // 符号档
                textKey("K3", "c", 0.25f),
                textKey("K4", "d", 0.25f),
            ),
        )
        val snap = snapshot(
            textSizes = mapOf(
                KeyTextRole.MAIN to 20f,
                KeyTextRole.SYMBOL to 14f,
            ),
            overrides = mapOf(
                "K4" to KeyOverride(textSizes = mapOf(KeyTextRole.MAIN to 24f)),
            ),
        )
        val out = apply(rows, snap)
        assertEquals(20f, out[0][0].appearance.skinTextSize)
        assertEquals(14f, out[0][1].appearance.skinTextSize) // 符号键走符号档
        assertEquals(20f, out[0][2].appearance.skinTextSize)
        assertEquals(24f, out[0][3].appearance.skinTextSize) // 逐键优先于皮肤级
    }

    @Test
    fun foregroundTextLayerColorFallbackAndImageLayerAdmission() {
        val rows = listOf(listOf(textKey("K1", "a", 1f)))
        val snap = snapshot(
            overrides = mapOf(
                "K1" to KeyOverride(
                    foregroundLayers = listOf(
                        KeyForegroundLayer(
                            type = "text", text = "角标",
                            colorRaw = "not-a-color",
                        ),
                        KeyForegroundLayer(type = "image", image = "img_badge"),
                        KeyForegroundLayer(type = "image", image = "img_missing"),
                    ),
                ),
            ),
            imageAssets = mapOf("img_badge" to "sha"),
        )
        val out = apply(rows, snap)
        val layers = out[0][0].appearance.skinForegroundLayers!!
        assertEquals(2, layers.size)
        assertEquals(13, layers[0].color) // 非法颜色回落副文字色（scheme.altText）
        assertEquals("img_badge", layers[1].imageAssetId)
    }

    @Test
    fun dynamicTextSnapshotWinsAndFallsBack() {
        val rows = listOf(
            listOf(textKey("K1", "a", 0.5f), textKey("K2", "b", 0.5f)),
        )
        val snap = snapshot(
            overrides = mapOf(
                "K1" to KeyOverride(
                    label = "静态",
                    dynamicText = DynamicTextSource.CANDIDATE_FIRST,
                ),
                "K2" to KeyOverride(
                    label = "静态",
                    dynamicText = DynamicTextSource.COMPOSING,
                ),
            ),
        )
        val out = apply(
            rows, snap,
            dynamicTexts = mapOf(DynamicTextSource.CANDIDATE_FIRST to "首选"),
        )
        assertEquals(
            "首选",
            (out[0][0].appearance as KeyDef.Appearance.Text).displayText,
        )
        assertEquals(
            "静态",
            (out[0][1].appearance as KeyDef.Appearance.Text).displayText,
        )
    }

    @Test
    fun iconTintRoleResolved() {
        val rows = listOf(listOf(textKey("K1", "a", 1f)))
        val snap = snapshot(
            keyStyles = mapOf(
                "gold" to mapOf(KeyStyleRole.ICON to "#FF9C824A"),
            ),
            overrides = mapOf("K1" to KeyOverride(styleRef = "gold")),
        )
        val out = apply(rows, snap)
        assertEquals(0xFF9C824A.toInt(), out[0][0].appearance.skinIconTintColor)
    }

    @Test
    fun geometryFieldsCarriedToAppearance() {
        val rows = listOf(listOf(textKey("K1", "a", 1f)))
        val snap = snapshot(
            overrides = mapOf(
                "K1" to KeyOverride(
                    geometry = KeyGeometryOverride(
                        heightFactor = 0.8f,
                        contentCenterX = 0.3f,
                        contentCenterY = 0.6f,
                        hInsetDp = 2f,
                        vInsetDp = 3f,
                    ),
                ),
            ),
        )
        val out = apply(rows, snap)
        val appearance = out[0][0].appearance
        assertEquals(0.8f, appearance.skinHeightFactor)
        assertEquals(0.3f, appearance.skinContentBiasX)
        assertEquals(0.6f, appearance.skinContentBiasY)
        assertEquals(2f, appearance.skinHInsetDp)
        assertEquals(3f, appearance.skinVInsetDp)
    }

    @Test
    fun noEffectSnapshotReturnsIdentity() {
        val rows = listOf(listOf(textKey("K1", "a", 1f)))
        val out = apply(rows, snapshot())
        assertSame(rows, out)
    }
}
