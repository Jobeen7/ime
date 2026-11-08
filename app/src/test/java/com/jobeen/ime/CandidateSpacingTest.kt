// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime

import com.jobeen.ime.data.manager.CandidateManager
import com.jobeen.ime.data.manager.CandidateSpacingPolicy
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 候选间距／缩放（H2-3）纯策略单测：间距按 App > 皮肤 > 各面默认；
 * 缩放后字号 = 基准 × 倍率，倍率与结果双钳。均不依赖 Android 框架。
 */
class CandidateSpacingTest {

    @Test
    fun spacing_userWinsOverSkin() {
        assertEquals(16f, CandidateSpacingPolicy.resolveSpacing(16f, 10f, 8f), 0f)
    }

    @Test
    fun spacing_skinWinsOverDefault() {
        assertEquals(10f, CandidateSpacingPolicy.resolveSpacing(null, 10f, 8f), 0f)
    }

    @Test
    fun spacing_defaultWhenNeitherSet() {
        assertEquals(8f, CandidateSpacingPolicy.resolveSpacing(null, null, 8f), 0f)
        assertEquals(6f, CandidateSpacingPolicy.resolveSpacing(null, null, 6f), 0f)
    }

    @Test
    fun spacing_zeroIsARealValueNotAbsence() {
        assertEquals(0f, CandidateSpacingPolicy.resolveSpacing(0f, 10f, 8f), 0f)
    }

    @Test
    fun scale_identityAtDefault() {
        assertEquals(18f, CandidateSpacingPolicy.scaledTextSizeDp(18f, 1f), 0f)
        assertEquals(16f, CandidateSpacingPolicy.scaledTextSizeDp(16f, 1f), 0f)
    }

    @Test
    fun scale_multipliesBaseSize() {
        assertEquals(27f, CandidateSpacingPolicy.scaledTextSizeDp(18f, 1.5f), 0.001f)
        assertEquals(14.4f, CandidateSpacingPolicy.scaledTextSizeDp(18f, 0.8f), 0.001f)
    }

    @Test
    fun scale_clampsFactorToRange() {
        assertEquals(27f, CandidateSpacingPolicy.scaledTextSizeDp(18f, 2f), 0.001f)
        assertEquals(14.4f, CandidateSpacingPolicy.scaledTextSizeDp(18f, 0.5f), 0.001f)
    }

    @Test
    fun scale_clampsResultToFloorAndCeiling() {
        assertEquals(8f, CandidateSpacingPolicy.scaledTextSizeDp(8f, 0.8f), 0.001f)
        assertEquals(48f, CandidateSpacingPolicy.scaledTextSizeDp(40f, 1.5f), 0.001f)
    }

    @Test
    fun contract_rangesAreAsSpecified() {
        assertEquals(0f, CandidateManager.CANDIDATE_SPACING_MIN_DP, 0f)
        assertEquals(24f, CandidateManager.CANDIDATE_SPACING_MAX_DP, 0f)
        assertEquals(8f, CandidateManager.CANDIDATE_SPACING_DEFAULT_DP, 0f)
        assertEquals(0.8f, CandidateManager.CANDIDATE_SCALE_MIN, 0f)
        assertEquals(1.5f, CandidateManager.CANDIDATE_SCALE_MAX, 0f)
        assertEquals(1f, CandidateManager.CANDIDATE_SCALE_DEFAULT, 0f)
    }
}
