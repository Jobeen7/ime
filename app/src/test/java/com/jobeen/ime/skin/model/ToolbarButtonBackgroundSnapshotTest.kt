package com.jobeen.ime.skin.model

import com.jobeen.ime.data.keyboard.theme.KeyboardColors
import com.jobeen.ime.skin.store.SkinGenRef
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 工具栏钮背景图（审计 D 项）：校验 → 快照引用转换（描述＋sheet 引用）。 */
class ToolbarButtonBackgroundSnapshotTest {

    private val fallbacks = GeometryFallbacks(12f, 6f, 5f, 10f)
    private val ref = SkinGenRef("skin-1", "h".repeat(64), 1)
    private val shas = mapOf("toolbar_circle" to "c".repeat(64))
    private val exts = mapOf("toolbar_circle" to "png")

    private val defaultScheme = KeyboardColors.ColorScheme(
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

    private fun build(json: String): SkinSnapshot {
        val trimmed = json.trimEnd()
        require(trimmed.endsWith("}"))
        val withDark = if (json.contains("\"stylesDark\"")) json
        else trimmed.dropLast(1) + """, "stylesDark": {"keyBackground": "#FF222222"}}"""
        val tree = Json.parseToJsonElement(withDark) as JsonObject
        val result = SkinValidator.validate(tree, 157, fallbacks, assetKind = { null })
        assertTrue("expected Valid, got $result", result is SkinValidator.Result.Valid)
        val validated = (result as SkinValidator.Result.Valid).skin
        return SkinSnapshotBuilder.build(
            validated = validated,
            ref = ref,
            defaultScheme = defaultScheme,
            tier = MemoryTier.NORMAL,
            assetSha256 = { shas[it] },
            targetWidthPx = 1000,
            targetHeightPx = 400,
            assetExt = { exts[it] },
        )
    }

    private val peerBackground = """"toolbarButtonBackgroundStyle":
        {"buttonStyleType": "fileImage", "contentMode": "scaleAspectFit",
         "highlightImage": {"file": "toolbar_circle", "image": "IMG2"},
         "insets": {"bottom": 0, "left": 19, "right": 19, "top": 0},
         "normalImage": {"file": "toolbar_circle", "image": "IMG1"}}"""

    private val peerSprites = """"sprites":
        {"toolbar_circle":
          {"IMG1": {"rect": {"x": 0, "y": 0, "width": 200, "height": 200}},
           "IMG2": {"rect": {"x": 200, "y": 0, "width": 200, "height": 200}}}}"""

    @Test
    fun backgroundSnapshotCarriesDescriptorSheetRefAndSpriteMaps() {
        val snapshot = build(
            """{"formatVersion": 3, "styles": {"keyBackground": "#FFFFFFFF"},
               $peerBackground, $peerSprites}""",
        )
        val background = requireNotNull(snapshot.toolbar.buttonBackground)
        assertEquals(
            SkinImageDescriptor.File(
                normal = SkinImageRef("toolbar_circle", "IMG1"),
                highlight = SkinImageRef("toolbar_circle", "IMG2"),
                contentMode = ImageContentMode.SCALE_ASPECT_FIT,
                insets = SpriteInsets(left = 19, top = 0, right = 19, bottom = 0),
            ),
            background.descriptor,
        )
        assertEquals(
            mapOf("toolbar_circle" to SkinImageSheetRef("toolbar_circle", "c".repeat(64), "png")),
            background.sheets,
        )
        assertEquals(
            SpriteFrame(SpriteRect(200, 0, 200, 200)),
            snapshot.spriteMaps["toolbar_circle"]?.get("IMG2"),
        )
    }

    @Test
    fun normalSheetWithoutShaDropsWholeBackground() {
        val snapshot = build(
            """{"formatVersion": 3, "styles": {"keyBackground": "#FFFFFFFF"},
               "toolbarButtonBackgroundStyle":
                 {"buttonStyleType": "fileImage",
                  "normalImage": {"file": "ghost-sheet", "image": "IMG1"}}}""",
        )
        assertNull(snapshot.toolbar.buttonBackground)
    }

    @Test
    fun highlightSheetWithoutShaKeepsNormalOnly() {
        val snapshot = build(
            """{"formatVersion": 3, "styles": {"keyBackground": "#FFFFFFFF"},
               "toolbarButtonBackgroundStyle":
                 {"buttonStyleType": "fileImage",
                  "normalImage": {"file": "toolbar_circle", "image": "IMG1"},
                  "highlightImage": {"file": "ghost-sheet", "image": "IMG2"}}}""",
        )
        val background = requireNotNull(snapshot.toolbar.buttonBackground)
        val file = background.descriptor as SkinImageDescriptor.File
        assertEquals(SkinImageRef("toolbar_circle", "IMG1"), file.normal)
        assertNull(file.highlight)
        assertEquals(1, background.sheets.size)
    }

    @Test
    fun backgroundWithoutToolbarNodeStillCarried() {
        // 背景图独立于 toolbar 节点：仅定义背景图的皮肤，快照仍带背景
        // （Applier 据此返回 Applied，中央区／边缘走回落）。
        val snapshot = build(
            """{"formatVersion": 3, "styles": {"keyBackground": "#FFFFFFFF"},
               $peerBackground}""",
        )
        assertNotNull(snapshot.toolbar.buttonBackground)
        assertEquals(false, snapshot.toolbar.present)
    }
}
