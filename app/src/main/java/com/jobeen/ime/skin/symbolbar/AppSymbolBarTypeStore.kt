package com.jobeen.ime.skin.symbolbar

import android.content.Context
import com.jobeen.ime.skin.symbol.SymbolBarTypeStore

/**
 * 应用级符号栏类型记忆存储：与皮肤内符号条的类型记忆分开
 * （两者类型表互不相干，共用一键会互相串改）。
 */
class AppSymbolBarTypeStore(context: Context) : SymbolBarTypeStore {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun load(): String? = prefs.getString(KEY_TYPE, null)

    override fun save(type: String?) {
        prefs.edit().putString(KEY_TYPE, type).apply()
    }

    companion object {
        private const val PREFS_NAME = "app_symbol_bar"
        private const val KEY_TYPE = "current_type"
    }
}
