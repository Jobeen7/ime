// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.data.keyboard.theme

import android.content.Context
import androidx.core.content.edit
import com.jobeen.ime.ui.theme.AppAppearance

/**
 * 主题视频背景开关（2026-10-29 外观轮）：与应用外观共用
 * `appearance_settings` 偏好文件（键名 `video.*`）。
 * 默认开；关闭时皮肤视频不播放（静态背景仍显示），省电控制位。
 */
object VideoPrefs {
    const val KEY_ENABLED = "video.enabled"

    val ALL_KEYS: Set<String> = setOf(KEY_ENABLED)

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(AppAppearance.PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_ENABLED, true)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(AppAppearance.PREFS_NAME, Context.MODE_PRIVATE)
            .edit { putBoolean(KEY_ENABLED, enabled) }
    }
}
