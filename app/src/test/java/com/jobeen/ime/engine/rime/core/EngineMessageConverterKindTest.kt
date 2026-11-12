// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.engine.rime.core

import com.jobeen.ime.engine.data.EngineMessage
import com.jobeen.ime.engine.data.EngineMessage.DynamicPreedit.DynamicPreeditItem
import com.jobeen.ime.engine.data.EngineMessage.DynamicPreedit.DynamicPreeditType
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * EngineMessageConverter.applySchemaKind 的切分机制（E-M4）：部署窗口
 * 内 Schema 通知丢 kind 更新时，Finish  job 靠本函数无条件重应用
 * currentSchema.kind——全拼与双拼的组字切分必须随 kind 走对分流器。
 *
 * 用例：raw="sh"、spelling="shi"。全拼按已键入长度切（sh|i）；
 * 双拼两码确定整字（shi 全高亮）。finally 恢复默认全拼分流器，
 * 不污染其他测试。
 */
class EngineMessageConverterKindTest {

    private fun convertShShi(): EngineMessage.DynamicPreedit {
        val out = EngineMessageConverter.convert(
            RimeMessage.DynamicPreeditMessage(
                CompositionProto(
                    syllables = arrayOf(
                        SyllableProto(rawInput = "sh", spelling = "shi"),
                    ),
                ),
            ),
        )
        return out as EngineMessage.DynamicPreedit
    }

    @Test
    fun fullPinyinSplitsTypedPrefixAndRest() {
        try {
            EngineMessageConverter.applySchemaKind("PinYin")
            assertEquals(
                listOf(
                    DynamicPreeditItem("sh", DynamicPreeditType.Normal),
                    DynamicPreeditItem("i", DynamicPreeditType.Secondary),
                    DynamicPreeditItem(" ", DynamicPreeditType.Normal),
                ),
                convertShShi().preedits,
            )
        } finally {
            EngineMessageConverter.applySchemaKind("")
        }
    }

    @Test
    fun doublePinyinTreatsTwoCodesAsWholeSyllable() {
        try {
            EngineMessageConverter.applySchemaKind("DoublePinyin")
            assertEquals(
                listOf(
                    DynamicPreeditItem("shi", DynamicPreeditType.Normal),
                    DynamicPreeditItem(" ", DynamicPreeditType.Normal),
                ),
                convertShShi().preedits,
            )
        } finally {
            EngineMessageConverter.applySchemaKind("")
        }
    }

    @Test
    fun kindContainingDoublePinyinSwitchesSplitter() {
        try {
            EngineMessageConverter.applySchemaKind("wanxiang_doublepinyin")
            assertEquals(
                listOf(
                    DynamicPreeditItem("shi", DynamicPreeditType.Normal),
                    DynamicPreeditItem(" ", DynamicPreeditType.Normal),
                ),
                convertShShi().preedits,
            )
        } finally {
            EngineMessageConverter.applySchemaKind("")
        }
    }

    @Test
    fun unrelatedKindKeepsFullPinyinSplitter() {
        try {
            EngineMessageConverter.applySchemaKind("Wanxiang")
            assertEquals(
                listOf(
                    DynamicPreeditItem("sh", DynamicPreeditType.Normal),
                    DynamicPreeditItem("i", DynamicPreeditType.Secondary),
                    DynamicPreeditItem(" ", DynamicPreeditType.Normal),
                ),
                convertShShi().preedits,
            )
        } finally {
            EngineMessageConverter.applySchemaKind("")
        }
    }
}
