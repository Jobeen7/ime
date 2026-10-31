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
 * [readPendingCrashInfo] 展示「复制最近崩溃信息」入口与发生时间，
 * 点击才复制到剪贴板——不再有任何自动写剪贴板的路径（旧实现下次
 * 启动自动覆写用户剪贴板，崩溃文本还会被自家剪贴板历史当普通文本
 * 收录）。
 *
 * 文件按进程分开（crash/last_crash-<进程名>.txt）：主进程与 :speech
 * 进程各写各的，避免同日双崩时后写覆盖先写。文件统一收进 filesDir
 * 的 crash/ 子目录：备份规则按目录整体排除，不随系统备份/迁移离开
 * 设备（旧版在 filesDir 根下逐文件名列排除，加进程即漏）。
 */
object CrashTrap {
    private const val FILE_PREFIX = "last_crash-"
    private const val FILE_SUFFIX = ".txt"

    /** 崩溃文件子目录名（filesDir/crash）：备份规则按目录整体排除 */
    private const val CRASH_DIR_NAME = "crash"

    /** 复制到剪贴板时的截断上限（字素口径，与旧自动取回的上限一致） */
    const val MAX_CHARS = 24_000

    /** 一份崩溃记录：正文与发生时间（文件落盘时间，即异常时刻） */
    data class PendingCrash(
        val text: String,
        val occurredAtMs: Long,
    )

    /**
     * 崩溃记录头行：版本号随崩溃落盘，读诊断包时一眼区分新旧崩溃
     * （2026-10-31 观察项：旧记录无版本号，曾被误认成当前版本崩溃）。
     * 旧记录无此行时读侧不做任何假设，正文原样展示。
     */
    internal fun crashHeader(
        threadName: String,
        timeMs: Long,
        processName: String,
        versionName: String,
        versionCode: Long,
    ): String = buildString {
        append("thread: ").append(threadName).append('\n')
        append("time: ").append(timeMs).append('\n')
        append("process: ").append(processName).append('\n')
        append("version: ").append(versionName).append(" (").append(versionCode).append(')').append('\n')
    }

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
                // 版本号取不到时不挡崩溃落盘：行照写、值标 unknown
                val (versionName, versionCode) = runCatching {
                    @Suppress("DEPRECATION")
                    val info = appContext.packageManager.getPackageInfo(appContext.packageName, 0)
                    val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        info.longVersionCode
                    } else {
                        info.versionCode.toLong()
                    }
                    (info.versionName ?: "unknown") to code
                }.getOrElse { "unknown" to -1L }
                val text = buildString {
                    append(
                        crashHeader(
                            thread.name,
                            System.currentTimeMillis(),
                            currentProcessName(appContext),
                            versionName,
                            versionCode,
                        ),
                    )
                    append(writer.toString())
                }
                crashFile(appContext).writeText(text)
            }
            previous?.uncaughtException(thread, throwable)
        }
    }

    /**
     * 读最近一次崩溃记录（crash/ 子目录的全部进程文件里取修改时间
     * 最新的一份）；没有或内容为空返回 null。
     * 只读不删：查看与复制都不消耗记录（下次崩溃自然覆盖本进程的
     * 那一份）；删除走 [clearPendingCrashes]（日志页「清空日志」）。
     */
    fun readPendingCrashInfo(context: Context): PendingCrash? {
        val filesDir = context.filesDir ?: return null
        val candidates = crashDir(filesDir).listFiles { file ->
            file.isFile && isCrashFileName(file.name)
        }?.toList().orEmpty()
        val latest = candidates.maxByOrNull { it.lastModified() } ?: return null
        val text = runCatching { latest.readText() }.getOrNull()
            ?.takeIf { it.isNotBlank() } ?: return null
        return PendingCrash(text = text, occurredAtMs = latest.lastModified())
    }

    /** 只取正文的兼容读法（见 [readPendingCrashInfo]） */
    fun readPendingCrash(context: Context): String? =
        readPendingCrashInfo(context)?.text

    /** 清除全部崩溃记录（crash/ 子目录内的全部进程文件） */
    fun clearPendingCrashes(context: Context) {
        val filesDir = context.filesDir ?: return
        crashDir(filesDir).listFiles()?.forEach { it.delete() }
    }

    /** 文件名是否为崩溃记录（现行按进程命名） */
    internal fun isCrashFileName(name: String): Boolean =
        name.startsWith(FILE_PREFIX) && name.endsWith(FILE_SUFFIX)

    /** 某进程的崩溃文件名（进程名先做文件名安全化） */
    internal fun crashFileName(processName: String): String =
        FILE_PREFIX + sanitize(processName) + FILE_SUFFIX

    private fun crashDir(filesDir: File): File = File(filesDir, CRASH_DIR_NAME)

    private fun crashFile(context: Context): File {
        val dir = crashDir(context.filesDir)
        dir.mkdirs()
        return File(dir, crashFileName(currentProcessName(context)))
    }

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
