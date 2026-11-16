package com.jobeen.ime

import com.jobeen.ime.engine.data.EngineMessage
import com.jobeen.ime.engine.rime.core.CandidateProto
import com.jobeen.ime.engine.rime.core.CompositionProto
import com.jobeen.ime.engine.rime.core.EngineMessageConverter
import com.jobeen.ime.engine.rime.core.RimeMessage
import com.jobeen.ime.engine.rime.core.SchemaItem
import com.jobeen.ime.engine.rime.core.StatusProto
import com.jobeen.ime.engine.rime.core.SyllableProto
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 候选消息的组字快照（性能第 1 条）：组字码／音节／方案 id 随候选消息
 * 下发，restoreCandidates 不再经 awaitJob 回 jobs 取。
 *
 * 转换器是带状态的单例：每条用例都按真实消息顺序先喂 Schema 与组字
 * 再喂候选，避免用例间状态串扰（与 RimeMessageConverterKeycapFilterTest
 * 同纪律）。
 */
class RimeMessageConverterCompositionSnapshotTest {

    private fun feedSchema(id: String) {
        EngineMessageConverter.convert(
            RimeMessage.SchemaMessage(
                SchemaItem(id = id, name = id, layout = "QWERTY")
            )
        )
    }

    private fun feedComposition(preedit: String, vararg syllables: SyllableProto) {
        EngineMessageConverter.convert(
            RimeMessage.CompositionMessage(
                CompositionProto(preedit = preedit, syllables = arrayOf(*syllables))
            )
        )
    }

    private fun feedDynamicPreedit(preedit: String, vararg syllables: SyllableProto) {
        EngineMessageConverter.convert(
            RimeMessage.DynamicPreeditMessage(
                CompositionProto(preedit = preedit, syllables = arrayOf(*syllables))
            )
        )
    }

    private fun convertCandidates(vararg texts: String): EngineMessage.Candidates {
        val msg = RimeMessage.CandidateListMessage(
            total = texts.size,
            highlighted = 0,
            candidates = texts.mapIndexed { i, t ->
                CandidateProto(text = t, comment = "", label = "${(i + 1) % 10}")
            }.toTypedArray(),
        )
        return EngineMessageConverter.convert(msg) as EngineMessage.Candidates
    }

    @Test
    fun candidates_carryCompositionSnapshotOfSameBatch() {
        feedSchema("wanxiang")
        feedComposition(
            "wo",
            SyllableProto(rawInput = "wo", spelling = "wo"),
        )
        val msg = convertCandidates("我", "喔", "握")
        assertEquals("wo", msg.composition.preedit)
        assertEquals(listOf("wo"), msg.composition.spellings)
        assertEquals("wo", msg.composition.firstRawInput)
        assertEquals("wanxiang", msg.composition.schemaId)
    }

    @Test
    fun multiSyllable_spellingsAndFirstRawInputCarried() {
        feedSchema("wanxiang")
        feedComposition(
            "ni'hao",
            SyllableProto(rawInput = "ni", spelling = "ni"),
            SyllableProto(rawInput = "hao", spelling = "hao"),
        )
        val msg = convertCandidates("你好")
        assertEquals("ni'hao", msg.composition.preedit)
        assertEquals(listOf("ni", "hao"), msg.composition.spellings)
        assertEquals("ni", msg.composition.firstRawInput)
    }

    @Test
    fun nextBatch_replacesSnapshot() {
        feedSchema("wanxiang")
        feedComposition("wo", SyllableProto(rawInput = "wo", spelling = "wo"))
        assertEquals("wo", convertCandidates("我").composition.preedit)
        feedComposition("wom", SyllableProto(rawInput = "wom", spelling = "wo"))
        val msg = convertCandidates("我们")
        assertEquals("wom", msg.composition.preedit)
        assertEquals(listOf("wo"), msg.composition.spellings)
        assertEquals("wom", msg.composition.firstRawInput)
    }

    @Test
    fun dynamicPreedit_alsoStashesComposition() {
        feedSchema("wanxiang")
        feedDynamicPreedit("qu", SyllableProto(rawInput = "qu", spelling = "qu"))
        val msg = convertCandidates("去")
        assertEquals("qu", msg.composition.preedit)
        assertEquals(listOf("qu"), msg.composition.spellings)
    }

    @Test
    fun schemaMessage_updatesSchemaIdForLaterBatches() {
        feedSchema("wanxiang")
        feedComposition("wo", SyllableProto(rawInput = "wo", spelling = "wo"))
        assertEquals("wanxiang", convertCandidates("我").composition.schemaId)
        feedSchema("wanxiang_t9")
        assertEquals("wanxiang_t9", convertCandidates("我").composition.schemaId)
        // 组字快照本身不受方案切换影响
        assertEquals("wo", convertCandidates("我").composition.preedit)
    }

    @Test
    fun statusIncrement_withNewSchemaId_updatesSchemaId_blankDoesNot() {
        feedSchema("wanxiang")
        feedComposition("wo", SyllableProto(rawInput = "wo", spelling = "wo"))
        EngineMessageConverter.convert(
            RimeMessage.StatusMessage(StatusProto(schemaId = "wanxiang_t9", isComposing = true))
        )
        assertEquals("wanxiang_t9", convertCandidates("我").composition.schemaId)
        EngineMessageConverter.convert(
            RimeMessage.StatusMessage(StatusProto(schemaId = "", isComposing = true))
        )
        assertEquals("wanxiang_t9", convertCandidates("我").composition.schemaId)
    }
}
