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
 * 转义删词中的保留字符：`\`→`\\`、制表符→`\t`、换行→`\n`、
 * 回车→`\r`；其余原样。未知转义序列（如旧数据里的字面 `\b`）
 * 原样保留，不破坏旧数据。旧数据里字面的 `\t`／`\n`／`\r`
 * 两字符序列与转义歧义（解码时会当成转义还原）——候选词实际
 * 不含反斜杠，该歧义可忽略。
 */
internal fun escapeDeletedWord(word: String): String {
    if (word.none { it == '\\' || it == '\t' || it == '\n' || it == '\r' }) return word
    val sb = StringBuilder(word.length)
    for (c in word) {
        when (c) {
            '\\' -> sb.append("\\\\")
            '\t' -> sb.append("\\t")
            '\n' -> sb.append("\\n")
            '\r' -> sb.append("\\r")
            else -> sb.append(c)
        }
    }
    return sb.toString()
}

/** [escapeDeletedWord] 的逆变换：未知转义序列原样保留。 */
internal fun unescapeDeletedWord(escaped: String): String {
    if ('\\' !in escaped) return escaped
    val sb = StringBuilder(escaped.length)
    var i = 0
    while (i < escaped.length) {
        val c = escaped[i]
        if (c == '\\' && i + 1 < escaped.length) {
            when (escaped[i + 1]) {
                '\\' -> { sb.append('\\'); i += 2 }
                't' -> { sb.append('\t'); i += 2 }
                'n' -> { sb.append('\n'); i += 2 }
                'r' -> { sb.append('\r'); i += 2 }
                else -> { sb.append(c); i += 1 }
            }
        } else {
            sb.append(c)
            i += 1
        }
    }
    return sb.toString()
}

/**
 * 编码一条删词记录为单行文本：`词<TAB>状态(1=已删/0=已恢复)<TAB>时间戳`。
 * 词含制表符／换行时先转义再编码（见 [escapeDeletedWord]），不再
 * 返回 null——旧实现遇这类词直接丢弃，落盘时跳过，删除从不持久（D-M10）。
 */
internal fun encodeDeletedWordEntry(
    word: String,
    entry: DeletedWordEntry,
): String {
    return "${escapeDeletedWord(word)}\t${if (entry.deleted) 1 else 0}\t${entry.updatedAt}"
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
    if (parts.size < 3) {
        return unescapeDeletedWord(text) to DeletedWordEntry(deleted = true, updatedAt = 0L)
    }
    val word = unescapeDeletedWord(parts[0])
    if (word.isEmpty()) return null
    val deleted = parts[1] != "0"
    // 时间戳必须钳制：同步文件来自其他设备/对端写入，不设上限时一个
    // Long.MAX_VALUE 级的坏时间戳会在合并中永久压制该词（本机的新
    // 删除/恢复永远输给它）。上限取「当前时间 + 1 天」，容忍设备间
    // 时钟偏差，挡住离谱值
    val tsMax = System.currentTimeMillis() + 86_400_000L
    val ts = (parts[2].toLongOrNull() ?: 0L).coerceIn(0L, tsMax)
    return word to DeletedWordEntry(deleted, ts)
}

/**
 * 合并两台设备的删词记录：逐词取时间戳较新的一方；时间相同（多为
 * 旧格式的时间 0，或两台设备的毫秒时钟撞车）时「已恢复」优先——
 * 恢复在因果上必然晚于删除（只有删过的词才能被恢复），时间戳相同
 * 只是时钟撞车，不能据此让旧删除压过恢复（D-M10）。
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
            !r.deleted -> r
            !l.deleted -> l
            else -> r
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
            }
        }
    }

    /** 变更与落盘快照整体串行，避免交错落盘让旧快照覆盖新状态。 */
    private fun persistLocked(map: Map<String, DeletedWordEntry>) {
        val lines = HashSet<String>()
        for ((word, entry) in map) {
            lines.add(encodeDeletedWordEntry(word, entry))
        }
        prefs.edit().putStringSet(KEY_WORDS, lines).apply()
    }

    /**
     * 本机单调时间戳（D-M10）：同一毫秒内的连续删除／恢复取
     * System.currentTimeMillis() 会撞成同一时间戳、合并时走平局分支。
     * 本进程内单调递增（max(墙钟, 上次+1)），同设备操作不再同毫秒；
     * 跨设备同毫秒仍可能，由合并的“恢复赢”平局分支兜底。这不是
     * 清点 S20 否掉的 maxSeen 棘轮（不采信远端时间戳、不跨设备养
     * 时钟），只是本机操作的本地序号。调用方须持本对象锁（add／
     * remove 内）；本函数自身也加锁，可重入。
     */
    private var lastIssuedTs = 0L

    private fun nextTimestamp(): Long = synchronized(this) {
        val now = System.currentTimeMillis()
        lastIssuedTs = maxOf(now, lastIssuedTs + 1)
        lastIssuedTs
    }

    /**
     * 丢弃内存缓存（重置应用后调用，D-M11）：偏好已被清空，不丢缓存
     * 的话 isDeleted() 会继续按旧记录过滤候选、直到进程重启。
     */
    fun invalidate() {
        synchronized(this) { cache = null }
    }

    fun isDeleted(text: String): Boolean = load()[text]?.deleted == true

    /** 当前处于「已删」状态的全部词的快照。 */
    fun all(): Set<String> =
        load().filterValues { it.deleted }.keys.toHashSet()

    /** 当前全部记录（含已恢复的墓碑）的快照，供 WebDAV 同步合并。 */
    fun entriesSnapshot(): Map<String, DeletedWordEntry> = HashMap(load())

    /** 已删词列表（按词排序），供设置页展示与恢复。 */
    fun deletedWords(): List<String> = all().sorted()

    // 新记录时间戳取本机单调时间戳（见 nextTimestamp）：墙钟＋本进程
    // 内单调递增。清点 S20 否掉的是 maxSeen 棘轮（采信远端时间戳、
    // 跨设备养时钟，为「对端时钟中毒」养逻辑时钟不值），本地单调
    // 不在此列；解码侧的钳制保留，防坏数据本职。

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
                    updatedAt = nextTimestamp(),
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
                    updatedAt = nextTimestamp(),
                )
                persistLocked(map)
            }
        }
    }
}
