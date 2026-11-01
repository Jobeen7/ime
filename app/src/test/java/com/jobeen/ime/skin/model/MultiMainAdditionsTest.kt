package com.jobeen.ime.skin.model

import com.jobeen.ime.input.keyboard.window.KeyboardTypeState
import com.jobeen.ime.input.keyboard.window.shouldPersistType
import com.jobeen.ime.skin.layout.LandscapePatch
import com.jobeen.ime.skin.layout.LayoutKeyModel
import com.jobeen.ime.skin.layout.LayoutModel
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 多主类型补充：临时类型生命周期、工具类型表条目、滑动切类型、横屏补丁展开。 */
class MultiMainAdditionsTest {

    private fun key(id: String, label: String) = LayoutKeyModel(
        stableId = id, label = label, widthWeight = 1.0, rowSpan = 1,
        alignSpanEnd = false, tap = null, swipeUp = null, swipeDown = null,
        longPress = null,
    )

    @Test
    fun tempTypeNeverPersistedAndSkippedInLastChain() {
        assertFalse(shouldPersistType(KeyboardTypes.TEMP_PINYIN))
        assertTrue(shouldPersistType(KeyboardTypes.PINYIN))
        val state = KeyboardTypeState()
        state.switchTo(KeyboardTypes.PINYIN)
        state.switchTo(KeyboardTypes.TEMP_PINYIN)
        assertEquals(KeyboardTypes.PINYIN, state.primary)
        // 从临时切走：last 保持临时前的 pinyin，不指向临时板。
        state.switchTo(KeyboardTypes.SYMBOLIC)
        assertEquals(KeyboardTypes.PINYIN, state.last)
        assertEquals(KeyboardTypes.SYMBOLIC, state.current)
    }

    @Test
    fun tempTypeClearsToPrimaryOnHide() {
        val state = KeyboardTypeState()
        state.switchTo(KeyboardTypes.PINYIN)
        state.switchTo(KeyboardTypes.TEMP_PINYIN)
        assertTrue(state.clearTemp(KeyboardTypes.PINYIN))
        assertEquals(KeyboardTypes.PINYIN, state.current)
        assertFalse(state.clearTemp(KeyboardTypes.PINYIN))
    }

    @Test
    fun toolTypesAreTableEntries() {
        assertEquals(
            setOf("layoutSwitch", "returnPath", "floatPanel"),
            KeyboardTypes.TOOL,
        )
        assertTrue(KeyboardTypes.isTool("layoutSwitch"))
        assertFalse(KeyboardTypes.isTool("pinyin"))
        // keyboards 表接受任意非空键（含工具类型），见 SkinJson.parseKeyboards。
        val table = SkinJson.parseKeyboards(
            Json.parseToJsonElement(
                """{"keyboards": {
                    "layoutSwitch": "switcher",
                    "returnPath": {"layoutId": "back"},
                    "floatPanel": "float"
                }}""",
            ) as JsonObject,
        )
        assertEquals("switcher", table["layoutSwitch"]?.layoutId)
        assertEquals("back", table["returnPath"]?.layoutId)
        assertEquals("float", table["floatPanel"]?.layoutId)
    }

    @Test
    fun swipeKeyboardFieldsSynthesizeTypeSwitch() {
        val parsed = SkinKeyMappingsParser.parse(
            Json.parseToJsonElement(
                """{"symbol_key": {
                    "swipeUpKeyboard": "symbolic",
                    "swipeDownKeyboard": "emojis",
                    "showSwipeIndicators": true
                }}""",
            ) as JsonObject,
            isOverridable = { true },
            isAnchor = { false },
        )
        val override = parsed.overrides["symbol_key"]
        assertEquals(SkinAction.KeyboardType("symbolic"), override?.swipeUp)
        assertEquals(SkinAction.KeyboardType("emojis"), override?.swipeDown)
        assertTrue(override?.showSwipeIndicators == true)
    }

    @Test
    fun explicitSwipeWinsOverSwipeKeyboardField() {
        val parsed = SkinKeyMappingsParser.parse(
            Json.parseToJsonElement(
                """{"symbol_key": {
                    "swipeUp": {"type": "keyboardType", "value": "alphabetic"},
                    "swipeUpKeyboard": "symbolic"
                }}""",
            ) as JsonObject,
            isOverridable = { true },
            isAnchor = { false },
        )
        assertEquals(
            SkinAction.KeyboardType("alphabetic"),
            parsed.overrides["symbol_key"]?.swipeUp,
        )
    }

    @Test
    fun blankSwipeKeyboardFieldIsRejected() {
        val parsed = SkinKeyMappingsParser.parse(
            Json.parseToJsonElement(
                """{"symbol_key": {"swipeUpKeyboard": "  "}}""",
            ) as JsonObject,
            isOverridable = { true },
            isAnchor = { false },
        )
        assertTrue(parsed.overrides["symbol_key"] == null)
        assertTrue(parsed.rejections.isNotEmpty())
    }

    @Test
    fun landscapePatchExpandsOntoPortrait() {
        val portrait = LayoutModel(
            layoutId = "t9", name = "T9",
            rows = listOf(
                listOf(key("a", "a"), key("b", "b")),
                listOf(key("c", "c")),
            ),
            heightPercent = 55.0,
        )
        val patch = LayoutModel(
            layoutId = "t9-land", name = "patch",
            rows = listOf(
                listOf(key("b", "B-wide")),
                listOf(key("c", "c"), key("d", "d")),
            ),
            heightPercent = 48.0,
        )
        val expanded = LandscapePatch.expand(portrait, patch)
        assertEquals("t9", expanded.layoutId)
        assertEquals(48.0, expanded.heightPercent)
        // 行 0：b 被替换，a 保留；行 1：c 保留，d 追加。
        assertEquals(listOf("a", "B-wide"), expanded.rows[0].map { it.label })
        assertEquals(listOf("c", "d"), expanded.rows[1].map { it.label })
        // 补丁缺行时竖屏行原样保留。
        val partial = LandscapePatch.expand(
            portrait,
            LayoutModel("p", "p", listOf(listOf(key("a", "A")))),
        )
        assertEquals(listOf("A", "b"), partial.rows[0].map { it.label })
        assertEquals(listOf("c"), partial.rows[1].map { it.label })
        assertEquals(55.0, partial.heightPercent)
    }
}
