// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime

import com.jobeen.ime.input.panel.toolbar.customPressedFaceActive
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 自定义钮按压态判定：仅涟漪动画进行中且按压点落在槽内时为按压态。 */
class ToolbarRendererTest {

    @Test
    fun pressedFace_noPress_neverActive() {
        assertFalse(customPressedFaceActive(0, 0f, 0f, 50f, 0f, 100f))
    }

    @Test
    fun pressedFace_animatingOverSlot_active() {
        assertTrue(customPressedFaceActive(160, 10f, 100f, 50f, 0f, 100f))
        assertTrue(customPressedFaceActive(160, 0f, 100f, 50f, 0f, 100f))
    }

    @Test
    fun pressedFace_animatingOverOtherSlot_inactive() {
        assertFalse(customPressedFaceActive(160, 10f, 100f, 150f, 0f, 100f))
    }

    @Test
    fun pressedFace_animationEnded_inactive() {
        // 半径到峰值时涟漪已褪尽，按下颜色随之收回，不残留。
        assertFalse(customPressedFaceActive(160, 100f, 100f, 50f, 0f, 100f))
    }

    @Test
    fun pressedFace_cancelled_inactive() {
        assertFalse(customPressedFaceActive(0, 0f, 100f, 50f, 0f, 100f))
    }
}
