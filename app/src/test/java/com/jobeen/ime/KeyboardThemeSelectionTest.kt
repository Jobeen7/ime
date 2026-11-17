// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime

import com.jobeen.ime.data.keyboard.theme.KeyboardTheme
import com.jobeen.ime.ui.screen.THEME_KEY_DEFAULT
import com.jobeen.ime.ui.screen.resolveSelectedThemeKey
import com.jobeen.ime.ui.screen.themeTripleForChoice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 键盘皮肤页选择口径（纯函数，不触 Compose/Android）：
 * 三槽位与选中键互为逆运算；旧版混搭槽位不标任何行。
 */
class KeyboardThemeSelectionTest {

    private val light = KeyboardTheme.LIGHT_DEFAULT_ID // "light"
    private val dark = KeyboardTheme.DARK_DEFAULT_ID // "amoled"
    private val sunset = "sunset"
    private val monet = "monet"

    @Test
    fun defaultTripleIsDualSlot() {
        assertEquals(Triple(dark, light, dark), themeTripleForChoice(THEME_KEY_DEFAULT))
    }

    @Test
    fun singlePresetsAreFixedTriples() {
        assertEquals(Triple(dark, dark, dark), themeTripleForChoice(dark))
        assertEquals(Triple(light, light, light), themeTripleForChoice(light))
        assertEquals(Triple(sunset, sunset, sunset), themeTripleForChoice(sunset))
    }

    @Test
    fun monetAndCustomHaveNoTriple() {
        assertNull(themeTripleForChoice(monet))
        assertNull(themeTripleForChoice("custom_foo"))
    }

    @Test
    fun resolveFindsDefaultAndSingles() {
        assertEquals(
            THEME_KEY_DEFAULT,
            resolveSelectedThemeKey(dark, light, dark, emptySet()),
        )
        assertEquals(dark, resolveSelectedThemeKey(dark, dark, dark, emptySet()))
        assertEquals(light, resolveSelectedThemeKey(light, light, light, emptySet()))
        assertEquals(sunset, resolveSelectedThemeKey(sunset, sunset, sunset, emptySet()))
    }

    @Test
    fun resolveFindsMonetAndCustom() {
        assertEquals(monet, resolveSelectedThemeKey(monet, light, dark, emptySet()))
        assertEquals(
            "custom_foo",
            resolveSelectedThemeKey("custom_foo", light, dark, setOf("custom_foo")),
        )
    }

    @Test
    fun resolveReturnsNullForMixedSlots() {
        // 旧版混搭：主主题暗夜、浅槽落日——不在任一口径内，不标行。
        assertNull(resolveSelectedThemeKey(dark, sunset, dark, emptySet()))
        assertNull(resolveSelectedThemeKey("unknown_id", light, dark, emptySet()))
    }
}
