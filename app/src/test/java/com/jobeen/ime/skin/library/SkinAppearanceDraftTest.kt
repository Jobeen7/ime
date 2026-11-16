// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.skin.library

import com.jobeen.ime.data.keyboard.theme.KeyboardColors
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 可视化皮肤编辑器纯模型测试：几何字段读写、草稿撤销栈、
 * keyMappings 键格式、预设配色板映射。
 */
class SkinAppearanceDraftTest {

    private fun skinTree(): JsonObject = buildJsonObject {
        putJsonObject("styles") {
            put("keyBackground", "#FFFFFFFF")
            put("keyText", "#FF1A1A1A")
        }
        putJsonObject("appearance") {
            put("cornerRadius", 10.0)
            put("keyHMargin", 6.0)
        }
        putJsonObject("keyboards") {
            putJsonObject("pinyin") { put("layoutId", "bin-pinyin") }
            putJsonObject("alphabetic") { put("layoutId", "bin-alphabetic") }
        }
    }

    private fun layoutTree(): JsonObject = buildJsonObject {
        putJsonObject("rows") {}
    }

    @Test
    fun `geometry read write roundtrip`() {
        val tree = skinTree()
        val field = SKIN_GEOMETRY_FIELDS.first { it.path == "appearance.cornerRadius" }
        assertEquals(10f, readGeometryValue(tree, field))
        val updated = writeGeometryValue(tree, field, 20f)
        assertEquals(20f, readGeometryValue(updated, field))
        // 原树不动。
        assertEquals(10f, readGeometryValue(tree, field))
        // 缺失字段读 null。
        val missing = SKIN_GEOMETRY_FIELDS.first { it.path == "geometry.keyRadiusDp" }
        assertNull(readGeometryValue(tree, missing))
        val created = writeGeometryValue(tree, missing, 8f)
        assertEquals(8f, readGeometryValue(created, missing))
    }

    @Test
    fun `style color read write roundtrip`() {
        val tree = skinTree()
        assertEquals("#FFFFFFFF", readStyleColor(tree, "styles", "keyBackground"))
        val updated = writeStyleColor(tree, "stylesDark", "panel.candidateText", "#FF00FF00")
        assertEquals("#FF00FF00", readStyleColor(updated, "stylesDark", "panel.candidateText"))
        assertNull(readStyleColor(tree, "stylesDark", "panel.candidateText"))
    }

    @Test
    fun `draft undo redo dirty`() {
        val draft = SkinAppearanceDraft("s1", skinTree(), mapOf("bin-pinyin" to layoutTree()))
        assertFalse(draft.dirty)
        assertFalse(draft.canUndo)
        draft.setStyleColor("styles", "keyBackground", "#FF000000")
        assertTrue(draft.dirty)
        assertTrue(draft.canUndo)
        assertFalse(draft.canRedo)
        assertEquals("#FF000000", readStyleColor(draft.skin, "styles", "keyBackground"))
        assertTrue(draft.undo())
        assertFalse(draft.dirty)
        assertEquals("#FFFFFFFF", readStyleColor(draft.skin, "styles", "keyBackground"))
        assertTrue(draft.canRedo)
        assertTrue(draft.redo())
        assertTrue(draft.dirty)
        assertEquals("#FF000000", readStyleColor(draft.skin, "styles", "keyBackground"))
        // 新改动清空重做栈。
        assertTrue(draft.undo())
        draft.setGeometry(SKIN_GEOMETRY_FIELDS[0], 12f)
        assertFalse(draft.canRedo)
    }

    @Test
    fun `draft history capped at 30`() {
        val draft = SkinAppearanceDraft("s1", skinTree(), mapOf("bin-pinyin" to layoutTree()))
        repeat(35) { i ->
            draft.setStyleColor("styles", "keyBackground", "#FF0000%02X".format(i))
        }
        assertEquals(30, draft.undoDepth)
        repeat(30) { assertTrue(draft.undo()) }
        assertFalse(draft.undo())
    }

    @Test
    fun `keyMappingId format matches converter`() {
        assertEquals("bin-pinyin:K-KEY_Q", keyMappingId("bin-pinyin", "KEY_Q"))
    }

    @Test
    fun `keyMapping field set and delete removes empty entry`() {
        val draft = SkinAppearanceDraft("s1", skinTree(), mapOf("bin-pinyin" to layoutTree()))
        val id = keyMappingId("bin-pinyin", "KEY_Q")
        draft.setKeyMappingField(id, "preeditTap", buildJsonObject { put("type", "text"); put("value", "q") })
        val entry = draft.keyMappingEntry(id)
        assertEquals("text", ((entry?.get("preeditTap") as? JsonObject)?.get("type") as? JsonPrimitive)?.content)
        draft.setKeyMappingField(id, "preeditTap", null)
        assertNull(draft.keyMappingEntry(id))
        assertFalse(draft.skin.containsKey("keyMappings"))
    }

