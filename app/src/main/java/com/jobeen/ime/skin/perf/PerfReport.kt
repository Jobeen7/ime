// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.skin.perf

import android.content.Context
import com.jobeen.ime.R

/**
* 键盘性能报告：一次面板打开的采样快照 → 分区行 → 发送上屏用的纯文本报告。
*
* 口径（诚实项，勿“补”数字）：
* - 「脚本」行对应 Rhino 脚本宿主（SkinScriptSandbox）：Rhino 堆
* 无法直接计量，该行只写“不可计量”，不编字节数；「其余」=
* 总内存 − 键盘界面图形 − 皮肤图片缓存，脚本堆按 0 计，故其余
* 含脚本堆，可为负（对端面板同样会出现负值），照实显示。
* - 「按内存类型」不用 ImageIO：那是 iOS 专属，Android 没有，
* 该行直接不列，不硬凑；Java 堆未单列（对端分区无此行）。
* - 「其余」与各内存行差值以前次快照为基准；上次快照只放进程
* 内内存（PerformancePanelView 伴生对象），跨进程不保留。
* - CPU 是 /proc/self/stat 采样值（两次采样间的 utime+stime
* 增量／墙钟增量；首次采样用进程启动至今的累计平均）。
*
* 文案来源（WO36 抽串，与 PerfPanelLabels 同款做法）：
* 用户可见的分区标题／行标签／值模板一律走 [PerfReportLabels]——
* 运行时由调用方从 strings.xml（`perf_report_*`）解析后注入，
* 纯函数缺省值 [PerfReportLabels.DEFAULT] 与资源同值，供无 Context
* 的纯函数与单测使用；两处同值由 Wo36SkinPerfReportTest 逐项比对
* strings.xml 源码钉住，改串时两边必须一起改。本文件除
* [PerfReportLabels.resolve] 外仍是纯 Kotlin，不碰 Android 框架。
*/
data class PerfRow(val label: String, val value: String, val delta: String? = null)

data class PerfSection(val title: String, val rows: List<PerfRow>)

/**
 * 性能报告文案（WO36）：全部用户可见串的唯一来源。
 *
 * - [DEFAULT]：与 strings.xml `perf_report_*` 同值的 canonical 文案，
 *   纯函数缺省值与单测期望的唯一来源；单测不再硬编码中文，改串不改测试。
 * - [resolve]：运行时从资源解析（面板显示与发送文本共用同一份）。
 */
