// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.skin.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 主题视频背景校验字段（2026-10-29 外观轮）：只认 VIDEO 类，错类／缺失只丢字段。 */
class VideoBackgroundValidatorTest {

    private val fallbacks = GeometryFallbacks(
        cornerRadius = 12f, keyHMargin = 6f, keyVMargin = 5f, bottomInsetDp = 10f,
    )
    private val assetKinds = mapOf(
        "bg_main" to AssetKind.IMAGE,
        "vid_main" to AssetKind.VIDEO,
    )

    private fun validate(json: String): SkinValidator.Result.Valid {
        val tree = Json.parseToJsonElement(json) as JsonObject
        val result = SkinValidator.validate(tree, 143, fallbacks, { assetKinds[it] })
        assertTrue("expected Valid, got $result", result is SkinValidator.Result.Valid)
        return result as SkinValidator.Result.Valid
    }

    private fun backgroundJson(video: String?): String {
        val videoPart = if (video != null) ""","video":"$video"""" else ""
        return """{"formatVersion":3,"styles":{"keyBackground":"#FFFFFFFF"},"stylesDark":{"keyBackground":"#FF222222"},""" +
            """"appearance":{"background":{"type":"asset","asset":"bg_main"$videoPart}}}"""
    }

    @Test
    fun videoAssetAccepted() {
        val result = validate(backgroundJson("vid_main"))
        assertEquals("vid_main", result.skin.appearance.background?.videoAssetId)
    }

    @Test
    fun imageAssetAsVideoDroppedWithIssue() {
        val result = validate(backgroundJson("bg_main"))
        assertNull(result.skin.appearance.background?.videoAssetId)
        assertTrue(
            result.issues.any {
                it.path == "appearance.background.video" &&
                    it.detail == "background_video_wrong_type"
            },
        )
        // 静态段仍有效（字段级回退，不是整段回退）。
        assertEquals("bg_main", result.skin.appearance.background?.assetId)
    }

    @Test
    fun missingVideoDroppedWithIssue() {
        val result = validate(backgroundJson("nope"))
        assertNull(result.skin.appearance.background?.videoAssetId)
        assertTrue(
            result.issues.any {
                it.path == "appearance.background.video" &&
                    it.detail == "background_video_missing"
            },
        )
        assertEquals("bg_main", result.skin.appearance.background?.assetId)
    }

    @Test
    fun noVideoFieldStaysNull() {
        val result = validate(backgroundJson(null))
        assertNull(result.skin.appearance.background?.videoAssetId)
    }
}
