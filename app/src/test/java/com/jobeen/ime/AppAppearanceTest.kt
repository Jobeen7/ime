// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime

import com.jobeen.ime.data.manager.KeyboardManager
import com.jobeen.ime.ui.theme.AppAppearance
import com.jobeen.ime.ui.theme.AppAppearanceMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 应用外观映射纯逻辑：模式→主题模式／动态取色开关。 */
class AppAppearanceTest {

    @Test
    fun modes_areExactlyMonetLightDark() {
        // 2026-10-30 莫奈色轮：列表并为 3 项，莫奈色排首（接替自动的默认位）。
        assertEquals(
            listOf(AppAppearanceMode.MONET, AppAppearanceMode.LIGHT, AppAppearanceMode.DARK),
            AppAppearanceMode.entries,
        )
    }

    @Test
    fun themeModeFor_mapsAllModes() {
        assertEquals(
            KeyboardManager.Theme.MODE_LIGHT,
            AppAppearance.themeModeFor(AppAppearanceMode.LIGHT, true),
        )
        assertEquals(
            KeyboardManager.Theme.MODE_DARK,
            AppAppearance.themeModeFor(AppAppearanceMode.DARK, false),
        )
        // 莫奈色跟随系统深浅（动态取色另由开关控制）。
        assertEquals(
            KeyboardManager.Theme.MODE_LIGHT,
            AppAppearance.themeModeFor(AppAppearanceMode.MONET, false),
        )
        assertEquals(
            KeyboardManager.Theme.MODE_DARK,
            AppAppearance.themeModeFor(AppAppearanceMode.MONET, true),
        )
    }

    @Test
    fun useDynamicColorFor_onlyMonetOnSPlus() {
        assertTrue(AppAppearance.useDynamicColorFor(AppAppearanceMode.MONET, 31))
        assertTrue(AppAppearance.useDynamicColorFor(AppAppearanceMode.MONET, 36))
        assertFalse(AppAppearance.useDynamicColorFor(AppAppearanceMode.MONET, 30))
        assertFalse(AppAppearance.useDynamicColorFor(AppAppearanceMode.DARK, 36))
    }

    @Test
    fun appearanceModeChanged_onlyWhenModeDiffers() {
        assertFalse(
            AppAppearance.appearanceModeChanged(AppAppearanceMode.MONET, AppAppearanceMode.MONET),
        )
        assertTrue(
            AppAppearance.appearanceModeChanged(AppAppearanceMode.MONET, AppAppearanceMode.LIGHT),
        )
        assertTrue(
            AppAppearance.appearanceModeChanged(AppAppearanceMode.LIGHT, AppAppearanceMode.DARK),
        )
    }

    @Test
    fun parse_legacyAutoAndUnknownFallBackToMonet() {
        // 存量“auto”用户迁移到莫奈色（getMode 的写回是代码级，
        // 无 Robolectric 的 JVM 单测覆盖不到，见交回报告）。
        assertEquals(AppAppearanceMode.MONET, AppAppearanceMode.parse(null))
        assertEquals(AppAppearanceMode.MONET, AppAppearanceMode.parse("bogus"))
        assertEquals(AppAppearanceMode.MONET, AppAppearanceMode.parse("auto"))
        assertEquals(AppAppearanceMode.MONET, AppAppearanceMode.parse("monet"))
        assertEquals(AppAppearanceMode.LIGHT, AppAppearanceMode.parse("light"))
        assertEquals(AppAppearanceMode.DARK, AppAppearanceMode.parse("dark"))
    }
}
