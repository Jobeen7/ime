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
    fun modes_areExactlyFollowSystemMonetLightDark() {
        // 【用户定规则变更 2026-11-01，取代 2026-10-31 莫奈色默认】
        // 默认改跟随系统：枚举 4 项，跟随系统排首（贴合旧自动优先惯例）。
        assertEquals(
            listOf(
                AppAppearanceMode.FOLLOW_SYSTEM,
                AppAppearanceMode.MONET,
                AppAppearanceMode.LIGHT,
                AppAppearanceMode.DARK,
            ),
            AppAppearanceMode.entries,
        )
        assertEquals("auto", AppAppearanceMode.FOLLOW_SYSTEM.stored)
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
    fun parse_unknownAndEmptyFallBackToFollowSystem() {
        // 【用户定规则变更 2026-11-01，取代 2026-10-30 auto→莫奈色迁移】
        // 未知／空 → 跟随系统（新默认）；"auto" 恢复原意 → 跟随系统；
        // getMode 不再有 auto→monet 写回（需 Context，无 Robolectric 的
        // JVM 单测覆盖不到，见交回报告）。
        // 显式莫奈色用户不动："monet" 仍解析为莫奈色。
        assertEquals(AppAppearanceMode.FOLLOW_SYSTEM, AppAppearanceMode.parse(null))
        assertEquals(AppAppearanceMode.FOLLOW_SYSTEM, AppAppearanceMode.parse("bogus"))
        assertEquals(AppAppearanceMode.FOLLOW_SYSTEM, AppAppearanceMode.parse("auto"))
        assertEquals(AppAppearanceMode.MONET, AppAppearanceMode.parse("monet"))
        assertEquals(AppAppearanceMode.LIGHT, AppAppearanceMode.parse("light"))
        assertEquals(AppAppearanceMode.DARK, AppAppearanceMode.parse("dark"))
    }

    @Test
    fun followSystem_followsSystemShadeWithoutDynamicColor() {
        // 跟随系统：深浅映射与莫奈色相同，但一律不动态取色。
        assertEquals(
            KeyboardManager.Theme.MODE_LIGHT,
            AppAppearance.themeModeFor(AppAppearanceMode.FOLLOW_SYSTEM, false),
        )
        assertEquals(
            KeyboardManager.Theme.MODE_DARK,
            AppAppearance.themeModeFor(AppAppearanceMode.FOLLOW_SYSTEM, true),
        )
        assertFalse(AppAppearance.useDynamicColorFor(AppAppearanceMode.FOLLOW_SYSTEM, 30))
        assertFalse(AppAppearance.useDynamicColorFor(AppAppearanceMode.FOLLOW_SYSTEM, 31))
        assertFalse(AppAppearance.useDynamicColorFor(AppAppearanceMode.FOLLOW_SYSTEM, 36))
        // 显式莫奈色不受影响：存量值与动态取色均原样。
        assertEquals("monet", AppAppearanceMode.MONET.stored)
        assertTrue(AppAppearance.useDynamicColorFor(AppAppearanceMode.MONET, 31))
    }
}
