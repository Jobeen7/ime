package com.ninthsoft.ime

import com.ninthsoft.ime.data.theme.CompactColors
import com.ninthsoft.ime.data.theme.CompactPanel
import com.ninthsoft.ime.data.theme.CompactPinner
import com.ninthsoft.ime.data.theme.CompactSurfaceStyle
import com.ninthsoft.ime.data.theme.CompactTheme
import com.ninthsoft.ime.data.theme.ReadableColors
import com.ninthsoft.ime.data.theme.ReadablePanel
import com.ninthsoft.ime.data.theme.ReadablePinner
import com.ninthsoft.ime.data.theme.ReadableTheme
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeCodeRoundTripTest {
    private val json = Json { ignoreUnknownKeys = true }

    private fun sample() = CompactTheme(
        id = "custom_test",
        name = "测试主题",
        colors = CompactColors(
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
            surfaceStyle = CompactSurfaceStyle.Raised,
            cornerRadius = 6f,
            keyHMargin = 3f,
            keyVMargin = 4f,
            panel = CompactPanel(
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
            pinner = CompactPinner(
                background = 0xFF1A3F4E.toInt(),
                textColor = 0xFFE8F6FA.toInt(),
                secondaryTextColor = 0xFF6FA8BC.toInt(),
            ),
            toastBackground = 0xFF16333F.toInt(),
            toastText = 0xFFE8F6FA.toInt(),
        ),
    )

    @Test
    fun compactThemeRoundTrips() {
        val theme = sample()
        val encoded = json.encodeToString(theme)
        val decoded = json.decodeFromString<CompactTheme>(encoded)

        assertEquals(theme.id, decoded.id)
        assertEquals(theme.name, decoded.name)
        assertEquals(theme.colors.keyBackground, decoded.colors.keyBackground)
        assertEquals(theme.colors.specialKeyBackground, decoded.colors.specialKeyBackground)
        assertEquals(theme.colors.panel.candidateBackground, decoded.colors.panel.candidateBackground)
        assertEquals(theme.colors.pinner.textColor, decoded.colors.pinner.textColor)
        assertNotNull(decoded.toKeyboardTheme())
    }

    @Test
    fun readableThemeUsesHex() {
        val theme = ReadableTheme(
            id = sample().id,
            name = sample().name,
            colors = ReadableColors(
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
                panel = ReadablePanel(
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
                pinner = ReadablePinner(
                    background = 0xFF1A3F4E.toInt(),
                    textColor = 0xFFE8F6FA.toInt(),
                    secondaryTextColor = 0xFF6FA8BC.toInt(),
                ),
                toastBackground = 0xFF16333F.toInt(),
                toastText = 0xFFE8F6FA.toInt(),
            ),
        )

        val encoded = json.encodeToString(theme)
        assertTrue(encoded.contains("#FF1A3F4E"))

        val decoded = json.decodeFromString<ReadableTheme>(encoded)
        assertEquals(0xFF1A3F4E.toInt(), decoded.colors.keyBackground)
        assertEquals(0xFFE8F6FA.toInt(), decoded.colors.keyText)
        assertNotNull(decoded.toKeyboardTheme())
    }
}