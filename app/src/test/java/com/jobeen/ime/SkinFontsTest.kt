// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime

import com.jobeen.ime.data.keyboard.theme.SkinFonts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 字体回退：只有白名单族名放行，其余一律回落系统默认（null）。
 * 注意：本测试只覆盖纯函数 resolveFamily；Typeface.create 本身
 * 需 Android 环境，不可 JVM 单测，实际渲染为真机 only。
 */
class SkinFontsTest {

    @Test
    fun resolveFamily_acceptsWhitelistCaseInsensitive() {
        assertEquals("sans-serif", SkinFonts.resolveFamily("sans-serif"))
        assertEquals("serif-monospace", SkinFonts.resolveFamily("Serif-Monospace"))
        assertEquals("monospace", SkinFonts.resolveFamily("  monospace  "))
    }

    @Test
    fun resolveFamily_blankAndDefaultAreUnspecified() {
        assertNull(SkinFonts.resolveFamily(null))
        assertNull(SkinFonts.resolveFamily(""))
        assertNull(SkinFonts.resolveFamily("   "))
        assertNull(SkinFonts.resolveFamily("default"))
        assertNull(SkinFonts.resolveFamily("Default"))
    }

    @Test
    fun resolveFamily_unknownFallsBack() {
        assertNull(SkinFonts.resolveFamily("comic-sans"))
        assertNull(SkinFonts.resolveFamily("not-a-font"))
    }
}
