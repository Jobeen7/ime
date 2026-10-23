package com.jobeen.ime.skin.migrate

import com.jobeen.ime.data.keyboard.theme.KeyboardColors
import com.jobeen.ime.data.keyboard.theme.KeyboardTheme
import com.jobeen.ime.skin.model.GeometryFallbacks
import com.jobeen.ime.skin.model.GeometryField
import com.jobeen.ime.skin.model.SkinJson
import com.jobeen.ime.skin.model.SkinValidator
import com.jobeen.ime.skin.model.StyleField
import com.jobeen.ime.skin.store.ImportTransaction
import com.jobeen.ime.skin.store.SkinLibraryLayout
import com.jobeen.ime.skin.store.SkinLibraryStore
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 旧主题另存（口径甲）与预设树的 presence 语义（详设 §3.3）。 */
class LegacySkinSaverTest {

    private val scheme = KeyboardColors.ColorScheme(
        keyBackground = 0xFF101010.toInt(), keyPressed = 2, keyBorderStroke = 3,
        specialKeyBackground = 4, specialKeyPressed = 5, specialKeyBorderStroke = 6,
        accentKeyBackground = 7, accentKeyPressed = 8, accentKeyBorderStroke = 9,
        keyText = 10, specialKeyText = 11, accentKeyText = 12, altText = 13,
        background = 14,
        toastBackground = 15, toastText = 16,
        panel = KeyboardColors.ColorScheme.PanelColors(
            background = 20, toolbarText = 21, toolbarActived = 22, toolbarIcon = 23,
            candidateBackground = 24, candidateText = 25, candidateIndex = 26,
            candidateDivider = 27, toolbarPressed = 28,
        ),
        pinner = KeyboardColors.ColorScheme.PinnerColors(
            background = 30, textColor = 31, secondaryTextColor = 32,
        ),
    )

    @Test
    fun saveLegacyWritesPreferencesGeometryAndFullStyles() = runBlocking {
        val root = Files.createTempDirectory("jime-legacy").toFile()
        val layout = SkinLibraryLayout(root).apply { ensureDirs() }
        val store = SkinLibraryStore(layout)
        val saver = LegacySkinSaver(ImportTransaction(layout, store), newId = { "saved-1" })
        val prefs = GeometryFallbacks(9f, 3f, 4f, 12f)

        val result = saver.save(KeyboardTheme("old-id", "旧主题", scheme), prefs)
        assertEquals(LegacySkinSaver.Result.Saved("saved-1"), result)

        val index = (store.readIndex() as SkinLibraryStore.ReadResult.Ok).value
        assertEquals(listOf("saved-1"), index.skins.map { it.id })
        val genDir = layout.fileFromRelative(index.skins[0].dir)
        val tree = SkinJson.parseObject(File(genDir, "skin.json").readText())!!
        val validated = SkinValidator.validate(
            tree = tree,
            currentVersionCode = 143,
            fallbacks = GeometryFallbacks(1f, 1f, 1f, 1f),
            assetKind = { null },
        )
        assertTrue(validated is SkinValidator.Result.Valid)
        validated as SkinValidator.Result.Valid
        // 口径甲：四几何 presence 全指定、值 = 偏好现值（外观零变化）
        assertEquals(GeometryField.entries.toSet(), validated.skin.appearance.presence)
        assertEquals(
            mapOf(
                GeometryField.CORNER_RADIUS to 9f,
                GeometryField.KEY_H_MARGIN to 3f,
                GeometryField.KEY_V_MARGIN to 4f,
                GeometryField.BOTTOM_INSET to 12f,
            ),
            validated.skin.appearance.effectiveGeometry,
        )
        // 32 叶子全写实：样式回读等于原方案
        val restored = com.jobeen.ime.skin.model.SkinSnapshotBuilder.build(
            validated = validated.skin,
            ref = com.jobeen.ime.skin.store.SkinGenRef("saved-1", "x", 1),
            defaultScheme = scheme.copy(keyBackground = 0),
            tier = com.jobeen.ime.skin.model.MemoryTier.NORMAL,
            targetWidthPx = 100,
            targetHeightPx = 50,
            assetSha256 = { null },
        )
        // 配色 28+2 叶子逐项一致；几何取皮肤 appearance（口径甲值），非源主题默认
        assertEquals(scheme.copy(cornerRadius = 9f), restored.colorScheme)
        assertEquals(StyleField.entries.size, 28)
        assertNull(tree["layoutId"])
        assertNull(tree["keyMappings"])
    }

    @Test
    fun presetTreeHasNoGeometryPresence() {
        val tree = SkinTreeBuilder.buildTree("p1", "预设", scheme, geometry = null)
        assertNull(tree["appearance"])
        val validated = SkinValidator.validate(
            tree = tree,
            currentVersionCode = 143,
            fallbacks = GeometryFallbacks(8f, 2f, 2f, 6f),
            assetKind = { null },
        )
        assertTrue(validated is SkinValidator.Result.Valid)
        validated as SkinValidator.Result.Valid
        assertTrue(validated.skin.appearance.presence.isEmpty())
        assertEquals(8f, validated.skin.appearance.effectiveGeometry[GeometryField.CORNER_RADIUS])
    }
}
