package com.jobeen.ime.data.manager

import android.content.Context
import com.jobeen.ime.base.util.appContext
import java.util.concurrent.ConcurrentHashMap

/**
 * 用户长按删除的候选词（文本集合）。
 *
 * 背景：Rime 的 deleteCandidate 只在 userdb 打墓碑标记，系统词库查词时不检查墓碑，
 * 系统词删完会立刻重新出现。因此在 App 层维护删除词集合，候选列表展示前过滤掉。
 * Rime 的 userdb 墓碑仍然会写入（用于 WebDAV 同步）。
 *
 * 线程安全：读在引擎 Default 线程、写在主线程，用 ConcurrentHashMap 的 Set；
 * 落盘时传快照副本，不能把活集合交给 SharedPreferences（其异步序列化会读到后续修改）。
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
            cache ?: ConcurrentHashMap.newKeySet<String>().also { set ->
                set.addAll(prefs.getStringSet(KEY_WORDS, emptySet()).orEmpty())
                cache = set
            }
        }
    }

    fun isDeleted(text: String): Boolean = load().contains(text)

    /** 当前全部已删除词的快照（供 WebDAV 同步读取）。 */
    fun all(): Set<String> = HashSet(load())

    /** 批量并入（WebDAV 同步合并远端删除词表时用），只落盘一次。 */
    fun addAll(words: Collection<String>) {
        val set = load()
        var changed = false
        for (w in words) {
            val t = w.trim()
            if (t.isNotEmpty() && set.add(t)) changed = true
        }
        if (changed) {
            prefs.edit().putStringSet(KEY_WORDS, HashSet(set)).apply()
        }
    }

    fun add(text: String) {
        val set = load()
        if (set.add(text)) {
            prefs.edit().putStringSet(KEY_WORDS, HashSet(set)).apply()
        }
    }

    fun remove(text: String) {
        val set = load()
        if (set.remove(text)) {
            prefs.edit().putStringSet(KEY_WORDS, HashSet(set)).apply()
        }
    }
}
