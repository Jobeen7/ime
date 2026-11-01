package com.jobeen.ime.skin.model

import com.jobeen.ime.input.keyboard.window.KeyboardWindowView
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 横屏专属高度字段（皮肤 geometry heightPercentLandscape）：
 * 校验（区间 20..70、逐项回退）＋高度决议优先级；
 * 窗口实测（方向、快照）走真机验收。
 */
class LandscapeHeightTest {

    private val fallbacks = GeometryFallbacks(12f, 6f, 5f, 10f)

    private fun validate(json: String): SkinValidator.Result.Valid {
        val tree = Json.parseToJsonElement(json) as JsonObject
        val result = SkinValidator.validate(tree, 157, fallbacks, assetKind = { null })
        assertTrue("expected Valid, got $result", result is SkinValidator.Result.Valid)
        return result as SkinValidator.Result.Valid
    }

    private fun base(geometry: String): String =
        "{\"formatVersion\": 3, \"styles\": {\"keyBackground\": \"#FFFFFFFF\"}, " +
            "\"stylesDark\": {\"keyBackground\": \"#FF222222\"}, \"geometry\": {$geometry}}"

    @Test
    fun landscapeFieldValidatesAndCoexistsWithPortrait() {
        val skin = validate(base("\"heightPercent\": 30, \"heightPercentLandscape\": 45")).skin
        assertTrue(KeyboardGeometryField.HEIGHT_PERCENT in skin.keyboardGeometry.presence)
        assertTrue(KeyboardGeometryField.HEIGHT_PERCENT_LANDSCAPE in skin.keyboardGeometry.presence)
        assertEquals(30f, skin.keyboardGeometry.values.getValue(KeyboardGeometryField.HEIGHT_PERCENT))
        assertEquals(
            45f,
            skin.keyboardGeometry.values.getValue(KeyboardGeometryField.HEIGHT_PERCENT_LANDSCAPE),
        )
    }

    @Test
    fun landscapeFieldOutOfRangeFallsBackPerField() {
        val skin = validate(base("\"heightPercent\": 30, \"heightPercentLandscape\": 71")).skin
        assertTrue(KeyboardGeometryField.HEIGHT_PERCENT in skin.keyboardGeometry.presence)
        assertFalse(KeyboardGeometryField.HEIGHT_PERCENT_LANDSCAPE in skin.keyboardGeometry.presence)
    }

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
