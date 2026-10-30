package com.jobeen.ime.skin.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 参考三项的校验层：popupChars / icon / imageAdjust /
 * 自创布局命名空间键的接纳与回退。
 */
class RefValidatorTest {

    private val fallbacks = GeometryFallbacks(12f, 6f, 5f, 10f)

    private fun validate(json: String): SkinValidator.Result.Valid {
        val tree = Json.parseToJsonElement(json) as JsonObject
        val result = SkinValidator.validate(
            tree, 157, fallbacks, assetKind = { id ->
                when (id) {
                    "bg_main" -> AssetKind.IMAGE
                    else -> null
                }
            },
        )
        assertTrue("expected Valid, got $result", result is SkinValidator.Result.Valid)
        return result as SkinValidator.Result.Valid
    }

    private fun base(keyMappings: String, backgroundExtra: String = ""): String =
        """{"formatVersion": 3, "styles": {"keyBackground": "#FFFFFFFF"},
           "stylesDark": {"keyBackground": "#FF222222"},
           "appearance": {"background": {"type": "asset", "asset": "bg_main" $backgroundExtra}},
           "keyMappings": $keyMappings}"""

    @Test
    fun popupCharsKeptAndBadItemsDroppedPerItem() {
        val valid = validate(
            base(
                """{"KEY_Q": {"popupChars": ["，", "。", "", 5, "toolongpopupchar"]}}""",
            ),
        )
        val override = valid.skin.keyOverrides.getValue("KEY_Q")
        assertEquals(listOf("，", "。"), override.popupChars)
        // 3 项非法（空串、数字、超长）→ 3 条剔除记录
        assertEquals(3, valid.skin.keyRejections.size)
        assertTrue(valid.issues.any { it.path == "keyMappings.KEY_Q" })
    }

    @Test
    fun popupCharsEmptyArrayIsDefault() {
        val valid = validate(base("""{"KEY_Q": {"popupChars": []}}"""))
        // 空数组 = 不覆写：该键不进覆写表
        assertNull(valid.skin.keyOverrides["KEY_Q"])
    }

    @Test
    fun popupCharsNonArrayDropped() {
        val valid = validate(base("""{"KEY_Q": {"popupChars": "abc"}}"""))
        assertNull(valid.skin.keyOverrides["KEY_Q"])
        assertEquals(1, valid.skin.keyRejections.size)
    }

    @Test
    fun iconKeptEvenWhenUnknownName() {
        // 未知名不在解析期拒绝（装配期回退），已知/未知一律保留原值
        val valid = validate(
            base(
                """{"KEY_Q": {"icon": "mic"}, "T9_KEY_2": {"icon": "not_a_symbol_xyz"}}""",
            ),
        )
        assertEquals("mic", valid.skin.keyOverrides.getValue("KEY_Q").icon)
        assertEquals("not_a_symbol_xyz", valid.skin.keyOverrides.getValue("T9_KEY_2").icon)
    }

    @Test
    fun iconEmptyOrNonStringDropped() {
        val valid = validate(base("""{"KEY_Q": {"icon": ""}}"""))
        assertNull(valid.skin.keyOverrides["KEY_Q"])
    }

    @Test
    fun customLayoutNamespaceKeyAccepted() {
        val valid = validate(
            base(
                """{"demo_layout:K-r0c1": {"popupChars": ["a", "b"], "icon": "mic"}}""",
            ),
        )
        val override = valid.skin.keyOverrides.getValue("demo_layout:K-r0c1")
        assertEquals(listOf("a", "b"), override.popupChars)
        assertEquals("mic", override.icon)
        assertTrue(valid.skin.keyRejections.isEmpty())
    }

    @Test
    fun imageAdjustParsedIntoBackgroundSpec() {
        val valid = validate(
            base(
                "{}",
                """, "imageAdjust": {"brightnessPercent": 80, "rotationDegrees": 90,
                    "cropLeft": 0.1, "cropRight": 0.1, "blurRadiusDp": 6}""",
            ),
        )
        val adjust = valid.skin.appearance.background!!.imageAdjust!!
        assertEquals(80f, adjust.brightnessPercent)
        assertEquals(90f, adjust.rotationDegrees)
        assertEquals(6f, adjust.blurRadiusDp)
        assertEquals(0.1f, adjust.cropLeft)
    }

    @Test
    fun imageAdjustInvalidFieldsFallBackPerField() {
        val valid = validate(
            base(
                "{}",
                """, "imageAdjust": {"brightnessPercent": 999, "rotationDegrees": 45}""",
            ),
        )
        val adjust = valid.skin.appearance.background!!.imageAdjust!!
        assertNull(adjust.brightnessPercent)
        assertEquals(45f, adjust.rotationDegrees)
        assertTrue(
            valid.issues.any {
                it.path == "appearance.background.imageAdjust.brightnessPercent"
            },
        )
    }
}
