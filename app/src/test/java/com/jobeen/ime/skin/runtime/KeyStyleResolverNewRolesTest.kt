package com.jobeen.ime.skin.runtime

import com.jobeen.ime.data.keyboard.theme.KeyboardColors
import com.jobeen.ime.skin.model.KeyStyleRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 新样式角色（审计 A 项：按压描边、下边缘色）的解析。 */
class KeyStyleResolverNewRolesTest {

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
    fun newRolesResolveAsLiteralColors() {
        val keyStyles = mapOf(
            "s" to mapOf(
                KeyStyleRole.PRESSED_BORDER to "#FF112233",
                KeyStyleRole.LOWER_EDGE to "#FF445566",
                KeyStyleRole.PRESSED_LOWER_EDGE to "#FF778899",
            ),
        )
        val result = KeyStyleResolver.resolve("s", null, keyStyles, scheme)
        assertEquals(0xFF112233.toInt(), result.style?.pressedBorder)
        assertEquals(0xFF445566.toInt(), result.style?.lowerEdge)
        assertEquals(0xFF778899.toInt(), result.style?.pressedLowerEdge)
        assertTrue(result.warnings.isEmpty())
    }

    @Test
    fun newRolesResolveTokenPaths() {
        val keyStyles = mapOf(
            "s" to mapOf(
                KeyStyleRole.PRESSED_BORDER to "accentKeyBorderStroke",
                KeyStyleRole.LOWER_EDGE to "keyBorderStroke",
            ),
        )
        val result = KeyStyleResolver.resolve("s", null, keyStyles, scheme)
        assertEquals(9, result.style?.pressedBorder)
        assertEquals(3, result.style?.lowerEdge)
    }
}
