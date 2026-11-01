// SPDX-License-Identifier: Apache-2.0
package com.jobeen.ime.skin.model

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * 通用图标槽（对端 systemImage 的跨平台机制的 Jime 映射）：
 *
 * - systemImage 是跨平台机制（不是 iOS 专属）：同一字段在
 *   Android 侧自动映射到 Material——图标名本身不可移植，
 *   机制可移植。本槽即该机制的 Jime 形态。
 * - 写法：字符串（Material Symbols 名，`material:` 前缀可写
 *   可不写，解析期剥离）或平台映射对象 `{ios, android}`（键
 *   严格小写；其它键忽略）。Jime 既有 `icon` 字符串字段即
 *   本槽的字符串形态（向后兼容，不改字段名）。
 * - Android 取值：映射的 `android` 值（已是 Material 名，
 *   前缀照剥）；`android` 缺失或空时，以 SF→Material 起始表
 *   查 `ios` 名（查到则用，查不到则视为未配置，不猜测）；
 *   字符串形态直接取本名。缺当前平台键或值为空 = 未配置
 *   （返回 null，不报错、不回退到其它平台名之外的东西）。
 * - 按压态图标（highlight）：同口径解析；缺失时渲染侧回落
 *   常态图标（KeyView 按压切换在 pressed 为空时直接返回，
 *   常态图标不动——与对端回落规则一致）。
 *
 * 纯函数，可直接单测；装配期仍有 MaterialSymbols 注册表
 * 校验（未知名记日志回退），本层不判注册表。
 */
object IconSlot {

    /** `material:` 前缀（可写可不写；写时解析期剥离）。 */
    const val MATERIAL_PREFIX = "material:"

    /** 剥离 `material:` 前缀（无前缀原样返回；空白返回空串，调用方按空处理）。 */
    fun stripMaterialPrefix(raw: String): String =
        raw.removePrefix(MATERIAL_PREFIX)

    /**
     * 解析图标节点为 Android 侧 Material 名：字符串 → 剥前缀；
     * 对象 → android 值（剥前缀），缺失/空时以起始表查 ios 名；
     * 其余形态 → null（未配置）。空白字符串 → null（调用方
     * 按原有口径记 STRUCTURE  rejection，本函数不记）。
     */
    fun resolveAndroidName(node: JsonElement?): String? {
        return when (node) {
            is JsonPrimitive -> node.contentOrNull
                ?.takeIf { it.isNotEmpty() }
                ?.let { stripMaterialPrefix(it) }
            is JsonObject -> {
                val android = (node["android"] as? JsonPrimitive)?.contentOrNull
                    ?.takeIf { it.isNotEmpty() }
                    ?.let { stripMaterialPrefix(it) }
                android ?: run {
                    val ios = (node["ios"] as? JsonPrimitive)?.contentOrNull
                        ?.takeIf { it.isNotEmpty() }
                    ios?.let { SfMaterialTable[it] }
                }
            }
            else -> null
        }
    }

    /**
     * SF Symbols → Material Symbols 起始表（转换器数据表，
     * 亦供运行期 android 缺失时回落）。只收已确认的对应关系；
     * 未在表中的名一律不映射（不猜测、不回退到近似名）。
     * 填充变体（.fill）与无填充同名映射；方向键以 Material
     * 的键盘箭头名对应。
     */
    val SfMaterialTable: Map<String, String> = mapOf(
        "delete.left" to "backspace",
        "delete.left.fill" to "backspace",
        "keyboard" to "keyboard",
        "mic" to "mic",
        "mic.fill" to "mic",
        "globe" to "language",
        "return" to "keyboard_return",
        "checkmark" to "check",
        "xmark" to "close",
        "gearshape" to "settings",
        "list.bullet" to "list",
        "magnifyingglass" to "search",
        "trash" to "delete",
        "chevron.up" to "keyboard_arrow_up",
        "chevron.down" to "keyboard_arrow_down",
        "chevron.left" to "chevron_left",
        "chevron.right" to "chevron_right",
        "square.and.arrow.up" to "share",
    )
}
