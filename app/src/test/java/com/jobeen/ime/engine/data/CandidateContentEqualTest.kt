// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.engine.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 无变化候选帧丢弃的比较口径：只看内容字段（index/text/comment/
 * type，按序），score 是 var 评分、单调变化不代表内容变，故排除。
 * 用 data class 自带 equals 会把 score 算进去，score 一变就误判为
 * 变化帧、丢弃永不生效，故必须逐字段比。
 */
class CandidateContentEqualTest {

    private fun candidate(
        index: Int,
        text: String,
        comment: String = "",
        type: String = "",
        score: Double = 0.0,
    ) = EngineMessage.Candidate(index, text, comment, type, score)

    @Test
    fun equal_sameContent() {
        val a = listOf(candidate(0, "你", "ni"), candidate(1, "拟"))
        val b = listOf(candidate(0, "你", "ni"), candidate(1, "拟"))
        assertTrue(candidatesContentEqual(a, b))
    }

    @Test
    fun equal_scoreChangeIgnored() {
        val a = listOf(candidate(0, "你", score = 1.0))
        val b = listOf(candidate(0, "你", score = 9.5))
        assertTrue(candidatesContentEqual(a, b))
    }

    @Test
    fun notEqual_textChange() {
        assertFalse(
            candidatesContentEqual(
                listOf(candidate(0, "你")),
                listOf(candidate(0, "拟")),
            )
        )
    }

    @Test
    fun notEqual_commentChange() {
        assertFalse(
            candidatesContentEqual(
                listOf(candidate(0, "你", "ni")),
                listOf(candidate(0, "你", "ni2")),
            )
        )
    }

    @Test
    fun notEqual_typeChange() {
        assertFalse(
            candidatesContentEqual(
                listOf(candidate(0, "你", type = "a")),
                listOf(candidate(0, "你", type = "b")),
            )
        )
    }

    @Test
    fun notEqual_indexChange() {
        assertFalse(
            candidatesContentEqual(
                listOf(candidate(0, "你")),
                listOf(candidate(1, "你")),
            )
        )
    }

    @Test
    fun notEqual_orderChange() {
        assertFalse(
            candidatesContentEqual(
                listOf(candidate(0, "你"), candidate(1, "拟")),
                listOf(candidate(0, "拟"), candidate(1, "你")),
            )
        )
    }

    @Test
    fun notEqual_sizeChange() {
        assertFalse(
            candidatesContentEqual(
                listOf(candidate(0, "你")),
                listOf(candidate(0, "你"), candidate(1, "拟")),
            )
        )
    }

    @Test
    fun equal_emptyLists() {
        assertTrue(candidatesContentEqual(emptyList(), emptyList()))
    }
}
