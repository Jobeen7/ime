package com.jobeen.ime.skin.runtime

import org.junit.Assert.assertEquals
import org.junit.Test

/** 设置页跟随开关 → 皮肤颜色模式映射（2026-10-28 跟随系统统一）。 */
class SkinColorModeTest {

    @Test
    fun followToggleOnIsSystem() {
        assertEquals(
            SkinColorMode.Mode.SYSTEM,
            SkinColorMode.fromFollowToggle(true, false),
        )
        assertEquals(
            SkinColorMode.Mode.SYSTEM,
            SkinColorMode.fromFollowToggle(true, true),
        )
    }

    @Test
    fun followToggleOffFreezesCurrentEffectiveMode() {
        assertEquals(
            SkinColorMode.Mode.DARK,
            SkinColorMode.fromFollowToggle(false, true),
        )
        assertEquals(
            SkinColorMode.Mode.LIGHT,
            SkinColorMode.fromFollowToggle(false, false),
        )
    }

    @Test
    fun frozenModeIsStableAgainstSystemChange() {
        // 关后冻结：系统深浅翻转不改变已冻结的模式（需开关重开才跟随）。
        // DARK 模式在任何系统深浅下都取深色板——这正是“冻结”的含义。
        val frozen = SkinColorMode.fromFollowToggle(false, true)
        assertEquals(SkinColorMode.Mode.DARK, frozen)
        assertEquals(true, SkinColorMode.isDark(frozen, false))
        assertEquals(true, SkinColorMode.isDark(frozen, true))
        val frozenLight = SkinColorMode.fromFollowToggle(false, false)
        assertEquals(SkinColorMode.Mode.LIGHT, frozenLight)
        assertEquals(false, SkinColorMode.isDark(frozenLight, false))
        assertEquals(false, SkinColorMode.isDark(frozenLight, true))
    }
}
