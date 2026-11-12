// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.input.panel.component

import com.jobeen.ime.engine.data.EngineMessage
import org.junit.Assert.assertEquals
import org.junit.Test

/** 候选按本体重新定位（I-S3，上一位代理引入、本轮验证补测）。 */
class CandidateRelocateTest {

    private val a = EngineMessage.Candidate(index = 0, text = "你")
    private val b = EngineMessage.Candidate(index = 1, text = "好")
    private val c = EngineMessage.Candidate(index = 2, text = "吗")

    @Test
    fun relocatesByIdentityAfterListReplacement() {
        // 整表替换后下标已变，按本体重新定位，不点错词。
        assertEquals(1, relocateCandidateIndex(b, listOf(c, b, a)))
        assertEquals(0, relocateCandidateIndex(a, listOf(a, b)))
        assertEquals(2, relocateCandidateIndex(c, listOf(a, b, c)))
    }

    @Test
    fun missingCandidateAbortsPress() {
        // 本体已不在新表中：返回 -1，本次点按／遗忘放弃。
        assertEquals(-1, relocateCandidateIndex(b, listOf(a, c)))
        assertEquals(-1, relocateCandidateIndex(a, emptyList()))
    }

    @Test
    fun nullCandidateAbortsPress() {
        assertEquals(-1, relocateCandidateIndex(null, listOf(a, b)))
    }
}
