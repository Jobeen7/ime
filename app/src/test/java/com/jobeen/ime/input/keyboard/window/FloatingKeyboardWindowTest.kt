// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.input.keyboard.window

import com.jobeen.ime.input.keyboard.window.FloatingKeyboardWindow.ToggleOutcome
import com.jobeen.ime.skin.model.FloatingKeyboardGeometry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** WO52 1.7：开关翻转＋重建／requestLayout 判定外的纯几何与宽度规则。 */
class FloatingKeyboardWindowTest {

    @Test
    fun `toggle off to on when no hardware keyboard`() {
        assertEquals(
            ToggleOutcome.Enabled,
            FloatingKeyboardWindow.toggle(currentlyEnabled = false, hardwareKeyboardInUse = false),
        )
    }

    @Test
    fun `toggle on to off`() {
        assertEquals(
            ToggleOutcome.Disabled,
            FloatingKeyboardWindow.toggle(currentlyEnabled = true, hardwareKeyboardInUse = false),
        )
    }

    @Test
    fun `toggle refused when hardware keyboard in use`() {
        assertEquals(
            ToggleOutcome.HardwareKeyboardUnavailable,
            FloatingKeyboardWindow.toggle(currentlyEnabled = false, hardwareKeyboardInUse = true),
        )
    }

    @Test
    fun `turning off is allowed even with hardware keyboard in use`() {
        assertEquals(
            ToggleOutcome.Disabled,
            FloatingKeyboardWindow.toggle(currentlyEnabled = true, hardwareKeyboardInUse = true),
        )
    }

    @Test
    fun `hardware keyboard in use only when present and not hidden`() {
        assertTrue(FloatingKeyboardWindow.isHardwareKeyboardInUse(true, false))
        assertTrue(!FloatingKeyboardWindow.isHardwareKeyboardInUse(true, true))
        assertTrue(!FloatingKeyboardWindow.isHardwareKeyboardInUse(false, false))
    }

    @Test
    fun `geometryFor picks per orientation and defaults when absent`() {
        val portrait = FloatingKeyboardGeometry(0.8f, 0.4f)
        val landscape = FloatingKeyboardGeometry(0.7f, 0.6f)
        assertEquals(portrait, FloatingKeyboardWindow.geometryFor(false, portrait, landscape))
        assertEquals(landscape, FloatingKeyboardWindow.geometryFor(true, portrait, landscape))
        assertEquals(
            FloatingKeyboardGeometry(),
            FloatingKeyboardWindow.geometryFor(true, portrait, null),
        )
    }

    @Test
    fun `landscape width is smallestScreenWidthDp times density, portrait is null`() {
        assertEquals(600, FloatingKeyboardWindow.landscapeWidthPx(true, 400f, 1.5f))
        assertNull(FloatingKeyboardWindow.landscapeWidthPx(false, 400f, 1.5f))
    }

    @Test
    fun `portrait width follows width ratio`() {
        assertEquals(
            900,
            FloatingKeyboardWindow.portraitWidthPx(1000, FloatingKeyboardGeometry(0.9f, 0.5f)),
        )
    }

    @Test
    fun `resolveLeftPx centers on centerX and clamps inside screen`() {
        // 宽 800、中心 0.5 → 左缘 100。
        assertEquals(
            100,
            FloatingKeyboardWindow.resolveLeftPx(1000, 800, FloatingKeyboardGeometry(0.8f, 0.5f)),
        )
        // 中心 0 → 左缘钳到 0。
        assertEquals(
            0,
            FloatingKeyboardWindow.resolveLeftPx(1000, 800, FloatingKeyboardGeometry(0.8f, 0f)),
        )
        // 中心 1 → 左缘钳到 200。
        assertEquals(
            200,
            FloatingKeyboardWindow.resolveLeftPx(1000, 800, FloatingKeyboardGeometry(0.8f, 1f)),
        )
    }

    @Test
    fun `dragCenterX moves with dx and clamps`() {
        assertEquals(0.6f, FloatingKeyboardWindow.dragCenterX(0.5f, 100f, 1000))
        assertEquals(0f, FloatingKeyboardWindow.dragCenterX(0.1f, -500f, 1000))
        assertEquals(1f, FloatingKeyboardWindow.dragCenterX(0.9f, 500f, 1000))
    }

    @Test
    fun `geometry clamped to legal range`() {
        val clamped = FloatingKeyboardGeometry(0.1f, 1.5f).clamped()
        assertEquals(0.3f, clamped.widthRatio)
        assertEquals(1f, clamped.centerX)
    }
}