data class PerfReportLabels(
    val sectionTiming: String,
    val sectionOverview: String,
    val sectionAttribution: String,
    val sectionMemtype: String,
    val sectionMalloc: String,
    val sectionProcess: String,
    val sectionStorage: String,
    val rowNoData: String,
    val rowTotalMemory: String,
    val rowAvailMemory: String,
    val rowCpu: String,
    val rowSinceLast: String,
    val rowScript: String,
    val rowKeyboardUi: String,
    val rowSkinCache: String,
    val rowViewCount: String,
    val rowOther: String,
    val rowMallocHeap: String,
    val rowGraphics: String,
    val rowStack: String,
    val rowCode: String,
    val rowOtherMem: String,
    val rowMallocAllocated: String,
    val rowRimeFiles: String,
    val rowLeveldb: String,
    val rowUptime: String,
    val rowReportFile: String,
    val noDataValue: String,
    val cpuSampled: String,
    val firstCollection: String,
    val scriptValue: String,
    val keyboardUiValue: String,
    val skinCacheValue: String,
    val mallocAllocatedValue: String,
    val rimeFilesValue: String,
    val leveldbEmpty: String,
    val leveldbValue: String,
    val leveldbSeparator: String,
    val durationHoursMinutes: String,
    val durationMinutesSeconds: String,
    val durationSeconds: String,
    val seconds: String,
    val reportTitle: String,
    val firstSampleNote: String,
) {
    companion object {
        /** 与 strings.xml `perf_report_*` 同值的 canonical 文案。 */
        val DEFAULT = PerfReportLabels(
            sectionTiming = "耗时",
            sectionOverview = "总览",
            sectionAttribution = "按归属（估算）",
            sectionMemtype = "按内存类型",
            sectionMalloc = "malloc",
            sectionProcess = "进程",
            sectionStorage = "存储位置",
            rowNoData = "暂无数据",
            rowTotalMemory = "总内存",
            rowAvailMemory = "距上限剩余",
            rowCpu = "CPU",
            rowSinceLast = "距上次采集",
            rowScript = "脚本",
            rowKeyboardUi = "键盘界面",
            rowSkinCache = "皮肤图片缓存（估算）",
            rowViewCount = "视图数量",
            rowOther = "其余",
            rowMallocHeap = "malloc 堆",
            rowGraphics = "图形",
            rowStack = "栈",
            rowCode = "代码与映射文件",
            rowOtherMem = "其他",
            rowMallocAllocated = "已分配",
            rowRimeFiles = "RIME 打开的数据文件",
            rowLeveldb = "打开的 LevelDB",
            rowUptime = "运行时长",
            rowReportFile = "报告文件",
            noDataValue = "冷启动／按键提交／皮肤应用／掉帧／内存五项随使用累计",
            cpuSampled = "%1\$.1f%%（采样值）",
            firstCollection = "首次打开",
            scriptValue = "Rhino 宿主，堆不可计量",
            keyboardUiValue = "%1\$d 个视图 · 图形 %2\$s",
            skinCacheValue = "%1\$d 项 · 约 %2\$s",
            mallocAllocatedValue = "%1\$s（脚本部分不可计量，未扣除）",
            rimeFilesValue = "%1\$d 个 · 共 %2\$s（目录统计口径，非 fd 口径）",
            leveldbEmpty = "0 个",
            leveldbValue = "%1\$d 个：%2\$s",
            leveldbSeparator = "、",
            durationHoursMinutes = "%1\$d小时%2\$d分",
            durationMinutesSeconds = "%1\$d分%2\$d秒",
            durationSeconds = "%1\$d秒",
            seconds = "%1\$.1f 秒",
            reportTitle = "键盘性能",
            firstSampleNote = "首次采集（无差值）",
        )

        /** 运行时从资源解析全部报告文案（面板显示与发送文本共用同一份）。 */
        fun resolve(context: Context): PerfReportLabels = PerfReportLabels(
            sectionTiming = context.getString(R.string.perf_report_section_timing),
            sectionOverview = context.getString(R.string.perf_report_section_overview),
            sectionAttribution = context.getString(R.string.perf_report_section_attribution),
            sectionMemtype = context.getString(R.string.perf_report_section_memtype),
            sectionMalloc = context.getString(R.string.perf_report_section_malloc),
            sectionProcess = context.getString(R.string.perf_report_section_process),
            sectionStorage = context.getString(R.string.perf_report_section_storage),
            rowNoData = context.getString(R.string.perf_report_row_no_data),
            rowTotalMemory = context.getString(R.string.perf_report_row_total_memory),
            rowAvailMemory = context.getString(R.string.perf_report_row_avail_memory),
            rowCpu = context.getString(R.string.perf_report_row_cpu),
            rowSinceLast = context.getString(R.string.perf_report_row_since_last),
            rowScript = context.getString(R.string.perf_report_row_script),
            rowKeyboardUi = context.getString(R.string.perf_report_row_keyboard_ui),
            rowSkinCache = context.getString(R.string.perf_report_row_skin_cache),
            rowViewCount = context.getString(R.string.perf_report_row_view_count),
            rowOther = context.getString(R.string.perf_report_row_other),
            rowMallocHeap = context.getString(R.string.perf_report_row_malloc_heap),
            rowGraphics = context.getString(R.string.perf_report_row_graphics),
            rowStack = context.getString(R.string.perf_report_row_stack),
            rowCode = context.getString(R.string.perf_report_row_code),
            rowOtherMem = context.getString(R.string.perf_report_row_other_mem),
            rowMallocAllocated = context.getString(R.string.perf_report_row_malloc_allocated),
            rowRimeFiles = context.getString(R.string.perf_report_row_rime_files),
            rowLeveldb = context.getString(R.string.perf_report_row_leveldb),
            rowUptime = context.getString(R.string.perf_report_row_uptime),
            rowReportFile = context.getString(R.string.perf_report_row_report_file),
            noDataValue = context.getString(R.string.perf_report_no_data_value),
            cpuSampled = context.getString(R.string.perf_report_cpu_sampled),
            firstCollection = context.getString(R.string.perf_report_first_collection),
            scriptValue = context.getString(R.string.perf_report_script_value),
            keyboardUiValue = context.getString(R.string.perf_report_keyboard_ui_value),
            skinCacheValue = context.getString(R.string.perf_report_skin_cache_value),
            mallocAllocatedValue = context.getString(R.string.perf_report_malloc_allocated_value),
            rimeFilesValue = context.getString(R.string.perf_report_rime_files_value),
            leveldbEmpty = context.getString(R.string.perf_report_leveldb_empty),
            leveldbValue = context.getString(R.string.perf_report_leveldb_value),
            leveldbSeparator = context.getString(R.string.perf_report_leveldb_separator),
            durationHoursMinutes = context.getString(R.string.perf_report_duration_hours_minutes),
            durationMinutesSeconds = context.getString(R.string.perf_report_duration_minutes_seconds),
            durationSeconds = context.getString(R.string.perf_report_duration_seconds),
            seconds = context.getString(R.string.perf_report_seconds),
            reportTitle = context.getString(R.string.perf_report_title),
            firstSampleNote = context.getString(R.string.perf_report_first_sample_note),
        )
    }
}

