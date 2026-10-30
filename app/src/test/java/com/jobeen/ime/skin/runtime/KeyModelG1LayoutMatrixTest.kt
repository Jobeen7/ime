package com.jobeen.ime.skin.runtime

import com.jobeen.ime.data.keyboard.theme.KeyboardColors
import com.jobeen.ime.input.keyboard.key.KeyDef
import com.jobeen.ime.input.keyboard.key.KeyIds
import com.jobeen.ime.input.keyboard.key.KeyboardAction
import com.jobeen.ime.skin.layout.LayoutModel
import com.jobeen.ime.skin.model.AssetKind
import com.jobeen.ime.skin.model.GeometryFallbacks
import com.jobeen.ime.skin.model.KeyForegroundLayer
import com.jobeen.ime.skin.model.KeyGeometryOverride
import com.jobeen.ime.skin.model.KeyOverride
import com.jobeen.ime.skin.model.KeyTextRole
import com.jobeen.ime.skin.model.SkinAction
import com.jobeen.ime.skin.model.SkinSnapshot
import com.jobeen.ime.skin.model.SkinValidator
import com.jobeen.ime.skin.model.SoundBindings
import com.jobeen.ime.skin.model.WhitelistAction
import com.jobeen.ime.skin.store.SkinGenRef
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * G1 第三任补齐测试：能力 × 布局覆盖矩阵。
 *
 * 装配器本身与布局无关（只消费 KeyDef 行），但各布局的出厂外观
 * 类型不同（九宫格 AltText、26 键 Text、十五键 Text、数字 Text、
 * 自定义布局坐标 ID + 跨行键），本测试按外观类型逐一断言 G1 字段
 * 落点；校验器侧断言坐标 ID 放宽口径（导入/快照工厂接线点）。
 * 渲染层（KeyView 像素）不在单测内，属真机验证项。
 */
