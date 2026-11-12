// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.input.panel.component

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.content.Context
import android.graphics.Typeface
import android.os.Debug
import android.os.Process
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.jobeen.ime.R
import com.jobeen.ime.data.keyboard.theme.KeyboardColors
import com.jobeen.ime.engine.rime.data.DataManager
import com.jobeen.ime.skin.perf.PerfMonitor
import com.jobeen.ime.skin.perf.PerfRow
import com.jobeen.ime.skin.perf.PerfSnapshot
import com.jobeen.ime.skin.perf.buildPerfSections
import com.jobeen.ime.skin.perf.formatPerfReport
import com.jobeen.ime.skin.runtime.SkinKeyImageStore
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 键盘性能面板（P2-10，View 体系；v1.1.16.19 按对端 iOS 面板样式重做）：
 * 顶部标题「键盘性能」＋发送（蓝色纸飞机：报告文本上屏到宿主并
 * 关闭面板回键盘，对端 0.1.0(39) 同款口径）＋关闭（红色 ×）。
 *
 * - 内容分区：耗时（既有 5 项＋诊断探针，只加不减）／总览／
 *   按归属（估算）／按内存类型／malloc／进程／存储位置；
 *   每行带与上次采集的差值（上次快照只放进程内内存，跨进程不保留）。
 * - 报告文本与面板显示同一份分区列表拼出（见 PerfReport）。
 * - 每次打开采集一次并写一份报告文件到
 *   Logs/performance/memory-<yyyyMMdd-HHmmss>-<毫秒三位>-command.log
 *   （对端命名格式），面板末行显示该路径。
 * - 数据口径见 PerfReport.kt 注释：脚本行 Rhino 堆不可计量、
 *   ImageIO 是 iOS 专属不列、RIME 行是目录统计口径非 fd 口径。
 * - 内存单位 KB，其余单位 ms；只记元数据与耗时（与 PerfMonitor
 *   同口径，绝不记输入内容、候选词、剪贴板）。
 */
