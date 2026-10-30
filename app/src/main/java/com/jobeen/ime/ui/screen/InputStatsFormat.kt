// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.ui.screen

import com.jobeen.ime.input.stats.InputStats

/**
 * 输入统计屏的纯展示格式化（无 Android 依赖，可单测）。
 *
 * 空态一律显示“—”：typingMs 为 0 不等于“0 分钟”（慢速输入的间隙
 * 若都超过 5 秒则不计入打字时长），速度／准确率无数据时为 null，
 * 误触率＝误触修正数 ÷ 按键总数，0 按键时为“—”。
 */

/** 分段标签：WEEK 沿用滚动 7 天聚合，仅标签按参考写作“本周”。 */
fun segmentLabel(period: InputStats.Period): String = when (period) {
    InputStats.Period.DAY -> "今日"
    InputStats.Period.WEEK -> "本周"
    InputStats.Period.MONTH -> "本月"
    InputStats.Period.YEAR -> "今年"
}

/** 打字时长：0 → “—”；不足 1 小时 → “X分钟”；否则 → “X小时Y分钟”。 */
fun formatTypingMinutes(typingMs: Long): String {
    if (typingMs <= 0L) return "—"
    val minutes = typingMs / 60000L
    if (minutes < 60L) return "${minutes}分钟"
    val hours = minutes / 60L
    val rest = minutes % 60L
    return if (rest == 0L) "${hours}小时" else "${hours}小时${rest}分钟"
}

/** 速度：null → “—”，否则取整字／分钟。 */
fun formatSpeed(speed: Double?): String =
    speed?.let { "%.0f 字／分钟".format(it) } ?: "—"

/** 准确率：null → “—”，否则取整百分比。 */
fun formatAccuracy(accuracy: Double?): String =
    accuracy?.let { "%.0f%%".format(it * 100) } ?: "—"

/**
 * 误触率：误触修正数 ÷ 按键总数，保留 1 位小数；0 按键 → “—”。
 * 不用 1−准确率或删字数／按键数冒充（删除不一定都是误触修正）。
 */
fun formatMistouchRate(corrections: Long, keypresses: Long): String {
    if (keypresses <= 0L) return "—"
    return "%.1f%%".format(corrections * 100.0 / keypresses)
}

/** 段位进度说明：已满级或距下一段位还差的字数。 */
fun rankCaption(rank: InputStats.Rank): String =
    if (rank.nextThreshold == null) {
        "已满级"
    } else {
        "距下一段位还差 ${rank.nextThreshold - rank.xp} 字"
    }

/**
 * 段位内 EXP 条文案：当前段位已获字数 ／ 到下一段位差距；
 * 已满级（化神期顶）显“已满级”。分母取五阶阈值差距（新鲜用户
 * 炼气期为 0 / 2000），不是图示的 0 / 100——图示分母无 Jime
 * 对应物，交回等否决。
 */
fun stageExpText(rank: InputStats.Rank): String {
    val next = rank.nextThreshold ?: return "已满级"
    val cur = InputStats.stageFloor(rank.level)
    return "${rank.xp - cur} / ${next - cur} EXP"
}

/**
 * 迅速评级（全局最快速度驱动；与段位统一用段位名，2026-10-31 Jobeen 定）。
 * 阈值（字／分钟）：化神≥400、元婴≥300、金丹≥200、筑基≥120、其余炼气；
 * 无数据为炼气期。
 */
fun speedRating(speed: Double?): String = when {
    speed == null -> "炼气期"
    speed >= 400.0 -> "化神期"
    speed >= 300.0 -> "元婴期"
    speed >= 200.0 -> "金丹期"
    speed >= 120.0 -> "筑基期"
    else -> "炼气期"
}

/**
 * 精准评级（本年准确率推断值驱动；与段位统一用段位名，2026-10-31 Jobeen 定）。
 * 阈值（推断准确率）：化神≥99%、元婴≥97%、金丹≥95%、筑基≥90%、其余炼气；
 * 无数据为炼气期。
 */
fun accuracyRating(accuracy: Double?): String = when {
    accuracy == null -> "炼气期"
    accuracy >= 0.99 -> "化神期"
    accuracy >= 0.97 -> "元婴期"
    accuracy >= 0.95 -> "金丹期"
    accuracy >= 0.90 -> "筑基期"
    else -> "炼气期"
}

/**
 * 短版分享报告：核心数（段位／累计／本期提交／字数／按键／
 * 速度／准确率推断值／最快）。只含聚合数字，不含输入内容。
 */
fun shortStatsReport(
    rank: InputStats.Rank,
    stats: InputStats.PeriodStats,
    fastest: Double?,
): String {
    val t = stats.total
    return buildString {
        appendLine("Jime 输入统计")
        appendLine("段位：${rank.name} Lv.${rank.level + 1} · 累计 ${rank.xp} 字")
        appendLine("提交 ${t.commits} 次 · ${t.chars} 字 · 按键 ${t.keypresses} 次")
        appendLine("平均速度：" + (stats.avgSpeed?.let { "%.0f 字／分钟".format(it) } ?: "—"))
        appendLine("最快速度：" + (fastest?.let { "%.0f 字／分钟".format(it) } ?: "—"))
        append(
            "准确率（推断值）：" +
                (stats.accuracy?.let { "%.0f%%".format(it * 100) } ?: "—"),
        )
    }
}

/**
 * 长版分享报告：短版全部内容＋词长三档＋误触率＋键盘热力前 5 键。
 * 只含聚合数字，不含输入内容。
 */
fun longStatsReport(
    periodLabel: String,
    rank: InputStats.Rank,
    stats: InputStats.PeriodStats,
    fastest: Double?,
): String {
    val t = stats.total
    val single = t.wordLen1
    val double = t.wordLen2
    val multi = t.wordLen3 + t.wordLen4plus
    val wlTotal = single + double + multi
    fun pct(n: Long): String =
        if (wlTotal > 0L) "%.0f%%".format(n * 100.0 / wlTotal) else "0%"
    val heatTotal = stats.heat.values.sum()
    val topHeat = stats.heat.entries.sortedByDescending { it.value }.take(5)
    return buildString {
        appendLine(shortStatsReport(rank, stats, fastest))
        appendLine("分段：$periodLabel")
        appendLine("词长分布：单字 ${pct(single)} · 双字词 ${pct(double)} · 多字词 ${pct(multi)}")
        appendLine("误触率：" + formatMistouchRate(t.corrections, t.keypresses))
        if (heatTotal > 0L) {
            append(
                "键盘热力：" + topHeat.joinToString(" · ") { (k, n) ->
                    "$k ${"%.0f%%".format(n * 100.0 / heatTotal)}"
                },
            )
        } else {
            append("键盘热力：暂无数据")
        }
    }
}

/** 类别分布：标签＋数量＋占比（总量为 0 时为空表；零项不列出）。 */
data class CategoryShare(
    val label: String,
    val count: Long,
    val percent: Double,
)

fun categoryDistribution(categories: Map<String, Long>): List<CategoryShare> {
    val total = categories.values.sum()
    if (total <= 0L) return emptyList()
    return InputStats.Category.entries.mapNotNull { cat ->
        val n = categories[cat.name] ?: 0L
        if (n <= 0L) {
            null
        } else {
            CategoryShare(cat.label, n, n.toDouble() / total.toDouble())
        }
    }
}
