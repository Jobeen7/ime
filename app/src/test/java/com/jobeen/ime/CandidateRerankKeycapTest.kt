package com.jobeen.ime

import android.content.ContextWrapper
import com.jobeen.ime.engine.data.EngineMessage.Candidate
import com.jobeen.ime.engine.manager.CandidateRerankManager
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 键帽序列候选在智能重排中的口径（第十七批定责后立规）：
 *
 * 引擎原始序里键帽（如 7️⃣，simplified 类型）恒紧跟其基础字（七）
 * 之后；Lua context_reorder 的可重排白名单只有 user_phrase，动不了
 * 它——把它抬到基础字之前的只有 App 侧本重排（组合序列被算成 3 个
 * 码点、长度项虚高，零偏好历史即越过基础字；再叠偏好计数）。故
 * 口径为：键帽候选不参加分数竞争，沉到重排窗口末尾，恒在基础字
 * 之后；其余候选的排序、下标保留与窗口范围（0 固定、1..25）不变。
 *
 * CandidateRerankManager 的构造 Context 在 rerank 路径上不被调用，
 * 用 ContextWrapper(null) 占位（mockable android.jar 下可实例化）。
 */
class CandidateRerankKeycapTest {

    private val keycap7 = "7\uFE0F\u20E3" // 7️⃣
    private val keycap0 = "0\uFE0F\u20E3" // 0️⃣

    private val manager = CandidateRerankManager(ContextWrapper(null))

    private fun candidates(vararg texts: String): List<Candidate> =
        texts.mapIndexed { i, t -> Candidate(index = i, text = t) }

    private fun rerank(
        list: List<Candidate>,
        prefers: Map<String, Int> = emptyMap(),
    ): List<Candidate> = runBlocking {
        manager.rerank(list, inputContext = "", gramDb = null, prefers = prefers)
    }

    @Test
    fun keycap_neverRisesAboveBaseChar_withoutAnyHistory() {
        // 引擎原始序：7️⃣ 紧跟在基础字「七」之后（下标 5）
        val input = candidates("去", "丘", "切", "枪", "七", keycap7)
        val result = rerank(input)
        // 零偏好零语法分时其余单字同分保序，键帽沉底——整体与原始
        // 序一致；修复前键帽会凭长度项虚高升到下标 1
        assertEquals(
            listOf("去", "丘", "切", "枪", "七", keycap7),
            result.map { it.text },
        )
        // 下标仍是引擎全局下标，重排不改 index
        assertEquals(listOf(0, 1, 2, 3, 4, 5), result.map { it.index })
    }

    @Test
    fun keycap_preferenceHistoryCannotLiftIt_either() {
        val input = candidates("去", "丘", "切", "枪", "七", keycap7)
        // 「切」有偏好计数会正常升位；7️⃣ 计数再高也升不起来
        val result = rerank(input, prefers = mapOf("切" to 3, keycap7 to 50))
        assertEquals("切", result[1].text)
        assertEquals(keycap7, result.last().text)
        assertEquals(5, result.last().index)
        // 基础字「七」恒在键帽之前
        val texts = result.map { it.text }
        assertEquals(true, texts.indexOf("七") < texts.indexOf(keycap7))
    }

    @Test
    fun multipleKeycaps_sinkTogether_keepingRelativeOrder() {
        val input = candidates("去", "七", keycap7, "零", keycap0, "丘")
        val result = rerank(input, prefers = mapOf(keycap7 to 9, keycap0 to 9))
        val texts = result.map { it.text }
        // 两个键帽都沉到窗口末尾，且保持原始相对序（7️⃣ 先于 0️⃣）
        assertEquals(listOf(keycap7, keycap0), texts.takeLast(2))
        assertEquals(true, texts.indexOf("七") < texts.indexOf(keycap7))
        assertEquals(true, texts.indexOf("零") < texts.indexOf(keycap0))
    }
}