    @Test
    fun `preset board maps all StyleField paths present in scheme`() {
        // KeyboardThemePresets 用 android.graphics.Color 初始化，纯 JVM
        // 单测触不到；此处直接以字面 ARGB 构造 ColorScheme（纯数据类）。
        val colors = KeyboardColors.ColorScheme(
            keyBackground = -0x1,
            keyPressed = -0x191919,
            keyBorderStroke = -0x262626,
            specialKeyBackground = -0x121212,
            specialKeyPressed = -0x232323,
            specialKeyBorderStroke = -0x262626,
            accentKeyBackground = -0x7f6f76,
            accentKeyPressed = -0x8f7f86,
            accentKeyBorderStroke = -0x7f6f76,
            keyText = -0xe5e5e6,
            specialKeyText = -0xe5e5e6,
            accentKeyText = -0x1,
            altText = -0x8a8a8b,
            background = -0x0b0b0c,
            panel = KeyboardColors.ColorScheme.PanelColors(
                background = -0x0b0b0c,
                toolbarText = -0x0a0a0b,
                toolbarActived = -0x7f6f76,
                toolbarIcon = -0xa0a0a1,
                candidateBackground = -0xe1e1e2,
                candidateText = -0x0a0a0b,
                candidateIndex = -0x8a8a8b,
                candidateDivider = -0x1fdfdf,
                toolbarPressed = -0xd9ddd9,
            ),
            pinner = KeyboardColors.ColorScheme.PinnerColors(
                background = -0x0b0b0c,
                textColor = -0x1,
                secondaryTextColor = -0x8a8a8b,
            ),
        )
        val entries = presetBoardEntries(colors)
        // 36 色字段中除 null 面外全部映射；至少覆盖顶层 16 项。
        val paths = entries.map { it.first }.toSet()
        assertTrue(paths.contains("keyBackground"))
        assertTrue(paths.contains("panel.candidateText"))
        assertTrue(paths.contains("pinner.background"))
        // 预设应用只写当前板，不动另一板。
        val draft = SkinAppearanceDraft("s1", skinTree(), mapOf("bin-pinyin" to layoutTree()))
        draft.applyPreset(colors, dark = false)
        assertEquals(
            "#%08X".format(colors.keyBackground),
            readStyleColor(draft.skin, "styles", "keyBackground"),
        )
        assertNull(readStyleColor(draft.skin, "stylesDark", "keyBackground"))
        assertTrue(draft.canUndo)
    }

    @Test
    fun `layout edit tracked and undoable`() {
        val draft = SkinAppearanceDraft("s1", skinTree(), mapOf("bin-pinyin" to layoutTree()))
        val newLayout = buildJsonObject { put("rows", JsonObject(emptyMap())); put("edited", true) }
        draft.setLayout("bin-pinyin", newLayout)
        assertEquals(setOf("bin-pinyin"), draft.dirtyLayoutIds)
        assertTrue(draft.undo())
        assertTrue(draft.dirtyLayoutIds.isEmpty())
    }

    @Test
    fun `resetKey restores original key and drops keyMapping entry`() {
        // 用真实行结构构造原树。
        val origKey = buildJsonObject {
            put("stableId", "KEY_Q")
            put("label", "Q")
            put("widthWeight", 1.0)
        }
        val origTree = buildJsonObject {
            put("rows", kotlinx.serialization.json.buildJsonArray {
                add(buildJsonObject {
                    put("keys", kotlinx.serialization.json.buildJsonArray { add(origKey) })
                })
            })
        }
        val draft = SkinAppearanceDraft("s1", skinTree(), mapOf("bin-pinyin" to origTree))
        // 改标签＋加 keyMappings 覆写，再重置。
        val edited = com.jobeen.ime.skin.layout.LayoutEditorModel
            .setLabel(origTree, "KEY_Q", "W")!!
        draft.setLayout("bin-pinyin", edited)
        draft.setKeyMappingField(
            keyMappingId("bin-pinyin", "KEY_Q"),
            "preeditTap",
            buildJsonObject { put("type", "text"); put("value", "q") },
        )
        assertTrue(draft.dirty)
        draft.resetKey("bin-pinyin", "KEY_Q")
        val key = com.jobeen.ime.skin.layout.LayoutEditorModel
            .keyByStableId(draft.layouts.getValue("bin-pinyin"), "KEY_Q")
        assertEquals("Q", (key?.get("label") as? JsonPrimitive)?.content)
        assertNull(draft.keyMappingEntry(keyMappingId("bin-pinyin", "KEY_Q")))
        assertFalse(draft.dirty)
        assertTrue(draft.canUndo)
    }
}
