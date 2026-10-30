// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime

import com.jobeen.ime.input.keyboard.key.SwipeThresholds
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 滑动阈值：优先级逐键 > 皮肤 > 设置 > 默认；非法值顺延。
 */
class SwipeThresholdsTest {

    @Test
    fun resolve_prefersPerKey() {
        assertEquals(24f, SwipeThresholds.resolve(24f, 30f, 40f), 0.001f)
    }

    @Test
    fun resolve_fallsThroughInvalid() {
        assertEquals(30f, SwipeThresholds.resolve(4f, 30f, 40f), 0.001f)
        assertEquals(40f, SwipeThresholds.resolve(null, 200f, 40f), 0.001f)
        assertEquals(36f, SwipeThresholds.resolve(null, null, null), 0.001f)
        assertEquals(36f, SwipeThresholds.resolve(Float.NaN, null, null), 0.001f)
    }

    @Test
    fun resolve_boundaryValuesAccepted() {
        assertEquals(8f, SwipeThresholds.resolve(8f, null, null), 0.001f)
        assertEquals(160f, SwipeThresholds.resolve(null, 160f, null), 0.001f)
    }

    @Test
    fun isValid_rejectsOutOfRange() {
        assertEquals(false, SwipeThresholds.isValid(null))
        assertEquals(false, SwipeThresholds.isValid(7.9f))
        assertEquals(false, SwipeThresholds.isValid(160.1f))
        assertEquals(false, SwipeThresholds.isValid(Float.POSITIVE_INFINITY))
        assertEquals(true, SwipeThresholds.isValid(36f))
    }
}
