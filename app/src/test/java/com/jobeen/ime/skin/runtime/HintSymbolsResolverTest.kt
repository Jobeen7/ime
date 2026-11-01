// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.skin.runtime

import com.jobeen.ime.data.keyboard.theme.KeyboardColors
import com.jobeen.ime.skin.model.HintSymbolsStyle
import com.jobeen.ime.skin.model.KeyStyleRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 长按符号面板背景样式解析（面板级两背景走现有样式表）。 */
class HintSymbolsResolverTest {

    private val scheme = KeyboardColors.ColorScheme(
        keyBackground = 1, keyPressed = 2, keyBorderStroke = 3,
        specialKeyBackground = 4, specialKeyPressed = 5, specialKeyBorderStroke = 6,
        accentKeyBackground = 7, accentKeyPressed = 8, accentKeyBorderStroke = 9,
        keyText = 10, specialKeyText = 11, accentKeyText = 12, altText = 13,
        background = 14, cornerRadius = 5f, keyHMargin = 3f, keyVMargin = 4f,
        panel = KeyboardColors.ColorScheme.PanelColors(
            background = 20, toolbarText = 21, toolbarActived = 22, toolbarIcon = 23,
            candidateBackground = 24, candidateText = 25, candidateIndex = 26,
            candidateDivider = 27, toolbarPressed = 28,
        ),
        pinner = KeyboardColors.ColorScheme.PinnerColors(
            background = 30, textColor = 31, secondaryTextColor = 32,
        ),
        toastBackground = 40, toastText = 41,
    )

    @Test
    fun backgroundsResolveAgainstStyleTable() {
        val keyStyles = mapOf(
            "panelBg" to mapOf(KeyStyleRole.FACE to "#FF112233"),
            "selectedBg" to mapOf(KeyStyleRole.FACE to "accentKeyBackground"),
        )
        val style = HintSymbolsStyle(
            symbolStyles = listOf("sym1"),
            backgroundStyle = "panelBg",
            selectedBackgroundStyle = "selectedBg",
        )
        val resolved = HintSymbolsResolver.resolveBackgrounds(style, keyStyles, scheme)
        assertEquals(0xFF112233.toInt(), resolved.background?.face)
        assertEquals(7, resolved.selectedBackground?.face)
    }

    @Test
    fun unknownBackgroundNamesResolveToNull() {
        val style = HintSymbolsStyle(
            symbolStyles = listOf("sym1"),
            backgroundStyle = "missing",
        )
        val resolved = HintSymbolsResolver.resolveBackgrounds(
            style, emptyMap(), scheme,
        )
        assertNull(resolved.background)
        assertNull(resolved.selectedBackground)
    }
}
