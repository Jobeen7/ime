package com.jobeen.ime.data.manager

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

object SchemaManager {
    const val PREFS_NAME = "schema_settings"
    const val KEY_ENABLED_IDS = "enabled_schema_ids"
    const val KEY_GRAMMAR_MODEL = "grammar_model"

    /**
     * 分目录的已启用顺序键：enabled_schema_ids_<目录名>。
     * 键盘侧只读全局镜像键 [KEY_ENABLED_IDS]（见 [saveEnabledIds]），
     * 设置侧读写分目录键——切回旧目录时顺序原样恢复。
     */
    fun enabledKeyFor(dir: String): String = "${KEY_ENABLED_IDS}_$dir"

    /**
     * 保存某目录的已启用顺序：分目录键 + 全局镜像键双写。
     * 镜像恒等于当前目录的顺序（只经本函数写，调用方只对当前目录调），
     * 键盘侧监听镜像键即可跟随目录切换，无需感知目录。
     */
    fun saveEnabledIds(prefs: SharedPreferences, dir: String, ids: List<String>) {
        val joined = ids.joinToString(",")
        prefs.edit {
            putString(enabledKeyFor(dir), joined)
            putString(KEY_ENABLED_IDS, joined)
        }
    }

    /** 读某目录的已启用顺序：只读分目录键，缺失即空（调用方回落目录方案表顺序）。 */
    fun readEnabledIds(prefs: SharedPreferences, dir: String): List<String> {
        val raw = prefs.getString(enabledKeyFor(dir), null) ?: return emptyList()
        return raw.split(",").filter { it.isNotBlank() }
    }

    fun isGrammarModelEnabled(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_GRAMMAR_MODEL, true)
    }

    fun setGrammarModelEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
            putBoolean(KEY_GRAMMAR_MODEL, enabled)
        }
    }
}
