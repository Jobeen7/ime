package com.ninthsoft.ime.base.log

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber
import com.ninthsoft.ime.engine.rime.data.DataManager
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
    private var crashFile: File? = null
    private var installed = false

    fun install(context: android.content.Context) {
        synchronized(lock) {
            if (installed) return
            installed = true
            val appContext = context.applicationContext
            val oldCrashFile = File(appContext.filesDir, CRASH_LOG_NAME)
            DataManager.logDir.mkdirs()
            crashFile = File(DataManager.logDir, CRASH_LOG_NAME)
            migrateFile(oldCrashFile, crashFile!!)
            File(DataManager.logDir, "app.log").delete()
            loadLastCrash()
            Timber.plant(BufferTree())
            startLogcatReader()
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
            _entries.value = emptyList()
            crashFile?.delete()
            File(DataManager.logDir, "app.log").delete()
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
        _entries.value = listOf(
            AppLogEntry(
                time = "--:--:--.---",
                priority = Log.ERROR,
                tag = "CRASH",
                message = message,
            )
        )
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
            if (_entries.value.lastOrNull()?.let {
                    it.priority == entry.priority && it.tag == entry.tag && it.message == entry.message
                } == true) return
            _entries.value = (_entries.value + entry).takeLast(MAX_ENTRIES)
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
