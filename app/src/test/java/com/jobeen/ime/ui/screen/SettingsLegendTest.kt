// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.ui.screen

import com.jobeen.ime.data.keyboard.theme.KeyboardColors
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 设置页图例（CyIME D23）：白底白键守卫与图例样式解析。
 * 键面色与键盘底色相同时推开一档，保证浅色主题下白键可见；
 * 三类键面色（普通／功能／回车）全部过守卫。
 */
class SettingsLegendTest {

    private fun fakeScheme(
        keyBackground: Int = 0xFF3A3A3C.toInt(),
        specialKeyBackground: Int = 0xFF2C2C2E.toInt(),
        accentKeyBackground: Int = 0xFF9C824A.toInt(),
        background: Int = 0xFF000000.toInt(),
        keyText: Int = 0xFFFFFFFF.toInt(),
        altText: Int = 0xFF8E8E93.toInt(),
    ): KeyboardColors.ColorScheme = KeyboardColors.ColorScheme(
        keyBackground = keyBackground,
        keyPressed = keyBackground,
        keyBorderStroke = 0x00000000,
        specialKeyBackground = specialKeyBackground,
        specialKeyPressed = specialKeyBackground,
        specialKeyBorderStroke = 0x00000000,
        accentKeyBackground = accentKeyBackground,
        accentKeyPressed = accentKeyBackground,
        accentKeyBorderStroke = 0x00000000,
        keyText = keyText,
        specialKeyText = keyText,
        accentKeyText = keyText,
        altText = altText,
        background = background,
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

    // ---- 白底白键守卫 ----

    @Test
    fun keyVisible_unchangedWhenDifferentFromBackground() {
        assertEquals(
            0xFFFFFFFF.toInt(),
            ensureKeyVisibleOnBackground(0xFFFFFFFF.toInt(), 0xFFE1E2E7.toInt()),
        )
        assertEquals(
            0xFF3A3A3C.toInt(),
            ensureKeyVisibleOnBackground(0xFF3A3A3C.toInt(), 0xFF000000.toInt()),
        )
    }

    @Test
    fun keyVisible_darkensWhiteOnWhite() {
        // 浅色主题白键白底：255 * 88 / 100 = 224。
        assertEquals(
            0xFFE0E0E0.toInt(),
            ensureKeyVisibleOnBackground(0xFFFFFFFF.toInt(), 0xFFFFFFFF.toInt()),
        )
    }

    @Test
    fun keyVisible_lightensBlackOnBlack() {
        // 深色主题黑键黑底：0 + 255 * 12 / 100 = 30。
        assertEquals(
            0xFF1E1E1E.toInt(),
            ensureKeyVisibleOnBackground(0xFF000000.toInt(), 0xFF000000.toInt()),
        )
    }

    @Test
    fun keyVisible_darkensMidGrayOnMidGray() {
        // 亮度 128（≥128 走压暗）：128 * 88 / 100 = 112。
        assertEquals(
            0xFF707070.toInt(),
            ensureKeyVisibleOnBackground(0xFF808080.toInt(), 0xFF808080.toInt()),
        )
    }

    @Test
    fun keyVisible_preservesAlpha() {
        // 只推 RGB，透明度原样保留。
        assertEquals(
            0x80E0E0E0.toInt(),
            ensureKeyVisibleOnBackground(0x80FFFFFF.toInt(), 0xFFFFFFFF.toInt()),
        )
    }

    // ---- 图例样式解析 ----

    @Test
    fun resolve_mapsSchemeFields() {
        val scheme = fakeScheme()
        val style = resolveSettingsLegendStyle(scheme, 9f, 1f)
        assertEquals(0xFF000000.toInt(), style.background)
        assertEquals(0xFF3A3A3C.toInt(), style.keyBackground)
        assertEquals(0xFF2C2C2E.toInt(), style.specialKeyBackground)
        assertEquals(0xFF9C824A.toInt(), style.accentKeyBackground)
        assertEquals(0x00000000, style.keyBorderStroke)
        assertEquals(1f, style.keyBorderWidthDp)
        assertEquals(9f, style.keyCornerRadiusDp)
        assertEquals(0xFFFFFFFF.toInt(), style.keyText)
        assertEquals(0xFF8E8E93.toInt(), style.hintText)
    }

    @Test
    fun resolve_guardsAllThreeKeyFaces() {
        // 三类键面全与底色相同（白底白键）：全部推开一档。
        val scheme = fakeScheme(
            keyBackground = 0xFFFFFFFF.toInt(),
            specialKeyBackground = 0xFFFFFFFF.toInt(),
            accentKeyBackground = 0xFFFFFFFF.toInt(),
            background = 0xFFFFFFFF.toInt(),
        )
        val style = resolveSettingsLegendStyle(scheme, 9f, 1f)
        assertEquals(0xFFE0E0E0.toInt(), style.keyBackground)
        assertEquals(0xFFE0E0E0.toInt(), style.specialKeyBackground)
        assertEquals(0xFFE0E0E0.toInt(), style.accentKeyBackground)
        // 底板本身不动。
        assertEquals(0xFFFFFFFF.toInt(), style.background)
    }

    @Test
    fun resolve_passesRadiusAndBorderWidth() {
        val scheme = fakeScheme()
        val style = resolveSettingsLegendStyle(scheme, 20f, 2.5f)
        assertEquals(20f, style.keyCornerRadiusDp)
        assertEquals(2.5f, style.keyBorderWidthDp)
    }
}