class KeyModelG1LayoutMatrixTest {

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
        keyStyles = emptyMap(),
        textSizes = textSizes,
        imageAssets = imageAssets,
        soundBindings = SoundBindings.EMPTY,
    )

    private fun textKey(id: String, text: String, width: Float, rowSpan: Int = 1) =
        KeyDef(
            appearance = KeyDef.Appearance.Text(
                displayText = text, textSize = 18f, percentWidth = width, rowSpan = rowSpan,
            ),
            behaviors = setOf(KeyDef.Behavior.Press(KeyboardAction.KeySequenceAction(text))),
            id = id,
        )

    private fun altTextKey(id: String, text: String, alt: String, width: Float) =
        KeyDef(
            appearance = KeyDef.Appearance.AltText(
                displayText = text, altText = alt, textSize = 18f, percentWidth = width,
            ),
            behaviors = setOf(KeyDef.Behavior.Press(KeyboardAction.KeySequenceAction(text))),
            id = id,
        )

    @Test
    fun logicalIdsFromTreeSkipsMalformedEntries() {
        val tree = Json.parseToJsonElement(
            """
            {"layoutId": "demo-layout", "rows": [
              {"keys": [{"stableId": "a"}, {"stableId": "b"}, {"noId": true}, "junk"]},
              {"keys": [{"stableId": ""}]},
              {"noKeys": true},
              "junk-row"
            ]}
            """.trimIndent(),
        ) as JsonObject
        assertEquals(
            setOf("demo-layout:K-a", "demo-layout:K-b"),
            LayoutModel.logicalIdsFromTree(tree, "demo-layout"),
        )
        assertTrue(LayoutModel.logicalIdsFromTree(JsonObject(emptyMap()), "x").isEmpty())
    }

    @Test
    fun validatorCoordinateOverrideUnifiedPredicate() {
        val layoutTree = Json.parseToJsonElement(
            """{"layoutId": "demo-layout", "rows": [{"keys": [{"stableId": "a"}]}]}""",
        ) as JsonObject
        val coordIds = LayoutModel.logicalIdsFromTree(layoutTree, "demo-layout")
        val fallbacks = GeometryFallbacks(
            cornerRadius = 12f, keyHMargin = 6f, keyVMargin = 5f, bottomInsetDp = 10f,
        )
        val skinJson = """
            {
              "formatVersion": 3,
              "meta": {"id": "g1-matrix", "name": "G1", "author": "", "version": "1.0"},
              "styles": {"keyBackground": "#FFFFFFFF", "keyText": "#FF141414"},
              "stylesDark": {"keyBackground": "#FF222222", "keyText": "#FFFFFFFF"},
              "keyMappings": {
                "demo-layout:K-a": {"image": "img_key", "tap": {"type": "text", "value": "a"}},
                "KEY_Q": {"tap": {"type": "text", "value": "q"}}
              }
            }
        """.trimIndent()
        val tree = Json.parseToJsonElement(skinJson) as JsonObject
        // 集成统一口径（并集语义）：校验接纳 = 调用方判定 OR 坐标格式
        // 解析（`{layoutId}:K-<stableId>`）。单键模型流原先的“缺省
        // 严格”半段与 参考流的命名空间接纳取并集后不再成立：
        // 格式合法的坐标 ID 在缺省口径下也被接纳（装配期未命中键
        // 的覆写自然不生效）；格式非法的坐标仍按未知键剔掉。
        val strict = SkinValidator.validate(
            tree, 157, fallbacks, { if (it == "img_key") AssetKind.IMAGE else null },
        ) as SkinValidator.Result.Valid
        assertEquals("img_key", strict.skin.keyOverrides.getValue("demo-layout:K-a").image)
        assertTrue(strict.skin.keyOverrides.containsKey("KEY_Q"))
        // 放宽口径（导入端 layoutTrees / 工厂端 layoutCoordIds 的口径）：保留
        val relaxed = SkinValidator.validate(
            tree, 157, fallbacks, { if (it == "img_key") AssetKind.IMAGE else null },
            isOverridable = { it in coordIds || KeyIds.isOverridable(it) },
        ) as SkinValidator.Result.Valid
        assertEquals("img_key", relaxed.skin.keyOverrides.getValue("demo-layout:K-a").image)
        assertTrue(relaxed.skin.keyOverrides.containsKey("KEY_Q"))
        // 格式非法（无 :K- 标记）的坐标仍拒绝
        val malformedSkin = skinJson.replace("demo-layout:K-a", "demo-layout:X-a")
        val malformedTree = Json.parseToJsonElement(malformedSkin) as JsonObject
        val malformed = SkinValidator.validate(
            malformedTree, 157, fallbacks, { if (it == "img_key") AssetKind.IMAGE else null },
        ) as SkinValidator.Result.Valid
        assertFalse(malformed.skin.keyOverrides.containsKey("demo-layout:X-a"))
        assertTrue(malformed.issues.any { it.path == "keyMappings.demo-layout:X-a" })
    }

    @Test
    fun t9AltTextSubLabelAndSubTier() {
        val rows = listOf(
            listOf(altTextKey("T9_1", "1", "，", 0.33f)),
        )
        val out = KeyOverrideApplier.apply(
            "T9", rows,
            snapshot(
                overrides = mapOf(
                    "T9_1" to KeyOverride(subLabel = "句号"),
                ),
                textSizes = mapOf(KeyTextRole.SUB to 12f),
            ),
            emptyMap(),
        )
        val appearance = out[0][0].appearance as KeyDef.Appearance.AltText
        assertEquals("句号", appearance.altText)
        assertEquals(12f, appearance.skinAltTextSize)
    }

    @Test
    fun qwertyImageSwipeAndWidthRenormPreservesRowSum() {
        val rows = listOf(
            listOf(
                textKey("KEY_Q", "q", 0.5f),
                textKey("KEY_W", "w", 0.5f),
            ),
        )
        val out = KeyOverrideApplier.apply(
            "Qwerty", rows,
            snapshot(
                overrides = mapOf(
                    "KEY_Q" to KeyOverride(
                        image = "img_key",
                        geometry = KeyGeometryOverride(widthFactor = 1.5f),
                    ),
                    "KEY_W" to KeyOverride(
                        swipeLeft = SkinAction.Command(WhitelistAction.CURSOR_LEFT),
                    ),
                ),
                imageAssets = mapOf("img_key" to "sha"),
            ),
            emptyMap(),
        )
        assertEquals("img_key", out[0][0].appearance.skinImageAssetId)
        val swipe = out[0][1].behaviors.filterIsInstance<KeyDef.Behavior.Swipe>().single()
        assertEquals(KeyboardAction.CursorMoveAction(-1), swipe.leftAction)
        // 行宽和不变：0.5 + 0.5
        val sum = out[0].sumOf { it.appearance.percentWidth.toDouble() }
        assertEquals(1.0, sum, 0.001)
        assertTrue(out[0][0].appearance.percentWidth > 0.5f)
    }

    @Test
    fun fifteenAndNumberSymbolTierAndGeometry() {
        val rows = listOf(
            listOf(
                textKey("KEY_PERIOD", "。", 0.5f),
                textKey("NUM_1", "1", 0.5f),
            ),
        )
        val out = KeyOverrideApplier.apply(
            "T15/Number", rows,
            snapshot(
                overrides = mapOf(
                    "NUM_1" to KeyOverride(
                        geometry = KeyGeometryOverride(
                            contentCenterX = 0.3f, contentCenterY = 0.6f,
                            hInsetDp = 2f, vInsetDp = 1f, heightFactor = 0.8f,
                        ),
                        foregroundLayers = listOf(
                            KeyForegroundLayer(type = "text", text = "角", biasX = 0.85f),
                        ),
                    ),
                ),
                textSizes = mapOf(KeyTextRole.SYMBOL to 14f, KeyTextRole.MAIN to 20f),
            ),
            emptyMap(),
        )
        // 标点单字符走符号档，数字走主档
        assertEquals(14f, out[0][0].appearance.skinTextSize)
        assertEquals(20f, out[0][1].appearance.skinTextSize)
        assertEquals(0.3f, out[0][1].appearance.skinContentBiasX)
        assertEquals(0.6f, out[0][1].appearance.skinContentBiasY)
        assertEquals(2f, out[0][1].appearance.skinHInsetDp)
        assertEquals(0.8f, out[0][1].appearance.skinHeightFactor)
        assertEquals(1, out[0][1].appearance.skinForegroundLayers!!.size)
    }

    @Test
    fun customLayoutsCoordinateIdsAndSpanWidthIgnored() {
        // 11/14/17/18 键自定义布局：同一装配链，键 ID 均为坐标 ID；
        // 跨行键（rowSpan=2）的宽覆写不生效，其余维照常生效。
        for (layoutId in listOf("layout-11", "layout-14", "layout-17", "layout-18")) {
            val spanId = "$layoutId:K-span"
            val normalId = "$layoutId:K-n"
            val rows = listOf(
                listOf(
                    textKey(spanId, "X", 0.6f, rowSpan = 2),
                    textKey(normalId, "Y", 0.4f),
                ),
            )
            val out = KeyOverrideApplier.apply(
                "custom:$layoutId", rows,
                snapshot(
                    overrides = mapOf(
                        spanId to KeyOverride(
                            geometry = KeyGeometryOverride(widthFactor = 2f, heightFactor = 0.7f),
                            subLabel = "副",
                        ),
                        normalId to KeyOverride(subLabel = "副2"),
                    ),
                ),
                emptyMap(),
            )
            assertEquals(0.6f, out[0][0].appearance.percentWidth)
            assertEquals(0.7f, out[0][0].appearance.skinHeightFactor)
            assertEquals("副2", (out[0][1].appearance as KeyDef.Appearance.AltText).altText)
        }
    }
}