/**
* 一次面板采样的全部原始数（采集逻辑在 PerformancePanelView，
* 本文件只做分区与格式化；内存一律 KB，时长一律 ms）。
*/
data class PerfSnapshot(
/** 打开面板的墙钟（ms），用于“距上次采集”与报告文件名。 */
val openedAtMs: Long,
/** 进程 PSS（KB）。 */
val totalPssKb: Long,
/** 系统可用内存（KB，ActivityManager.MemoryInfo.availMem）。 */
val availMemKb: Long,
/** 进程 CPU 占用百分比（采样值）；采不到为 null。 */
val cpuPercent: Double?,
/** 键盘窗口层级视图总数（含面板自身）。 */
val viewCount: Int,
/** 图形内存（KB，summary.graphics）。 */
val graphicsKb: Long?,
/** 皮肤图片缓存：项数与估算字节（位图 allocationByteCount 求和）。 */
val skinCacheCount: Int,
val skinCacheBytes: Long,
/** 按内存类型（KB，Debug.MemoryInfo summary.*；缺键为 null）。 */
val nativeHeapKb: Long?,
val stackKb: Long?,
val codeKb: Long?,
val otherKb: Long?,
/** malloc 已分配（KB，Debug.getNativeHeapAllocatedSize）。 */
val mallocAllocatedKb: Long,
/** RimeUserData 下 *.userdb 条目数与大小合计（字节，目录统计口径，非 fd 口径）。 */
val userdbCount: Int,
val userdbBytes: Long,
/** 打开的 LevelDB 名（即 *.userdb 条目名）。 */
val leveldbNames: List<String>,
/** 进程运行时长（ms，进程启动至今）。 */
val uptimeMs: Long,
/** 本次报告落盘路径（Logs/performance/memory-<时间>-command.log）。 */
val reportPath: String,
)

/** KB → "123.4 MB"（1 位小数）。 */
fun formatMb(kb: Long): String = "%.1f MB".format(kb / 1024.0)

/** 字节 → "123.4 MB"（1 位小数）。 */
fun formatBytesMb(bytes: Long): String = "%.1f MB".format(bytes / (1024.0 * 1024.0))

