package com.jobeen.ime.skin.model

import com.jobeen.ime.input.keyboard.window.KeyboardTypeState
import com.jobeen.ime.input.keyboard.window.shouldPersistType
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





}
