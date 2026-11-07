// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.ui.screen

import com.jobeen.ime.data.keyboard.theme.BackgroundBlurMode
import com.jobeen.ime.data.keyboard.theme.BackgroundBlurOverride
import com.jobeen.ime.data.keyboard.theme.GlobalStyle
import com.jobeen.ime.data.keyboard.theme.KeyboardColors
import com.jobeen.ime.data.keyboard.theme.SurfaceOverride
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 外观对话框按视频定稿重设计：
 * 标签分区映射、标签切换不丢草稿／展开态、预览解析（草稿优先、
 * 未设取键盘主题底色，含动态色板角色断言）、药丸取值提交、
 * 圆点滑杆分段与置灰口径。
 */
class GlobalStyleDialogTest {

    // ---- 标签 → 分区 ----

    @Test
    fun tabs_coverAllFiveSectionsExactlyOnce() {
        val all = GlobalStyleTab.entries.flatMap { sectionsForTab(it) }
        assertEquals(5, all.size)
        assertEquals(
            GlobalStyleSectionId.entries.toSet(),
            all.toSet(),
        )
    }

    @Test
    fun sectionsForTab_mapping() {
        assertEquals(
            listOf(GlobalStyleSectionId.KEYS),
            sectionsForTab(GlobalStyleTab.KEYS),
        )
        assertEquals(
            listOf(GlobalStyleSectionId.ICONS, GlobalStyleSectionId.BAR),
            sectionsForTab(GlobalStyleTab.TOOLBAR),
        )
        assertEquals(
            listOf(GlobalStyleSectionId.KEYBOARD, GlobalStyleSectionId.FROST),
            sectionsForTab(GlobalStyleTab.OVERALL),
        )
    }

    // ---- 状态持有：切标签不丢草稿、不丢展开态 ----

    @Test
    fun tabSwitch_preservesDraftAndExpanded() {
        val initial = GlobalStyle(
            key = SurfaceOverride(background = 0xFFE53935.toInt()),
            linkToolbar = false,
        )
        val state = GlobalStyleDialogState(initial)
        state.toggleSection(GlobalStyleSectionId.KEYS)
        state.toggleSection(GlobalStyleSectionId.BAR)
        state.updateDraft { it.copy(keyboardTopCornerDp = 24f) }

        state.selectTab(GlobalStyleTab.TOOLBAR)
        state.selectTab(GlobalStyleTab.OVERALL)
        state.selectTab(GlobalStyleTab.KEYS)

        assertEquals(initial.key.background, state.draft.key.background)
        assertEquals(false, state.draft.linkToolbar)
        assertEquals(24f, state.draft.keyboardTopCornerDp)
        assertTrue(state.isExpanded(GlobalStyleSectionId.KEYS))
        assertTrue(state.isExpanded(GlobalStyleSectionId.BAR))
        assertFalse(state.isExpanded(GlobalStyleSectionId.ICONS))
        assertEquals(GlobalStyleTab.KEYS, state.tab)
    }

    @Test
    fun resetDraft_restoresDefaults() {
        val state = GlobalStyleDialogState(
            GlobalStyle(key = SurfaceOverride(background = 0xFFE53935.toInt())),
        )
        state.resetDraft()
        assertEquals(GlobalStyle(), state.draft)
    }

    @Test
    fun toggleSection_flipsOnlyThatSection() {
        val state = GlobalStyleDialogState(GlobalStyle())
        state.toggleSection(GlobalStyleSectionId.FROST)
        assertTrue(state.isExpanded(GlobalStyleSectionId.FROST))
        assertFalse(state.isExpanded(GlobalStyleSectionId.KEYS))
        state.toggleSection(GlobalStyleSectionId.FROST)
        assertFalse(state.isExpanded(GlobalStyleSectionId.FROST))
    }

    // ---- 预览解析 ----

    private fun fakeBase(
        keyBackground: Int = 0xFF3A3A3C.toInt(),
        keyPressed: Int = 0xFF48484A.toInt(),
        keyBorderStroke: Int = 0x00000000,
        background: Int = 0xFF000000.toInt(),
        cornerRadius: Float = 5f,
        keyText: Int = 0xFFFFFFFF.toInt(),
    ): KeyboardColors.ColorScheme = KeyboardColors.ColorScheme(
        keyBackground = keyBackground,
        keyPressed = keyPressed,
        keyBorderStroke = keyBorderStroke,
        specialKeyBackground = keyBackground,
        specialKeyPressed = keyPressed,
        specialKeyBorderStroke = keyBorderStroke,
        accentKeyBackground = 0xFF9C824A.toInt(),
        accentKeyPressed = keyPressed,
        accentKeyBorderStroke = keyBorderStroke,
        keyText = keyText,
        specialKeyText = keyText,
        accentKeyText = keyText,
        altText = keyText,
        background = background,
        cornerRadius = cornerRadius,
        panel = KeyboardColors.ColorScheme.PanelColors(
            background = background,
            toolbarText = keyText,
            toolbarActived = keyText,
            toolbarIcon = keyText,
            candidateBackground = background,
            candidateText = keyText,
            candidateIndex = keyText,
            candidateDivider = keyText,
            toolbarPressed = keyText,
        ),
        pinner = KeyboardColors.ColorScheme.PinnerColors(
            background = background,
            textColor = keyText,
            secondaryTextColor = keyText,
        ),
    )

