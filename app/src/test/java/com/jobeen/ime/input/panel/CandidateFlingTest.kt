package com.jobeen.ime.input.panel

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 施工单 10 H2-10：候选甩动摩擦 ×4，同一下甩动停下更快。
 * 比值口径与对端 CandidateFlingTest 相同（距离／时长约剩 36%）；
 * 绝对停时由真机 OverScroller 执行，主机只证相对倍率。
 */
class CandidateFlingTest {
    private val density = 2.75f

    @Test
    fun candidateFrictionIsFourTimesDefault() {
        assertEquals(4f, CandidateFlingPolicy.FRICTION_MULTIPLIER, 0f)
        assertEquals(0.015f, CandidateFlingPolicy.DEFAULT_SCROLL_FRICTION, 0f)
        assertEquals(0.06f, CandidateFlingPolicy.candidateFriction(), 0.0001f)
    }


}
