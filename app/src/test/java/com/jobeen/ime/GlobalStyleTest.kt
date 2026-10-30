// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime

import com.jobeen.ime.data.keyboard.theme.BackgroundBlurMode
import com.jobeen.ime.data.keyboard.theme.BackgroundBlurOverride
import com.jobeen.ime.data.keyboard.theme.GlobalStyle
import com.jobeen.ime.data.keyboard.theme.GlobalStyleMode
import com.jobeen.ime.data.keyboard.theme.GlobalStylePrefs
import com.jobeen.ime.data.keyboard.theme.GlobalStyleResolver
import com.jobeen.ime.data.keyboard.theme.SurfaceOverride
import com.jobeen.ime.data.keyboard.theme.resolveGlobalStyleMode
import com.jobeen.ime.skin.model.ImageAdjust
import com.jobeen.ime.skin.model.StyleField
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 全局样式分层纯逻辑：皮肤指定胜出，全局只补空位，越界钳制。 */
class GlobalStyleTest {

    private val style = GlobalStyle(
        key = SurfaceOverride(
            background = 0xFF112233.toInt(),
            borderWidthDp = 2.5f,
            borderColor = 0xFF445566.toInt(),
            cornerRadiusDp = 12f,
        ),
        linkToolbar = true,
        toolbar = SurfaceOverride(background = 0xFF778899.toInt()),
    )

    @Test
    fun keyBackground_skinPresenceBlocksGlobal() {
        assertNull(
            GlobalStyleResolver.keyBackground(style, setOf(StyleField.KEY_BACKGROUND)),
        )
        assertEquals(
            0xFF112233.toInt(),
            GlobalStyleResolver.keyBackground(style, emptySet()),
        )
        assertEquals(
            0xFF112233.toInt(),
            GlobalStyleResolver.specialKeyBackground(style, emptySet()),
        )
        assertNull(
            GlobalStyleResolver.specialKeyBorderStroke(
                style, setOf(StyleField.SPECIAL_KEY_BORDER_STROKE),
            ),
        )
    }

    @Test
    fun cornerRadius_skinPresenceBlocksAndClamps() {
        assertNull(GlobalStyleResolver.keyCornerRadius(style, true))
        assertEquals(12f, GlobalStyleResolver.keyCornerRadius(style, false))
        val wide = style.copy(key = style.key.copy(cornerRadiusDp = 40f))
        assertEquals(32f, GlobalStyleResolver.keyCornerRadius(wide, false))
        val negative = style.copy(key = style.key.copy(cornerRadiusDp = -5f))
        assertEquals(0f, GlobalStyleResolver.keyCornerRadius(negative, false))
        val unset = style.copy(key = style.key.copy(cornerRadiusDp = null))
        assertNull(GlobalStyleResolver.keyCornerRadius(unset, false))
    }

    @Test
    fun borderWidth_defaultsAndClamps() {
        assertEquals(1f, GlobalStyleResolver.clampBorderWidth(null))
        assertEquals(4f, GlobalStyleResolver.clampBorderWidth(10f))
        assertEquals(0f, GlobalStyleResolver.clampBorderWidth(-1f))
        assertEquals(2.5f, GlobalStyleResolver.clampBorderWidth(2.5f))
    }

    @Test
    fun toolbarSurface_linkTakesKeyOtherwiseOwn() {
        val keySurface = SurfaceOverride(
            background = 0xFF112233.toInt(),
            borderWidthDp = 2.5f,
            borderColor = 0xFF445566.toInt(),
        )
        assertEquals(keySurface, GlobalStyleResolver.toolbarSurface(style, keySurface))
        val unlinked = style.copy(linkToolbar = false)
        assertEquals(
            0xFF778899.toInt(),
            GlobalStyleResolver.toolbarSurface(unlinked, keySurface).background,
        )
    }

    @Test
    fun barBackground_skinPresenceBlocks() {
        assertNull(
            GlobalStyleResolver.barBackground(
                style.copy(barBackground = 0xFF000000.toInt()),
                setOf(StyleField.PANEL_BACKGROUND),
            ),
        )
        assertEquals(
            0xFF000000.toInt(),
            GlobalStyleResolver.barBackground(
                style.copy(barBackground = 0xFF000000.toInt()), emptySet(),
            ),
        )
    }

