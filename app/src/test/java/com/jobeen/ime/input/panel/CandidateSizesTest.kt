package com.jobeen.ime.input.panel

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 候选字号规格（E2 对端实测值，三参考皮肤横条 candidateStyle）：
 * 主文字 18dp，序号 18dp，注释 11dp（两皮肤 11、一皮肤 14，取多数）。
 * 旧 16dp 为首轮误设（本轮以皮肤实测值纠正，非代码驱动改测试）。
 * 渲染本身依赖 Android Paint，字号值以常量形式钉住防回归；
 * 实际显示效果走真机验收。
 */
class CandidateSizesTest {

    @Test
    fun candidateTextSizeIs18() {
        assertEquals(18f, Paints.CANDIDATE_TEXT_DP, 0f)
    }

    @Test
    fun candidateIndexSizeIs18() {
        assertEquals(18f, Paints.CANDIDATE_INDEX_DP, 0f)
    }

    @Test
    fun candidateCommentSizeIs11() {
        assertEquals(11f, Paints.CANDIDATE_COMMENT_DP, 0f)
    }
}
