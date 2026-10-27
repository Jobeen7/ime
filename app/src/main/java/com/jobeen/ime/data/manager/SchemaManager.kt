package com.jobeen.ime.data.manager

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

object SchemaManager {
    const val PREFS_NAME = "schema_settings"
    const val KEY_ENABLED_IDS = "enabled_schema_ids"
    const val KEY_GRAMMAR_MODEL = "grammar_model"

    /**
     * 分目录的已启用顺序键：enabled_schema_ids_<目录名>（历史键）。
     * 现行模型：各目录的已启用顺序以该目录 default.custom.yaml 的
     * schema_list 为准，全局镜像键 [KEY_ENABLED_IDS] 只镜像当前目录
     * 的顺序；设置侧不再读写分目录键（见 SchemeEnabled.writeEnabledMirror，
     * 只写镜像键），键盘侧只读镜像键。
     * 分目录键现仅由重置（AppReset）与单目录迁移（SchemeDirs，初始目录）
     * 写入，备份还原按包内原样写回；生产侧已无 [readEnabledIds] 调用方。
     */
    fun enabledKeyFor(dir: String): String = "${KEY_ENABLED_IDS}_$dir"

    /**
     * 保存某目录的已启用顺序：分目录键 + 全局镜像键双写。
     * 本函数现仅由重置路径调用（写初始目录）；设置侧写顺序走各目录
     * custom 的 schema_list，镜像键另由 SchemeEnabled.writeEnabledMirror 写。
     * 键盘侧监听镜像键即可跟随目录切换，无需感知目录。
     */
    fun saveEnabledIds(prefs: SharedPreferences, dir: String, ids: List<String>) {
        val joined = ids.joinToString(",")
        prefs.edit {
            putString(enabledKeyFor(dir), joined)
            putString(KEY_ENABLED_IDS, joined)
        }
    }

    /**
     * 读某目录的已启用顺序：只读分目录键，缺失即空。
     * 注意：现行模型下生产侧已无调用方（顺序以各目录 custom 的
     * schema_list 与全局镜像键为准）；本函数仅作历史兼容保留。
     */
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
