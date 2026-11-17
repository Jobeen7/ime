package com.jobeen.ime.skin.model

import com.jobeen.ime.input.keyboard.window.KeyboardWindowView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 高度决议优先级：布局专用 > 横屏专属 > 通用 > App 默认；
 * 窗口实测（方向、快照）走真机验收。
 */
class LandscapeHeightTest {



    private fun base(geometry: String): String =
        "{\"formatVersion\": 3, \"styles\": {\"keyBackground\": \"#FFFFFFFF\"}, " +
            "\"stylesDark\": {\"keyBackground\": \"#FF222222\"}, \"geometry\": {$geometry}}"



    @Test
    fun resolvePrefersLayoutThenLandscapeThenSkinThenApp() {
        // 布局专用最高。
        assertEquals(
            55,
            KeyboardWindowView.resolveHeightPercent(55, 30, 45, true, 40, 50),
        )
        // 横屏：专属 > 通用 > App 横屏。
        assertEquals(
            45,
            KeyboardWindowView.resolveHeightPercent(null, 30, 45, true, 40, 50),
        )
        assertEquals(
            30,
            KeyboardWindowView.resolveHeightPercent(null, 30, null, true, 40, 50),
        )
        assertEquals(
            50,
            KeyboardWindowView.resolveHeightPercent(null, null, null, true, 40, 50),
        )
        // 竖屏：专属不生效，通用 > App 竖屏。
        assertEquals(
            30,
            KeyboardWindowView.resolveHeightPercent(null, 30, 45, false, 40, 50),
        )
        assertEquals(
            40,
            KeyboardWindowView.resolveHeightPercent(null, null, null, false, 40, 50),
        )
    }
}
