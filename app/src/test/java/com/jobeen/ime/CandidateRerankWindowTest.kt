package com.jobeen.ime

import android.content.ContextWrapper
import com.jobeen.ime.engine.data.EngineMessage.Candidate
import com.jobeen.ime.engine.manager.CandidateRerankManager
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 重排窗口（2026-11-16 性能 ②）：
 *
 * - 窗口：只重排首屏可见数——下标 0 固定，1..10 进窗；窗口外保持
 *   引擎原始序（旧窗 1..24 时每批对 24 个候选各做一次 gram 查询）。
 * - gram 分缓存已删除（2026-11-17 阶段 4）：生产恒传 gramDb=null，
 *   缓存不可达；rerank 的 gramDb 入参路径仍保留直接查询，传真实
 *   GramDb 的调用点不受影响。
 *
 * CandidateRerankManager 的构造 Context 在 rerank 路径上不被调用，
 * 用 ContextWrapper(null) 占位（mockable android.jar 下可实例化）。
 */
class CandidateRerankWindowTest {

    private fun manager() = CandidateRerankManager(ContextWrapper(null))

    private fun candidates(vararg texts: String): List<Candidate> =
        texts.mapIndexed { i, t -> Candidate(index = i, text = t) }

    @Test
    fun window_onlyFirstScreenReranked_tailKeepsEngineOrder() {
        val input = candidates(*(0 until 25).map { "w%02d".format(it) }.toTypedArray())
        val result = runBlocking {
            manager().rerank(
                input, inputContext = "", gramDb = null,
                prefers = mapOf("w20" to 100, "w05" to 50),
            )
        }
        // 下标 0 固定；窗内偏好最高的 w05 升到首位
        assertEquals("w00", result[0].text)
        assertEquals("w05", result[1].text)
        assertEquals(5, result[1].index)
        // 窗外 w20 偏好再高也不进窗：位置与原始序一致，尾段原样
        assertEquals(20, result.indexOfFirst { it.text == "w20" })
        assertEquals(
            (11 until 25).map { "w%02d".format(it) },
            result.subList(11, 25).map { it.text },
        )
        assertEquals((0 until 25).toList(), result.map { it.index }.sorted())
    }

    @Test
    fun window_shortListUnchanged() {
        // 候选只有 2 个时窗口只有下标 1 一个元素：重排不改变顺序
        //（rerank 对 size<=1 才原样返回，2 个候选会新建列表，故
        // 只断言内容与顺序不变，不断言同一实例）
        val input = candidates("去", "丘")
        val result = runBlocking {
            manager().rerank(input, inputContext = "", gramDb = null, prefers = mapOf("丘" to 9))
        }
        assertEquals(listOf("去", "丘"), result.map { it.text })
        assertEquals(listOf(0, 1), result.map { it.index })
    }
}
