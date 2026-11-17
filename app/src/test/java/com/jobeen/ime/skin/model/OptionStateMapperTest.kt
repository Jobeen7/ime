// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.skin.model

import org.junit.Assert.assertEquals
import org.junit.Test

/** 选项态映射：只剩 mapOptionLabels（mapConditionCurrent 随皮肤 v3 死壳删除）。 */
class OptionStateMapperTest {

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
