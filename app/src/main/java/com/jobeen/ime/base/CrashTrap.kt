package com.jobeen.ime.base

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import java.io.File

/**
 * 崩溃自捕获：未捕获异常先把堆栈写入私有文件再交回原处理器（进程
 * 仍按原样终止）；下次输入法服务启动时若发现该文件，把内容复制
 * 到剪贴板并提示一次，随后删除。供真机现场没有 adb 时取回堆栈。
 */
object CrashTrap {
    private const val FILE_NAME = "last_crash.txt"
    private const val MAX_CHARS = 24_000

    fun install(context: Context) {
        val appContext = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                val text = buildString {
                    append("thread: ").append(thread.name).append('\n')
                    append("time: ").append(System.currentTimeMillis()).append('\n')
                    append(Log.getStackTraceString(throwable))
                }
                File(appContext.filesDir, FILE_NAME).writeText(text)
            }
            previous?.uncaughtException(thread, throwable)
        }
    }

    /** 输入法服务启动时调用：有待取回的崩溃日志则复制到剪贴板并提示，随后删除。 */
    fun surfacePending(context: Context) {
        val file = File(context.filesDir, FILE_NAME)
        if (!file.exists()) return
        val text = runCatching { file.readText() }.getOrNull().orEmpty()
        runCatching { file.delete() }
        if (text.isBlank()) return
        Handler(Looper.getMainLooper()).post {
            runCatching {
                val clipboard = context.getSystemService(ClipboardManager::class.java)
                clipboard?.setPrimaryClip(
                    ClipData.newPlainText("Jime crash log", text.take(MAX_CHARS))
                )
                Toast.makeText(
                    context,
                    "已捕获上次崩溃日志并复制到剪贴板，可粘贴发送反馈",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }
}
