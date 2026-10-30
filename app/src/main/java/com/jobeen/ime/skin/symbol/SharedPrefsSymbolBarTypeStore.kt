// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.skin.symbol

import android.content.Context

/**
 * 符号栏类型偏好存储（接线轮 R5）：
 * [SymbolBarTypeStore] 的偏好实现——类型跨进程记住，
 * 打开态不记住（仍只存在于 SymbolBarState 实例内）。
 */
class SharedPrefsSymbolBarTypeStore(context: Context) : SymbolBarTypeStore {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun load(): String? = prefs.getString(KEY_TYPE, null)

    override fun save(type: String?) {
        prefs.edit().putString(KEY_TYPE, type).apply()
    }

    companion object {
        private const val PREFS_NAME = "symbol_bar"
        private const val KEY_TYPE = "current_type"
    }
}
