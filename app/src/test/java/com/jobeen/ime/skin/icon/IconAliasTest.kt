// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.skin.icon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 图标名平台映射（重点候选 8-7）：注册表优先、别名、透传、空白。 */
class IconAliasTest {

    private val known = setOf(
        "content_copy", "content_paste", "content_cut", "mic", "mood",
        "arrow_back", "arrow_forward", "arrow_upward", "arrow_downward",
        "check", "close", "keyboard_hide", "keyboard_return",
        "backspace", "delete", "settings",
    )

    @Test
    fun registryWinsOverAlias() {
        assertEquals("delete", IconAlias.resolve("delete", known))
        assertEquals("backspace", IconAlias.resolve("backspace", known))
        assertEquals("settings", IconAlias.resolve("settings", known))
    }

    @Test
    fun aliasesMap() {
        assertEquals("content_copy", IconAlias.resolve("copy", known))
        assertEquals("content_paste", IconAlias.resolve("paste", known))
        assertEquals("content_cut", IconAlias.resolve("cut", known))
        assertEquals("mic", IconAlias.resolve("voice", known))
        assertEquals("mood", IconAlias.resolve("emoji", known))
        assertEquals("arrow_back", IconAlias.resolve("back", known))
        assertEquals("keyboard_return", IconAlias.resolve("return", known))
    }

    @Test
    fun normalization() {
        assertEquals("content_copy", IconAlias.resolve(" Copy ", known))
        assertEquals("content_copy", IconAlias.resolve("CONTENT_COPY", known))
        assertEquals("mic", IconAlias.resolve(" Voice ", known))
    }

    @Test
    fun unknownPassesThrough_blankIsUnset() {
        assertEquals("nope", IconAlias.resolve("nope", known))
        assertNull(IconAlias.resolve(null, known))
        assertNull(IconAlias.resolve("", known))
        assertNull(IconAlias.resolve("   ", known))
    }

    @Test
    fun aliasTargetOutsideRegistry_passesThrough() {
        val withoutMood = known - "mood"
        assertEquals("emoji", IconAlias.resolve("emoji", withoutMood))
    }
}
