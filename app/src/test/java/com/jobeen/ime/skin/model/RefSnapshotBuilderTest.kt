package com.jobeen.ime.skin.model

import com.jobeen.ime.data.keyboard.theme.KeyboardColors
import com.jobeen.ime.skin.store.SkinGenRef
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 参考项的快照构建：imageAdjust 模糊取代、产物键、降级剥除。 */
class RefSnapshotBuilderTest {

    private val fallbacks = GeometryFallbacks(12f, 6f, 5f, 10f)
    private val ref = SkinGenRef("skin-1", "h".repeat(64), 1)

    private val defaultScheme = KeyboardColors.ColorScheme(
        keyBackground = 100, keyPressed = 101, keyBorderStroke = 102,
        specialKeyBackground = 200, specialKeyPressed = 201, specialKeyBorderStroke = 202,
        accentKeyBackground = 300, accentKeyPressed = 301, accentKeyBorderStroke = 302,
        keyText = 400, specialKeyText = 401, accentKeyText = 402, altText = 403,
        background = 500, cornerRadius = 5f, keyHMargin = 3f, keyVMargin = 4f,
        panel = KeyboardColors.ColorScheme.PanelColors(
            background = 600, toolbarText = 601, toolbarActived = 602, toolbarIcon = 603,
            candidateBackground = 604, candidateText = 605, candidateIndex = 606,
            candidateDivider = 607, toolbarPressed = 608,
        ),
        pinner = KeyboardColors.ColorScheme.PinnerColors(
            background = 700, textColor = 701, secondaryTextColor = 702,
        ),
        toastBackground = 999, toastText = 998,
    )

    private fun build(json: String, tier: MemoryTier = MemoryTier.NORMAL): SkinSnapshot {
        val tree = Json.parseToJsonElement(json) as JsonObject
        val result = SkinValidator.validate(tree, 157, fallbacks, assetKind = { id ->
            if (id == "bg_main") AssetKind.IMAGE else null
        })
        assertTrue(result is SkinValidator.Result.Valid)
        val validated = (result as SkinValidator.Result.Valid).skin
        return SkinSnapshotBuilder.build(
            validated, ref, defaultScheme, tier, { "a".repeat(64) }, 1000, 400,
        )
    }

    private fun bgJson(adjust: String): String =
        """{"formatVersion": 3, "styles": {"keyBackground": "#FFFFFFFF"},
           "stylesDark": {"keyBackground": "#FF222222"},
           "appearance": {"background": {"type": "asset", "asset": "bg_main",
             "blurRadius": 10, "overlayAlpha": 0.2, "imageAdjust": $adjust}}}"""

    @Test
    fun adjustBlurReplacesBackgroundBlur() {
        val snapshot = build(bgJson("""{"blurRadiusDp": 4, "brightnessPercent": 120}"""))
        // 调节 blur 4 取代背景 blur 10（不叠加）
        assertEquals(4f, snapshot.background!!.blurRadius)
        assertEquals(120f, snapshot.background.imageAdjust!!.brightnessPercent)
    }

    @Test
    fun noAdjustKeepsLegacyPreparedKey() {
        val snapshot = build(
            """{"formatVersion": 3, "styles": {"keyBackground": "#FFFFFFFF"},
               "stylesDark": {"keyBackground": "#FF222222"},
               "appearance": {"background": {"type": "asset", "asset": "bg_main",
                 "blurRadius": 10, "overlayAlpha": 0.2}}}""",
        )
        assertNull(snapshot.background!!.imageAdjust)
        assertEquals(
            SkinSnapshotBuilder.preparedKey("a".repeat(64), 1000, 400, 10f),
            snapshot.background.preparedKey,
        )
    }

    @Test
    fun adjustChangesPreparedKey() {
        val plain = build(
            """{"formatVersion": 3, "styles": {"keyBackground": "#FFFFFFFF"},
               "stylesDark": {"keyBackground": "#FF222222"},
               "appearance": {"background": {"type": "asset", "asset": "bg_main",
                 "blurRadius": 10, "overlayAlpha": 0.2}}}""",
        )
        val adjusted = build(bgJson("""{"brightnessPercent": 80}"""))
        assertTrue(adjusted.background!!.preparedKey != plain.background!!.preparedKey)
        assertTrue(adjusted.background.preparedKey.startsWith(plain.background.preparedKey))
    }

    @Test
    fun extremeTierStripsAdjustBlurButKeepsOtherAdjusts() {
        // EXTREME 档位降级关模糊：调节 blur 被剥除，亮度保留，
        // 生效 blur 为 0，产物键不再含 blur 调节后缀。
        val snapshot = build(
            bgJson("""{"blurRadiusDp": 4, "brightnessPercent": 120}"""),
            tier = MemoryTier.EXTREME,
        )
        assertEquals(0f, snapshot.background!!.blurRadius)
        val adjust = snapshot.background.imageAdjust!!
        assertNull(adjust.blurRadiusDp)
        assertEquals(120f, adjust.brightnessPercent)
    }
}
