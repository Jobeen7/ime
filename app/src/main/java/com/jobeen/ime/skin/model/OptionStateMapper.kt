// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.skin.model

import com.jobeen.ime.skin.notify.ConditionCurrent
import com.jobeen.ime.skin.notify.ConditionStyleParser

/**
 * 选项态映射（接线轮 R2，纯函数）：
 *
 * 把引擎读回的选项值映射成条件现值——条件表里的
 * `rime$<选项名>` 取对应选项布尔值（读不到的选项不写入，
 * 条件按不成立处理）；`symbolLock` 取符号键盘是否为活动键盘。
 *
 * 窗口层只负责读值与推送，映射规则全在这里，可直接单测。
 * 选项标签映射见 R3（同一文件的后续扩展）。
 */
object OptionStateMapper {

    /**
     * 条件现值映射。
     *
     * @param conditionOptionNames 条件表里声明的 rime 选项名
     *   （已去前缀）；values 之外的名字不写入。
     */
    fun mapConditionCurrent(
        conditionOptionNames: List<String>,
        values: Map<String, Boolean>,
        symbolKeyboardActive: Boolean,
    ): Map<String, ConditionCurrent> = buildMap {
        for (name in conditionOptionNames) {
            values[name]?.let { put(ConditionStyleParser.RIME_OPTION_PREFIX + name, ConditionCurrent.Bool(it)) }
        }
        put(
            ConditionStyleParser.SYMBOL_LOCK_KEY,
            ConditionCurrent.Bool(symbolKeyboardActive),
        )
    }

    /**
     * 选项标签映射（R3）：每开关各算全称与缩写两项，有值才入表；
     * 多选项开关／态标签不足／选项值未知 → 该项缺席（调用方
     * 回落静态 label，与 OptionLabelResolver 的 null 口径一致）。
     */
    fun mapOptionLabels(
        switches: List<OptionLabelResolver.Switch>,
        values: Map<String, Boolean>,
    ): Map<String, String> = buildMap {
        for (sw in switches) {
            val full = DynamicTextSource.OptionLabel(sw.name, false)
            OptionLabelResolver.resolve(switches, values, full)?.let { put(full.jsonName, it) }
            val abbrev = DynamicTextSource.OptionLabel(sw.name, true)
            OptionLabelResolver.resolve(switches, values, abbrev)?.let { put(abbrev.jsonName, it) }
        }
    }
}
