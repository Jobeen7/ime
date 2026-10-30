package com.jobeen.ime.skin.runtime

import com.jobeen.ime.data.keyboard.theme.KeyboardColors
import com.jobeen.ime.input.keyboard.impl.QwertyKeyboard
import com.jobeen.ime.input.keyboard.key.KeyDef
import com.jobeen.ime.input.keyboard.key.KeyDef.Appearance.Variant
import com.jobeen.ime.skin.model.KeyOverride
import com.jobeen.ime.skin.model.KeyStyleRole
import com.jobeen.ime.skin.model.SkinSnapshot
import com.jobeen.ime.skin.model.SoundBindings
import com.jobeen.ime.skin.store.SkinGenRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** 覆写装配器样式挂接（2026-10-27 并入项）：解析色落到 Appearance 槽位。 */
class KeyStyleApplierTest {

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

    private fun snapshot(
        overrides: Map<String, KeyOverride> = emptyMap(),
        keyStyles: Map<String, Map<KeyStyleRole, String>> = emptyMap(),
    ) = SkinSnapshot(
        ref = SkinGenRef("skin-1", "h".repeat(64), 1),
        name = "test",
        colorScheme = scheme,
        geometryPresence = emptySet(),
        effectiveGeometry = emptyMap(),
        background = null,
        degradations = emptySet(),
        layoutId = null,
        keyOverrides = overrides,
        soundBindings = SoundBindings.EMPTY,
        keyStyles = keyStyles,
    )

    private val goldTable = mapOf(
        "gold" to mapOf(
            KeyStyleRole.FACE to "accentKeyBackground",
            KeyStyleRole.TEXT to "#FF000000",
        ),
    )

    @Test
    fun layoutKeyStyleRefAttachesResolvedColors() {
        val layout = QwertyKeyboard.buildLayout(Variant.Accent)
        val target = layout.flatten().firstOrNull { it.id != null }
        assertNotNull(target)
        target!!.skinStyleRef = "gold"
        val result = KeyOverrideApplier.apply(
            "Qwerty", layout, snapshot(keyStyles = goldTable), emptyMap(),
        )
        val styled = result.flatten().first { it.id == target.id }
        // 令牌 accentKeyBackground → 方案值 7；字面色直取
        assertEquals(7, styled.appearance.skinFaceColor)
        assertEquals(0xFF000000.toInt(), styled.appearance.skinTextColor)
        // 输入布局不被改动（复制纪律）
        assertNull(target.appearance.skinFaceColor)
    }

    @Test
    fun styleOnlyOverrideCountsAsEffect() {
        val layout = QwertyKeyboard.buildLayout(Variant.Accent)
        val target = layout.flatten().firstOrNull { it.id != null }
        assertNotNull(target)
        val overrides = mapOf(
            target!!.id!! to KeyOverride(styleRef = "gold"),
        )
        val result = KeyOverrideApplier.apply(
            "Qwerty", layout, snapshot(overrides, goldTable), emptyMap(),
        )
        val styled = result.flatten().first { it.id == target.id }
        assertEquals(7, styled.appearance.skinFaceColor)
        assertEquals(0xFF000000.toInt(), styled.appearance.skinTextColor)
    }

    @Test
    fun missingStyleFallsBackToSchemeDefault() {
        val layout = QwertyKeyboard.buildLayout(Variant.Accent)
        val target = layout.flatten().firstOrNull { it.id != null }
        assertNotNull(target)
        target!!.skinStyleRef = "gone"
        val result = KeyOverrideApplier.apply(
            "Qwerty", layout, snapshot(keyStyles = goldTable), emptyMap(),
        )
        val styled = result.flatten().first { it.id == target.id }
        // 引用缺失 → 该键无样式槽（保持方案默认），不崩溃不拒整键
        assertNull(styled.appearance.skinFaceColor)
        assertNull(styled.appearance.skinTextColor)
    }

    @Test
    fun keyMappingStyleOverridesBeatLayoutKeyStyle() {
        val layout = QwertyKeyboard.buildLayout(Variant.Accent)
        val target = layout.flatten().firstOrNull { it.id != null }
        assertNotNull(target)
        target!!.skinStyleRef = "gold"
        val overrides = mapOf(
            target.id!! to KeyOverride(
                styleOverrides = mapOf(KeyStyleRole.FACE to "#FF112233"),
            ),
        )
        val result = KeyOverrideApplier.apply(
            "Qwerty", layout, snapshot(overrides, goldTable), emptyMap(),
        )
        val styled = result.flatten().first { it.id == target.id }
        // 逐角色：face 被键映射覆盖胜出，text 仍取布局键引用样式
        assertEquals(0xFF112233.toInt(), styled.appearance.skinFaceColor)
        assertEquals(0xFF000000.toInt(), styled.appearance.skinTextColor)
    }
}
