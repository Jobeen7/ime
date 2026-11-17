// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime

import com.jobeen.ime.data.keyboard.theme.KeyboardColors
import com.jobeen.ime.data.keyboard.theme.KeyboardTheme
import com.jobeen.ime.data.keyboard.theme.ThemeCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ThemeCode 编解码（QR 字符串 ↔ 主题）：扫码导入的编解码器，
 * 2026-11-18 随扫码导入从正式版 v1.1.20 原样恢复。
 * 只用手写配色值建主题，不触 KeyboardThemePresets（其初始化调
 * android.graphics.Color，主机单测会抛）；旧版「IMEKBTHEME:主题id」
 * 回落分支经 KeyboardTheme.byId 读预设，主机单测覆盖不了，
 * 只在真机扫码路径走（与正式版一致）。
 */
class ThemeCodeTest {

    private fun sampleTheme() = KeyboardTheme(
        id = "custom_qr_test",
        name = "扫码测试",
        colors = KeyboardColors.ColorScheme(
            keyBackground = 0xFF1A3F4E.toInt(),
            keyPressed = 0xFF24546A.toInt(),
            keyBorderStroke = 0xFF2F6280.toInt(),
            specialKeyBackground = 0xFF16333F.toInt(),
            specialKeyPressed = 0xFF205067.toInt(),
            specialKeyBorderStroke = 0xFF2F6280.toInt(),
            accentKeyBackground = 0xFF2EC2E6.toInt(),
            accentKeyPressed = 0xFF6BD6EF.toInt(),
            accentKeyBorderStroke = 0xFF2EC2E6.toInt(),
            keyText = 0xFFE8F6FA.toInt(),
            specialKeyText = 0xFF8FC3D4.toInt(),
            accentKeyText = 0xFF062331.toInt(),
            altText = 0xFF6FA8BC.toInt(),
            background = 0xFF0F2A33.toInt(),
            panel = KeyboardColors.ColorScheme.PanelColors(
                background = 0xFF0F2A33.toInt(),
                toolbarText = 0xFFE8F6FA.toInt(),
                toolbarActived = 0xFF2EC2E6.toInt(),
                toolbarIcon = 0xFF8FC3D4.toInt(),
                candidateBackground = 0xFF1A3F4E.toInt(),
                candidateText = 0xFFE8F6FA.toInt(),
                candidateIndex = 0xFF6FA8BC.toInt(),
                candidateDivider = 0xFF6FA8BC.toInt(),
                toolbarPressed = 0xFF24546A.toInt(),
            ),
            pinner = KeyboardColors.ColorScheme.PinnerColors(
                background = 0xFF1A3F4E.toInt(),
                textColor = 0xFFE8F6FA.toInt(),
                secondaryTextColor = 0xFF6FA8BC.toInt(),
            ),
        ),
    )

    @Test
    fun encodeDecodeRoundTrips() {
        val theme = sampleTheme()
        val code = ThemeCode.encode(theme)
        assertTrue(code.startsWith(ThemeCode.PREFIX))
        val decoded = ThemeCode.decode(code)!!
        assertEquals(theme.id, decoded.id)
        assertEquals(theme.name, decoded.name)
        assertEquals(theme.colors.keyBackground, decoded.colors.keyBackground)
        assertEquals(theme.colors.accentKeyBackground, decoded.colors.accentKeyBackground)
        assertEquals(
            theme.colors.panel.candidateBackground,
            decoded.colors.panel.candidateBackground,
        )
        assertEquals(theme.colors.pinner.textColor, decoded.colors.pinner.textColor)
    }

    @Test
    fun decodeTrimsWhitespace() {
        val code = ThemeCode.encode(sampleTheme())
        assertEquals(sampleTheme().id, ThemeCode.decode("  $code\n")!!.id)
    }

    @Test
    fun decodeRejectsNonThemeCode() {
        assertNull(ThemeCode.decode(""))
        assertNull(ThemeCode.decode("hello"))
        assertNull(ThemeCode.decode("IMEKBTHEME"))
        assertNull(ThemeCode.decode("SKIN:xxx"))
    }
}
