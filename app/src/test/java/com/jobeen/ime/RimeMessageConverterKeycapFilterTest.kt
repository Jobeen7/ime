package com.jobeen.ime

import com.jobeen.ime.engine.data.EngineMessage
import com.jobeen.ime.engine.rime.core.CandidateProto
import com.jobeen.ime.engine.rime.core.CompositionProto
import com.jobeen.ime.engine.rime.core.EngineMessageConverter
import com.jobeen.ime.engine.rime.core.RimeMessage
import com.jobeen.ime.engine.rime.core.SchemaItem
import com.jobeen.ime.engine.rime.core.SyllableProto
import com.jobeen.ime.engine.rime.core.isKeycapSequence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 键帽表情过滤的规格（据 code-review §2.4 与 2026-10-22 核实）：
 * 仅当「当前方案布局为 T9」且「当前组字含未解析数字段」（某段原始码
 * 含数字、未成词、未解析出拼音字母）时，候选转换才摘除键帽序列候选
 * （如 7️⃣）；其余情形一律保留。摘除只删条目、保留其余候选的引擎
 * 全局下标（选词按下标定位，先滤再编号会整体错位）。
 *
 * 转换器是带状态的单例：每条用例都按真实消息顺序先喂 Schema 与
 * DynamicPreedit 建立状态，再喂候选，避免用例间状态串扰。
 */
class RimeMessageConverterKeycapFilterTest {

    private val keycap7 = "7\uFE0F\u20E3" // 7️⃣

    private fun feedSchema(layout: String) {
        EngineMessageConverter.convert(
            RimeMessage.SchemaMessage(
                SchemaItem(id = "test", name = "test", layout = layout)
            )
        )
    }

    private fun feedPreedit(composition: CompositionProto) {
        EngineMessageConverter.convert(RimeMessage.DynamicPreeditMessage(composition))
    }

    private fun convertCandidates(vararg texts: String): List<EngineMessage.Candidate> {
        val msg = RimeMessage.CandidateListMessage(
            total = texts.size,
            highlighted = 0,
            candidates = texts.mapIndexed { i, t ->
                CandidateProto(text = t, comment = "", label = "${(i + 1) % 10}")
            }.toTypedArray(),
        )
        return (EngineMessageConverter.convert(msg) as EngineMessage.Candidates).list
    }

    /** qu'77 形态：首段解析为 qu，尾段 77 无拼写、未成词——未解析数字段。 */
    private fun unresolvedComposition() = CompositionProto(
        preedit = "qu'77",
        syllables = arrayOf(
            SyllableProto(rawInput = "78'", spelling = "qu"),
            SyllableProto(rawInput = "77", spelling = ""),
        ),
    )

    /** wode 形态：每段都解析出拼音字母，无未解析数字段。 */
    private fun resolvedComposition() = CompositionProto(
        preedit = "wo de",
        syllables = arrayOf(
            SyllableProto(rawInput = "96", spelling = "wo"),
            SyllableProto(rawInput = "33'", spelling = "de"),
        ),
    )

    @Test
    fun t9_unresolvedDigitSegment_keycapFiltered_indicesPreserved() {
        feedSchema("T9")
        feedPreedit(unresolvedComposition())
        val result = convertCandidates("去", "丘", keycap7, "切", "七", "7", "🔥")
        // 键帽被摘除；裸数字候选「7」与普通 emoji 不在过滤范围
        assertEquals(listOf("去", "丘", "切", "七", "7", "🔥"), result.map { it.text })
        // 其余候选保留引擎全局下标（bulk 自 0 起编号），选词不错位
        assertEquals(listOf(0, 1, 3, 4, 5, 6), result.map { it.index })
    }

    @Test
    fun t9_fullyResolved_keycapKept() {
        feedSchema("T9")
        feedPreedit(resolvedComposition())
        val result = convertCandidates("去", "丘", keycap7, "七")
        assertEquals(listOf("去", "丘", keycap7, "七"), result.map { it.text })
        assertEquals(listOf(0, 1, 2, 3), result.map { it.index })
    }

    @Test
    fun qwerty_unresolvedDigitSegment_keycapKept() {
        feedSchema("Qwerty")
        feedPreedit(unresolvedComposition())
        val result = convertCandidates("去", "丘", keycap7, "七")
        assertEquals(listOf("去", "丘", keycap7, "七"), result.map { it.text })
    }

    @Test
    fun t9_digitSegmentCoveredByConfirmedCandidate_keycapKept() {
        feedSchema("T9")
        // 首段已选词成「去」且其音节跨度覆盖尾段：尾段 77 属于已确认
        // 段的一部分，不算未解析数字段（与 buildDynamicPreedit 的
        // confirmed 段跳过同口径）
        feedPreedit(
            CompositionProto(
                preedit = "qu'77",
                syllables = arrayOf(
                    SyllableProto(
                        rawInput = "78'", spelling = "qu", text = "去",
                        textSyllableStart = 0, textSyllableEnd = 1,
                    ),
                    SyllableProto(rawInput = "77", spelling = ""),
                ),
            )
        )
        val result = convertCandidates("去", "丘", keycap7, "七")
        assertEquals(listOf("去", "丘", keycap7, "七"), result.map { it.text })
    }

    @Test
    fun stateRefresh_compositionMessageAlsoUpdates_andClearsTrigger() {
        feedSchema("T9")
        feedPreedit(unresolvedComposition())
        val first = convertCandidates("去", keycap7, "七")
        assertEquals(listOf("去", "七"), first.map { it.text })

        // 下一轮组字经 Composition 路刷新为全解析：触发条件随之解除
        EngineMessageConverter.convert(RimeMessage.CompositionMessage(resolvedComposition()))
        val second = convertCandidates("去", keycap7, "七")
        assertEquals(listOf("去", keycap7, "七"), second.map { it.text })
    }

    @Test
    fun keycapSequence_detector() {
        assertTrue(isKeycapSequence("7\uFE0F\u20E3")) // 数字 + FE0F + 20E3
        assertTrue(isKeycapSequence("7\u20E3")) // 数字 + 20E3（无 FE0F）
        assertTrue(isKeycapSequence("#\uFE0F\u20E3"))
        assertTrue(isKeycapSequence("*\u20E3"))
        assertTrue(isKeycapSequence("0\uFE0F\u20E3"))
        assertFalse(isKeycapSequence("七"))
        assertFalse(isKeycapSequence("🔥"))
        assertFalse(isKeycapSequence("7")) // 裸数字不是键帽序列
        assertFalse(isKeycapSequence("77\uFE0F\u20E3")) // 两个基字符不是单键帽
        assertFalse(isKeycapSequence(""))
    }
}
