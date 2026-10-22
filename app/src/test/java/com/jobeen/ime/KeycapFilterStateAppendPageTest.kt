package com.jobeen.ime

import com.jobeen.ime.engine.data.EngineMessage
import com.jobeen.ime.engine.rime.core.CompositionProto
import com.jobeen.ime.engine.rime.core.EngineMessageConverter
import com.jobeen.ime.engine.rime.core.KeycapFilterState
import com.jobeen.ime.engine.rime.core.RimeMessage
import com.jobeen.ime.engine.rime.core.SchemaItem
import com.jobeen.ime.engine.rime.core.SyllableProto
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 键帽过滤在分页追加页上的规格（第十七批补覆盖）：追加页候选在
 * RimeEngine.loadMoreCandidates 内直构、不经转换器，过滤改走共享的
 * [KeycapFilterState.applyTo]——与首屏完全同一触发口径（T9 布局＋
 * 当前组字含未解析数字段），只摘键帽条目、保留其余条目的引擎全局
 * 下标。本类用例直接按追加页形态构造候选（下标自 50 起），状态仍
 * 经真实消息顺序喂转换器建立（与生产刷新点同源）。
 */
class KeycapFilterStateAppendPageTest {

    private val keycap7 = "7\uFE0F\u20E3" // 7️⃣
    private val keycap0 = "0\uFE0F\u20E3" // 0️⃣

    private fun feedSchema(layout: String) {
        EngineMessageConverter.convert(
            RimeMessage.SchemaMessage(
                SchemaItem(id = "test", name = "test", layout = layout)
            )
        )
    }

    /** qu'77 形态：尾段 77 无拼写、未成词——未解析数字段。 */
    private fun feedUnresolved() {
        EngineMessageConverter.convert(
            RimeMessage.DynamicPreeditMessage(
                CompositionProto(
                    preedit = "qu'77",
                    syllables = arrayOf(
                        SyllableProto(rawInput = "78'", spelling = "qu"),
                        SyllableProto(rawInput = "77", spelling = ""),
                    ),
                )
            )
        )
    }

    /** wode 形态：每段都解析出拼音字母，无未解析数字段。 */
    private fun feedResolved() {
        EngineMessageConverter.convert(
            RimeMessage.DynamicPreeditMessage(
                CompositionProto(
                    preedit = "wo de",
                    syllables = arrayOf(
                        SyllableProto(rawInput = "96", spelling = "wo"),
                        SyllableProto(rawInput = "33'", spelling = "de"),
                    ),
                )
            )
        )
    }

    /** 追加页形态：下标是引擎全局下标（本页自 50 起），与首屏异批。 */
    private fun appendPage(vararg texts: String): List<EngineMessage.Candidate> =
        texts.mapIndexed { i, t -> EngineMessage.Candidate(index = 50 + i, text = t) }

    @Test
    fun appendPage_t9Unresolved_keycapRemoved_indicesPreserved() {
        feedSchema("T9")
        feedUnresolved()
        val result = KeycapFilterState.applyTo(appendPage("去", keycap7, "切", keycap0, "七"))
        assertEquals(listOf("去", "切", "七"), result.map { it.text })
        // 摘除后其余条目保留引擎全局下标，选词不错位
        assertEquals(listOf(50, 52, 54), result.map { it.index })
    }

    @Test
    fun appendPage_t9Resolved_keycapKept() {
        feedSchema("T9")
        feedResolved()
        val page = appendPage("去", keycap7, "七")
        val result = KeycapFilterState.applyTo(page)
        assertEquals(page, result)
    }

    @Test
    fun appendPage_qwertyUnresolved_keycapKept() {
        feedSchema("Qwerty")
        feedUnresolved()
        val page = appendPage("去", keycap7, "七")
        val result = KeycapFilterState.applyTo(page)
        assertEquals(page, result)
    }

    @Test
    fun appendPage_compositionCleared_triggerReset() {
        feedSchema("T9")
        feedUnresolved()
        // 组字清空：空 syllables 的 Composition 消息把未解析标记重算为
        // false，追加页过滤随之解除（无独立清理口、靠刷新点复位）
        EngineMessageConverter.convert(
            RimeMessage.CompositionMessage(
                CompositionProto(preedit = "", syllables = emptyArray())
            )
        )
        val page = appendPage("去", keycap7, "七")
        val result = KeycapFilterState.applyTo(page)
        assertEquals(page, result)
    }
}
