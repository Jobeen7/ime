package com.jobeen.ime.base.log

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber
import com.jobeen.ime.data.App
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.io.BufferedReader
import java.io.InputStreamReader
import kotlin.system.exitProcess

data class AppLogEntry(
    val time: String,
    val priority: Int,
    val tag: String,
    val message: String,
)

object AppLogBuffer {
    private const val MAX_ENTRIES = 1000
    private const val CRASH_LOG_NAME = "crash.log"
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
    private var crashFile: File? = null
    private var installed = false

    fun install(context: android.content.Context, enableLogging: Boolean = true) {
        synchronized(lock) {
            if (installed) return
            installed = true
            val appContext = context.applicationContext
            val oldCrashFile = File(appContext.filesDir, CRASH_LOG_NAME)
            App.logDir.mkdirs()
            crashFile = File(App.logDir, CRASH_LOG_NAME)
            migrateFile(oldCrashFile, crashFile!!)
            File(App.logDir, "app.log").delete()
            loadLastCrash()
            if (enableLogging) {
                Timber.plant(BufferTree())
                startLogcatReader()
            }
            val previous = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
                recordCrash(thread, throwable)
                if (previous != null) {
                    previous.uncaughtException(thread, throwable)
                } else {
                    android.os.Process.killProcess(android.os.Process.myPid())
                    exitProcess(10)
                }
            }
        }
    }

    fun clear() {
        synchronized(lock) {
            buffer.clear()
            _entries.value = emptyList()
            crashFile?.delete()
            File(App.logDir, "app.log").delete()
        }
    }

    private fun migrateFile(oldFile: File, newFile: File) {
        if (oldFile == newFile || !oldFile.isFile || newFile.isFile) return
        runCatching {
            oldFile.copyTo(newFile, overwrite = false)
            oldFile.delete()
        }
    }

    private fun loadLastCrash() {
        val message = runCatching { crashFile?.takeIf { it.isFile }?.readText() }.getOrNull()
        if (message.isNullOrBlank()) return
        // 上次崩溃记录作为首条：必须同时种进 buffer——buffer 是存储的
        // 唯一事实来源，只写 _entries 会在下一条日志发布快照时丢失它
        val entry = AppLogEntry(
            time = "--:--:--.---",
            priority = Log.ERROR,
            tag = "CRASH",
            message = message,
        )
        buffer.addLast(entry)
        _entries.value = listOf(entry)
    }

    private fun recordCrash(thread: Thread, throwable: Throwable) {
        val message = "Uncaught exception in ${thread.name}\n${Log.getStackTraceString(throwable)}"
        runCatching { crashFile?.writeText(message) }
        appendEntry(
            AppLogEntry(
                time = synchronized(formatter) { formatter.format(Date()) },
                priority = Log.ERROR,
                tag = "CRASH",
                message = message,
            )
        )
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
        if (isNoise(entry)) return
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

    private fun isNoise(entry: AppLogEntry): Boolean {
        if (entry.tag.startsWith("DynamicFramerate")) return true
        if (entry.tag.startsWith("VRI[")) return true
        if (entry.tag == "InputMethodManager" || entry.tag == "InsetsController") return true
        return entry.message.contains("motion event handled by client, just return") || entry.message.contains(
            "requestHideFillUi"
        )
    }

    private fun startLogcatReader() {
        val pid = android.os.Process.myPid().toString()
        Thread {
            val process = runCatching {
                ProcessBuilder("logcat", "-v", "threadtime", "--pid", pid).redirectErrorStream(true)
                    .start()
            }.getOrNull() ?: return@Thread
            runCatching {
                BufferedReader(InputStreamReader(process.inputStream)).useLines { lines ->
                    lines.forEach { line -> parseLogcatLine(line)?.let(::appendEntry) }
                }
            }
        }.apply {
            name = "app-logcat-reader"
            isDaemon = true
            start()
        }
    }

    private fun parseLogcatLine(line: String): AppLogEntry? {
        val match =
            Regex("^\\S+\\s+(\\S+)\\s+\\d+\\s+\\d+\\s+([VDIWEF])\\s+([^:]+):\\s?(.*)$").find(line)
                ?: return null
        val priority = when (match.groupValues[2]) {
            "V" -> Log.VERBOSE
            "D" -> Log.DEBUG
            "I" -> Log.INFO
            "W" -> Log.WARN
            "E" -> Log.ERROR
            else -> Log.ASSERT
        }
        return AppLogEntry(
            time = match.groupValues[1],
            priority = priority,
            tag = match.groupValues[3].trim(),
            message = match.groupValues[4],
        )
    }
}
