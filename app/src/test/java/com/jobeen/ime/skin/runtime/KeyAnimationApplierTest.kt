package com.jobeen.ime.skin.runtime

import com.jobeen.ime.data.keyboard.theme.KeyboardColors
import com.jobeen.ime.input.keyboard.impl.QwertyKeyboard
import com.jobeen.ime.input.keyboard.key.KeyDef.Appearance.Variant
import com.jobeen.ime.skin.model.KeyAnimationDef
import com.jobeen.ime.skin.model.KeyStyleGeometry
import com.jobeen.ime.skin.model.SkinSnapshot
import com.jobeen.ime.skin.model.SoundBindings
import com.jobeen.ime.skin.store.SkinGenRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** 样式动效装配（审计 H 项）：动效名经表解析、图片帧过滤接纳资产。 */
class KeyAnimationApplierTest {

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
        imageAssets = mapOf("frame-a" to "sha-a"),
        keyGeometries = mapOf(
            "anim" to KeyStyleGeometry(
                animationNames = listOf("scaleAnim", "cartoonAnim", "goneAnim"),
            ),
        ),
        keyAnimations = mapOf(
            "scaleAnim" to KeyAnimationDef.Scale(0.8f, 90, 90, true),
            "cartoonAnim" to KeyAnimationDef.Cartoon(
                listOf("frame-a", "frame-missing"), 24, null, null,
            ),
        ),
    )

    @Test
    fun styleAnimationsAttachWithAdmittedFramesOnly() {
        val layout = QwertyKeyboard.buildLayout(Variant.Accent)
        val target = layout.flatten().firstOrNull { it.id != null }
        assertNotNull(target)
        target!!.skinStyleRef = "anim"
        val result = KeyOverrideApplier.apply("Qwerty", layout, snapshot(), emptyMap())
        val styled = result.flatten().first { it.id == target.id }
        val animations = styled.appearance.skinAnimations
        assertNotNull(animations)
        // scale 直通；cartoon 只留已接纳帧；未知动效名丢弃。
        assertEquals(2, animations!!.size)
        assertEquals(
            KeyAnimationDef.Scale(0.8f, 90, 90, true),
            animations[0],
        )
        assertEquals(
            KeyAnimationDef.Cartoon(listOf("frame-a"), 24, null, null),
            animations[1],
        )
        assertNull(target.appearance.skinAnimations)
    }

    @Test
    fun cartoonWithNoAdmittedFramesIsDropped() {
        val layout = QwertyKeyboard.buildLayout(Variant.Accent)
        val target = layout.flatten().firstOrNull { it.id != null }
        assertNotNull(target)
        target!!.skinStyleRef = "anim"
        val snap = snapshot().copy(imageAssets = emptyMap())
        val result = KeyOverrideApplier.apply("Qwerty", layout, snap, emptyMap())
        val styled = result.flatten().first { it.id == target.id }
        // cartoon 全滤掉后只剩 scale。
        assertEquals(
            listOf(KeyAnimationDef.Scale(0.8f, 90, 90, true)),
            styled.appearance.skinAnimations,
        )
    }
}
