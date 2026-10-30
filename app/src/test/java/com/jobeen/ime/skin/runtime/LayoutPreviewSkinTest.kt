package com.jobeen.ime.skin.runtime

import com.jobeen.ime.data.keyboard.theme.KeyboardColors
import com.jobeen.ime.input.keyboard.key.KeyDef
import com.jobeen.ime.skin.layout.LayoutKeyDefBuilder
import com.jobeen.ime.skin.layout.LayoutModel
import com.jobeen.ime.skin.layout.LayoutKeyModel
import com.jobeen.ime.skin.model.GeometryFallbacks
import com.jobeen.ime.skin.model.KeyTextRole
import com.jobeen.ime.skin.model.SkinAction
import com.jobeen.ime.skin.model.SkinSnapshot
import com.jobeen.ime.skin.store.SkinLibraryLayout
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 布局编辑器预览皮肤上下文（G1 第 5 项）：预览快照构建 +
 * 坐标 ID 覆写经运行时链路解析 + 自定义键样式在预览结构中可见。
 */
class LayoutPreviewSkinTest {

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

    private fun shaHex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }

    @Test
    fun previewSnapshotResolvesCoordinateOverrideAndKeyStyle() {
        val root = Files.createTempDirectory("preview-skin").toFile()
        val genDir = File(root, "skins/skin-1/gen-1").apply { mkdirs() }
        val skinJson = """
            {
              "formatVersion": 3,
              "meta": {"id": "skin-1", "name": "预览皮", "author": "", "version": "1.0"},
              "styles": {"keyBackground": "#FFFFFFFF", "keyText": "#FF141414"},
              "stylesDark": {"keyBackground": "#FF222222", "keyText": "#FFFFFFFF"},
              "keyStyles": {"gold": {"face": "#FFFF0000", "icon": "#FF9C824A"}},
              "textSizes": {"main": 22},
              "keyMappings": {
                "demo-layout:K-s1": {"style": "gold", "label": "改"}
              }
            }
        """.trimIndent()
        File(genDir, "skin.json").writeText(skinJson)
        val manifest = """
            {"formatVersion": 3, "contents": [
              {"type": "skin", "id": "skin-1", "path": "skin.json",
               "sha256": "${shaHex(skinJson.toByteArray())}"}
            ]}
        """.trimIndent()
        File(genDir, "manifest.json").writeText(manifest)

        val entry = LayoutPreviewSkin.Entry(
            id = "skin-1", name = "预览皮", dir = "skins/skin-1/gen-1",
            hash = shaHex(skinJson.toByteArray()), generation = 1,
        )
        val snapshot: SkinSnapshot? = LayoutPreviewSkin.buildSnapshot(
            layout = SkinLibraryLayout(root),
            entry = entry,
            layoutKeyIds = setOf("demo-layout:K-s1"),
            defaultScheme = scheme,
            currentVersionCode = 157,
            fallbacks = GeometryFallbacks(12f, 6f, 5f, 10f),
        )
        assertNotNull(snapshot)
        assertEquals(mapOf(KeyTextRole.MAIN to 22f), snapshot!!.textSizes)
        // 坐标 ID 覆写经放宽判定进入快照（内置登记表本会以 UNKNOWN_ID 剔除）
        assertTrue(snapshot.keyOverrides.containsKey("demo-layout:K-s1"))

        // 自定义布局结构过预览装配链：样式解析 + 逐键覆写 + 字号档全部可见
        val model = LayoutModel(
            layoutId = "demo-layout",
            name = "演示",
            rows = listOf(
                listOf(
                    LayoutKeyModel(
                        stableId = "s1", label = "原", widthWeight = 1.0,
                        rowSpan = 1, alignSpanEnd = false,
                        tap = SkinAction.Text("a"),
                        swipeUp = null, swipeDown = null, longPress = null,
                    ),
                ),
            ),
        )
        val built = LayoutKeyDefBuilder.build(model, emptyMap())
        assertEquals(18f, (built[0][0].appearance as KeyDef.Appearance.Text).textSize)
        val previewed = KeyOverrideApplier.apply(
            "custom:demo-layout", built, snapshot, emptyMap(),
        )
        val appearance = previewed[0][0].appearance as KeyDef.Appearance.Text
        assertEquals("改", appearance.displayText)
        assertEquals(0xFFFF0000.toInt(), appearance.skinFaceColor)
        assertEquals(0xFF9C824A.toInt(), appearance.skinIconTintColor)
        assertEquals(22f, appearance.skinTextSize)
    }

    @Test
    fun previewSnapshotRejectedSkinReturnsNull() {
        val root = Files.createTempDirectory("preview-skin-bad").toFile()
        val genDir = File(root, "skins/skin-9/gen-1").apply { mkdirs() }
        // 无任何配色 → 校验整包拒绝（no_color_scheme）
        File(genDir, "skin.json").writeText(
            """{"formatVersion": 3, "meta": {"name": "坏皮"}}""",
        )
        File(genDir, "manifest.json").writeText(
            """{"formatVersion": 3, "contents": []}""",
        )
        val snapshot = LayoutPreviewSkin.buildSnapshot(
            layout = SkinLibraryLayout(root),
            entry = LayoutPreviewSkin.Entry(
                id = "skin-9", name = "坏皮", dir = "skins/skin-9/gen-1",
                hash = "h", generation = 1,
            ),
            layoutKeyIds = emptySet(),
            defaultScheme = scheme,
            currentVersionCode = 157,
            fallbacks = GeometryFallbacks(12f, 6f, 5f, 10f),
        )
        assertNull(snapshot)
    }
}
