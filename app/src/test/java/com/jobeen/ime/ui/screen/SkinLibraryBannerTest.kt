package com.jobeen.ime.ui.screen

import com.jobeen.ime.data.keyboard.theme.KeyboardColors
import com.jobeen.ime.skin.model.LayoutResolution
import com.jobeen.ime.skin.model.SkinSnapshot
import com.jobeen.ime.skin.store.SkinGenRef
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * K-M4：库页布局回退常驻提示面——[activeLayoutMissing] 的判定口径单测。
 * 横幅可见性即本函数的返回值（SkinLibraryScreen 内 item 按此值渲染，
 * 文案固定为“绑定的自定义布局缺失／无效，已回退出厂布局，请重新导入皮肤”）；
 * Compose 渲染本身走真机验收。
 */
class SkinLibraryBannerTest {

    private val scheme = KeyboardColors.ColorScheme(
        keyBackground = 1, keyPressed = 2, keyBorderStroke = 3,
        specialKeyBackground = 4, specialKeyPressed = 5, specialKeyBorderStroke = 6,
        accentKeyBackground = 7, accentKeyPressed = 8, accentKeyBorderStroke = 9,
        keyText = 10, specialKeyText = 11, accentKeyText = 12, altText = 13,
        background = 14,
        panel = KeyboardColors.ColorScheme.PanelColors(
            background = 15, toolbarText = 16, toolbarActived = 17, toolbarIcon = 18,
            candidateBackground = 19, candidateText = 20, candidateIndex = 21,
            candidateDivider = 22, toolbarPressed = 23,
        ),
        pinner = KeyboardColors.ColorScheme.PinnerColors(
            background = 24, textColor = 25, secondaryTextColor = 26,
        ),
    )

    private fun snapshot(skinId: String, resolution: LayoutResolution): SkinSnapshot =
        SkinSnapshot(
            ref = SkinGenRef(skinId, "h", 1),
            name = "S",
            colorScheme = scheme,
            geometryPresence = emptySet(),
            effectiveGeometry = emptyMap(),
            background = null,
            degradations = emptySet(),
            layoutId = "lay-1",
            layoutResolution = resolution,
        )

    @Test
    fun missingLayoutShowsBanner() {
        assertTrue(activeLayoutMissing(snapshot("skin-1", LayoutResolution.MISSING), "skin-1"))
    }

    @Test
    fun invalidLayoutShowsBanner() {
        assertTrue(activeLayoutMissing(snapshot("skin-1", LayoutResolution.INVALID), "skin-1"))
    }

    @Test
    fun resolvedLayoutHidesBanner() {
        assertFalse(activeLayoutMissing(snapshot("skin-1", LayoutResolution.RESOLVED), "skin-1"))
    }

    @Test
    fun unsetLayoutHidesBanner() {
        assertFalse(activeLayoutMissing(snapshot("skin-1", LayoutResolution.NOT_SET), "skin-1"))
    }

    @Test
    fun otherSkinsResolutionDoesNotShowBanner() {
        assertFalse(activeLayoutMissing(snapshot("skin-1", LayoutResolution.MISSING), "skin-2"))
    }

    @Test
    fun noSnapshotHidesBanner() {
        assertFalse(activeLayoutMissing(null, "skin-1"))
    }

    @Test
    fun noActiveSkinHidesBanner() {
        assertFalse(activeLayoutMissing(snapshot("skin-1", LayoutResolution.MISSING), null))
    }
}