    @Test
    fun effectiveBlur_followKeepsCurrentBehavior() {
        // 跟随＋未降级：调节模糊优先于文件模糊。
        val (blur, adjust) = GlobalStyleResolver.effectiveBlur(
            5f, ImageAdjust(blurRadiusDp = 8f), false,
            BackgroundBlurOverride(BackgroundBlurMode.FOLLOW, null),
        )
        assertEquals(8f, blur)
        assertEquals(8f, adjust?.blurRadiusDp)
        // 跟随＋降级：恒为 0 且调节去模糊。
        val (blur2, adjust2) = GlobalStyleResolver.effectiveBlur(
            5f, ImageAdjust(blurRadiusDp = 8f), true,
            BackgroundBlurOverride(BackgroundBlurMode.FOLLOW, null),
        )
        assertEquals(0f, blur2)
        assertNull(adjust2)
    }

    @Test
    fun effectiveBlur_onOffAndDegradationPriority() {
        // 强制开＋全局半径：半径与调节同写全局值。
        val (blur, adjust) = GlobalStyleResolver.effectiveBlur(
            5f, ImageAdjust(blurRadiusDp = 8f), false,
            BackgroundBlurOverride(BackgroundBlurMode.ON, 20f),
        )
        assertEquals(20f, blur)
        assertEquals(20f, adjust?.blurRadiusDp)
        // 强制开＋无半径＋无调节＋文件模糊为 0：取默认半径。
        val (blur2, _) = GlobalStyleResolver.effectiveBlur(
            0f, null, false,
            BackgroundBlurOverride(BackgroundBlurMode.ON, null),
        )
        assertEquals(GlobalStyleResolver.DEFAULT_BLUR_RADIUS_DP, blur2)
        // 强制关：恒为 0 且调节去模糊。
        val (blur3, adjust3) = GlobalStyleResolver.effectiveBlur(
            5f, ImageAdjust(blurRadiusDp = 8f), false,
            BackgroundBlurOverride(BackgroundBlurMode.OFF, null),
        )
        assertEquals(0f, blur3)
        assertNull(adjust3)
        // 降级优先于全局强制开：仍为 0。
        val (blur4, _) = GlobalStyleResolver.effectiveBlur(
            5f, ImageAdjust(blurRadiusDp = 8f), true,
            BackgroundBlurOverride(BackgroundBlurMode.ON, 20f),
        )
        assertEquals(0f, blur4)
    }

    @Test
    fun dualMode_snapshotDecidesMode() {
        assertEquals(GlobalStyleMode.CUSTOM, resolveGlobalStyleMode(true))
        assertEquals(GlobalStyleMode.THEME, resolveGlobalStyleMode(false))
    }

    @Test
    fun dualMode_themeKeysKeepLegacyNames_customKeysUseNewPrefix() {
        assertEquals("global_style.key_bg", GlobalStylePrefs.KEY_KEY_BG)
        assertEquals(
            "global_style.custom.key_bg",
            GlobalStylePrefs.keyFor(GlobalStyleMode.CUSTOM, "key_bg"),
        )
        assertEquals(
            "global_style.key_bg",
            GlobalStylePrefs.keyFor(GlobalStyleMode.THEME, "key_bg"),
        )
        // 两模式键集互不重叠，且监听集覆盖两模式。
        val themeKeys = GlobalStyleMode.THEME.let { mode ->
            setOf(GlobalStylePrefs.keyFor(mode, "key_bg"))
        }
        val customKeys = setOf(GlobalStylePrefs.keyFor(GlobalStyleMode.CUSTOM, "key_bg"))
        assertTrue(themeKeys.intersect(customKeys).isEmpty())
        assertTrue(GlobalStylePrefs.ALL_KEYS.containsAll(themeKeys + customKeys))
        assertTrue(
            GlobalStylePrefs.ALL_KEYS.contains(
                GlobalStylePrefs.keyFor(GlobalStyleMode.CUSTOM, "frost_overlay"),
            ),
        )
    }

    @Test
    fun frostOverlay_nullDefaultsToQuarter_explicitZeroMeansNoLayer_outOfRangeClamped() {
        assertEquals(
            0.25f,
            GlobalStyleResolver.effectiveFrostOverlay(GlobalStyle()),
        )
        assertNull(
            GlobalStyleResolver.effectiveFrostOverlay(GlobalStyle(frostOverlayAlpha = 0f)),
        )
        assertEquals(
            0.5f,
            GlobalStyleResolver.effectiveFrostOverlay(
                GlobalStyle(frostOverlayAlpha = 0.5f),
            ),
        )
        assertEquals(
            1f,
            GlobalStyleResolver.effectiveFrostOverlay(
                GlobalStyle(frostOverlayAlpha = 1.5f),
            ),
        )
        assertNull(
            GlobalStyleResolver.effectiveFrostOverlay(
                GlobalStyle(frostOverlayAlpha = -0.2f),
            ),
        )
    }
}
