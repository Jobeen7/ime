package com.jobeen.ime.skin.runtime

import com.jobeen.ime.data.keyboard.theme.KeyboardColors
import com.jobeen.ime.skin.model.KeyStyleRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 单键样式解析（2026-10-27 并入项）：令牌优先、字面回退、逐角色回退。 */
class KeyStyleResolverTest {

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
    fun tokenReferenceResolvesAgainstScheme() {
        val keyStyles = mapOf(
            "gold" to mapOf(
                KeyStyleRole.FACE to "accentKeyBackground",
                KeyStyleRole.TEXT to "keyText",
            ),
        )
        val result = KeyStyleResolver.resolve("gold", null, keyStyles, scheme)
        assertEquals(7, result.style?.face)
        assertEquals(10, result.style?.text)
        assertNull(result.style?.pressed)
        assertTrue(result.warnings.isEmpty())
    }

    @Test
    fun literalColorFallback() {
        val keyStyles = mapOf(
            "custom" to mapOf(KeyStyleRole.FACE to "#FF112233"),
        )
        val result = KeyStyleResolver.resolve("custom", null, keyStyles, scheme)
        assertEquals(0xFF112233.toInt(), result.style?.face)
        assertTrue(result.warnings.isEmpty())
    }

    @Test
    fun missingStyleWarnsButOverridesStillApply() {
        val result = KeyStyleResolver.resolve(
            "gone",
            mapOf(KeyStyleRole.FACE to "#FF000000"),
            emptyMap(),
            scheme,
        )
        assertEquals(listOf("style_missing:gone"), result.warnings)
        assertEquals(0xFF000000.toInt(), result.style?.face)
    }

    @Test
    fun overrideBeatsNamedStylePerRole() {
        val keyStyles = mapOf(
            "gold" to mapOf(
                KeyStyleRole.FACE to "accentKeyBackground",
                KeyStyleRole.TEXT to "keyText",
            ),
        )
        val result = KeyStyleResolver.resolve(
            "gold",
            mapOf(KeyStyleRole.FACE to "#FF000000"),
            keyStyles,
            scheme,
        )
        // face 被覆盖逐角色胜出，text 仍取引用样式
        assertEquals(0xFF000000.toInt(), result.style?.face)
        assertEquals(10, result.style?.text)
    }

    @Test
    fun unknownValueSkippedWithWarning() {
        val keyStyles = mapOf(
            "bad" to mapOf(
                KeyStyleRole.FACE to "not-a-color-nor-token",
                KeyStyleRole.TEXT to "keyText",
            ),
        )
        val result = KeyStyleResolver.resolve("bad", null, keyStyles, scheme)
        assertNull(result.style?.face)
        assertEquals(10, result.style?.text)
        assertEquals(listOf("style_value_invalid:face"), result.warnings)
    }

    @Test
    fun noStyleNoOverridesResolvesEmpty() {
        val result = KeyStyleResolver.resolve(null, null, emptyMap(), scheme)
        assertNull(result.style)
        assertTrue(result.warnings.isEmpty())
    }

    @Test
    fun conditionalGating_inactiveStyleFallsBack() {
        val keyStyles = mapOf(
            "accent" to mapOf(KeyStyleRole.FACE to "#FF112233"),
        )
        // 被门控但未激活 → 样式不生效（回退空）并记警告
        val inactive = KeyStyleResolver.resolve(
            "accent", null, keyStyles, scheme,
            gatedStyleNames = setOf("accent"), activeStyleNames = emptySet(),
        )
        assertNull(inactive.style)
        assertEquals(listOf("style_condition_inactive:accent"), inactive.warnings)
        // 被门控且激活 → 正常生效
        val active = KeyStyleResolver.resolve(
            "accent", null, keyStyles, scheme,
            gatedStyleNames = setOf("accent"), activeStyleNames = setOf("accent"),
        )
        assertEquals(0xFF112233.toInt(), active.style?.face)
        assertTrue(active.warnings.isEmpty())
        // 未被门控 → 门控参数无影响
        val ungated = KeyStyleResolver.resolve(
            "accent", null, keyStyles, scheme,
            gatedStyleNames = setOf("other"), activeStyleNames = emptySet(),
        )
        assertEquals(0xFF112233.toInt(), ungated.style?.face)
    }
}
