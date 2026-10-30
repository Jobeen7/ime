// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.ui.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Test

/** 莫奈色卡片面取值（2026-10-30 莫奈色轮）：动态色优先，无动态色回落静态色。 */
class MonetCardColorTest {

    @Test
    fun dynamicContainer_winsInBothModes() {
        assertEquals(Color.Red, monetCardContainerColor(Color.Red, false))
        assertEquals(Color.Red, monetCardContainerColor(Color.Red, true))
    }

    @Test
    fun fallback_usesStaticContainerByMode() {
        assertEquals(SurfaceContainerLight, monetCardContainerColor(null, false))
        assertEquals(SurfaceContainerDark, monetCardContainerColor(null, true))
    }
}
