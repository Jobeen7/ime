package com.jobeen.ime

import com.jobeen.ime.data.manager.applySavedCandidateOrder
import com.jobeen.ime.data.manager.candidateSortingKey
import com.jobeen.ime.data.manager.candidateTextHash
import com.jobeen.ime.engine.data.EngineMessage.Candidate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 拖拽排序的顺序应用：保存顺序（候选文本哈希序列）套到任意同集
 * 候选列表（含智能重排后的列表）上都必须成立，未保存的新候选按
 * 原相对顺序追加到末尾——这是拖拽排序在重排默认开启时也能生效
 * 的落点。身份按文本而非原始序号：序号随词频学习/词库更新漂移，
 * 存序号会把顺序错套到别的词上。
 */
class CandidateSortingOrderTest {

    private fun cand(index: Int, text: String) = Candidate(
        index = index, text = text, comment = "", type = "",
    )

    private fun listOf(vararg pairs: Pair<Int, String>) = pairs.map { cand(it.first, it.second) }

    /** 保存序按候选文本给出（落库形态是其哈希序列）。 */
    private fun saved(vararg texts: String) = texts.map { candidateTextHash(it) }

    @Test
    fun savedOrder_appliesExactly() {
        val list = listOf(0 to "甲", 1 to "乙", 2 to "丙")
        val result = applySavedCandidateOrder(list, saved("丙", "甲", "乙"))
        assertEquals(listOf("丙", "甲", "乙"), result.map { it.text })
    }

    @Test
    fun savedOrder_appliesOnTopOfRerankedList() {
        // 重排后的列表顺序已变（丙被提至首位），保存顺序仍按文本套用
        val reranked = listOf(2 to "丙", 0 to "甲", 1 to "乙")
        val result = applySavedCandidateOrder(reranked, saved("乙", "丙", "甲"))
        assertEquals(listOf("乙", "丙", "甲"), result.map { it.text })
    }

    @Test
    fun savedOrder_newCandidatesAppendInOriginalRelativeOrder() {
        val list = listOf(0 to "甲", 1 to "乙", 2 to "丙", 3 to "丁")
        val result = applySavedCandidateOrder(list, saved("丙", "甲"))
        assertEquals(listOf("丙", "甲", "乙", "丁"), result.map { it.text })
    }

    @Test
    fun savedOrder_missingEntriesAreSkipped() {
        val list = listOf(0 to "甲", 1 to "乙")
        val result = applySavedCandidateOrder(list, saved("不存在", "乙", "甲"))
        assertEquals(listOf("乙", "甲"), result.map { it.text })
    }

    @Test
    fun savedOrder_survivesIndexReshuffle() {
        // 词频学习/词库更新后同一批词的原始序号全部改指、列表顺序
        // 也变了：保存序按文本回套，结果仍是用户拖出的顺序
        val list = listOf(7 to "甲", 3 to "乙", 9 to "丙")
        val result = applySavedCandidateOrder(list, saved("丙", "甲", "乙"))
        assertEquals(listOf("丙", "甲", "乙"), result.map { it.text })
    }

    @Test
    fun savedOrder_duplicateTextsConsumedInOrder() {
        // 同文多条（同词不同注释）：保存序里该文本出现一次，只取走
        // 原列表中的第一条，第二条按未保存候选原序追加，不重复取用
        val first = Candidate(index = 0, text = "同", comment = "a", type = "")
        val second = Candidate(index = 1, text = "同", comment = "b", type = "")
        val other = cand(2, "异")
        val result = applySavedCandidateOrder(
            listOf(first, second, other), saved("同", "异")
        )
        assertEquals(
            listOf("同" to "a", "异" to "", "同" to "b"),
            result.map { it.text to it.comment },
        )
    }

    // ---- 文本哈希：候选身份（跨进程/版本恒定、互异文本不撞） ----

    @Test
    fun textHash_stableAndDistinct() {
        assertEquals(candidateTextHash("甲"), candidateTextHash("甲"))
        assertNotEquals(candidateTextHash("甲"), candidateTextHash("乙"))
        assertNotEquals(candidateTextHash(""), candidateTextHash("甲"))
    }

    // ---- 排序键：按组字串＋方案派生（保存/还原两端同源同值） ----

    @Test
    fun sortingKey_blankPreeditIsEmpty() {
        assertEquals("", candidateSortingKey("", "wanxiang"))
        assertEquals("", candidateSortingKey("   ", "wanxiang"))
    }

    @Test
    fun sortingKey_prefixedStableAndDistinct() {
        val key = candidateSortingKey("nihao", "wanxiang")
        assertTrue(key.startsWith("p:wanxiang:"))
        assertEquals(key, candidateSortingKey("nihao", "wanxiang"))
        assertNotEquals(key, candidateSortingKey("nihaoma", "wanxiang"))
    }

    @Test
    fun sortingKey_differsBySchema() {
        // 同一组字串在不同方案下候选集完全不同，键必须隔离
        assertNotEquals(
            candidateSortingKey("nihao", "wanxiang"),
            candidateSortingKey("nihao", "wanxiang_shuangpin"),
        )
    }
}
