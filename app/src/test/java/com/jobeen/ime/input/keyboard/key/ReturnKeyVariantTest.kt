package com.jobeen.ime.input.keyboard.key

import com.jobeen.ime.input.keyboard.key.KeyDef.Appearance.Variant
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 回车键变体按主题取（2026-10-27 澄清的原意），由 spec 直接推导：
 * 仅素白（light）与暗夜（amoled）跟随普通键；其余主题与未知 id
 * 一律取 Accent（上游行为）。
 */
class ReturnKeyVariantTest {

    @Test
    fun lightAndAmoledFollowNormalKeys() {
        assertEquals(Variant.Normal, returnKeyVariantForTheme("light"))
        assertEquals(Variant.Normal, returnKeyVariantForTheme("amoled"))
    }

    @Test
    fun sunsetAndMonetKeepAccent() {
        assertEquals(Variant.Accent, returnKeyVariantForTheme("sunset"))
        assertEquals(Variant.Accent, returnKeyVariantForTheme("monet"))
    }

    @Test
    fun customAndUnknownIdsDefaultToAccent() {
        assertEquals(Variant.Accent, returnKeyVariantForTheme("my-custom-theme"))
        assertEquals(Variant.Accent, returnKeyVariantForTheme("unknown"))
        assertEquals(Variant.Accent, returnKeyVariantForTheme(""))
        // id 区分大小写：非精确命中不算素白/暗夜
        assertEquals(Variant.Accent, returnKeyVariantForTheme("LIGHT"))
    }

    @Test
    fun returnKeyDefaultsToAccentAndPassesVariantThrough() {
        val defaulted = returnKey(percentWidth = 0.15f)
        assertEquals(Variant.Accent, defaulted.appearance.variant)
        assertEquals(0.15f, defaulted.appearance.percentWidth)

        val normal = returnKey(percentWidth = 0.18f, rowSpan = 2, variant = Variant.Normal)
        assertEquals(Variant.Normal, normal.appearance.variant)
        assertEquals(0.18f, normal.appearance.percentWidth)
        assertEquals(2, normal.appearance.rowSpan)

        // 主题函数与回车定义的接线：light 应当产出 Normal 回车
        val wired = returnKey(
            percentWidth = 0.15f,
            variant = returnKeyVariantForTheme("light"),
        )
        assertEquals(Variant.Normal, wired.appearance.variant)
    }
}
