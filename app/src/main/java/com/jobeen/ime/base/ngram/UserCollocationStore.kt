package com.jobeen.ime.base.ngram

import java.io.File

/**
 * 用户搭配学习存储：记录打字上屏的相邻词对（prev → next）计数，
 * 供候选预测优先给出个人习惯搭配。
 *
 * 设计口径（与通用预测模型完全隔离）：
 * - 只存词对与计数、最后使用日期，不存整句原文；
 * - 独立文件存储（TSV），不并入主数据库：损坏时预测退回通用
 *   模型，打字与词库不受影响（故障域隔离）；
 * - 内存计数、攒批落盘，学习调用不阻塞输入路径；
 * - 总量封顶 [MAX_PAIRS]，落盘时按（计数、最近使用）淘汰尾部，
 *   低频旧搭配在加载时清掉，避免旧习惯无限累积。
 *
 * 线程安全：内部状态全部在锁内访问；落盘由内部单线程执行器
 * 串行执行。任何 IO 异常都被吞掉（runCatching），学习失败静默
 * 降级，绝不向上抛。
 */
class UserCollocationStore(private val file: File) {

    data class Entry(var count: Int, var lastDay: Long)

    private val lock = Any()
    private val table = HashMap<String, HashMap<String, Entry>>()
    private var loaded = false
    private var dirtyCount = 0

    private val flushExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "collocation-flush").apply { isDaemon = true }
    }

    private fun today(): Long = System.currentTimeMillis() / MILLIS_PER_DAY

    private fun ensureLoadedLocked() {
        if (loaded) return
        loaded = true
        runCatching {
            if (file.isFile) {
                parseInto(file.readText(Charsets.UTF_8), table)
                evictStaleLocked(today())
            }
        }
    }

    /**
     * 学一对相邻搭配。词段不合规（纯标点/纯数字/过长等）直接忽略。
     * 调用方只应从打字上屏路径调用（语音/剪贴板上屏不学，由调用方把关）。
     */
    fun learn(prev: String, next: String) {
        if (!isLearnableSegment(prev) || !isLearnableSegment(next)) return
        synchronized(lock) {
            ensureLoadedLocked()
            val day = today()
            val inner = table.getOrPut(prev) { HashMap() }
            val e = inner[next]
            if (e == null) {
                inner[next] = Entry(1, day)
            } else {
                e.count++
                e.lastDay = day
            }
            dirtyCount++
            if (dirtyCount >= FLUSH_EVERY) {
                dirtyCount = 0
                scheduleFlushLocked()
            }
        }
    }

    /** 按计数降序（同数按最近使用）返回 [prev] 的后续搭配。 */
    fun continuations(prev: String, limit: Int = 8): List<Pair<String, Int>> {
        if (!isLearnableSegment(prev)) return emptyList()
        synchronized(lock) {
            ensureLoadedLocked()
            val inner = table[prev] ?: return emptyList()
            return inner.entries
                .sortedWith(
                    compareByDescending<Map.Entry<String, Entry>> { it.value.count }
                        .thenByDescending { it.value.lastDay }
                )
                .take(limit)
                .map { it.key to it.value.count }
        }
    }

    /** 立即落盘（进程内异步串行执行，重复调用安全）。 */
    fun flush() {
        synchronized(lock) { scheduleFlushLocked() }
    }

    private fun scheduleFlushLocked() {
        // 快照在锁内序列化成文本，写文件在执行器线程做，不阻塞调用方
        val text = runCatching {
            pruneLocked()
            serialize(table)
        }.getOrNull() ?: return
        flushExecutor.execute {
            runCatching {
                file.parentFile?.mkdirs()
                val tmp = File(file.parentFile, file.name + ".tmp")
                tmp.writeText(text, Charsets.UTF_8)
                if (!tmp.renameTo(file)) {
                    file.writeText(text, Charsets.UTF_8)
                    tmp.delete()
                }
            }
        }
    }

    /** 总量超上限时淘汰（计数低、久未用优先淘汰）。 */
    private fun pruneLocked() {
        var total = 0
        for ((_, inner) in table) total += inner.size
        if (total <= MAX_PAIRS) return
        val all = ArrayList<Triple<String, String, Entry>>()
        for ((prev, inner) in table) {
            for ((next, e) in inner) all.add(Triple(prev, next, e))
        }
        all.sortWith(
            compareByDescending<Triple<String, String, Entry>> { it.third.count }
                .thenByDescending { it.third.lastDay }
        )
        val keep = all.take(MAX_PAIRS)
        table.clear()
        for ((prev, next, e) in keep) {
            table.getOrPut(prev) { HashMap() }[next] = e
        }
    }

    /** 加载时清掉低频（≤1 次）且久未使用（> [STALE_DAYS] 天）的搭配。 */
    private fun evictStaleLocked(today: Long) {
        val it = table.entries.iterator()
        while (it.hasNext()) {
            val inner = it.next().value
            val innerIt = inner.entries.iterator()
            while (innerIt.hasNext()) {
                val e = innerIt.next().value
                if (e.count <= 1 && today - e.lastDay > STALE_DAYS) innerIt.remove()
            }
            if (inner.isEmpty()) it.remove()
        }
    }

    companion object {
        const val MAX_PAIRS = 20_000
        const val FLUSH_EVERY = 40
        const val STALE_DAYS = 90L
        private const val MILLIS_PER_DAY = 86_400_000L

        /**
         * 词段可学口径：1–8 个字符、至少含一个汉字、非纯标点/数字/空白。
         * 上屏单位本就是候选词（多为 1–4 字），长段（如粘贴整句）不合规。
         */
        fun isLearnableSegment(segment: String): Boolean {
            val s = segment.trim()
            if (s.isEmpty()) return false
            if (s.codePointCount(0, s.length) > 8) return false
            var hasCjk = false
            for (ch in s) {
                if (ch.isWhitespace()) return false
                if (isCjk(ch)) hasCjk = true
            }
            return hasCjk
        }

        private fun isCjk(ch: Char): Boolean =
            ch in '\u3400'..'\u4DBF' || ch in '\u4E00'..'\u9FFF' || ch in '\uF900'..'\uFAFF'

        fun serialize(table: Map<String, Map<String, Entry>>): String {
            val sb = StringBuilder()
            for (prev in table.keys.sorted()) {
                val inner = table[prev] ?: continue
                for (next in inner.keys.sorted()) {
                    val e = inner[next] ?: continue
                    sb.append(prev).append('\t').append(next).append('\t')
                        .append(e.count).append('\t').append(e.lastDay).append('\n')
                }
            }
            return sb.toString()
        }

        fun parseInto(text: String, table: MutableMap<String, HashMap<String, Entry>>) {
            table.clear()
            for (line in text.split('\n')) {
                if (line.isEmpty()) continue
                val parts = line.split('\t')
                if (parts.size != 4) continue
                val count = parts[2].toIntOrNull() ?: continue
                val day = parts[3].toLongOrNull() ?: continue
                if (parts[0].isEmpty() || parts[1].isEmpty() || count <= 0) continue
                table.getOrPut(parts[0]) { HashMap() }[parts[1]] = Entry(count, day)
            }
        }
    }
}
