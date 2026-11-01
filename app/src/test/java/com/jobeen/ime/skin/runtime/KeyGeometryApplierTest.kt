package com.jobeen.ime.skin.runtime

import com.jobeen.ime.data.keyboard.theme.KeyboardColors
import com.jobeen.ime.input.keyboard.impl.QwertyKeyboard
import com.jobeen.ime.input.keyboard.key.KeyDef.Appearance.Variant
import com.jobeen.ime.skin.model.KeyCornerCurve
import com.jobeen.ime.skin.model.KeyGradient
import com.jobeen.ime.skin.model.KeyGradientType
import com.jobeen.ime.skin.model.KeyPerStyleShadow
import com.jobeen.ime.skin.model.KeyShadowLayer
import com.jobeen.ime.skin.model.KeyStyleGeometry
import com.jobeen.ime.skin.model.KeyStyleRole
import com.jobeen.ime.skin.model.KeyVisibleBounds
import com.jobeen.ime.skin.model.KeyBoundsHorizontal
import com.jobeen.ime.skin.model.SkinSnapshot
import com.jobeen.ime.skin.model.SoundBindings
import com.jobeen.ime.skin.store.SkinGenRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** 样式几何面装配（审计 A 项）：几何落到 Appearance 槽位，角色值作其次。 */
class KeyGeometryApplierTest {

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

    private val geometry = KeyStyleGeometry(
        gradientNormal = KeyGradient(
            listOf(0x8078787D.toInt(), 0x475C5C60.toInt()),
            listOf(0f, 1f), KeyGradientType.LINEAR,
        ),
        shadow = KeyPerStyleShadow(
            normal = KeyShadowLayer(0x50000000.toInt(), 0.08f, 4f, 0f, 5f),
            pressed = null,
        ),
        borderWidthDp = 1f,
        cornerRadiusDp = 7f,
        cornerCurve = KeyCornerCurve.CONTINUOUS,
        normalBorderColor = 0x26FFFFFF.toInt(),
        highlightBorderColor = 0x40FFFFFF.toInt(),
        normalLowerEdgeColor = 0xFF1E1E1E.toInt(),
        highlightLowerEdgeColor = 0xFF1D1D1D.toInt(),
        bounds = KeyVisibleBounds(2f / 3f, KeyBoundsHorizontal.RIGHT),
    )

    private fun snapshot() = SkinSnapshot(
        ref = SkinGenRef("skin-1", "h".repeat(64), 1),
        name = "test",
        colorScheme = scheme,
        geometryPresence = emptySet(),
        effectiveGeometry = emptyMap(),
        background = null,
        degradations = emptySet(),
        layoutId = null,
        soundBindings = SoundBindings.EMPTY,
        keyStyles = mapOf(
            "geo" to mapOf(KeyStyleRole.BORDER to "#FF0000FF"),
        ),
        keyGeometries = mapOf("geo" to geometry),
    )

    @Test
    fun styleGeometryAttachesToAppearance() {
        val layout = QwertyKeyboard.buildLayout(Variant.Accent)
        val target = layout.flatten().firstOrNull { it.id != null }
        assertNotNull(target)
        target!!.skinStyleRef = "geo"
        val result = KeyOverrideApplier.apply("Qwerty", layout, snapshot(), emptyMap())
        val styled = result.flatten().first { it.id == target.id }
        assertEquals(geometry.gradientNormal, styled.appearance.skinGradient)
        assertEquals(geometry.shadow, styled.appearance.skinShadow)
        assertEquals(1f, styled.appearance.skinBorderWidthDp)
        assertEquals(7f, styled.appearance.skinCornerRadiusDp)
        assertEquals(KeyCornerCurve.CONTINUOUS, styled.appearance.skinCornerCurve)
        // 几何面字面描边优先于角色描边（角色蓝被几何白覆盖）。
        assertEquals(0x26FFFFFF.toInt(), styled.appearance.skinBorderColor)
        assertEquals(0x40FFFFFF.toInt(), styled.appearance.skinPressedBorderColor)
        assertEquals(0xFF1E1E1E.toInt(), styled.appearance.skinLowerEdgeColor)
        assertEquals(0xFF1D1D1D.toInt(), styled.appearance.skinPressedLowerEdgeColor)
        assertEquals(2f / 3f, styled.appearance.skinVisibleBounds?.widthFraction)
        assertEquals(KeyBoundsHorizontal.RIGHT, styled.appearance.skinVisibleBounds?.horizontal)
        // 输入布局不被改动（复制纪律）。
        assertNull(target.appearance.skinGradient)
    }

    @Test
    fun roleBorderFallsBackWhenGeometryHasNoBorderColor() {
        val layout = QwertyKeyboard.buildLayout(Variant.Accent)
        val target = layout.flatten().firstOrNull { it.id != null }
        assertNotNull(target)
        target!!.skinStyleRef = "geo"
        val snap = snapshot().copy(
            keyGeometries = mapOf(
                "geo" to geometry.copy(
                    normalBorderColor = null, highlightBorderColor = null,
                ),
            ),
        )
        val result = KeyOverrideApplier.apply("Qwerty", layout, snap, emptyMap())
        val styled = result.flatten().first { it.id == target.id }
        assertEquals(0xFF0000FF.toInt(), styled.appearance.skinBorderColor)
        assertNull(styled.appearance.skinPressedBorderColor)
    }
}
