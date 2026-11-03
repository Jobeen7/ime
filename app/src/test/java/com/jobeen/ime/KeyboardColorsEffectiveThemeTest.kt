// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime

import com.jobeen.ime.data.keyboard.theme.KeyboardColors
import com.jobeen.ime.skin.library.SkinColorMode
import org.junit.Assert.assertEquals
import org.junit.Test

/** 内置主题深浅唯一决定逻辑：颜色模式三项是唯一控制（2026-11-03 单一跟随系统口径）。 */
class KeyboardColorsEffectiveThemeTest {

    private val light = "light"
    private val dark = "amoled"
    private val monet = "monet"
    private val imported = "custom_legacy"

    @Test
    fun system_forcesPairToFollowSystemShade() {
        // 旧顶栏关跟随固定住默认对的遗留状态，在跟随系统下不再生效：按系统取槽位。
        assertEquals(
            light,
            KeyboardColors.effectiveBuiltinThemeId(
                SkinColorMode.SYSTEM, light, light, dark, false,
            ),
        )
        assertEquals(
            dark,
            KeyboardColors.effectiveBuiltinThemeId(
                SkinColorMode.SYSTEM, light, light, dark, true,
            ),
        )
        assertEquals(
            light,
            KeyboardColors.effectiveBuiltinThemeId(
                SkinColorMode.SYSTEM, dark, light, dark, false,
            ),
        )
        assertEquals(
            dark,
            KeyboardColors.effectiveBuiltinThemeId(
                SkinColorMode.SYSTEM, dark, light, dark, true,
            ),
        )
    }

    @Test
    fun system_keepsFixedThemeOutsideSlots() {
        // 莫奈色与导入固定主题在跟随系统下保持本身（莫奈调色板自身按系统深浅分支）。
        assertEquals(
            monet,
            KeyboardColors.effectiveBuiltinThemeId(
                SkinColorMode.SYSTEM, monet, light, dark, true,
            ),
        )
        assertEquals(
            monet,
            KeyboardColors.effectiveBuiltinThemeId(
                SkinColorMode.SYSTEM, monet, light, dark, false,
            ),
        )
        assertEquals(
            imported,
            KeyboardColors.effectiveBuiltinThemeId(
                SkinColorMode.SYSTEM, imported, light, dark, true,
            ),
        )
    }

    @Test
    fun lightAndDark_forceSlotsEvenForFixedTheme() {
        // 浅色／深色强制槽位，现行行为保留：莫奈色与导入固定主题亦被强制到槽位。
        assertEquals(
            light,
            KeyboardColors.effectiveBuiltinThemeId(
                SkinColorMode.LIGHT, monet, light, dark, true,
            ),
        )
        assertEquals(
            dark,
            KeyboardColors.effectiveBuiltinThemeId(
                SkinColorMode.DARK, monet, light, dark, false,
            ),
        )
        assertEquals(
            light,
            KeyboardColors.effectiveBuiltinThemeId(
                SkinColorMode.LIGHT, imported, light, dark, true,
            ),
        )
        assertEquals(
            dark,
            KeyboardColors.effectiveBuiltinThemeId(
                SkinColorMode.DARK, imported, light, dark, false,
            ),
        )
    }
}
