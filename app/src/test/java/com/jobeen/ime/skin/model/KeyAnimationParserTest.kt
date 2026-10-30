package com.jobeen.ime.skin.model

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 按键动效（2026-10-29 item-1）：解析口径。 */
class KeyAnimationParserTest {

    @Test
    fun missingNodeDefaultsToDisabled() {
        val (spec, issues) = KeyAnimationParser.parse(null, "keyAnimation")
        assertFalse(spec.enabled)
        assertTrue(issues.isEmpty())
    }

    @Test
    fun fullAnimationParses() {
        val (spec, issues) = KeyAnimationParser.parse(
            Json.parseToJsonElement(
                """{"enabled": true, "pressedScale": 0.9,
                   "durationMs": 200, "easing": "overshoot"}""",
            ),
            "keyAnimation",
        )
        assertTrue(spec.enabled)
        assertEquals(0.9f, spec.pressedScale)
        assertEquals(200, spec.durationMs)
        assertEquals(KeyAnimationEasing.OVERSHOOT, spec.easing)
        assertTrue(issues.isEmpty())
    }

    @Test
    fun invalidFieldsFallBackIndividually() {
        val (spec, issues) = KeyAnimationParser.parse(
            Json.parseToJsonElement(
                """{"enabled": 1, "pressedScale": 0.5,
                   "durationMs": 9999, "easing": "bounce"}""",
            ),
            "keyAnimation",
        )
        assertFalse(spec.enabled)
        assertEquals(KeyAnimationSpec.DEFAULT_PRESSED_SCALE, spec.pressedScale)
        assertEquals(KeyAnimationSpec.DEFAULT_DURATION_MS, spec.durationMs)
        assertEquals(KeyAnimationEasing.EASE_OUT, spec.easing)
        assertEquals(4, issues.size)
    }

    @Test
    fun nonObjectFallsBackToDisabled() {
        val (spec, issues) = KeyAnimationParser.parse(
            Json.parseToJsonElement("""true"""),
            "keyAnimation",
        )
        assertFalse(spec.enabled)
        assertEquals(1, issues.size)
    }

    @Test
    fun spaceIdsCoverAllLayouts() {
        assertTrue("KEY_SPACE" in SPACE_KEY_IDS)
        assertTrue("T9_SPACE" in SPACE_KEY_IDS)
        assertTrue("T15_SPACE" in SPACE_KEY_IDS)
        assertTrue("NUM_SPACE" in SPACE_KEY_IDS)
    }
}
