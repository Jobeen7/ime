package com.jobeen.ime.skin.library

import com.jobeen.ime.skin.diag.DiagLogCore
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 库页长按菜单的纯函数支持（可单测，不触碰 Android API）：
 * 皮肤错误日志过滤、详情行组装、尺寸/时间格式化。
 * 日志行格式与 DiagLogCore.formatLine 一致：
 * `日期 时间 级别 [模块] 事件 k=v ...`，一行一条。
 */
object SkinCardMenuSupport {

    /** 单个皮肤错误日志视图的上限行数。 */
    const val MAX_LOG_LINES = 50

    /**
     * 从按时间顺序（旧→新）的日志行中筛出某皮肤的错误行：
     * 仅 WARN/ERROR 级，且含 `skinId=<id>` 标记（标记后须为空格或行尾，
     * 避免 id 前缀误 match 更长的 id）。返回新→旧顺序，上限 [maxLines]。
     * 堆栈附行不带标记，不在此列（只保留条目首行）。
     */
    fun filterSkinLogLines(
        lines: List<String>,
        skinId: String,
        maxLines: Int = MAX_LOG_LINES,
    ): List<String> {
        if (skinId.isBlank() || maxLines <= 0) return emptyList()
        val token = "skinId=$skinId"
        val kept = ArrayList<String>()
        for (line in lines) {
            val parts = line.split(' ')
            val level = parts.getOrNull(2)
            if (level != "WARN" && level != "ERROR") continue
            val idx = line.indexOf(token)
            if (idx < 0) continue
            val after = idx + token.length
            if (after < line.length && line[after] != ' ') continue
            kept.add(line)
        }
        return kept.takeLast(maxLines).reversed()
    }

    /**
     * 从诊断日志目录读某皮肤的错误行：按名序（即创建序）读全部
     * diag-*.log，拼接后走 [filterSkinLogLines]。目录为空或不可读
     * 时返回空列表，不抛异常（调用方在 IO 线程调用）。
     */
    fun loadSkinLogLines(
        logsDir: File?,
        skinId: String,
        maxLines: Int = MAX_LOG_LINES,
    ): List<String> {
        if (logsDir == null || !logsDir.isDirectory) return emptyList()
        val files = logsDir.listFiles { f ->
            f.isFile && DiagLogCore.isLogFileName(f.name)
        }?.sortedBy { it.name } ?: return emptyList()
        val lines = ArrayList<String>()
        for (file in files) {
            runCatching { file.readLines() }.getOrNull()?.let { lines += it }
        }
        return filterSkinLogLines(lines, skinId, maxLines)
    }

    /** 字节数 → B / KB / MB（一位小数，与系统习惯一致）。 */
    fun formatSkinSize(bytes: Long): String = when {
        bytes < 1024L -> "$bytes B"
        bytes < 1024L * 1024L -> "%.1f KB".format(Locale.US, bytes / 1024.0)
        else -> "%.1f MB".format(Locale.US, bytes / 1024.0 / 1024.0)
    }

    /** 毫秒时间戳 → yyyy-MM-dd HH:mm（详情行用，不含秒）。 */
    fun formatSkinTime(millis: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(millis))

    /**
     * 布局摘要：parsed=false（快照未建成）→ 无法解析；
     * rows=null（快照建成但未绑定自定义布局）→ 方案默认；
     * 否则 “R 行 K 键”。
     */
    fun layoutSummaryText(parsed: Boolean, rows: Int?, keys: Int?): String = when {
        !parsed -> "无法解析"
        rows == null -> "方案默认"
        else -> "$rows 行 ${keys ?: 0} 键"
    }

    /** 详情对话框的行（标签→值）：名称空白时回退 id；状态取生效/上个/未生效。 */
    fun skinDetailRows(
        name: String,
        id: String,
        generation: Int,
        sizeBytes: Long,
        updatedAt: Long,
        isActive: Boolean,
        isLastGood: Boolean,
        layoutParsed: Boolean,
        layoutRows: Int?,
        layoutKeys: Int?,
    ): List<Pair<String, String>> = listOf(
        "名称" to name.ifBlank { id },
        "皮肤 id" to id,
        "版本" to "第 $generation 版",
        "大小" to formatSkinSize(sizeBytes),
        "更新" to formatSkinTime(updatedAt),
        "状态" to when {
            isActive -> "生效中"
            isLastGood -> "上个皮肤"
            else -> "未生效"
        },
        "布局" to layoutSummaryText(layoutParsed, layoutRows, layoutKeys),
    )
}
