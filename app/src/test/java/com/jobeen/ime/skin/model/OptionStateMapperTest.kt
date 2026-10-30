// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.skin.model

import com.jobeen.ime.skin.notify.ConditionCurrent
import org.junit.Assert.assertEquals
import org.junit.Test

/** 选项态映射（接线轮 R2）：条件现值只含条件表声明的选项＋符号锁定态。 */
class OptionStateMapperTest {

    @Test
    fun mapConditionCurrent_onlyDeclaredOptionsAndSymbolLock() {
        val current = OptionStateMapper.mapConditionCurrent(
            conditionOptionNames = listOf("ascii_mode", "missing_option"),
            values = mapOf("ascii_mode" to true, "unrelated" to false),
            symbolKeyboardActive = true,
        )
        assertEquals(
            mapOf(
                "rime\$ascii_mode" to ConditionCurrent.Bool(true),
                "symbolLock" to ConditionCurrent.Bool(true),
            ),
            current,
        )
    }

    @Test
    fun mapConditionCurrent_symbolKeyboardInactive() {
        val current = OptionStateMapper.mapConditionCurrent(
            conditionOptionNames = emptyList(),
            values = emptyMap(),
            symbolKeyboardActive = false,
        )
        assertEquals(
            mapOf("symbolLock" to ConditionCurrent.Bool(false)),
            current,
        )
    }

    @Test
    fun mapOptionLabels_fullAndAbbreviated() {
        val switches = listOf(
            OptionLabelResolver.Switch(
                name = "ascii_mode", optionNames = listOf("ascii_mode"),
                states = listOf("中文", "英文"),
            ),
        )
        assertEquals(
            mapOf(
                "optionLabel\$ascii_mode" to "英文",
                "optionLabelAbbreviated\$ascii_mode" to "英",
            ),
            OptionStateMapper.mapOptionLabels(switches, mapOf("ascii_mode" to true)),
        )
        assertEquals(
            mapOf(
                "optionLabel\$ascii_mode" to "中文",
                "optionLabelAbbreviated\$ascii_mode" to "中",
            ),
            OptionStateMapper.mapOptionLabels(switches, mapOf("ascii_mode" to false)),
        )
    }

    @Test
    fun mapOptionLabels_multiOptionOrUnknownOmitted() {
        val switches = listOf(
            // 多选项开关：不解析
            OptionLabelResolver.Switch(
                name = "multi", optionNames = listOf("a", "b"),
                states = listOf("甲", "乙"),
            ),
            // 选项值未知：不入表
            OptionLabelResolver.Switch(
                name = "ghost", optionNames = listOf("ghost_opt"),
                states = listOf("关", "开"),
            ),
            // 态标签不足两项：不解析
            OptionLabelResolver.Switch(
                name = "thin", optionNames = listOf("thin_opt"),
                states = listOf("只一项"),
            ),
        )
        assertEquals(
            emptyMap<String, String>(),
            OptionStateMapper.mapOptionLabels(
                switches, mapOf("a" to true, "b" to false, "thin_opt" to true),
            ),
        )
    }
}
