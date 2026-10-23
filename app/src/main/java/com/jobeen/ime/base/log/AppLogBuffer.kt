package com.jobeen.ime.base.log

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class AppLogEntry(
    val time: String,
    val priority: Int,
    val tag: String,
    val message: String,
)

/**
 * 日志页的条目缓冲：只收本进程经 Timber 打出的条目（BufferTree）。
 * 崩溃记录不在这里：未捕获异常由 CrashTrap 单点落盘（双捕获并行
 * 已于清点 S9 收敛，避免一次崩溃两份记录、且本缓冲旧支用的
 * Log.getStackTraceString 在某些 cause 下会返回空串）；logcat 子
 * 进程读取器一并拆除（调试期设施，曾把日志页刷满系统帧率警告，
 * debug 调试有 adb logcat 可用）。
 */
object AppLogBuffer {
    private const val MAX_ENTRIES = 1000
    private val lock = Any()
    private val formatter = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val _entries = MutableStateFlow<List<AppLogEntry>>(emptyList())
    val entries: StateFlow<List<AppLogEntry>> = _entries.asStateFlow()

    /**
     * 条目存储的唯一事实来源：环形缓冲（全部访问都在 [lock] 内）。
     * 原实现每条日志都对整表做 `+ entry` 复制再 takeLast——两次全表
     * 分配；改为 deque 后追加/逐出是 O(1)，仅发布快照时拷一份给
     * StateFlow（对外契约与发布节奏不变：仍每条一发、内容不可变）。
     */
    private val buffer = ArrayDeque<AppLogEntry>()
    private var installed = false

    fun install(context: android.content.Context, enableLogging: Boolean = true) {
        synchronized(lock) {
            if (installed) return
            installed = true
            if (enableLogging) {
                Timber.plant(BufferTree())
            }
        }
    }

    fun clear() {
        synchronized(lock) {
            buffer.clear()
            _entries.value = emptyList()
        }
    }

    private class BufferTree : Timber.Tree() {
        override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
            val fullMessage = if (t == null) message else "$message\n${Log.getStackTraceString(t)}"
            val entry = AppLogEntry(
                time = synchronized(formatter) { formatter.format(Date()) },
                priority = priority,
                tag = tag ?: "APP",
                message = fullMessage,
            )
            appendEntry(entry)
        }
    }

    private fun appendEntry(entry: AppLogEntry) {
        synchronized(lock) {
            // 相邻去重与上限裁剪口径与原实现一致，只是载体换成环形缓冲
            if (buffer.lastOrNull()?.let {
                    it.priority == entry.priority && it.tag == entry.tag && it.message == entry.message
                } == true) return
            if (buffer.size >= MAX_ENTRIES) buffer.removeFirst()
            buffer.addLast(entry)
            _entries.value = ArrayList(buffer)
        }
    }
}
