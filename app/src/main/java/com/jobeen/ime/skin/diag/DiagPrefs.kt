package com.jobeen.ime.skin.diag

import android.content.Context
import androidx.core.content.edit
import com.jobeen.ime.data.manager.KeyboardManager

/**
 * 「诊断模式」开关（详设十三.6）：开启后 [DiagLog] 的 DEBUG 级条目
 * 才落盘；默认关闭、只记 INFO 及以上。偏好与键盘设置同文件
 * （[KeyboardManager.PREFS_NAME]），键名自带 skin.diag 前缀隔离。
 */
object DiagPrefs {
    internal const val KEY_DIAGNOSTIC_MODE = "skin.diag.diagnostic_mode"

    /** 诊断偏好文件（PerfMonitor 的门控监听与本开关共用同一文件与键）。 */
    fun prefs(context: Context): android.content.SharedPreferences =
        context.getSharedPreferences(KeyboardManager.PREFS_NAME, Context.MODE_PRIVATE)

    fun isDiagnosticMode(context: Context): Boolean =
        prefs(context).getBoolean(KEY_DIAGNOSTIC_MODE, false)

    fun setDiagnosticMode(context: Context, enabled: Boolean) {
        context.getSharedPreferences(KeyboardManager.PREFS_NAME, Context.MODE_PRIVATE).edit {
            putBoolean(KEY_DIAGNOSTIC_MODE, enabled)
        }
    }
}
