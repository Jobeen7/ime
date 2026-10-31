package com.jobeen.ime.skin.perf

import android.app.ActivityManager
import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import com.jobeen.ime.skin.diag.DiagPrefs
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

    enum class Event {
        COLD_START, KEY_TO_COMMIT, SKIN_APPLY, SLOW_FRAME, MEMORY,
        // P4 分段探针（默认关闭的诊断门控之下才记；聚合按事件 pooled，
        // 逐条明细（含 step/stage 名）在 JSONL 里，见 noteStartupStep/noteStage）：
        STARTUP_STEP, KEY_DISPATCH, KEY_ENGINE, KEY_COMMIT, MAIN_LAG,
    }

    data class Aggregate(val count: Long, val avg: Double, val max: Long)

    /** 掉帧阈值：帧间隔超过它记一次 SLOW_FRAME。 */
    const val SLOW_FRAME_THRESHOLD_MS = 100L

    /** 按键→提交配对窗口：超过它视为无配对（引擎异步滞后，不记）。 */
    const val KEY_COMMIT_WINDOW_MS = 2000L

    internal var clockMs: () -> Long = { SystemClock.elapsedRealtime() }

    /**
     * P4 诊断门控（复用 DiagPrefs.diagnostic_mode，默认关）：
     * 缓存的 @Volatile 标志，热路径只读它——关时探针在读表前即返回，
     * 不读钟、不加锁、不聚合、不落盘（DiagLog 的 DEBUG 是每次读偏好，
     * 热路径照搬会丢“零开销”，故此处用监听刷新缓存）。
     * 注意：本标志只门控 P4 新增探针；既有 record() 调用（COLD_START 等）
     * 行为不变——关掉它们会抹掉已有的常驻诊断，P4 不动。
     */
    @Volatile
    internal var diagnosticEnabled = false

    private val lock = Any()
    private val aggregates = mutableMapOf<Event, Aggregate>()
    private var perfDir: File? = null
    private var processStartMs: Long = -1L
    private var coldStartRecorded = false
    private var lastPressMs: Long = 0L

    /** 门控监听强持有（SharedPreferences 弱持有监听，不持有会被 GC 掉）。 */
    private var gateListener: SharedPreferences.OnSharedPreferenceChangeListener? = null

    private var watchdog: MainThreadWatchdog? = null

    private val defaultWatchdogFactory: ((Long) -> Unit) -> MainThreadWatchdog? = { onLag ->
        runCatching {
            val handler = Handler(Looper.getMainLooper())
            MainThreadWatchdog(
                post = { r -> handler.post(r) },
                remove = { r -> handler.removeCallbacks(r) },
                clockMs = { SystemClock.elapsedRealtime() },
                onLag = onLag,
            )
        }.getOrNull()
    }

    /** 仅测试用：替换看门狗工厂（JVM 单测里默认工厂触 Looper 会抛 stub 异常，被 runCatching 兜成 null）。 */
    internal var watchdogFactory: ((Long) -> Unit) -> MainThreadWatchdog? = defaultWatchdogFactory

    /**
     * 诊断门控开关：开则起主线程看门狗，关则停它。
     * 看门狗只在门控开时跑——关时主线程零附加 runnable。
     */
    fun setDiagnosticEnabled(enabled: Boolean) {
        diagnosticEnabled = enabled
        if (enabled) {
            if (watchdog == null) {
                watchdog = watchdogFactory { lag -> record(Event.MAIN_LAG, lag) }
                watchdog?.start()
            }
        } else {
            watchdog?.stop()
            watchdog = null
        }
    }

    /** 绑定诊断门控到偏好：启动时读一次现值，之后键变化经监听刷新缓存。 */
    fun bindDiagnosticGate(context: Context) {
        val prefs = DiagPrefs.prefs(context)
        setDiagnosticEnabled(prefs.getBoolean(DiagPrefs.KEY_DIAGNOSTIC_MODE, false))
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { changed, key ->
            if (key == DiagPrefs.KEY_DIAGNOSTIC_MODE) {
                setDiagnosticEnabled(changed.getBoolean(DiagPrefs.KEY_DIAGNOSTIC_MODE, false))
            }
        }
        gateListener = listener
        prefs.registerOnSharedPreferenceChangeListener(listener)
    }

    /**
     * 分段探针起点：门控开时返回当前钟，关时返回 -1（调用方把 -1
     * 原样传给 [noteStage] 即 no-op）。长方法体用这对标记，避免整段
     * 包进 lambda 重缩进；短方法体直接用 [measureStage]。
     */
    internal fun stageStart(): Long = if (diagnosticEnabled) clockMs() else -1L

    /** 分段探针终点：只记阶段名对应的耗时，绝不记按键内容、候选词、剪贴板。 */
    internal fun noteStage(event: Event, startMs: Long) {
        if (!diagnosticEnabled || startMs < 0L) return
        record(event, clockMs() - startMs)
    }

    /**
     * 短方法体的分段探针：门控关时直接跑 block（一次 volatile 读之外零开销）；
     * 开时量 block 的同步耗时并记入事件聚合与 JSONL。函数类型参数放最后，
     * 调用方 trailing-lambda 绑定不变。抛错时 finally 仍记（耗时含异常路径）。
     */
    internal inline fun <T> measureStage(event: Event, block: () -> T): T {
        if (!diagnosticEnabled) return block()
        val start = clockMs()
        try {
            return block()
        } finally {
            record(event, clockMs() - start)
        }
    }

    /** 启动单步耗时（AppStartup.runStep 调）：门控关时直接返回，零聚合零落盘。 */
    internal fun noteStartupStep(name: String, durationMs: Long, ok: Boolean) {
        if (!diagnosticEnabled) return
        record(
            Event.STARTUP_STEP, durationMs,
            mapOf("step" to name, "ok" to ok.toString()),
        )
    }

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

    /** 仅测试用：清聚合与配对状态（不碰落盘目录配置），并复位诊断门控与看门狗。 */
    internal fun resetForTest() {
        synchronized(lock) {
            aggregates.clear()
            coldStartRecorded = false
            lastPressMs = 0L
            processStartMs = -1L
        }
        diagnosticEnabled = false
        watchdog?.stop()
        watchdog = null
        watchdogFactory = defaultWatchdogFactory
    }
}
