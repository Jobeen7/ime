// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.input.keyboard.window

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 候选追加页版本号放行判定（I-S2，上一位代理引入、本轮验证补测）：
 * 追加页 revision 必须与已应用首屏同源（引擎侧批次号＝首屏 requestId），
 * 迟到旧页与无首屏可合的页一律丢弃；未版本化（revision 0）恒放行。
 */
class CandidateAppendRevisionTest {

    @Test
    fun unversionedAppendAlwaysApplies() {
        assertTrue(shouldApplyCandidateAppend(0L, -1L))
        assertTrue(shouldApplyCandidateAppend(0L, 5L))
        assertTrue(shouldApplyCandidateAppend(-1L, 5L))
    }

    @Test
    fun versionedAppendRequiresAppliedFirstFrame() {
        // 尚未应用任何版本化首屏时，版本化追加页无处可合。
        assertFalse(shouldApplyCandidateAppend(5L, -1L))
    }

    @Test
    fun versionedAppendOnlyMatchesCurrentBatch() {
        assertTrue(shouldApplyCandidateAppend(5L, 5L))
        // 迟到旧页（新组字首屏已应用）丢弃。
        assertFalse(shouldApplyCandidateAppend(4L, 5L))
        // 另一批次的页（首屏尚未应用）丢弃，不抢先合入。
        assertFalse(shouldApplyCandidateAppend(6L, 5L))
    }
}
