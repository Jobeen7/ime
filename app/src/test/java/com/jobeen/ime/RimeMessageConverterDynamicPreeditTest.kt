package com.jobeen.ime

import com.jobeen.ime.engine.data.EngineMessage
import com.jobeen.ime.engine.rime.core.CompositionProto
import com.jobeen.ime.engine.rime.core.EngineMessageConverter
import com.jobeen.ime.engine.rime.core.RimeMessage
import com.jobeen.ime.engine.rime.core.SyllableProto
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * buildDynamicPreedit「整串」分支的规格（据 2026-10-22 代码核实）：
 * syllables 为空、或全部 spelling 拼接（无分隔符）后每个字符都是数字
 * （空串按真空真计）时命中——命中且 preedit 非空白则只产一个
 * item＝composition.preedit（Normal）；preedit 空白则零 item。
 * 任一段 spelling 含字母即不命中、走逐音节路径。
 *
 * 修复前 joinToString 用默认分隔符 ", "，多音节拼出串恒含逗号与空格、
 * 判定恒假，多段全空/纯数字 spelling 误落逐音节路径（每段多出
 * rawInput 回退项与分隔符项）；以下多音节用例即钉此分叉。
 */
class RimeMessageConverterDynamicPreeditTest {

    @Before
    fun setUp() {
        // 转换器切分器是全局单例状态，固定全拼，避免用例间互相干扰
        EngineMessageConverter.applySchemaKind("PinYin")
    }

    private fun convert(composition: CompositionProto): List<EngineMessage.DynamicPreedit.DynamicPreeditItem> {
        val result = EngineMessageConverter.convert(RimeMessage.DynamicPreeditMessage(composition))
        return (result as EngineMessage.DynamicPreedit).preedits
    }

    private fun normal(text: String) =
        EngineMessage.DynamicPreedit.DynamicPreeditItem(
            text,
            EngineMessage.DynamicPreedit.DynamicPreeditType.Normal
        )

    @Test
    fun multiSyllable_allEmptySpelling_takesWholePreeditBranch() {
        // T9 未完整音节化的多段形态：每段 spelling 皆为空串
        val items = convert(
            CompositionProto(
                preedit = "9633'9633",
                syllables = arrayOf(
                    SyllableProto(rawInput = "9633'", spelling = ""),
                    SyllableProto(rawInput = "9633", spelling = ""),
                ),
            )
        )
        assertEquals(listOf(normal("9633'9633")), items)
    }

    @Test
    fun multiSyllable_allDigitSpelling_takesWholePreeditBranch() {
        val items = convert(
            CompositionProto(
                preedit = "777",
                syllables = arrayOf(
                    SyllableProto(rawInput = "77", spelling = "77"),
                    SyllableProto(rawInput = "7", spelling = "7"),
                ),
            )
        )
        assertEquals(listOf(normal("777")), items)
    }

    @Test
    fun multiSyllable_mixedEmptyAndDigitSpelling_takesWholePreeditBranch() {
        // 拼接语义：空串不贡献字符，数字段决定全串仍为「全数字」
        val items = convert(
            CompositionProto(
                preedit = "773",
                syllables = arrayOf(
                    SyllableProto(rawInput = "77", spelling = "77"),
                    SyllableProto(rawInput = "3", spelling = ""),
                ),
            )
        )
        assertEquals(listOf(normal("773")), items)
    }

    @Test
    fun multiSyllable_allEmptySpelling_blankPreedit_yieldsNoItems() {
        val items = convert(
            CompositionProto(
                preedit = "",
                syllables = arrayOf(
                    SyllableProto(rawInput = "96", spelling = ""),
                    SyllableProto(rawInput = "33", spelling = ""),
                ),
            )
        )
        assertEquals(emptyList<EngineMessage.DynamicPreedit.DynamicPreeditItem>(), items)
    }

    @Test
    fun multiSyllable_letterSpelling_staysOnPerSyllablePath() {
        // 对照：任一段 spelling 含字母即不进整串分支。逐音节路径下
        // （全拼切分器）第一段出拼写＋系统分隔符，第二段 spelling 为空
        // 出 rawInput 回退＋分隔符，不等于单条整串 preedit
        val items = convert(
            CompositionProto(
                preedit = "wo de",
                syllables = arrayOf(
                    SyllableProto(rawInput = "9633", spelling = "wode"),
                    SyllableProto(rawInput = "96", spelling = ""),
                ),
            )
        )
        assertEquals(
            listOf(normal("wode"), normal(" "), normal("96"), normal(" ")),
            items
        )
    }

    @Test
    fun singleSyllable_digitSpelling_takesWholePreeditBranch() {
        // 回归钉：单音节时分隔符本就不出现，修复前后行为一致
        val items = convert(
            CompositionProto(
                preedit = "7",
                syllables = arrayOf(
                    SyllableProto(rawInput = "7", spelling = "7"),
                ),
            )
        )
        assertEquals(listOf(normal("7")), items)
    }

    @Test
    fun noSyllables_takesWholePreeditBranch() {
        val items = convert(CompositionProto(preedit = "abc"))
        assertEquals(listOf(normal("abc")), items)
    }
}
