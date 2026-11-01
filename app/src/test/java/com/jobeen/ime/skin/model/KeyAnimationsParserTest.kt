package com.jobeen.ime.skin.model

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 动效定义解析（审计 H 项：scale/cartoon/physics/transform）。 */
class KeyAnimationsParserTest {

    @Test
    fun peerScaleAnimationParses() {
        // 参考皮肤实测 scale 动效（两块皮肤的压下/抬起时长与缩放）。
        val (table, warnings) = KeyAnimationDefParser.parseTable(
            Json.parseToJsonElement(
                """{"ButtonScaleAnimation": {"animationType": "scale",
                    "isAutoReverse": true, "pressDuration": 90,
                    "releaseDuration": 90, "scale": 0.8},
                   "ButtonScaleAnimation2": {"animationType": "scale",
                    "isAutoReverse": true, "pressDuration": 60,
                    "releaseDuration": 80, "scale": 0.87}}""",
            ),
        )
        assertTrue(warnings.isEmpty())
        assertEquals(
            KeyAnimationDef.Scale(0.8f, 90, 90, true),
            table["ButtonScaleAnimation"],
        )
        assertEquals(
            KeyAnimationDef.Scale(0.87f, 60, 80, true),
            table["ButtonScaleAnimation2"],
        )
    }

    @Test
    fun cartoonRequiresImages() {
        val (def, warnings) = KeyAnimationDefParser.parseOne(
            Json.parseToJsonElement(
                """{"animationType": "cartoon", "fps": 24,
                    "images": ["a", "b"], "targetScale": 1.1}""",
            ),
        )
        assertEquals(
            KeyAnimationDef.Cartoon(listOf("a", "b"), 24, 1.1f, null),
            def,
        )
        assertTrue(warnings.isEmpty())
        val (empty, emptyWarnings) = KeyAnimationDefParser.parseOne(
            Json.parseToJsonElement("""{"animationType": "cartoon"}"""),
        )
        assertNull(empty)
        assertTrue(emptyWarnings.contains("key_animation_images_invalid"))
    }

    @Test
    fun physicsParsesPositionsAndSwitches() {
        val (def, _) = KeyAnimationDefParser.parseOne(
            Json.parseToJsonElement(
                """{"animationType": "physics", "images": ["p"],
                    "randomImage": true, "startPosition": {"x": 0, "y": 0},
                    "endPosition": {"x": 10, "y": -20},
                    "randomOffset": {"x": 5, "y": 5},
                    "useOpacity": true, "useRotation": true,
                    "startAngle": 0, "endAngle": 90,
                    "durationMs": 500, "positionUnit": "dp"}""",
            ),
        )
        val physics = def as KeyAnimationDef.Physics
        assertEquals(listOf("p"), physics.images)
        assertTrue(physics.randomImage)
        assertEquals(10f, physics.endX)
        assertEquals(-20f, physics.endY)
        assertEquals(5f, physics.randomOffsetX)
        assertTrue(physics.useOpacity)
        assertTrue(physics.useRotation)
        assertEquals(90f, physics.endAngleDeg)
        assertEquals(500, physics.durationMs)
        assertEquals(KeyAnimationPositionUnit.DP, physics.positionUnit)
    }

    @Test
    fun transformParsesTimingAndHold() {
        val (def, _) = KeyAnimationDefParser.parseOne(
            Json.parseToJsonElement(
                """{"animationType": "transform", "target": "face",
                    "trigger": "press", "durationMs": 300, "delayMs": 50,
                    "timing": [0.25, 0.1, 0.25, 1.0], "repeatCount": 1,
                    "autoreverses": true, "restoreOnFinish": false,
                    "holdUntilRelease": true, "releaseDuration": 200,
                    "anchorPoint": {"x": 0.5, "y": 0.5},
                    "positionUnit": "fraction", "detached": true,
                    "endScale": 0.9, "endAlpha": 0.5}""",
            ),
        )
        val transform = def as KeyAnimationDef.Transform
        assertEquals("face", transform.target)
        assertEquals(KeyAnimationTrigger.PRESS, transform.trigger)
        assertEquals(listOf(0.25f, 0.1f, 0.25f, 1.0f), transform.bezier)
        assertEquals(1, transform.repeatCount)
        assertTrue(transform.autoReverse)
        assertTrue(!transform.restoreOnFinish)
        assertTrue(transform.holdUntilRelease)
        assertEquals(200, transform.releaseDurationMs)
        assertEquals(0.5f, transform.anchorX)
        assertEquals(KeyAnimationPositionUnit.FRACTION, transform.positionUnit)
        assertTrue(transform.detached)
        assertEquals(0.9f, transform.endScale)
        assertEquals(0.5f, transform.endAlpha)
    }

    @Test
    fun unknownTypeAndBadTableAreDropped() {
        val (def, warnings) = KeyAnimationDefParser.parseOne(
            Json.parseToJsonElement("""{"animationType": "wobble"}"""),
        )
        assertNull(def)
        assertTrue(warnings.contains("key_animation_type_invalid"))
        val (table, tableWarnings) = KeyAnimationDefParser.parseTable(
            Json.parseToJsonElement("""[1, 2]"""),
        )
        assertTrue(table.isEmpty())
        assertTrue(tableWarnings.contains("key_animations_not_object"))
    }

    @Test
    fun globalSpecSplitDurationsParse() {
        val (spec, issues) = KeyAnimationParser.parse(
            Json.parseToJsonElement(
                """{"enabled": true, "pressedScale": 0.9, "durationMs": 200,
                    "pressDuration": 60, "releaseDuration": 80,
                    "isAutoReverse": false}""",
            ),
            "keyAnimation",
        )
        assertTrue(issues.isEmpty())
        assertEquals(60, spec.pressDurationMs)
        assertEquals(80, spec.releaseDurationMs)
        assertTrue(!spec.autoReverse)
    }
}
