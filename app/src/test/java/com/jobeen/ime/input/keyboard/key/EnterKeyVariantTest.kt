package com.jobeen.ime.input.keyboard.key

import com.jobeen.ime.data.keyboard.theme.KeyboardColors
import com.jobeen.ime.input.keyboard.impl.T9Keyboard
import com.jobeen.ime.input.keyboard.key.KeyDef.Appearance.Variant
import com.jobeen.ime.skin.model.KeyOverride
import com.jobeen.ime.skin.model.KeyStyleRole
import com.jobeen.ime.skin.model.SkinSnapshot
import com.jobeen.ime.skin.model.SoundBindings
import com.jobeen.ime.skin.runtime.KeyOverrideApplier
import com.jobeen.ime.skin.runtime.SkinColorMode
import com.jobeen.ime.skin.store.SkinGenRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * 回车变体（Lee 2026-10-28 决定：自定义皮肤生效时回车跟皮肤，
 * 判断点 9 的“跟内置主题 id”作废）：
 * 皮肤生效 → Accent（皮肤色板的 accent 槽；单键样式覆写的颜色槽
 * 优先于变体）；无皮肤 → 内置主题口径原样不动；
 * 开关只决定皮肤取浅/深色板，永不改变回车变体。
 * 纯逻辑测试（不碰 Android 框架）；生产接线 EnterKeyVariant.resolve
 * 是对本纯函数的薄委托（快照存在性 + 主题 id），其规则全由此覆盖。
 */
class EnterKeyVariantTest {

    private fun scheme(
        keyBackground: Int,
        accentKeyBackground: Int,
        accentKeyText: Int,
    ) = KeyboardColors.ColorScheme(
        keyBackground = keyBackground, keyPressed = keyBackground + 1,
        keyBorderStroke = keyBackground + 2,
        specialKeyBackground = keyBackground + 3,
        specialKeyPressed = keyBackground + 4,
        specialKeyBorderStroke = keyBackground + 5,
        accentKeyBackground = accentKeyBackground,
        accentKeyPressed = accentKeyBackground + 1,
        accentKeyBorderStroke = accentKeyBackground + 2,
        keyText = keyBackground + 10, specialKeyText = keyBackground + 11,
        accentKeyText = accentKeyText, altText = keyBackground + 13,
        background = keyBackground + 14,
        panel = KeyboardColors.ColorScheme.PanelColors(
            background = 20, toolbarText = 21, toolbarActived = 22,
            toolbarIcon = 23, candidateBackground = 24, candidateText = 25,
            candidateIndex = 26, candidateDivider = 27, toolbarPressed = 28,
        ),
        pinner = KeyboardColors.ColorScheme.PinnerColors(
            background = 30, textColor = 31, secondaryTextColor = 32,
        ),
    )

    private val lightScheme = scheme(
        keyBackground = 1, accentKeyBackground = 101, accentKeyText = 112,
    )
    private val darkScheme = scheme(
        keyBackground = 2, accentKeyBackground = 201, accentKeyText = 212,
    )

    private fun snapshot(
        dark: KeyboardColors.ColorScheme? = null,
        overrides: Map<String, KeyOverride> = emptyMap(),
    ) = SkinSnapshot(
        ref = SkinGenRef("skin-1", "h".repeat(64), 1),
        name = "test",
        colorScheme = lightScheme,
        darkColorScheme = dark,
        geometryPresence = emptySet(),
        effectiveGeometry = emptyMap(),
        background = null,
        degradations = emptySet(),
        layoutId = null,
        keyOverrides = overrides,
        soundBindings = SoundBindings.EMPTY,
    )

    // ---------- 皮肤生效：主题 id 不再起作用 ----------

    @Test
    fun skinActiveAlwaysAccentRegardlessOfThemeId() {
        for (themeId in listOf("light", "amoled", "sunset", "monet", "unknown", "")) {
            assertEquals(
                "皮肤生效时回车变体不应读内置主题 id：$themeId",
                Variant.Accent,
                returnKeyVariantForSkin(true, themeId),
            )
        }
    }

    // ---------- 无皮肤：内置行为原样不动 ----------

