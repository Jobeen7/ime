// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.skin.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 按压态独立图标／图片（重点候选 8-2）：回退规则。 */
class PressedContentTest {

    @Test
    fun icon_pressedWinsWhenBothSet() {
        assertEquals(
            "close", PressedContent.effectiveIcon("menu", "close", true),
        )
        assertEquals(
            "menu", PressedContent.effectiveIcon("menu", "close", false),
        )
    }

    @Test
    fun icon_fallsBackToNormal() {
        assertEquals("menu", PressedContent.effectiveIcon("menu", null, true))
        assertNull(PressedContent.effectiveIcon(null, "close", true))
        assertNull(PressedContent.effectiveIcon(null, null, true))
        assertNull(PressedContent.effectiveIcon(null, null, false))
    }

    @Test
    fun image_pressedWinsOrFallsBack() {
        assertEquals(
            "pressed", PressedContent.effectiveImageAssetId("normal", "pressed", true),
        )
        assertEquals(
            "normal", PressedContent.effectiveImageAssetId("normal", null, true),
        )
        assertEquals(
            "pressed", PressedContent.effectiveImageAssetId(null, "pressed", true),
        )
        assertEquals(
            "normal", PressedContent.effectiveImageAssetId("normal", "pressed", false),
        )
        assertNull(PressedContent.effectiveImageAssetId(null, null, true))
    }
}
