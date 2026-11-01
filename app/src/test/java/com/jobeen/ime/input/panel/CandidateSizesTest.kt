package com.jobeen.ime.input.panel

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 候选字号规格（Jime 自有默认，2026-11-01 纠正 1 强制改动）：
 * 主文字 18dp，序号 16dp，注释 16dp——恢复纠正前既有值；
 * 此前 18/11 是单套参考皮肤的值，不作通用回落（字号按皮肤取值）。
 * 本测试改动由用户纠正决定强制，非代码驱动改测试。
 * 渲染本身依赖 Android Paint，字号值以常量形式钉住防回归；
 * 实际显示效果走真机验收。
 */
class CandidateSizesTest {

    @Test
    fun candidateTextSizeIs18() {
        assertEquals(18f, Paints.CANDIDATE_TEXT_DP, 0f)
    }

    @Test
    fun candidateIndexSizeIs16() {
        assertEquals(16f, Paints.CANDIDATE_INDEX_DP, 0f)
    }

    @Test
    fun candidateCommentSizeIs16() {
        assertEquals(16f, Paints.CANDIDATE_COMMENT_DP, 0f)
    }
}
