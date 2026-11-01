package com.jobeen.ime.input.panel

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 候选字号规格（对端候选字号 18/16）：主文字 18dp，序号与注释 16dp。
 * 渲染本身依赖 Android Paint，字号值以常量形式钉住防回归；
 * 实际显示效果走真机验收。
 */
class CandidateSizesTest {

    @Test
    fun candidateTextSizeIs18() {
        assertEquals(18f, Paints.CANDIDATE_TEXT_DP, 0f)
    }

    @Test
    fun candidateSecondarySizeIs16() {
        assertEquals(16f, Paints.CANDIDATE_SECONDARY_DP, 0f)
    }
}
