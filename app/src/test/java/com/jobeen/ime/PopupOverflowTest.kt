// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime

import com.jobeen.ime.input.keyboard.key.PopupOverflow
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 气泡溢出调位：上方够放 stay 上方；上方不够翻到下方；
 * 下方也不够贴底边；横向夹在窗口内。
 */
class PopupOverflowTest {

    @Test
    fun aboveFits_staysAbove() {
        val pos = PopupOverflow.resolve(
            anchorCenterX = 500, anchorTop = 400, anchorHeight = 100,
            popupWidth = 200, popupHeight = 100,
            windowWidth = 1000, windowHeight = 800, gapPx = 12,
        )
        assertEquals(400, pos.x)
        assertEquals(288, pos.y)
    }

    @Test
    fun aboveOverflows_flipsBelow() {
        val pos = PopupOverflow.resolve(
            anchorCenterX = 500, anchorTop = 80, anchorHeight = 100,
            popupWidth = 200, popupHeight = 100,
            windowWidth = 1000, windowHeight = 800, gapPx = 12,
        )
        assertEquals(400, pos.x)
        assertEquals(192, pos.y)
    }

    @Test
    fun belowAlsoOverflows_clampsToBottom() {
        val pos = PopupOverflow.resolve(
            anchorCenterX = 500, anchorTop = 700, anchorHeight = 100,
            popupWidth = 200, popupHeight = 750,
            windowWidth = 1000, windowHeight = 800, gapPx = 12,
        )
        assertEquals(400, pos.x)
        assertEquals(50, pos.y)
    }

    @Test
    fun horizontalOverflow_clampedInside() {
        val left = PopupOverflow.resolve(
            anchorCenterX = 30, anchorTop = 400, anchorHeight = 100,
            popupWidth = 200, popupHeight = 100,
            windowWidth = 1000, windowHeight = 800, gapPx = 12,
        )
        assertEquals(0, left.x)
        val right = PopupOverflow.resolve(
            anchorCenterX = 990, anchorTop = 400, anchorHeight = 100,
            popupWidth = 200, popupHeight = 100,
            windowWidth = 1000, windowHeight = 800, gapPx = 12,
        )
        assertEquals(800, right.x)
    }

    @Test
    fun popupWiderThanWindow_clampsToZero() {
        val pos = PopupOverflow.resolve(
            anchorCenterX = 500, anchorTop = 400, anchorHeight = 100,
            popupWidth = 1200, popupHeight = 100,
            windowWidth = 1000, windowHeight = 800, gapPx = 12,
        )
        assertEquals(0, pos.x)
    }
}