/** KB 差值 → "+1.2 MB" / "-1.2 MB"（零差值显 "+0.0 MB"）。 */
fun formatDeltaMb(diffKb: Long): String {
val sign = if (diffKb < 0) "-" else "+"
return "$sign%.1f MB".format(kotlin.math.abs(diffKb) / 1024.0)
}

/** 个数差值 → "+3" / "-2" / "0"。 */
fun formatDeltaCount(diff: Int): String = when {
diff > 0 -> "+$diff"
diff < 0 -> "$diff"
else -> "0"
}

/** ms → "2小时15分" / "45分32秒" / "12秒"（不足 1 秒显 "0秒"）。 */
fun formatDuration(ms: Long, labels: PerfReportLabels = PerfReportLabels.DEFAULT): String {
val totalSeconds = ms / 1000
val hours = totalSeconds / 3600
val minutes = (totalSeconds % 3600) / 60
val seconds = totalSeconds % 60
return when {
hours > 0 -> labels.durationHoursMinutes.format(hours, minutes)
minutes > 0 -> labels.durationMinutesSeconds.format(minutes, seconds)
else -> labels.durationSeconds.format(seconds)
}
}

/** 秒（1 位小数）→ "12.3 秒"。 */
fun formatSeconds(seconds: Double, labels: PerfReportLabels = PerfReportLabels.DEFAULT): String =
    labels.seconds.format(seconds)

