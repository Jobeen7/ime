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
 * - 内存计数、攒批落盘（tmp 文件 + rename 原子替换），学习调用不阻塞输入路径；
 * - 读文件整体失败时本进程内禁用写入，绝不让空内存覆写磁盘上的全部历史；
 * - 总量封顶 [MAX_PAIRS]，落盘时按（计数、最近使用）淘汰尾部，
 *   低频旧搭配在加载时清掉，避免旧习惯无限累积。
 *
 * 线程安全：内部状态全部在锁内访问；落盘由内部单线程执行器
 * 串行执行。写盘失败不静默丢数据：脏计数恢复并挂延时重试
 * （B-M1），失败的 tmp 残留清掉；读失败仍静默降级，绝不向上抛。
 *
 * 写盘节奏（H2-1，2026-11-08）：按量攒批＋按时间节流，二者取先到者——
 * - 脏学习满 [FLUSH_EVERY] 条且距上次写盘已满 [FLUSH_DELAY_MS] 时立即写盘；
 * - 否则只挂一个延时写盘任务，写盘时刻为上次写盘后满 [FLUSH_DELAY_MS]
 *   （从未写盘时为首次脏学习后满一个间隔），保证突发学习下写盘
 *   次数有上界（每 30 秒至多一次），稀疏打字的数据也不在内存里
 *   无限期滞留；
 * - 键盘收起（PredictionManager.flushCollocations，经
 *   RimeEngine.onFinishInputView）与销毁时立即写盘（flush()，
 *   无脏数据时零开销）。
 * 不丢数据的底线：进程被杀时最多丢一个节流间隔内的学习（见下文
 * 已知差距）；正常收起／销毁路径不丢。
 */
