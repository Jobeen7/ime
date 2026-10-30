package com.jobeen.ime.skin.perf

import android.app.ActivityManager
import android.content.Context
import android.os.Process
import android.os.SystemClock
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 键盘性能埋点与报告（2026-10-29 item-1 B）。
 *
 * - 事件（只记元数据与耗时，绝不记输入内容、候选词、剪贴板）：
 *   COLD_START（进程启动→首个键盘窗口挂接）、KEY_TO_COMMIT
 *   （按键分发→引擎提交上屏，近似值：最近一次按键到其后
 *   首次提交的间隔，含引擎异步，仅作趋势参考）、SKIN_APPLY
 *   （皮肤应用事务耗时）、SLOW_FRAME（帧间隔＞100ms 的掉帧）、
 *   MEMORY（进程 PSS，value 字段单位 KB，非耗时）。
 * - 聚合：进程内计数＋均值＋最大值（开发页直接读）。
 * - 落盘：JSONL 按日分文件写 `Logs/performance/perf-<日期>.jsonl`
 *  （filesDir 下），后台单线程追加，不阻塞主线程；诊断 zip
 *   导出时一并打包（见 DiagExporter）。
 * - 自动化基准需真机／CI：本 VM 无模拟器，埋点本身不产生
 *   基准数字；基线数据以真机实测为准，不在此处伪造。
 */
object PerfMonitor {

    enum class Event { COLD_START, KEY_TO_COMMIT, SKIN_APPLY, SLOW_FRAME, MEMORY }

    data class Aggregate(val count: Long, val avg: Double, val max: Long)

    /** 掉帧阈值：帧间隔超过它记一次 SLOW_FRAME。 */
    const val SLOW_FRAME_THRESHOLD_MS = 100L

    /** 按键→提交配对窗口：超过它视为无配对（引擎异步滞后，不记）。 */
    const val KEY_COMMIT_WINDOW_MS = 2000L

    internal var clockMs: () -> Long = { SystemClock.elapsedRealtime() }

    private val lock = Any()
    private val aggregates = mutableMapOf<Event, Aggregate>()
    private var perfDir: File? = null
    private var processStartMs: Long = -1L
    private var coldStartRecorded = false
    private var lastPressMs: Long = 0L

    private val writer = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "perf-writer").apply { isDaemon = true }
    }

    /** 应用启动时调用一次（filesDir 锚定落盘目录）。 */
    fun configure(filesDir: File) {
        synchronized(lock) {
            perfDir = File(filesDir, "Logs/performance")
        }
    }

    /** 进程启动时刻（调用方传 Process.getStartElapsedRealtime()）。 */
    fun noteProcessStart(startElapsedMs: Long) {
        synchronized(lock) { processStartMs = startElapsedMs }
    }

    /** 首个键盘窗口挂接时调用一次：冷启动耗时只记首个窗口。 */
    fun noteFirstFrame() {
        val now = clockMs()
        val start = synchronized(lock) {
            if (coldStartRecorded) return
            coldStartRecorded = true
            processStartMs
        }
        if (start > 0L) {
            val duration = now - start
            if (duration in 0..60_000L) record(Event.COLD_START, duration)
        }
    }

    /** 按键分发起点（BaseKeyboard.onAction 入口）。 */
    fun noteKeyPress() {
        synchronized(lock) { lastPressMs = clockMs() }
    }

    /** 引擎提交终点（MessageHandler 处理 Commit 前）：首个提交配对一次。 */
    fun noteCommit() {
        val now = clockMs()
        val press = synchronized(lock) {
            val p = lastPressMs
            lastPressMs = 0L
            p
        }
        if (press > 0L) {
            val duration = now - press
            if (duration in 0..KEY_COMMIT_WINDOW_MS) record(Event.KEY_TO_COMMIT, duration)
        }
    }

    /** 帧间隔（Choreographer 回调）：超阈值记一次掉帧。 */
    fun noteFrameInterval(intervalMs: Long) {
        if (intervalMs > SLOW_FRAME_THRESHOLD_MS) record(Event.SLOW_FRAME, intervalMs)
    }

    /** 内存采样（进程 PSS，单位 KB；meta 带可用内存 MB）。 */
    fun sampleMemory(context: Context) {
        runCatching {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val pss = am.getProcessMemoryInfo(intArrayOf(Process.myPid()))
                .firstOrNull()?.totalPss ?: 0
            val memInfo = ActivityManager.MemoryInfo()
            am.getMemoryInfo(memInfo)
            record(
                Event.MEMORY, pss.toLong(),
                mapOf("availMb" to (memInfo.availMem / (1024 * 1024)).toString()),
            )
        }
    }

    /** 通用记录：聚合＋JSONL 落盘（只记事件名、数值、元数据字符串）。 */
    fun record(event: Event, value: Long, meta: Map<String, String> = emptyMap()) {
        synchronized(lock) {
            val prev = aggregates[event]
            aggregates[event] = if (prev == null) {
                Aggregate(1, value.toDouble(), value)
            } else {
                Aggregate(
                    prev.count + 1,
                    (prev.avg * prev.count + value) / (prev.count + 1),
                    maxOf(prev.max, value),
                )
            }
        }
        val dir = synchronized(lock) { perfDir } ?: return
        val ts = System.currentTimeMillis()
        val line = buildJsonObject {
            put("ts", ts)
            put("event", event.name)
            put("value", value)
            put("meta", JsonObject(meta.mapValues { (_, v) -> kotlinx.serialization.json.JsonPrimitive(v) }))
        }.toString()
        writer.execute {
            runCatching {
                dir.mkdirs()
                val name = "perf-" +
                    SimpleDateFormat("yyyyMMdd", Locale.US).format(Date(ts)) + ".jsonl"
                File(dir, name).appendText(line + "\n", Charsets.UTF_8)
            }
        }
    }

    /** 开发页读数：各事件聚合快照（MEMORY 的 avg/max 单位为 KB）。 */
    fun aggregates(): Map<Event, Aggregate> = synchronized(lock) { aggregates.toMap() }

    /** 等待后台写盘完成（测试与关机路径用）。 */
    fun flush() {
        runCatching { writer.submit {}.get() }
    }

    /** 仅测试用：清聚合与配对状态（不碰落盘目录配置）。 */
    internal fun resetForTest() {
        synchronized(lock) {
            aggregates.clear()
            coldStartRecorded = false
            lastPressMs = 0L
            processStartMs = -1L
        }
    }
}