@SuppressLint("ViewConstructor")
class PerformancePanelView(
    context: Context,
    colors: KeyboardColors.ColorScheme,
) : ComponentView(context, colors) {

    /**
     * 发送回调：KawaiiPanel 接线——经 PanelListener.onCopyTextCommit
     * 把报告文本上屏到宿主，再 returnToKeyboard() 关闭面板回键盘。
     * 本面板是 ComponentView、没有 listener，不直接碰服务层。
     */
    var onSendReport: ((String) -> Unit)? = null

    /** 关闭回调：KawaiiPanel 接线为 returnToKeyboard()。 */
    var onClose: (() -> Unit)? = null

    /** 本次显示的报告文本（与面板同一份分区列表拼出）；发送按钮即发它。 */
    private var currentReportText: String = ""

    private val titleView = TextView(context).apply {
        text = context.getString(R.string.perf_panel_title)
        setTextSize(17f)
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(colors.panel.candidateText)
    }

    private val sendView = TextView(context).apply {
        text = "✈"
        setTextSize(20f)
        setTextColor(SEND_BLUE)
        contentDescription = context.getString(R.string.perf_send)
        val pad = (8 * context.resources.displayMetrics.density).toInt()
        setPadding(pad, pad / 2, pad, pad / 2)
        setOnClickListener { onSendReport?.invoke(currentReportText) }
    }

    private val closeView = TextView(context).apply {
        text = "✕"
        setTextSize(20f)
        setTextColor(CLOSE_RED)
        contentDescription = context.getString(R.string.close)
        val pad = (8 * context.resources.displayMetrics.density).toInt()
        setPadding(pad, pad / 2, pad, pad / 2)
        setOnClickListener { onClose?.invoke() }
    }

    private val rowsContainer = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        val pad = (16 * context.resources.displayMetrics.density).toInt()
        setPadding(pad, pad / 2, pad, pad / 2)
    }

    init {
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val hPad = (16 * context.resources.displayMetrics.density).toInt()
            val vPad = (8 * context.resources.displayMetrics.density).toInt()
            setPadding(hPad, vPad, hPad, vPad)
        }
        header.addView(
            titleView,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
        )
        header.addView(sendView)
        header.addView(closeView)
        root.addView(
            header,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ),
        )
        root.addView(
            ScrollView(context).apply {
                addView(
                    rowsContainer,
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                    ),
                )
            },
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT,
            ),
        )
        addView(
            root,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT),
        )
        refresh()
    }

    override fun refreshTheme(newColors: KeyboardColors.ColorScheme) {
        super.refreshTheme(newColors)
        titleView.setTextColor(newColors.panel.candidateText)
        refresh()
    }

    override fun show() {
        refresh()
        super.show()
    }

    /** 重采快照、重建分区行、写报告文件（空耗时聚合时耗时分区显“暂无数据”）。 */
    fun refresh() {
        val prev = lastSnapshot
        val snapshot = collectSnapshot()
        lastSnapshot = snapshot
        val sections = buildPerfSections(snapshot, prev, rowsFor(PerfMonitor.aggregates()))
        currentReportText = formatPerfReport(sections, prev == null)
        writeReportFile(snapshot.reportPath, currentReportText)
        rowsContainer.removeAllViews()
        for (section in sections) {
            rowsContainer.addView(sectionHeaderView(section.title))
            for (row in section.rows) {
                rowsContainer.addView(buildRowView(row.label, row.displayValue()))
            }
        }
    }

    private fun buildRowView(label: String, value: String): LinearLayout {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val vPad = (6 * context.resources.displayMetrics.density).toInt()
            setPadding(0, vPad, 0, vPad)
        }
        row.addView(
            rowTextView(label, 15f),
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
        )
        row.addView(rowTextView(value, 13f))
        return row
    }

    private fun rowTextView(text: String, sizeSp: Float = 14f): TextView =
        TextView(context).apply {
            this.text = text
            setTextSize(sizeSp)
            setTextColor(colors.panel.candidateText)
        }

    private fun sectionHeaderView(title: String): TextView =
        TextView(context).apply {
            text = title
            setTextSize(15f)
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(colors.panel.candidateText)
            val vPad = (10 * context.resources.displayMetrics.density).toInt()
            setPadding(0, vPad, 0, vPad / 2)
        }

    companion object {
        /** 发送按钮蓝（纸飞机）／关闭按钮红（×），按对端面板配色固定，不跟皮肤走。 */
        private val SEND_BLUE = 0xFF2196F3.toInt()
        private val CLOSE_RED = 0xFFF44336.toInt()

        /** Linux USER_HZ：/proc/self/stat 的 utime/stime 单位是 1/100 秒。 */
        private const val CLK_TCK = 100L

        /** 上次快照（进程内保留一份；跨进程不保留）＋ CPU 采样基准。 */
        private var lastSnapshot: PerfSnapshot? = null
        private var lastCpuTicks: Long = -1L
        private var lastCpuWallMs: Long = -1L

        /** 基础 5 项固定顺序（与开发页文案一致）。 */
        val BASE_ORDER = listOf(
            PerfMonitor.Event.COLD_START,
            PerfMonitor.Event.KEY_TO_COMMIT,
            PerfMonitor.Event.SKIN_APPLY,
            PerfMonitor.Event.SLOW_FRAME,
            PerfMonitor.Event.MEMORY,
        )

        /** P4 分段探针顺序（仅已有项显示）。 */
        val PROBE_ORDER = listOf(
            PerfMonitor.Event.STARTUP_STEP,
            PerfMonitor.Event.KEY_DISPATCH,
            PerfMonitor.Event.KEY_ENGINE,
            PerfMonitor.Event.KEY_COMMIT,
            PerfMonitor.Event.MAIN_LAG,
        )

        private fun labelFor(event: PerfMonitor.Event): String = when (event) {
            PerfMonitor.Event.COLD_START -> "冷启动"
            PerfMonitor.Event.KEY_TO_COMMIT -> "按键提交"
            PerfMonitor.Event.SKIN_APPLY -> "皮肤应用"
            PerfMonitor.Event.SLOW_FRAME -> "掉帧"
            PerfMonitor.Event.MEMORY -> "内存"
            PerfMonitor.Event.STARTUP_STEP -> "启动步骤"
            PerfMonitor.Event.KEY_DISPATCH -> "按键分发"
            PerfMonitor.Event.KEY_ENGINE -> "引擎处理"
            PerfMonitor.Event.KEY_COMMIT -> "提交上屏"
            PerfMonitor.Event.MAIN_LAG -> "主线程卡顿"
        }

        /** 单项文案（纯函数，可单测）：内存 KB，其余 ms；均值 1 位小数。 */
        fun formatAggregate(event: PerfMonitor.Event, agg: PerfMonitor.Aggregate): String {
            val unit = if (event == PerfMonitor.Event.MEMORY) "KB" else "ms"
            return "${agg.count} 次，均值 ${"%.1f".format(agg.avg)} $unit，最大 ${agg.max} $unit"
        }

        /**
         * 行顺序（纯函数，可单测）：基础 5 项固定顺序在前（缺失项
         * 跳过），探针项在后（仅已有项显示）；空聚合返回空列表
         * （调用方显示“暂无数据”）。
         */
        fun rowsFor(
            aggregates: Map<PerfMonitor.Event, PerfMonitor.Aggregate>,
        ): List<Pair<String, String>> {
            val rows = ArrayList<Pair<String, String>>()
            for (event in BASE_ORDER + PROBE_ORDER) {
                val agg = aggregates[event] ?: continue
                rows += labelFor(event) to formatAggregate(event, agg)
            }
            return rows
        }

        /** 报告文件名（对端命名格式）：memory-<yyyyMMdd-HHmmss>-<毫秒三位>-command.log。 */
        internal fun reportFileName(openedAtMs: Long): String {
            val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(openedAtMs))
            return "memory-$stamp-" + "%03d".format(openedAtMs % 1000) + "-command.log"
        }

        /** /proc/self/stat 的 utime+stime（时钟滴答数）；读不到为 -1。 */
        private fun readCpuTicks(): Long = runCatching {
            val stat = File("/proc/self/stat").readText()
            // comm 可能含空格／括号：取最后一个 ')' 之后，utime 是第 14 字段、
            // stime 第 15 字段，即 ')' 后第 12、13 个 token（下标 11、12）。
            val rest = stat.substringAfterLast(')').trim().split(' ')
            rest[11].toLong() + rest[12].toLong()
        }.getOrDefault(-1L)

        /** 视图总数（递归计入全部子视图）。 */
        private fun countViews(view: View): Int {
            if (view !is ViewGroup) return 1
            var total = 1
            for (i in 0 until view.childCount) total += countViews(view.getChildAt(i))
            return total
        }

        /** RimeUserData 下 *.userdb 条目：个数、大小合计（字节）、逐个名。 */
        private fun userdbStats(): Triple<Int, Long, List<String>> = runCatching {
            val dir = DataManager.userDataDir
            val entries = dir.listFiles { f -> f.name.endsWith(".userdb") }
                ?.sortedBy { it.name } ?: emptyList()
            var bytes = 0L
            for (entry in entries) {
                bytes += if (entry.isDirectory) {
                    entry.walkTopDown().sumOf { runCatching { it.length() }.getOrDefault(0L) }
                } else {
                    runCatching { entry.length() }.getOrDefault(0L)
                }
            }
            Triple(entries.size, bytes, entries.map { it.name })
        }.getOrDefault(Triple(0, 0L, emptyList()))

        /** 报告落盘（后台线程写，不阻塞面板打开；失败静默，面板照常显示）。 */
        private fun writeReportFile(path: String, text: String) {
            Thread {
                runCatching {
                    val file = File(path)
                    file.parentFile?.mkdirs()
                    file.writeText(text, Charsets.UTF_8)
                }
            }.apply { isDaemon = true }.start()
        }
    }

    /**
     * 一次面板打开的采样（全部取数容错：单项读不到记 null/0，不让
     * 一项异常拖垮整面；CPU 是采样值，见 PerfReport 注释）。
     */
    private fun collectSnapshot(): PerfSnapshot {
        val openedAtMs = System.currentTimeMillis()
        val wallMs = SystemClock.elapsedRealtime()
        val processStart = Process.getStartElapsedRealtime()
        val uptimeMs = if (processStart > 0L) wallMs - processStart else wallMs

        var totalPssKb = 0L
        var availMemKb = 0L
        var memStats: Map<String, String> = emptyMap()
        runCatching {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val info = am.getProcessMemoryInfo(intArrayOf(Process.myPid())).firstOrNull()
            totalPssKb = info?.totalPss?.toLong() ?: 0L
            memStats = info?.getMemoryStats() ?: emptyMap()
            val memInfo = ActivityManager.MemoryInfo()
            am.getMemoryInfo(memInfo)
            availMemKb = memInfo.availMem / 1024
        }
        fun summary(key: String): Long? = memStats["summary.$key"]?.toLongOrNull()

        // CPU：两次采样间的 utime+stime 增量／墙钟增量；首次采样用
        // 进程启动至今的累计平均（仍是采样值，非精确占用）。
        val ticks = readCpuTicks()
        val cpuPercent: Double? = when {
            ticks < 0L -> null
            lastCpuTicks >= 0L && wallMs > lastCpuWallMs -> {
                val cpuMs = (ticks - lastCpuTicks) * 1000.0 / CLK_TCK
                cpuMs / (wallMs - lastCpuWallMs) * 100.0
            }
            uptimeMs > 0L -> ticks * 1000.0 / CLK_TCK / uptimeMs * 100.0
            else -> null
        }
        if (ticks >= 0L) {
            lastCpuTicks = ticks
            lastCpuWallMs = wallMs
        }

        val (skinCount, skinBytes) = runCatching { SkinKeyImageStore.cacheStats() }
            .getOrDefault(0 to 0L)
        val (userdbCount, userdbBytes, leveldbNames) = userdbStats()
        // 键盘窗口层级视图总数：面板挂在键盘窗口内，rootView 即窗口根；
        // 未挂接时退回面板自身层级。
        val viewCount = runCatching { countViews(rootView ?: this) }.getOrDefault(0)

        val reportPath = File(
            context.filesDir,
            "Logs/performance/" + reportFileName(openedAtMs),
        ).absolutePath
        // “其他”= private-other + system；两键都缺时记 null（行显“—”），不硬凑 0。
        val privateOther = summary("private-other")
        val systemMem = summary("system")
        val otherKb = if (privateOther == null && systemMem == null) {
            null
        } else {
            (privateOther ?: 0L) + (systemMem ?: 0L)
        }

        return PerfSnapshot(
            openedAtMs = openedAtMs,
            totalPssKb = totalPssKb,
            availMemKb = availMemKb,
            cpuPercent = cpuPercent,
            viewCount = viewCount,
            graphicsKb = summary("graphics"),
            skinCacheCount = skinCount,
            skinCacheBytes = skinBytes,
            nativeHeapKb = summary("native-heap"),
            stackKb = summary("stack"),
            codeKb = summary("code"),
            otherKb = otherKb,
            mallocAllocatedKb = runCatching { Debug.getNativeHeapAllocatedSize() / 1024 }
                .getOrDefault(0L),
            userdbCount = userdbCount,
            userdbBytes = userdbBytes,
            leveldbNames = leveldbNames,
            uptimeMs = uptimeMs,
            reportPath = reportPath,
        )
    }
}

/** 行显示值：有差值时拼成「值（差值）」，与发送的报告文本同一格式。 */
private fun PerfRow.displayValue(): String =
    if (delta != null) "$value（$delta）" else value
