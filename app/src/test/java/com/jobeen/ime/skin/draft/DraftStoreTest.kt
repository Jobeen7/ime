package com.jobeen.ime.skin.draft

import com.jobeen.ime.base.util.DigestUtil
import com.jobeen.ime.data.keyboard.theme.KeyboardColors
import com.jobeen.ime.skin.model.GeometryField
import com.jobeen.ime.skin.model.SkinJson
import com.jobeen.ime.skin.model.StyleField
import com.jobeen.ime.skin.store.SkinLibraryLayout
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 草稿区（十四.5）与树编辑纯函数：恢复/漂移锚/未知节点保留。 */
class DraftStoreTest {

    private fun baseTree(id: String): JsonObject = buildJsonObject {
        put("formatVersion", 3)
        putJsonObject("meta") {
            put("id", id)
            put("name", "原名")
            put("version", "1.0")
        }
        putJsonObject("styles") {
            put("keyBackground", "#FF101010")
            putJsonObject("panel") { put("background", "#FF202020") }
            put("futureStyle", "keep-me")
        }
        putJsonObject("appearance") { put("cornerRadius", 8.0) }
        put("futureTopLevel", "keep-me-too")
    }

    private fun newStore(): Pair<DraftStore, SkinLibraryLayout> {
        val root = Files.createTempDirectory("jime-draft").toFile()
        val layout = SkinLibraryLayout(root).apply { ensureDirs() }
        return DraftStore(layout) to layout
    }

    @Test
    fun createLoadRoundTripAndSelfAnchoredBaseHash() = runBlocking {
        val (store, _) = newStore()
        val tree = baseTree("d1")
        val draft = store.create("d1", tree, DraftStore.SOURCE_PRESET, baseGeneration = 0)
        // 预设来源：baseHash = 初始草稿内容 hash（§5.1 回填口径）
        assertEquals(draft.contentHash, draft.meta.baseHash)
        val loaded = store.load("d1")
        assertEquals(draft.meta, loaded?.meta)
        assertTrue(SkinJson.deepEquals(tree, loaded!!.tree))
    }

    @Test
    fun existingSourceUsesGivenBaseHash() = runBlocking {
        val (store, _) = newStore()
        val sourceHash = DigestUtil.sha256Hex("source-bytes".encodeToByteArray())
        val draft = store.create(
            "d2", baseTree("d2"), DraftStore.SOURCE_EXISTING,
            baseGeneration = 3, baseHash = sourceHash,
        )
        assertEquals(sourceHash, draft.meta.baseHash)
        assertEquals(3, draft.meta.baseGeneration)
        assertNotEquals(draft.contentHash, draft.meta.baseHash)
    }

    @Test
    fun saveUpdatesContentAndDeleteRemoves() = runBlocking {
        val (store, layout) = newStore()
        store.create("d3", baseTree("d3"), DraftStore.SOURCE_BLANK, baseGeneration = 0)
        val edited = SkinTreeEditor.setName(baseTree("d3"), "新名")
        val saved = store.save("d3", edited)
        assertEquals("新名", SkinTreeEditor.nameOf(saved!!.tree))
        assertEquals(1, store.listMetas().size)
        store.delete("d3")
        assertNull(store.load("d3"))
        assertTrue(store.listMetas().isEmpty())
        assertTrue(!File(layout.draftsDir, "d3").exists())
    }

    @Test
    fun treeEditorPreservesUnknownNodes() {
        val tree = baseTree("d4")
        var edited = SkinTreeEditor.setColor(tree, StyleField.KEY_BACKGROUND, 0xFFABCDEF.toInt())
        edited = SkinTreeEditor.setColor(edited, StyleField.PANEL_BACKGROUND, 0xFF010203.toInt())
        edited = SkinTreeEditor.setGeometry(edited, GeometryField.BOTTOM_INSET, 20f)
        edited = SkinTreeEditor.setName(edited, "改名")
        assertEquals(0xFFABCDEF.toInt(), SkinTreeEditor.colorOf(edited, StyleField.KEY_BACKGROUND))
        assertEquals(0xFF010203.toInt(), SkinTreeEditor.colorOf(edited, StyleField.PANEL_BACKGROUND))
        assertEquals(20f, SkinTreeEditor.geometryOf(edited, GeometryField.BOTTOM_INSET))
        assertEquals(8f, SkinTreeEditor.geometryOf(edited, GeometryField.CORNER_RADIUS))
        assertEquals("改名", SkinTreeEditor.nameOf(edited))
        // 未知节点逐字保留
        assertEquals(
            "keep-me",
            ((edited["styles"] as JsonObject)["futureStyle"] as JsonPrimitive).content,
        )
        assertEquals("keep-me-too", (edited["futureTopLevel"] as JsonPrimitive).content)
        assertEquals("d4", SkinTreeEditor.skinIdOf(edited))
    }

    @Test
    fun backgroundSpecRoundTrip() {
        var tree = baseTree("d5")
        assertNull(SkinTreeEditor.backgroundOf(tree))
        tree = SkinTreeEditor.setBackground(
            tree,
            com.jobeen.ime.skin.model.BackgroundSpec("bg_main", 12f, 0.3f),
        )
        val spec = SkinTreeEditor.backgroundOf(tree)
        assertEquals("bg_main", spec?.assetId)
        assertEquals(12f, spec?.blurRadius)
        tree = SkinTreeEditor.setBackgroundParams(tree, blurRadius = 5f)
        assertEquals(5f, SkinTreeEditor.backgroundOf(tree)?.blurRadius)
        assertEquals(setOf("bg_main"), SkinTreeEditor.referencedAssetIds(tree))
        tree = SkinTreeEditor.setBackground(tree, null)
        assertNull(SkinTreeEditor.backgroundOf(tree))
    }

    @Test
    fun contrastWarningsFireOnLowContrastOnly() {
        val bad = KeyboardColors.ColorScheme(
            keyBackground = 0xFFFFFFFF.toInt(), keyPressed = 0, keyBorderStroke = 0,
            specialKeyBackground = 0xFF000000.toInt(), specialKeyPressed = 0,
            specialKeyBorderStroke = 0,
            accentKeyBackground = 0xFF000000.toInt(), accentKeyPressed = 0,
            accentKeyBorderStroke = 0,
            keyText = 0xFFEEEEEE.toInt(), specialKeyText = 0xFFFFFFFF.toInt(),
            accentKeyText = 0xFFFFFFFF.toInt(), altText = 0, background = 0,
            panel = KeyboardColors.ColorScheme.PanelColors(
                background = 0xFF000000.toInt(), toolbarText = 0xFFFFFFFF.toInt(),
                toolbarActived = 0, toolbarIcon = 0, candidateBackground = 0xFF000000.toInt(),
                candidateText = 0xFFFFFFFF.toInt(), candidateIndex = 0,
                candidateDivider = 0, toolbarPressed = 0,
            ),
            pinner = KeyboardColors.ColorScheme.PinnerColors(
                background = 0, textColor = 0, secondaryTextColor = 0,
            ),
        )
        val warnings = ContrastWarnings.check(bad)
        assertEquals(1, warnings.size)
        assertTrue(warnings[0].contains("按键文字"))
        assertTrue(
            ContrastWarnings.contrastRatio(0xFF000000.toInt(), 0xFFFFFFFF.toInt()) > 20.0,
        )
    }
}
