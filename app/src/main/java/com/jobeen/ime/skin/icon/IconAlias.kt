// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.skin.icon

/**
 * 图标名平台映射（重点候选 8-7，纯函数）：
 * 逻辑图标名 → Jime 原生 Material 命名空间的规范名。
 *
 * 解析顺序（注册表优先，别名不覆盖注册表）：
 * 1. 空白／空 → null（未设置 → 调用方回退，不记日志）；
 * 2. 去空白＋小写化后已在注册表 → 返回规范小写名；
 * 3. 在别名表且目标在注册表 → 返回目标名；
 * 4. 其他 → 原名透传（调用方按注册表查无处理：回退／空白，绝不崩）。
 *
 * 边界：只做 Jime 原生 Material 命名空间内的别名；绝不接
 * 外部符号体系名（已否决的兼容层不在此表内，查无即回退）。
 * 别名表刻意收敛：只收无歧义的通用词（工具栏池 id 与方向词）；
 * 有歧义的不进表（如 delete 本就是注册表名，注册表优先兜底）。
 */
object IconAlias {

    val ALIASES: Map<String, String> = mapOf(
        "copy" to "content_copy",
        "paste" to "content_paste",
        "cut" to "content_cut",
        "voice" to "mic",
        "emoji" to "mood",
        "back" to "arrow_back",
        "forward" to "arrow_forward",
        "up" to "arrow_upward",
        "down" to "arrow_downward",
        "ok" to "check",
        "cancel" to "close",
        "hide_keyboard" to "keyboard_hide",
        "return" to "keyboard_return",
    )

    fun resolve(name: String?, knownNames: Set<String>): String? {
        val normalized = name?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
        if (normalized in knownNames) return normalized
        val target = ALIASES[normalized] ?: return normalized
        return if (target in knownNames) target else normalized
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
