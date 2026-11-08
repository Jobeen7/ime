package com.jobeen.ime.skin.runtime

import com.jobeen.ime.data.keyboard.theme.KeyboardColors
import com.jobeen.ime.skin.model.KeyStyleParser
import com.jobeen.ime.skin.model.KeyStyleRole
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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

    /** 按压态主文字色（Bin-2 补项）：字面色与令牌路径都能解析。 */
    @Test
    fun pressedTextResolvesAsLiteralAndToken() {
        val keyStyles = mapOf(
            "s" to mapOf(KeyStyleRole.PRESSED_TEXT to "#FF112233"),
            "t" to mapOf(KeyStyleRole.PRESSED_TEXT to "accentKeyText"),
        )
        val literal = KeyStyleResolver.resolve("s", null, keyStyles, scheme)
        assertEquals(0xFF112233.toInt(), literal.style?.pressedText)
        assertTrue(literal.warnings.isEmpty())
        val token = KeyStyleResolver.resolve("t", null, keyStyles, scheme)
        assertEquals(12, token.style?.pressedText)
    }

    /** 按压态主文字色：逐键覆盖优先于样式表（与 text 角色同优先序）。 */
    @Test
    fun pressedTextOverrideWinsOverStyleTable() {
        val keyStyles = mapOf(
            "s" to mapOf(
                KeyStyleRole.TEXT to "#FF111111",
                KeyStyleRole.PRESSED_TEXT to "#FF222222",
            ),
        )
        val overrides = mapOf(KeyStyleRole.PRESSED_TEXT to "#FF333333")
        val result = KeyStyleResolver.resolve("s", overrides, keyStyles, scheme)
        assertEquals(0xFF111111.toInt(), result.style?.text)
        assertEquals(0xFF333333.toInt(), result.style?.pressedText)
    }

    /** 转换器写出的 pressedText 不再被当 unknown_role 丢弃。 */
    @Test
    fun pressedTextParsesFromJsonName() {
        val node = JsonObject(
            mapOf(
                "text" to JsonPrimitive("#FF111111"),
                "pressedText" to JsonPrimitive("#FF222222"),
            ),
        )
        val (roles, warnings) = KeyStyleParser.parseRoles("s", node)
        assertEquals("#FF222222", roles[KeyStyleRole.PRESSED_TEXT])
        assertTrue(warnings.isEmpty())
    }

    /** 只有 pressedText 的样式不算空样式（isEmpty 须计入新角色）。 */
    @Test
    fun styleWithOnlyPressedTextIsNotEmpty() {
        val keyStyles = mapOf(
            "s" to mapOf(KeyStyleRole.PRESSED_TEXT to "#FF112233"),
        )
        val result = KeyStyleResolver.resolve("s", null, keyStyles, scheme)
        assertEquals(0xFF112233.toInt(), result.style?.pressedText)
    }
}