    @Test
    fun preview_followFallsBackToKeyboardScheme() {
        val base = fakeBase()
        val style = resolveGlobalStylePreviewStyle(base, GlobalStyle(), 9f)
        assertEquals(0xFF000000.toInt(), style.background)
        assertEquals(0xFF3A3A3C.toInt(), style.keyBackground)
        assertEquals(0xFF48484A.toInt(), style.keyPressed)
        assertEquals(0x00000000, style.keyBorderStroke)
        assertEquals(1f, style.keyBorderWidthDp)
        assertEquals(9f, style.keyCornerRadiusDp)
        assertEquals(0xFFFFFFFF.toInt(), style.keyText)
    }

    @Test
    fun preview_draftOverridesBase() {
        val base = fakeBase()
        val draft = GlobalStyle(
            key = SurfaceOverride(
                background = 0xFFE53935.toInt(),
                borderWidthDp = 2.5f,
                borderColor = 0xFF1E88E5.toInt(),
                cornerRadiusDp = 20f,
            ),
        )
        val style = resolveGlobalStylePreviewStyle(base, draft, 9f)
        assertEquals(0xFFE53935.toInt(), style.keyBackground)
        assertEquals(0xFF1E88E5.toInt(), style.keyBorderStroke)
        assertEquals(2.5f, style.keyBorderWidthDp)
        assertEquals(20f, style.keyCornerRadiusDp)
        // 草稿不覆盖的项仍取底色。
        assertEquals(0xFF48484A.toInt(), style.keyPressed)
        assertEquals(0xFF000000.toInt(), style.background)
    }

    @Test
    fun preview_clampsRadiusAndBorderWidth() {
        val base = fakeBase()
        val over = GlobalStyle(
            key = SurfaceOverride(borderWidthDp = 9f, cornerRadiusDp = 40f),
        )
        val overStyle = resolveGlobalStylePreviewStyle(base, over, 9f)
        assertEquals(4f, overStyle.keyBorderWidthDp)
        assertEquals(32f, overStyle.keyCornerRadiusDp)
        val under = GlobalStyle(
            key = SurfaceOverride(borderWidthDp = -1f, cornerRadiusDp = -5f),
        )
        val underStyle = resolveGlobalStylePreviewStyle(base, under, 9f)
        assertEquals(0f, underStyle.keyBorderWidthDp)
        assertEquals(0f, underStyle.keyCornerRadiusDp)
    }

    @Test
    fun preview_followsAnyBaseScheme_includingDynamicLike() {
        // 动态（莫奈）色板：解析只跟 base 的角色走，不写死任何壁纸色。
        // 圆角回落值由调用方传入（有皮肤圆角时传 base.cornerRadius）。
        val dynamicBase = fakeBase(
            keyBackground = 0xFF2B2930.toInt(),
            keyPressed = 0xFF36343B.toInt(),
            background = 0xFF141218.toInt(),
            cornerRadius = 12f,
            keyText = 0xFFE6E0E9.toInt(),
        )
        val style = resolveGlobalStylePreviewStyle(dynamicBase, GlobalStyle(), 12f)
        assertEquals(0xFF141218.toInt(), style.background)
        assertEquals(0xFF2B2930.toInt(), style.keyBackground)
        assertEquals(0xFF36343B.toInt(), style.keyPressed)
        assertEquals(12f, style.keyCornerRadiusDp)
        assertEquals(0xFFE6E0E9.toInt(), style.keyText)
    }

    @Test
    fun previewCell_pressedUsesKeyPressed() {
        val style = resolveGlobalStylePreviewStyle(fakeBase(), GlobalStyle(), 9f)
        assertEquals(0xFF48484A.toInt(), previewKeyCellColor(true, style))
        assertEquals(0xFF3A3A3C.toInt(), previewKeyCellColor(false, style))
    }

    // ---- 药丸取值提交 ----

