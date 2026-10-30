// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.skin.model

/**
 * 选项标签解析（重点候选 8-8，纯函数）：
 *
 * 键格式 `optionLabel$<开关名>` / `optionLabelAbbreviated$<开关名>`，
 * 开关配置取自方案 `switches`（名／选项名／态标签）：
 *
 * - 只解析单选项开关：states[0] = 选项关时的标签，states[1] =
 *   选项开时的标签（Rime 单选项开关惯例）；多选项开关、态标签
 *   不足两项、选项值未知 → null（调用方回落静态 label）；
 * - abbreviated = 标签首字（空标签 → null）。
 *
 * 选项值来自引擎（getRuntimeOption），不在本模块内读取；供给
 * 链按开关配置＋选项值算出值后经 updateDynamicTexts 推送。
 */
object OptionLabelResolver {

    /** 方案 switches 表中的一项（与 RimeSchema.Switch 同义，解耦引擎类型）。 */
    data class Switch(
        val name: String,
        val optionNames: List<String>,
        val states: List<String>,
    )

    fun resolve(
        switches: List<Switch>,
        optionValues: Map<String, Boolean>,
        key: DynamicTextSource.OptionLabel,
    ): String? {
        val sw = switches.firstOrNull { it.name == key.switchName } ?: return null
        if (sw.optionNames.size != 1 || sw.states.size < 2) return null
        val on = optionValues[sw.optionNames[0]] ?: return null
        val label = sw.states[if (on) 1 else 0]
        if (label.isEmpty()) return null
        return if (key.abbreviated) label.substring(0, 1) else label
    }
}
