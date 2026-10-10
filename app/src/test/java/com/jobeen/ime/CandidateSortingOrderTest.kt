package com.jobeen.ime

import com.jobeen.ime.data.manager.applySavedCandidateOrder
import com.jobeen.ime.data.manager.candidateSortingKey
import com.jobeen.ime.engine.data.EngineMessage.Candidate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 拖拽排序的顺序应用：保存顺序（原始序号序列）套到任意同集候选列表
 * （含智能重排后的列表）上都必须成立，未保存的新候选按原相对顺序
 * 追加到末尾——这是拖拽排序在重排默认开启时也能生效的落点。
 */
class CandidateSortingOrderTest {

    private fun cand(index: Int, text: String) = Candidate(
        index = index, text = text, comment = "", type = "",
    )

    private fun listOf(vararg pairs: Pair<Int, String>) = pairs.map { cand(it.first, it.second) }

    @Test
    fun savedOrder_appliesExactly() {
        val list = listOf(0 to "甲", 1 to "乙", 2 to "丙")
        val result = applySavedCandidateOrder(list, listOf(2, 0, 1))
        assertEquals(listOf("丙", "甲", "乙"), result.map { it.text })
    }

    @Test
    fun savedOrder_appliesOnTopOfRerankedList() {
        // 重排后的列表顺序已变（丙被提至首位），保存顺序仍按原始序号套用
        val reranked = listOf(2 to "丙", 0 to "甲", 1 to "乙")
        val result = applySavedCandidateOrder(reranked, listOf(1, 2, 0))
        assertEquals(listOf("乙", "丙", "甲"), result.map { it.text })
    }

    @Test
    fun savedOrder_newCandidatesAppendInOriginalRelativeOrder() {
        val list = listOf(0 to "甲", 1 to "乙", 2 to "丙", 3 to "丁")
        val result = applySavedCandidateOrder(list, listOf(2, 0))
        assertEquals(listOf("丙", "甲", "乙", "丁"), result.map { it.text })
    }

    @Test
    fun savedOrder_missingIdsAreSkipped() {
        val list = listOf(0 to "甲", 1 to "乙")
        val result = applySavedCandidateOrder(list, listOf(9, 1, 0))
        assertEquals(listOf("乙", "甲"), result.map { it.text })
    }

    // ---- 排序键：按组字串派生（保存/还原两端同源同值，集合大小无关） ----

    @Test
    fun sortingKey_blankPreeditIsEmpty() {
        assertEquals("", candidateSortingKey(""))
        assertEquals("", candidateSortingKey("   "))
    }

    @Test
    fun sortingKey_prefixedStableAndDistinct() {
        val key = candidateSortingKey("nihao")
        assertTrue(key.startsWith("p:"))
        assertEquals(key, candidateSortingKey("nihao"))
        assertNotEquals(key, candidateSortingKey("nihaoma"))
    }
}
