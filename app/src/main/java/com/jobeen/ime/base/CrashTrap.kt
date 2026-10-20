package com.jobeen.ime.base

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Process
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

/**
 * 崩溃自捕获：未捕获异常先把完整堆栈写入私有文件再交回原处理器
 * （进程仍按原样终止）。取回靠用户在日志页手动操作：页面读
 * [readPendingCrash] 展示「复制最近崩溃信息」入口，点击才复制到
 * 剪贴板——不再有任何自动写剪贴板的路径（旧实现下次启动自动覆写
 * 用户剪贴板，崩溃文本还会被自家剪贴板历史当普通文本收录）。
 *
 * 文件按进程分开（last_crash-<进程名>.txt）：主进程与 :speech
 * 进程各写各的，避免同日双崩时后写覆盖先写；旧版单文件
 * last_crash.txt 只读兼容（[readPendingCrash] 一并纳入候选）。
 * 备份规则已排除 last_crash*.txt，不随系统备份/迁移离开设备。
 */
object CrashTrap {
    private const val LEGACY_FILE_NAME = "last_crash.txt"
    private const val FILE_PREFIX = "last_crash-"
    private const val FILE_SUFFIX = ".txt"

    /** 复制到剪贴板时的截断上限（与旧自动取回口径一致） */
    const val MAX_CHARS = 24_000

    fun install(context: Context) {
        val appContext = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                // 用 printStackTrace 捕获完整栈（含 cause 链）：
                // Log.getStackTraceString 在 cause 链含某些异常
                // （如 UnknownHostException）时按 AOSP 实现直接
                // 返回空串，文件里会只剩 thread/time 两行
                val writer = StringWriter()
                PrintWriter(writer).use { throwable.printStackTrace(it) }
                val text = buildString {
                    append("thread: ").append(thread.name).append('\n')
                    append("time: ").append(System.currentTimeMillis()).append('\n')
                    append("process: ").append(currentProcessName(appContext)).append('\n')
                    append(writer.toString())
                }
                crashFile(appContext).writeText(text)
            }
            previous?.uncaughtException(thread, throwable)
        }
    }

    /**
     * 读最近一次崩溃记录（全部进程的文件与旧版单文件里取修改时间
     * 最新的一份）；没有或内容为空返回 null。只读不删：查看与复制
     * 都不消耗记录（下次崩溃自然覆盖本进程的那一份）。
     */
    fun readPendingCrash(context: Context): String? {
        val dir = context.filesDir ?: return null
        val candidates = dir.listFiles { file ->
            file.isFile && (
                file.name == LEGACY_FILE_NAME ||
                    (file.name.startsWith(FILE_PREFIX) && file.name.endsWith(FILE_SUFFIX))
                )
        } ?: return null
        val latest = candidates.maxByOrNull { it.lastModified() } ?: return null
        return runCatching { latest.readText() }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    private fun crashFile(context: Context): File =
        File(context.filesDir, FILE_PREFIX + sanitize(currentProcessName(context)) + FILE_SUFFIX)

    /** 进程名里的冒号等字符换成下划线，保证文件名在各文件系统上合法 */
    private fun sanitize(processName: String): String =
        processName.map { ch ->
            if (ch.isLetterOrDigit() || ch == '.' || ch == '-' || ch == '_') ch else '_'
        }.joinToString("")

    private fun currentProcessName(context: Context): String {
        if (Build.VERSION.SDK_INT >= 28) {
            runCatching { android.app.Application.getProcessName() }
                .getOrNull()?.takeIf { it.isNotEmpty() }?.let { return it }
        }
        val pid = Process.myPid()
        val name = runCatching {
            context.getSystemService(ActivityManager::class.java)
                ?.runningAppProcesses
                ?.firstOrNull { it.pid == pid }
                ?.processName
        }.getOrNull()
        return name?.takeIf { it.isNotEmpty() } ?: context.packageName
    }
}
