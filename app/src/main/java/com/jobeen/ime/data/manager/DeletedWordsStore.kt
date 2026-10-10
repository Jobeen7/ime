package com.jobeen.ime.data.manager

import android.content.Context
import com.jobeen.ime.base.util.appContext
import java.util.concurrent.ConcurrentHashMap

/** 一条删词记录：当前状态与最后变更时间（毫秒，写入设备时钟）。 */
data class DeletedWordEntry(
    val deleted: Boolean,
    val updatedAt: Long,
)

/**
 * 编码一条删词记录为单行文本：`词<TAB>状态(1=已删/0=已恢复)<TAB>时间戳`。
 * 词本身含制表符/换行时无法编码，返回 null（候选词实际不会出现这类字符，
 * 同步与落盘时跳过即可）。
 */
internal fun encodeDeletedWordEntry(
    word: String,
    entry: DeletedWordEntry,
): String? {
    if (word.contains('\t') || word.contains('\n')) return null
    return "$word\t${if (entry.deleted) 1 else 0}\t${entry.updatedAt}"
}

/**
 * 解码一行删词记录。兼容旧格式：没有制表符的整行就是一个已删词
 * （旧版只存词表），按「已删、时间 0」处理——合并时任何带时间戳的
 * 新记录都能正确覆盖它。
 */
internal fun decodeDeletedWordEntry(line: String): Pair<String, DeletedWordEntry>? {
    val text = line.trim()
    if (text.isEmpty()) return null
    val parts = text.split('\t')
    if (parts.size < 3) return text to DeletedWordEntry(deleted = true, updatedAt = 0L)
    val word = parts[0]
    if (word.isEmpty()) return null
    val deleted = parts[1] != "0"
    val ts = parts[2].toLongOrNull() ?: 0L
    return word to DeletedWordEntry(deleted, ts)
}

/**
 * 合并两台设备的删词记录：逐词取时间戳较新的一方；时间相同（多为
 * 旧格式的时间 0）时「已删」优先，保证删除不被旧状态覆盖、恢复也
 * 不被旧删除覆盖——恢复是一次带新时间戳的显式操作，天然会赢。
 */
internal fun mergeDeletedWordEntries(
    local: Map<String, DeletedWordEntry>,
    remote: Map<String, DeletedWordEntry>,
): Map<String, DeletedWordEntry> {
    val merged = LinkedHashMap<String, DeletedWordEntry>(local)
    for ((word, r) in remote) {
        val l = merged[word]
        merged[word] = when {
            l == null -> r
            r.updatedAt > l.updatedAt -> r
            r.updatedAt < l.updatedAt -> l
            else -> if (r.deleted) r else l
        }
    }
    return merged
}

/**
 * 用户长按删除的候选词（带状态与时间戳的记录表）。
 *
 * 背景：Rime 的 deleteCandidate 只在 userdb 打墓碑标记，系统词库查词时不检查墓碑，
 * 系统词删完会立刻重新出现。因此在 App 层维护删除词集合，候选列表展示前过滤掉。
 *
 * 记录保留「已恢复」状态（墓碑的墓碑）：删除与恢复都是带时间戳的事件，
 * WebDAV 同步逐词取较新状态，误删后可以在任意设备恢复并传播到所有设备。
 *
 * 线程安全：读在引擎 Default 线程、写在主线程，内部用 ConcurrentHashMap；
 * 落盘时传快照副本，不能把活集合交给 SharedPreferences（其异步序列化会读到后续修改）。
 */
object DeletedWordsStore {
    private const val PREFS_NAME = "deleted_words"
    private const val KEY_WORDS = "words"

