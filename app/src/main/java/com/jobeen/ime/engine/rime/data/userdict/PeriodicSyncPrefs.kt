// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.engine.rime.data.userdict

import android.content.Context
import com.jobeen.ime.base.util.appContext

/**
 * 后台定期同步配置（2026-10-29 批量第 5 项）。
 *
 * 与 WebDAV 配置同文件（user_dict_sync，设备本地状态、不随备份走）：
 * 定时任务是本机调度状态，换设备后由用户重新开启。
 */
object PeriodicSyncPrefs {
    private const val PREFS_NAME = "user_dict_sync"

    private const val KEY_ENABLED = "periodic_sync_enabled"
    private const val KEY_INTERVAL_HOURS = "periodic_sync_interval_hours"
    private const val KEY_LAST_TIME = "periodic_sync_last_time"
    private const val KEY_LAST_OK = "periodic_sync_last_ok"

    /** 可选间隔（小时）：6 / 12 / 24，默认 24 */
    val INTERVAL_OPTIONS = listOf(6, 12, 24)
    const val DEFAULT_INTERVAL_HOURS = 24

    private val prefs by lazy {
        appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    var enabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_ENABLED, value).apply()

    var intervalHours: Int
        get() = prefs.getInt(KEY_INTERVAL_HOURS, DEFAULT_INTERVAL_HOURS)
            .let { if (it in INTERVAL_OPTIONS) it else DEFAULT_INTERVAL_HOURS }
        set(value) = prefs.edit()
            .putInt(KEY_INTERVAL_HOURS, if (value in INTERVAL_OPTIONS) value else DEFAULT_INTERVAL_HOURS)
            .apply()

    var lastSyncTime: Long
        get() = prefs.getLong(KEY_LAST_TIME, 0L)
        set(value) = prefs.edit().putLong(KEY_LAST_TIME, value).apply()

    var lastSyncOk: Boolean
        get() = prefs.getBoolean(KEY_LAST_OK, false)
        set(value) = prefs.edit().putBoolean(KEY_LAST_OK, value).apply()
}
