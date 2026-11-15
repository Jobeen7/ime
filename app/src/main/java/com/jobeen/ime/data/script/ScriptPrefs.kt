// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.data.script

import android.content.Context
import androidx.core.content.edit

/**
 * 脚本前缀配置（WO52 8.1，键名照 `ot5.java`）：
 * `commandViewScriptPrefix`。
 *
 * 默认值在对端源码里不可考（ot5 只存键名）：Jime 侧取前缀空串
 * （未设＝命令面板不设脚本前缀），与 9.1 的 AI 前缀同口径。
 * 命令面板本身不在 8.1 单子内——这里只存配置，供命令面板接入时读。
 */
object ScriptPrefs {
    private const val PREFS_NAME = "script_prefs"
    private const val KEY_COMMAND_VIEW_SCRIPT_PREFIX = "commandViewScriptPrefix"

    const val DEFAULT_COMMAND_VIEW_SCRIPT_PREFIX = ""

    fun getCommandViewScriptPrefix(context: Context): String =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_COMMAND_VIEW_SCRIPT_PREFIX, DEFAULT_COMMAND_VIEW_SCRIPT_PREFIX).orEmpty()

    fun setCommandViewScriptPrefix(context: Context, value: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
            putString(KEY_COMMAND_VIEW_SCRIPT_PREFIX, value)
        }
    }
}