    private val prefs by lazy {
        appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    @Volatile
    private var cache: MutableMap<String, DeletedWordEntry>? = null

    private fun load(): MutableMap<String, DeletedWordEntry> {
        return cache ?: synchronized(this) {
            cache ?: ConcurrentHashMap<String, DeletedWordEntry>().also { map ->
                prefs.getStringSet(KEY_WORDS, emptySet()).orEmpty().forEach { line ->
                    decodeDeletedWordEntry(line)?.let { (word, entry) ->
                        map[word] = entry
                    }
                }
                cache = map
                observeLocked(map)
            }
        }
    }

    /** 变更与落盘快照整体串行，避免交错落盘让旧快照覆盖新状态。 */
    private fun persistLocked(map: Map<String, DeletedWordEntry>) {
        val lines = HashSet<String>()
        for ((word, entry) in map) {
            encodeDeletedWordEntry(word, entry)?.let { lines.add(it) }
        }
        prefs.edit().putStringSet(KEY_WORDS, lines).apply()
    }

    fun isDeleted(text: String): Boolean = load()[text]?.deleted == true

    /** 当前处于「已删」状态的全部词的快照。 */
    fun all(): Set<String> =
        load().filterValues { it.deleted }.keys.toHashSet()

    /** 当前全部记录（含已恢复的墓碑）的快照，供 WebDAV 同步合并。 */
    fun entriesSnapshot(): Map<String, DeletedWordEntry> = HashMap(load())

    /** 已删词列表（按词排序），供设置页展示与恢复。 */
    fun deletedWords(): List<String> = all().sorted()

    /**
     * 已见过的最大时间戳（本地记录与历次同步远端记录）：本机时钟偏慢
     * 时，新操作的墙钟时间戳会输给其他设备先前的高时间戳记录、合并
     * 时被旧状态覆盖。新记录的时间戳取 max(当前时刻, 已见最大+1)，
     * 保证「我见过一切之后做的操作」在合并中必胜过我见过的记录；
     * 远端未来时间戳造成的棘轮抬升以对方时钟偏差为界，是时间戳合并
     * 的固有代价。
     */
    @Volatile
    private var maxSeenUpdatedAt = 0L

    private fun nextTimestampLocked(): Long {
        val ts = maxOf(System.currentTimeMillis(), maxSeenUpdatedAt + 1)
        maxSeenUpdatedAt = ts
        return ts
    }

    private fun observeLocked(entries: Map<String, DeletedWordEntry>) {
        for (entry in entries.values) {
            if (entry.updatedAt > maxSeenUpdatedAt) {
                maxSeenUpdatedAt = entry.updatedAt
            }
        }
    }

    /**
     * 把一次同步拿到的远端记录合并进本地并返回合并结果（供上传）。
     * 合并在锁内对着**当前**本地状态计算，而不是对着网络往返前的
     * 快照：往返窗口内用户的新增长按删除/恢复会直接参与本次合并，
     * 不会被「快照合并结果整表覆盖」吞掉。
     */
    fun applyRemoteMerge(
        remote: Map<String, DeletedWordEntry>,
    ): Map<String, DeletedWordEntry> {
        synchronized(this) {
            val map = load()
            observeLocked(remote)
            val merged = mergeDeletedWordEntries(map, remote)
            map.clear()
            map.putAll(merged)
            persistLocked(map)
            return HashMap(merged)
        }
    }

    fun add(text: String) {
        val word = text.trim()
        if (word.isEmpty()) return
        synchronized(this) {
            val map = load()
            if (map[word]?.deleted != true) {
                map[word] = DeletedWordEntry(
                    deleted = true,
                    updatedAt = nextTimestampLocked(),
                )
                persistLocked(map)
            }
        }
    }

    /** 恢复一个误删的词：写入带新时间戳的「已恢复」记录，同步后全设备生效。 */
    fun remove(text: String) {
        val word = text.trim()
        if (word.isEmpty()) return
        synchronized(this) {
            val map = load()
            if (map[word]?.deleted == true) {
                map[word] = DeletedWordEntry(
                    deleted = false,
                    updatedAt = nextTimestampLocked(),
                )
                persistLocked(map)
            }
        }
    }
}
