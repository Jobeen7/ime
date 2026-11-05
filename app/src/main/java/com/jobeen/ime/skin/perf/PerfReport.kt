// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.skin.perf

/**
* 键盘性能报告（纯 Kotlin，不碰 Android 框架，可 JVM 单测）：
* 一次面板打开的采样快照 → 分区行 → 发送上屏用的纯文本报告。
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
*/
data class PerfRow(val label: String, val value: String, val delta: String? = null)

data class PerfSection(val title: String, val rows: List<PerfRow>)

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
fun formatDuration(ms: Long): String {
val totalSeconds = ms / 1000
val hours = totalSeconds / 3600
val minutes = (totalSeconds % 3600) / 60
val seconds = totalSeconds % 60
return when {
hours > 0 -> "${hours}小时${minutes}分"
minutes > 0 -> "${minutes}分${seconds}秒"
else -> "${seconds}秒"
}
}

/** 秒（1 位小数）→ "12.3 秒"。 */
fun formatSeconds(seconds: Double): String = "%.1f 秒".format(seconds)

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
): List<PerfSection> {
val sections = ArrayList<PerfSection>()

// 耗时：只加不减，现有 5 项＋诊断探针原样保留，无差值
// （累计聚合，单次差值无意义）。
val timing = if (timingRows.isEmpty()) {
listOf(PerfRow("暂无数据", "冷启动／按键提交／皮肤应用／掉帧／内存五项随使用累计"))
} else {
timingRows.map { (label, value) -> PerfRow(label, value)}
}
sections += PerfSection("耗时", timing)

// 总览
val sinceLast = prev?.let { (snapshot.openedAtMs - it.openedAtMs) / 1000.0}
sections += PerfSection(
"总览",
listOf(
PerfRow("总内存", formatMb(snapshot.totalPssKb), prev?.let { formatDeltaMb(snapshot.totalPssKb - it.totalPssKb)}),
PerfRow("距上限剩余", formatMb(snapshot.availMemKb), prev?.let { formatDeltaMb(snapshot.availMemKb - it.availMemKb)}),
PerfRow(
"CPU",
snapshot.cpuPercent?.let { "%.1f%%（采样值）".format(it)}?: "—",
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
PerfRow("距上次采集", sinceLast?.let { formatSeconds(it)}?: "首次打开"),
),
)

// 按归属（估算）：脚本不可计量按 0 计，其余含脚本堆，可为负。
val skinCacheKb = snapshot.skinCacheBytes / 1024
val graphicsKb = snapshot.graphicsKb?: 0L
val otherKb = snapshot.totalPssKb - graphicsKb - skinCacheKb
val prevOtherKb = prev?.let { it.totalPssKb - (it.graphicsKb?: 0L) - it.skinCacheBytes / 1024}
sections += PerfSection(
"按归属（估算）",
listOf(
// Rhino 堆无法直接计量：只写口径，不编数字，无差值。
PerfRow("脚本", "Rhino 宿主，堆不可计量"),
PerfRow(
"键盘界面",
"${snapshot.viewCount} 个视图 · 图形 ${snapshot.graphicsKb?.let { formatMb(it)}?: "—"}",
prev?.let {
formatDeltaCount(snapshot.viewCount - it.viewCount)
},
),
PerfRow(
"皮肤图片缓存（估算）",
"${snapshot.skinCacheCount} 项 · 约 ${formatBytesMb(snapshot.skinCacheBytes)}",
prev?.let { formatDeltaMb(skinCacheKb - it.skinCacheBytes / 1024)},
),
PerfRow(
"视图数量",
"${snapshot.viewCount}",
prev?.let { formatDeltaCount(snapshot.viewCount - it.viewCount)},
),
PerfRow(
"其余",
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
"按内存类型",
listOf(
memRow("malloc 堆", snapshot.nativeHeapKb, prev?.nativeHeapKb),
memRow("图形", snapshot.graphicsKb, prev?.graphicsKb),
memRow("栈", snapshot.stackKb, prev?.stackKb),
memRow("代码与映射文件", snapshot.codeKb, prev?.codeKb),
memRow("其他", snapshot.otherKb, prev?.otherKb),
),
)

// malloc（已分配口径）：脚本部分不可计量，只写已分配一行并注明。
sections += PerfSection(
"malloc",
listOf(
PerfRow("已分配", "${formatMb(snapshot.mallocAllocatedKb)}（脚本部分不可计量，未扣除）"),
),
)

// 进程：RIME 行是目录统计口径（*.userdb 条目数与大小合计），非 fd 口径。
sections += PerfSection(
"进程",
listOf(
PerfRow(
"RIME 打开的数据文件",
"${snapshot.userdbCount} 个 · 共 ${formatBytesMb(snapshot.userdbBytes)}（目录统计口径，非 fd 口径）",
prev?.let { formatDeltaCount(snapshot.userdbCount - it.userdbCount)},
),
PerfRow(
"打开的 LevelDB",
if (snapshot.leveldbNames.isEmpty()) "0 个"
else "${snapshot.leveldbNames.size} 个：${snapshot.leveldbNames.joinToString("、")}",
),
PerfRow("运行时长", formatDuration(snapshot.uptimeMs)),
),
)

// 存储位置：末行显示本次报告落盘路径。
sections += PerfSection(
"存储位置",
listOf(PerfRow("报告文件", snapshot.reportPath)),
)

return sections
}

/**
* 发送上屏用的纯文本报告（纯函数）：与面板显示同一份
* [PerfSection] 列表逐行拼出，分区标题＋每行「标签 值（差值）」；
* 首次采集（无上次快照）时标题下加一行说明。
*/
fun formatPerfReport(sections: List<PerfSection>, firstSample: Boolean): String {
val out = StringBuilder("键盘性能\n")
if (firstSample) out.append("首次采集（无差值）\n")
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
