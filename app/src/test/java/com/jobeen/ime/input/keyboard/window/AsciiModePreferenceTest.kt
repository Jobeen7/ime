package com.jobeen.ime.input.keyboard.window

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 偏好 → Rime ascii_mode 极性（施工单 16）：偏好 true＝中文、
 * Rime ascii_mode true＝英文，两者相反（OptionsApplier.kt:18）。
 * 纯函数 initialType／resolveFirstAttachType／reconcileOpenType
 * 的参数本就是 Rime ascii_mode，口径不变；本文件只锁调用点
 * 必须经 asciiModeFromPreference 取反这一层。
 */
class AsciiModePreferenceTest {

    @Test
    fun asciiModeFromPreferenceInverts() {
        // 偏好 true（中文，CandidateManager 默认）→ Rime false。
        assertFalse(asciiModeFromPreference(true))
        // 偏好 false（英文）→ Rime true。
        assertTrue(asciiModeFromPreference(false))
    }

    @Test
    fun preferenceTrueMapsToPinyinViaInitialType() {
        val asciiMode = asciiModeFromPreference(true)
        assertEquals("pinyin", KeyboardTypeState().initialType(asciiMode))
    }

    @Test
    fun preferenceFalseMapsToAlphabeticViaInitialType() {
        val asciiMode = asciiModeFromPreference(false)
        assertEquals("alphabetic", KeyboardTypeState().initialType(asciiMode))
    }

    @Test
    fun preferenceTrueFallsBackToPinyinOnFirstAttach() {
        assertEquals(
            "pinyin",
            resolveFirstAttachType(null, asciiModeFromPreference(true)),
        )
        assertEquals(
            "alphabetic",
            resolveFirstAttachType(null, asciiModeFromPreference(false)),
        )
    }

    @Test
    fun preferenceTrueLetsSchemeWinReconcile() {
        // 偏好中文时打开对账以方案为准：留存 alphabetic 被拉回 pinyin。
        assertEquals(
            "pinyin",
            reconcileOpenType("pinyin", "alphabetic", asciiModeFromPreference(true)),
        )
    }

    @Test
    fun preferenceFalseKeepsCurrentTypeOnReconcile() {
        // 偏好英文（主动英文）时打开对账保留留存类型。
        assertEquals(
            "alphabetic",
            reconcileOpenType("pinyin", "alphabetic", asciiModeFromPreference(false)),
        )
    }
}
