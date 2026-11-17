package com.jobeen.ime.skin.model

import com.jobeen.ime.skin.runtime.SkinColorMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 颜色模式纯函数（2026-10-27 第一组余部；双色板／布局绑定／快照取板
 * 随皮肤 v3 删除）。
 */
class PaletteLayoutTest {



    /** 夹具默认深色板：已有 stylesDark 时不动；否则在根对象末尾补一色深色板 */



    // ---- 颜色模式纯函数 ----

    @Test
    fun colorModeMatrix() {
        assertTrue(SkinColorMode.isDark(SkinColorMode.Mode.SYSTEM, true))
        assertFalse(SkinColorMode.isDark(SkinColorMode.Mode.SYSTEM, false))
        assertTrue(SkinColorMode.isDark(SkinColorMode.Mode.DARK, false))
        assertFalse(SkinColorMode.isDark(SkinColorMode.Mode.LIGHT, true))
        assertEquals(SkinColorMode.Mode.SYSTEM, SkinColorMode.fromStored(null))
        assertEquals(SkinColorMode.Mode.DARK, SkinColorMode.fromStored("DARK"))
        // 库页口径小写与旧实现大写两种拼写都接受（存储契约）
        assertEquals(SkinColorMode.Mode.DARK, SkinColorMode.fromStored("dark"))
        assertEquals(SkinColorMode.Mode.LIGHT, SkinColorMode.fromStored("light"))
        assertEquals(SkinColorMode.Mode.SYSTEM, SkinColorMode.fromStored("system"))
        assertEquals("dark", SkinColorMode.stored(SkinColorMode.Mode.DARK))
        assertEquals("system", SkinColorMode.stored(SkinColorMode.Mode.SYSTEM))
        assertEquals("color_mode", SkinColorMode.KEY)
        assertEquals("skin_library", SkinColorMode.PREFS_NAME)
        // 未知存储值按 SYSTEM 处理（不抛、不返回 null）
        assertEquals(SkinColorMode.Mode.SYSTEM, SkinColorMode.fromStored("bogus"))
    }

    // ---- 双色板：显式深色 > 浅色回填 ----






    // ---- 横屏绑定：形态校验 ----


    // ---- 横屏生效：绑定优先，缺失回退竖屏横屏变体 ----





    // ---- 演示包原字节（与交付 .jime 同源的测试夹具） ----




}