    @Test
    fun colorPreset_firstIsFollowThenPresets() {
        assertEquals(null, colorPresetValueAt(0))
        assertEquals(0xFFFFFFFF.toInt(), colorPresetValueAt(1))
        assertEquals(0xFFEEEEEE.toInt(), colorPresetValueAt(2))
        assertEquals(0xFF000000.toInt(), colorPresetValueAt(4))
    }

    @Test
    fun blurMode_optionIndexOrder() {
        assertEquals(BackgroundBlurMode.FOLLOW, blurModeForOptionIndex(0))
        assertEquals(BackgroundBlurMode.ON, blurModeForOptionIndex(1))
        assertEquals(BackgroundBlurMode.OFF, blurModeForOptionIndex(2))
    }

    @Test
    fun currentColor_readsDraftFieldPerTarget() {
        val draft = GlobalStyle(
            key = SurfaceOverride(
                background = 0xFFE53935.toInt(),
                borderColor = 0xFF43A047.toInt(),
            ),
            toolbar = SurfaceOverride(
                background = 0xFF1E88E5.toInt(),
                borderColor = 0xFFFB8C00.toInt(),
            ),
            barBackground = 0xFF000000.toInt(),
        )
        assertEquals(0xFFE53935.toInt(), currentColorForTarget(draft, ColorTarget.KEY_BG))
        assertEquals(0xFF43A047.toInt(), currentColorForTarget(draft, ColorTarget.KEY_BORDER))
        assertEquals(0xFF1E88E5.toInt(), currentColorForTarget(draft, ColorTarget.TOOLBAR_BG))
        assertEquals(0xFFFB8C00.toInt(), currentColorForTarget(draft, ColorTarget.TOOLBAR_BORDER))
        assertEquals(0xFF000000.toInt(), currentColorForTarget(draft, ColorTarget.BAR_BG))
        assertEquals(null, currentColorForTarget(GlobalStyle(), ColorTarget.KEY_BG))
    }

    // ---- 圆点滑杆 ----

    @Test
    fun slider_fractionAndSnapRoundTrip() {
        assertEquals(0.25f, sliderFraction(1f, 0f..4f))
        assertEquals(1f, sliderValueAtFraction(0.25f, 0f..4f, 7))
        assertEquals(2f, sliderValueAtFraction(0.5f, 0f..4f, 7))
        assertEquals(0.5f, sliderValueAtFraction(0.125f, 0f..4f, 7))
        assertEquals(4f, sliderValueAtFraction(1f, 0f..4f, 7))
        assertEquals(0f, sliderValueAtFraction(0f, 0f..4f, 7))
        // 0.3 落在 1.0 与 1.5 之间，贴到 1.0（2.4 格 → 2 格）。
        assertEquals(1f, sliderValueAtFraction(0.3f, 0f..4f, 7))
        assertEquals(1.5f, sliderValueAtFraction(0.32f, 0f..4f, 7))
    }

    @Test
    fun slider_continuousWhenNoSteps() {
        assertEquals(1.2f, sliderValueAtFraction(0.3f, 0f..4f, 0), 0.0001f)
    }

    @Test
    fun slider_radiusAndOverlaySteps() {
        assertEquals(16f, sliderValueAtFraction(0.5f, 0f..32f, 31))
        assertEquals(10f, sliderValueAtFraction(0.3125f, 0f..32f, 31))
        assertEquals(0.25f, sliderValueAtFraction(0.25f, 0f..1f, 99), 0.0001f)
        assertEquals(0.26f, sliderValueAtFraction(0.257f, 0f..1f, 99), 0.0001f)
    }

    @Test
    fun slider_fractionClampsOutOfRange() {
        assertEquals(0f, sliderValueAtFraction(-0.5f, 0f..4f, 7))
        assertEquals(4f, sliderValueAtFraction(1.5f, 0f..4f, 7))
        assertEquals(0f, sliderFraction(-1f, 0f..4f))
        assertEquals(1f, sliderFraction(9f, 0f..4f))
    }

    @Test
    fun slider_labelAndPillText() {
        assertEquals("边框粗细 · 1.0 dp", sliderLabel("边框粗细", "1.0 dp"))
        assertEquals("背景叠加强度 · 25.0%", sliderLabel("背景叠加强度", "25.0%"))
        assertEquals("按键背景 · 跟随", valuePillText("按键背景", "跟随"))
        assertEquals("动态模糊背景 · 跟随", valuePillText("动态模糊背景", "跟随"))
    }

    @Test
    fun blurOverride_defaultIsFollow() {
        assertEquals(
            BackgroundBlurMode.FOLLOW,
            BackgroundBlurOverride().mode,
        )
    }
}
