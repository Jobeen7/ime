package com.jobeen.ime.data.manager

import android.content.Context
import com.jobeen.ime.base.util.appContext

/**
 * 用户长按删除的候选词（文本集合）。
 *
 * 背景：Rime 的 deleteCandidate 只在 userdb 打墓碑标记，系统词库查词时不检查墓碑，
 * 系统词删完会立刻重新出现。因此在 App 层维护删除词集合，候选列表展示前过滤掉。
 * Rime 的 userdb 墓碑仍然会写入（用于 WebDAV 同步）。
 */
object DeletedWordsStore {
    private const val PREFS_NAME = "deleted_words"
    private const val KEY_WORDS = "words"

    private val prefs by lazy {
        appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    @Volatile
    private var cache: MutableSet<String>? = null

    private fun load(): MutableSet<String> {
        return cache ?: synchronized(this) {
            cache ?: prefs.getStringSet(KEY_WORDS, emptySet())!!.toMutableSet().also { cache = it }
        }
    }

    fun isDeleted(text: String): Boolean = load().contains(text)

    fun add(text: String) {
        val set = load()
        if (set.add(text)) {
            prefs.edit().putStringSet(KEY_WORDS, set).apply()
        }
    }

    fun remove(text: String) {
        val set = load()
        if (set.remove(text)) {
            prefs.edit().putStringSet(KEY_WORDS, set).apply()
        }
    }
}