/**
* 分区行（纯函数）：[timingRows] 为已有的耗时聚合行
* （PerformancePanelView.rowsFor 的输出，标签＋文案），放
* 「耗时」分区；其余分区由本次与上次快照算出，差值只在有上次
* 快照时出现。
*/
fun buildPerfSections(
snapshot: PerfSnapshot,
prev: PerfSnapshot?,
timingRows: List<Pair<String, String>>,
labels: PerfReportLabels = PerfReportLabels.DEFAULT,
): List<PerfSection> {
val sections = ArrayList<PerfSection>()

// 耗时：只加不减，现有 5 项＋诊断探针原样保留，无差值
// （累计聚合，单次差值无意义）。
val timing = if (timingRows.isEmpty()) {
listOf(PerfRow(labels.rowNoData, labels.noDataValue))
} else {
timingRows.map { (label, value) -> PerfRow(label, value)}
}
sections += PerfSection(labels.sectionTiming, timing)

// 总览
val sinceLast = prev?.let { (snapshot.openedAtMs - it.openedAtMs) / 1000.0}
sections += PerfSection(
labels.sectionOverview,
listOf(
PerfRow(labels.rowTotalMemory, formatMb(snapshot.totalPssKb), prev?.let { formatDeltaMb(snapshot.totalPssKb - it.totalPssKb)}),
PerfRow(labels.rowAvailMemory, formatMb(snapshot.availMemKb), prev?.let { formatDeltaMb(snapshot.availMemKb - it.availMemKb)}),
PerfRow(
labels.rowCpu,
snapshot.cpuPercent?.let { labels.cpuSampled.format(it)}?: "—",
prev?.let {
val cur = snapshot.cpuPercent
val old = it.cpuPercent
if (cur!= null && old!= null) {
val diff = cur - old
"${if (diff < 0) "-" else "+"}%.1f%%".format(kotlin.math.abs(diff))
} else {
null
}
},
),
PerfRow(labels.rowSinceLast, sinceLast?.let { formatSeconds(it, labels)}?: labels.firstCollection),
),
)

// 按归属（估算）：脚本不可计量按 0 计，其余含脚本堆，可为负。
val skinCacheKb = snapshot.skinCacheBytes / 1024
val graphicsKb = snapshot.graphicsKb?: 0L
val otherKb = snapshot.totalPssKb - graphicsKb - skinCacheKb
val prevOtherKb = prev?.let { it.totalPssKb - (it.graphicsKb?: 0L) - it.skinCacheBytes / 1024}
sections += PerfSection(
labels.sectionAttribution,
listOf(
// Rhino 堆无法直接计量：只写口径，不编数字，无差值。
PerfRow(labels.rowScript, labels.scriptValue),
PerfRow(
labels.rowKeyboardUi,
labels.keyboardUiValue.format(
snapshot.viewCount,
snapshot.graphicsKb?.let { formatMb(it)}?: "—",
),
prev?.let {
formatDeltaCount(snapshot.viewCount - it.viewCount)
},
),
PerfRow(
labels.rowSkinCache,
labels.skinCacheValue.format(snapshot.skinCacheCount, formatBytesMb(snapshot.skinCacheBytes)),
prev?.let { formatDeltaMb(skinCacheKb - it.skinCacheBytes / 1024)},
),
PerfRow(
labels.rowViewCount,
"${snapshot.viewCount}",
prev?.let { formatDeltaCount(snapshot.viewCount - it.viewCount)},
),
PerfRow(
labels.rowOther,
formatMb(otherKb),
prevOtherKb?.let { formatDeltaMb(otherKb - it)},
),
),
)

// 按内存类型：ImageIO 是 iOS 专属，Android 没有，不列。
fun memRow(label: String, cur: Long?, old: Long?): PerfRow = PerfRow(
label,
cur?.let { formatMb(it)}?: "—",
if (cur!= null && old!= null) formatDeltaMb(cur - old) else null,
)
sections += PerfSection(
labels.sectionMemtype,
listOf(
memRow(labels.rowMallocHeap, snapshot.nativeHeapKb, prev?.nativeHeapKb),
memRow(labels.rowGraphics, snapshot.graphicsKb, prev?.graphicsKb),
memRow(labels.rowStack, snapshot.stackKb, prev?.stackKb),
memRow(labels.rowCode, snapshot.codeKb, prev?.codeKb),
memRow(labels.rowOtherMem, snapshot.otherKb, prev?.otherKb),
),
)

// malloc（已分配口径）：脚本部分不可计量，只写已分配一行并注明。
sections += PerfSection(
labels.sectionMalloc,
listOf(
PerfRow(labels.rowMallocAllocated, labels.mallocAllocatedValue.format(formatMb(snapshot.mallocAllocatedKb))),
),
)

// 进程：RIME 行是目录统计口径（*.userdb 条目数与大小合计），非 fd 口径。
sections += PerfSection(
labels.sectionProcess,
listOf(
PerfRow(
labels.rowRimeFiles,
labels.rimeFilesValue.format(snapshot.userdbCount, formatBytesMb(snapshot.userdbBytes)),
prev?.let { formatDeltaCount(snapshot.userdbCount - it.userdbCount)},
),
PerfRow(
labels.rowLeveldb,
if (snapshot.leveldbNames.isEmpty()) labels.leveldbEmpty
else labels.leveldbValue.format(
snapshot.leveldbNames.size,
snapshot.leveldbNames.joinToString(labels.leveldbSeparator),
),
),
PerfRow(labels.rowUptime, formatDuration(snapshot.uptimeMs, labels)),
),
)

// 存储位置：末行显示本次报告落盘路径。
sections += PerfSection(
labels.sectionStorage,
listOf(PerfRow(labels.rowReportFile, snapshot.reportPath)),
)

return sections
}

/**
* 发送上屏用的纯文本报告（纯函数）：与面板显示同一份
* [PerfSection] 列表逐行拼出，分区标题＋每行「标签 值（差值）」；
* 首次采集（无上次快照）时标题下加一行说明。
*/
fun formatPerfReport(
sections: List<PerfSection>,
firstSample: Boolean,
labels: PerfReportLabels = PerfReportLabels.DEFAULT,
): String {
val out = StringBuilder(labels.reportTitle).append("\n")
if (firstSample) out.append(labels.firstSampleNote).append("\n")
for (section in sections) {
out.append("\n\n")
for (row in section.rows) {
out.append(row.label).append(' ').append(row.value)
if (row.delta!= null) out.append("（").append(row.delta).append('）')
out.append('\n')
}
}
return out.toString().trimEnd() + "\n"
}
