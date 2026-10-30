package com.jobeen.ime.skin.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 背景图调节解析（参考项）：逐项回退、永不拒皮。 */
class ImageAdjustTest {

    private fun parse(json: String): Pair<ImageAdjust?, List<SkinIssue>> {
        val tree = Json.parseToJsonElement(json) as JsonObject
        val issues = mutableListOf<SkinIssue>()
        val adjust = ImageAdjust.parse(tree["imageAdjust"], "appearance.background.imageAdjust", issues)
        return adjust to issues
    }

    @Test
    fun allFieldsValid() {
        val (adjust, issues) = parse(
            """{"imageAdjust": {"brightnessPercent": 120, "blurRadiusDp": 8,
               "rotationDegrees": 90, "cropLeft": 0.1, "cropTop": 0.05,
               "cropRight": 0.2, "cropBottom": 0.1}}""",
        )
        assertTrue(issues.isEmpty())
        assertEquals(120f, adjust!!.brightnessPercent)
        assertEquals(8f, adjust.blurRadiusDp)
        assertEquals(90f, adjust.rotationDegrees)
        assertEquals(0.1f, adjust.cropLeft)
        assertEquals(0.2f, adjust.cropRight)
    }

    @Test
    fun invalidFieldsDroppedIndividually() {
        val (adjust, issues) = parse(
            """{"imageAdjust": {"brightnessPercent": 250, "blurRadiusDp": -3,
               "rotationDegrees": "90", "cropLeft": 0.1}}""",
        )
        // 亮度/模糊/旋转三项非法被丢弃，裁剪保留 → 仍有调节
        assertEquals(3, issues.size)
        assertNull(adjust!!.brightnessPercent)
        assertNull(adjust.blurRadiusDp)
        assertNull(adjust.rotationDegrees)
        assertEquals(0.1f, adjust.cropLeft)
    }

    @Test
    fun cropAxisOverflowDropsBothSides() {
        val (adjust, issues) = parse(
            """{"imageAdjust": {"cropLeft": 0.6, "cropRight": 0.5, "cropTop": 0.1}}""",
        )
        assertEquals(2, issues.size)
        assertNull(adjust!!.cropLeft)
        assertNull(adjust.cropRight)
        assertEquals(0.1f, adjust.cropTop)
    }

    @Test
    fun singleFullEdgeDropped() {
        val (adjust, issues) = parse("""{"imageAdjust": {"cropLeft": 1, "cropTop": 0.1}}""")
        assertEquals(1, issues.size)
        assertNull(adjust!!.cropLeft)
        assertEquals(0.1f, adjust.cropTop)
    }

    @Test
    fun allInvalidBecomesNoAdjust() {
        val (adjust, issues) = parse("""{"imageAdjust": {"brightnessPercent": 999}}""")
        assertEquals(1, issues.size)
        assertNull(adjust)
    }

    @Test
    fun nonObjectAndMissingAreNoAdjust() {
        val (fromScalar, issues1) = parse("""{"imageAdjust": 5}""")
        assertNull(fromScalar)
        assertEquals(1, issues1.size)
        val (fromMissing, issues2) = parse("""{}""")
        assertNull(fromMissing)
        assertTrue(issues2.isEmpty())
    }

    @Test
    fun preparedKeySuffixDistinguishesCropSides() {
        // 不同边/不同项不得拼出同键（否则产物复用错图）
        val leftOnly = ImageAdjust(cropLeft = 0.1f).preparedKeySuffix()
        val topOnly = ImageAdjust(cropTop = 0.1f).preparedKeySuffix()
        val rightOnly = ImageAdjust(cropRight = 0.1f).preparedKeySuffix()
        assertTrue(leftOnly != topOnly && topOnly != rightOnly && leftOnly != rightOnly)
        assertTrue(
            ImageAdjust(brightnessPercent = 120f).preparedKeySuffix() !=
                ImageAdjust(blurRadiusDp = 12f).preparedKeySuffix(),
        )
    }

    @Test
    fun preparedKeySuffixEmptyWithoutAdjustAndStableWithAdjust() {
        assertEquals("", ImageAdjust().preparedKeySuffix())
        val a = ImageAdjust(brightnessPercent = 120f, rotationDegrees = 90f)
        val b = ImageAdjust(brightnessPercent = 120f, rotationDegrees = 450f)
        // 旋转归一化：90° 与 450° 同产物键
        assertEquals(a.preparedKeySuffix(), b.preparedKeySuffix())
        assertTrue(a.preparedKeySuffix().isNotEmpty())
        val c = ImageAdjust(brightnessPercent = 80f, rotationDegrees = 90f)
        assertTrue(a.preparedKeySuffix() != c.preparedKeySuffix())
    }
}