class UserCollocationStore(
    private val file: File,
    /** 写盘最小间隔（单测可注短；生产 30 秒）。 */
    private val flushDelayMs: Long = FLUSH_DELAY_MS,
) {

    data class Entry(var count: Int, var lastDay: Long)

    private val lock = Any()
    private val table = HashMap<String, HashMap<String, Entry>>()
    private var loaded = false

    /** 读文件整体失败：一旦置真，本进程内不再落盘，防止空内存覆写历史。 */
    private var loadFailed = false
    private var dirtyCount = 0

    /** 上次安排写盘的时刻（0 = 从未安排）；写盘间隔的节流基准。成功后
     * 刷新为完成时刻；失败时保留安排时刻——脏计数已恢复、延时重试接手，
     * 不因“刚安排过”丢数据。 */
    private var lastFlushMs = 0L

    /**
     * 成功写盘的代次：每次成功写盘后递增。写盘失败时只当快照之后
     * 没有更新的成功写盘，才把脏计数恢复并重试——其后已有成功写盘
     * 覆盖本批快照内容时，不再重复恢复（避免无害的重写盘）。
     */
    private var flushGeneration = 0L

    /** 已挂起的延时写盘任务（仅当 dirtyCount > 0 时存在）；锁内访问。 */
    private var pendingFlush: java.util.concurrent.ScheduledFuture<*>? = null

    /** 已完成的实际写盘次数（单测观测用；锁外 @Volatile 可见）。 */
    @Volatile
    var flushCount = 0
        private set

    private val flushScheduler =
        java.util.concurrent.Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "collocation-flush").apply { isDaemon = true }
        }

    private fun today(): Long = System.currentTimeMillis() / MILLIS_PER_DAY

    private fun ensureLoadedLocked() {
        if (loaded) return
        runCatching {
            if (file.isFile) {
                parseInto(file.readText(Charsets.UTF_8), table)
                evictStaleLocked(today())
            } else if (file.exists()) {
                // 路径存在却不是普通文件（损坏形态）：按读失败处理
                throw java.io.IOException("user collocations path is not a regular file")
            }
        }.onSuccess {
            loaded = true
            loadFailed = false
        }.onFailure {
            // 整体读失败：不标记 loaded（下次调用可重试），且在读
            // 成功之前禁用一切写入，防止空内存覆写磁盘历史；
            // 行级坏行仍由 parseInto 逐行跳过，不走这里
            loadFailed = true
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
            val now = System.currentTimeMillis()
            if (dirtyCount >= FLUSH_EVERY && isFlushDueLocked(now)) {
                // 量 cap 到了且节流间隔已满：立即写盘
                scheduleFlushLocked(now)
            } else {
                // 否则只保证挂一个延时写盘，不在打字路径上多写
                ensureDelayedFlushLocked(now)
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

    /**
     * 立即落盘（进程内异步串行执行，重复调用安全）。
     * 无脏数据时零开销：键盘收起／销毁等高频调用点可放心直调。
     */
    fun flush() {
        synchronized(lock) {
            if (dirtyCount == 0) return
            scheduleFlushLocked(System.currentTimeMillis())
        }
    }

    /** 距上次写盘已满一个节流间隔（或从未写盘）时才允许立即写盘。 */
    private fun isFlushDueLocked(now: Long): Boolean =
        lastFlushMs == 0L || now - lastFlushMs >= flushDelayMs

    /**
     * 保证挂一个延时写盘任务：写盘时刻为上次写盘后满一个间隔
     * （从未写盘时为首次脏学习后满一个间隔）。已挂起时不重复挂。
     */
    private fun ensureDelayedFlushLocked(now: Long) {
        if (loadFailed || dirtyCount == 0 || pendingFlush != null) return
        val delay = if (lastFlushMs == 0L) {
            flushDelayMs
        } else {
            maxOf(0L, lastFlushMs + flushDelayMs - now)
        }
        pendingFlush = flushScheduler.schedule(
            { onDelayedFlush() },
            delay,
            java.util.concurrent.TimeUnit.MILLISECONDS,
        )
    }

    /** 延时写盘任务到时：锁内复核脏数据，仍有才写盘。 */
    private fun onDelayedFlush() {
        synchronized(lock) {
            pendingFlush = null
            if (dirtyCount == 0 || loadFailed) return
            scheduleFlushLocked(System.currentTimeMillis())
        }
    }

    /**
     * 安排一次实际写盘：取消挂起的延时任务、锁内序列化成文本，写文件
     * 在执行器线程做，不阻塞调用方。调用方须持 lock。
     *
     * 失败不丢数据（B-M1）：写盘失败（序列化失败／磁盘满／权限／
     * tmp rename 与回落写盘双败）时脏计数恢复、挂延时重试；显式
     * flush()（键盘收起／销毁）同样靠恢复后的脏计数再次触发。
     * 脏计数只在快照取走时清零；[lastFlushMs] 在安排时即更新（节流
     * 基准）——写盘是异步执行的，若只在成功后更新，安排与完成之间
     * 的窗口内 isFlushDueLocked 恒为真（lastFlushMs 仍为 0），突发
     * 学习每满 40 条就再安排一次立即写盘，节流失效。
     * 成功后 [lastFlushMs] 再刷新为完成时刻。失败的那批学习不再因
     * “已清零”而永久丢失。
     */
    private fun scheduleFlushLocked(now: Long) {
        if (loadFailed) return // 读未成功前不落盘，仅留内存状态
        pendingFlush?.cancel(false)
        pendingFlush = null
        // 快照在锁内序列化成文本；序列化失败即保留脏数据、挂延时重试
        val text = runCatching {
            pruneLocked()
            serialize(table)
        }.getOrNull()
        if (text == null) {
            ensureDelayedFlushLocked(now)
            return
        }
        val pendingDirty = dirtyCount
        val generationAtSchedule = flushGeneration
        dirtyCount = 0
        // 节流基准在安排时即更新（见本函数注释）：异步写盘完成前
        // lastFlushMs 若仍为旧值，突发学习会反复撞上“已满间隔”。
        lastFlushMs = now
        flushScheduler.execute {
            val ok = runCatching {
                file.parentFile?.mkdirs()
                val tmp = File(file.parentFile, file.name + ".tmp")
                tmp.writeText(text, Charsets.UTF_8)
                if (!tmp.renameTo(file)) {
                    file.writeText(text, Charsets.UTF_8)
                    tmp.delete()
                }
            }.onFailure {
                // 失败的 tmp 残留清掉，避免下次写盘误认
                runCatching { File(file.parentFile, file.name + ".tmp").delete() }
            }.isSuccess
            synchronized(lock) {
                if (ok) {
                    flushCount++
                    lastFlushMs = System.currentTimeMillis()
                    flushGeneration++
                } else if (flushGeneration == generationAtSchedule) {
                    // 本批快照之后无成功写盘覆盖：脏计数恢复、挂延时重试
                    dirtyCount += pendingDirty
                    ensureDelayedFlushLocked(System.currentTimeMillis())
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
        /** 写盘最小间隔：两次实际写盘之间至少隔这么久（突发学习节流用）。 */
        const val FLUSH_DELAY_MS = 30_000L
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
