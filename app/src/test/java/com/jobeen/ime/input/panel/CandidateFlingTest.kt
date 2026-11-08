package com.jobeen.ime.input.panel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 施工单 10 H2-10：候选甩动摩擦 ×4，同一下甩动停下更快。
 * 比值口径与 CyIME CandidateFlingTest 相同（距离／时长约剩 36%）；
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

    @Test
    fun candidateFlingStopsInAboutAThirdOfTheDefaultDistanceAndTime() {
        val default = CandidateFlingPolicy.DEFAULT_SCROLL_FRICTION
        val candidate = CandidateFlingPolicy.candidateFriction()
        for (velocity in listOf(3000f, 6000f, 12000f)) {
            for (density in listOf(1.0f, 2.75f, 3.5f)) {
                val defaultDistance =
                    CandidateFlingPolicy.splineFlingDistancePx(velocity, default, density)
                val candidateDistance =
                    CandidateFlingPolicy.splineFlingDistancePx(velocity, candidate, density)
                assertEquals(0.36f, candidateDistance / defaultDistance, 0.02f)
                val defaultMs =
                    CandidateFlingPolicy.splineFlingDurationMs(velocity, default, density)
                val candidateMs =
                    CandidateFlingPolicy.splineFlingDurationMs(velocity, candidate, density)
                assertTrue(
                    "$velocity px/s @ $density: $candidateMs ms vs $defaultMs ms",
                    candidateMs <= defaultMs * 0.4f,
                )
            }
        }
    }

    @Test
    fun flingDirectionIsKept() {
        val candidate = CandidateFlingPolicy.candidateFriction()
        assertTrue(CandidateFlingPolicy.splineFlingDistancePx(-6000f, candidate, density) < 0f)
        assertTrue(CandidateFlingPolicy.splineFlingDistancePx(6000f, candidate, density) > 0f)
    }
}
