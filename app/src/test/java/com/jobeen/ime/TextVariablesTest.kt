// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime

import android.view.inputmethod.EditorInfo
import com.jobeen.ime.input.keyboard.window.ReturnKeyTypeText
import com.jobeen.ime.skin.model.DynamicTextSource
import com.jobeen.ime.skin.model.OptionLabelResolver
import com.jobeen.ime.skin.model.OptionLabelResolver.Switch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 文本变量（重点候选 8-8）：新来源解析、选项标签解析、回车键类型映射。 */
class TextVariablesTest {

    @Test
    fun fixedSources_parse() {
        assertEquals(DynamicTextSource.SCHEME_NAME, DynamicTextSource.byJsonName("schemeName"))
        assertEquals(
            DynamicTextSource.RETURN_KEY_TYPE, DynamicTextSource.byJsonName("returnKeyType"),
        )
        assertEquals(
            DynamicTextSource.CANDIDATE_COMMENT, DynamicTextSource.byJsonName("candidateComment"),
        )
        assertEquals(DynamicTextSource.CANDIDATE_FIRST, DynamicTextSource.byJsonName("candidateFirst"))
        assertEquals(DynamicTextSource.COMPOSING, DynamicTextSource.byJsonName("composing"))
    }

    @Test
    fun optionLabelKeys_parse() {
        assertEquals(
            DynamicTextSource.OptionLabel("ascii_mode", false),
            DynamicTextSource.byJsonName("optionLabel\$ascii_mode"),
        )
        assertEquals(
            DynamicTextSource.OptionLabel("ascii_mode", true),
            DynamicTextSource.byJsonName("optionLabelAbbreviated\$ascii_mode"),
        )
        assertEquals(
            "optionLabel\$ascii_mode",
            (DynamicTextSource.byJsonName("optionLabel\$ascii_mode") as DynamicTextSource.OptionLabel).jsonName,
        )
        assertNull(DynamicTextSource.byJsonName("optionLabel\$"))
        assertNull(DynamicTextSource.byJsonName("optionLabelAbbreviated\$"))
        assertNull(DynamicTextSource.byJsonName("optionLabel"))
        assertNull(DynamicTextSource.byJsonName("clipboard"))
    }

    @Test
    fun optionLabel_resolve() {
        val switches = listOf(
            Switch("ascii_mode", listOf("ascii_mode"), listOf("中文", "英文")),
            Switch("full_shape", listOf("full_shape"), listOf("半角", "全角")),
            Switch("multi", listOf("a", "b"), listOf("甲", "乙")),
            Switch("single_state", listOf("x"), listOf("唯一")),
        )
        val off = mapOf("ascii_mode" to false, "full_shape" to true)
        assertEquals(
            "中文",
            OptionLabelResolver.resolve(
                switches, off, DynamicTextSource.OptionLabel("ascii_mode", false),
            ),
        )
        assertEquals(
            "全",
            OptionLabelResolver.resolve(
                switches, off, DynamicTextSource.OptionLabel("full_shape", true),
            ),
        )
        assertEquals(
            "英文",
            OptionLabelResolver.resolve(
                switches, mapOf("ascii_mode" to true),
                DynamicTextSource.OptionLabel("ascii_mode", false),
            ),
        )
        assertNull(
            OptionLabelResolver.resolve(
                switches, off, DynamicTextSource.OptionLabel("multi", false),
            ),
        )
        assertNull(
            OptionLabelResolver.resolve(
                switches, off, DynamicTextSource.OptionLabel("single_state", false),
            ),
        )
        assertNull(
            OptionLabelResolver.resolve(
                switches, off, DynamicTextSource.OptionLabel("no_such", false),
            ),
        )
        assertNull(
            OptionLabelResolver.resolve(
                switches, emptyMap(), DynamicTextSource.OptionLabel("ascii_mode", false),
            ),
        )
    }

    @Test
    fun returnKeyType_maps() {
        assertEquals("GO", ReturnKeyTypeText.fromImeAction(EditorInfo.IME_ACTION_GO))
        assertEquals("SEND", ReturnKeyTypeText.fromImeAction(EditorInfo.IME_ACTION_SEND))
        assertEquals("SEARCH", ReturnKeyTypeText.fromImeAction(EditorInfo.IME_ACTION_SEARCH))
        assertEquals("NEXT", ReturnKeyTypeText.fromImeAction(EditorInfo.IME_ACTION_NEXT))
        assertEquals("PREVIOUS", ReturnKeyTypeText.fromImeAction(EditorInfo.IME_ACTION_PREVIOUS))
        assertEquals("DONE", ReturnKeyTypeText.fromImeAction(EditorInfo.IME_ACTION_DONE))
        assertEquals("ENTER", ReturnKeyTypeText.fromImeAction(EditorInfo.IME_ACTION_UNSPECIFIED))
        assertEquals("ENTER", ReturnKeyTypeText.fromImeAction(EditorInfo.IME_ACTION_NONE))
    }
}