    @Test
    fun skinInactiveKeepsBuiltinBehavior() {
        assertEquals(Variant.Normal, returnKeyVariantForSkin(false, "light"))
        assertEquals(Variant.Normal, returnKeyVariantForSkin(false, "amoled"))
        assertEquals(Variant.Accent, returnKeyVariantForSkin(false, "sunset"))
        assertEquals(Variant.Accent, returnKeyVariantForSkin(false, "monet"))
        assertEquals(Variant.Accent, returnKeyVariantForSkin(false, "unknown"))
        assertEquals(Variant.Accent, returnKeyVariantForSkin(false, ""))
    }

    // ---------- 双色板：回车跟当前生效色板走 ----------

    @Test
    fun dualPaletteEnterFollowsEffectivePalette() {
        val snap = snapshot(dark = darkScheme)
        // 浅模式：回车变体 Accent，取浅色板 accent 槽
        assertEquals(Variant.Accent, returnKeyVariantForSkin(true, "light"))
        assertEquals(101, snap.effectiveColorScheme(false).accentKeyBackground)
        assertEquals(112, snap.effectiveColorScheme(false).accentKeyText)
        // 深模式：回车变体仍 Accent，取深色板 accent 槽
        assertEquals(Variant.Accent, returnKeyVariantForSkin(true, "amoled"))
        assertEquals(201, snap.effectiveColorScheme(true).accentKeyBackground)
        assertEquals(212, snap.effectiveColorScheme(true).accentKeyText)
        // 出厂 T9 回车键按皮肤变体构建后，变体即 Accent
        val enter = T9Keyboard.buildLayout(Variant.Accent).flatten()
            .first { it.id == KeyIds.T9.T9_ENTER }
        assertEquals(Variant.Accent, enter.appearance.variant)
    }

    // ---------- 单色板：两模式同色 ----------

    @Test
    fun singlePaletteEnterIdenticalInBothModes() {
        val snap = snapshot(dark = null)
        assertSame(snap.effectiveColorScheme(false), snap.effectiveColorScheme(true))
        assertEquals(Variant.Accent, returnKeyVariantForSkin(true, "light"))
        assertEquals(Variant.Accent, returnKeyVariantForSkin(true, "amoled"))
    }

    // ---------- 开关切换：只选板，不改变变体 ----------

    @Test
    fun switchToggleNeverChangesEnterVariant() {
        val single = snapshot(dark = null)
        val dual = snapshot(dark = darkScheme)
        for (mode in SkinColorMode.Mode.entries) {
            for (systemNight in listOf(false, true)) {
                val isDark = SkinColorMode.isDark(mode, systemNight)
                // 变体恒为 Accent，与开关/系统深浅无关
                assertEquals(Variant.Accent, returnKeyVariantForSkin(true, "light"))
                assertEquals(Variant.Accent, returnKeyVariantForSkin(true, "sunset"))
                // 单色板：开关换不出任何外观差异（同一板）
                assertSame(
                    single.effectiveColorScheme(false),
                    single.effectiveColorScheme(isDark),
                )
                // 双色板：开关只决定取哪块板（板内 accent 槽），不变变体
                val expectedAccent = if (isDark) 201 else 101
                assertEquals(
                    expectedAccent,
                    dual.effectiveColorScheme(isDark).accentKeyBackground,
                )
            }
        }
    }

    // ---------- 单键覆写：颜色槽优先于变体 ----------

    @Test
    fun enterKeyStyleOverrideWinsOverVariant() {
        val layout = T9Keyboard.buildLayout(Variant.Accent)
        val snap = snapshot(
            overrides = mapOf(
                KeyIds.T9.T9_ENTER to KeyOverride(
                    styleOverrides = mapOf(KeyStyleRole.FACE to "#FF112233"),
                ),
            ),
        )
        val result = KeyOverrideApplier.apply("T9", layout, snap, emptyMap())
        val enter = result.flatten().first { it.id == KeyIds.T9.T9_ENTER }
        // 覆写颜色槽已写上（渲染层取色优先于变体），变体本身保持 Accent
        assertEquals(0xFF112233.toInt(), enter.appearance.skinFaceColor)
        assertEquals(Variant.Accent, enter.appearance.variant)
    }
}
