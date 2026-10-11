package com.jobeen.ime.base.speech

import java.io.File
import java.util.Collections
import java.util.WeakHashMap

/**
 * 语音专名纠错的词对存储：记录用户在语音上屏后亲手纠正出的
 * （错形 → 正形）词对，以及被用户改回错形而停用的正形。
 *
 * 设计口径（照 [com.jobeen.ime.base.ngram.UserCollocationStore] 的
 * 故障隔离模式）：
 * - 独立 TSV 文件存储，不并入主数据库：文件损坏时纠错整体降级为
 *   不纠，识别与上屏不受影响；
 * - 内存状态 + 内部单线程执行器串行落盘，学习调用不阻塞输入路径；
 * - 落盘走 tmp 文件 + rename 原子替换，进程中途被杀不会留下
 *   写一半的文件；
 * - 读文件整体失败时本进程内禁用写入：宁可这次不落盘，也绝不
 *   让空内存把磁盘上的全部历史覆写掉（行级坏行仍逐行跳过）；
 * - 任何 IO 异常都被吞掉（runCatching），绝不向上抛；
 * - 总量封顶 [MAX_PAIRS]：在 learn/disable 的锁内就地淘汰最旧，
 *   内存与落盘口径一致；停用集合同样封顶 [MAX_DISABLED]。
 *
 * 开关：默认开；关闭后 [correct] 直通原文、会话层不再沉淀
 * （见 VoiceCorrectionSession 的判定）。开关状态随本文件以
 * `E` 行持久化。设置页与输入法服务在同一进程内各持一个实例，
 * 实例间经 [liveInstances] 同步 enabled 与 clear，避免一处改了
 * 另一处还拿着旧状态。
 *
 * 文件格式（每行一条，Tab 分隔）：
 * - `P\t<正形>\t<错形>`：一条学到的词对；
 * - `D\t<正形>`：该正形已被停用（用户把自动纠正改回了错形）；
 * - `E\t0`：纠错总开关关闭（开启时不写此行，缺省即开）。
 */
class VoiceCorrectionStore(private val file: File) {

    private val lock = Any()

    /** 正形 → 错形集合（保持学入顺序）。 */
    private val pairs = LinkedHashMap<String, LinkedHashSet<String>>()
    private val disabled = LinkedHashSet<String>()
    private var loaded = false

    /** 读文件整体失败：一旦置真，本进程内不再落盘（见类注释）。 */
    private var loadFailed = false
    private var dirty = false
    private var enabledField = true

    /**
     * 读盘失败期间被 [setEnabled] 改过开关：置真后，加载成功的
     * parseInto 不得用文件 E 行覆盖内存里已设的值，且加载成功后
     * 要补一次落盘把这个值写回文件（见 ensureLoadedLocked）。
     */
    private var enabledDirtyWhileUnloaded = false

    private val flushExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "voice-correction-flush").apply { isDaemon = true }
    }

    init {
        synchronized(liveInstances) { liveInstances.add(this) }
    }

    /** 纠错总开关（默认开）。读取时顺带完成首次加载以拿到持久化值。 */
    val enabled: Boolean
        get() = synchronized(lock) {
            ensureLoadedLocked()
            enabledField
        }

    /** 设置总开关：本实例落盘持久化，同进程其他实例即时同步。 */
    fun setEnabled(value: Boolean) {
        synchronized(lock) {
            ensureLoadedLocked()
            if (enabledField != value) {
                enabledField = value
                // 读盘失败期间改的开关：加载成功时 parseInto 的 E 行
                // 不得覆盖它（见 enabledDirtyWhileUnloaded），且要
                // 在加载成功后补落盘，否则这次切换会被随后读回的
                // 文件旧值回滚、或永远不落盘而丢失
                if (loadFailed) enabledDirtyWhileUnloaded = true
                markDirtyLocked()
            }
        }
        for (peer in peers()) {
            synchronized(peer.lock) { peer.enabledField = value }
        }
    }

    /** 清空全部已学词对与停用记录（用户在设置页主动清除）。 */
    fun clear() {
        // 先自增清除代次再清：清除前已拍出的在途落盘快照在写文件
        // 前会因代次过期被跳过，不会把刚清掉的词对又写回磁盘
        clearEpoch.incrementAndGet()
        synchronized(lock) {
            ensureLoadedLocked()
            pairs.clear()
            disabled.clear()
            probation.clear()
            // 主动清除是用户明确要抹掉历史，不受读失败禁写约束
            loadFailed = false
            markDirtyLocked()
        }
        for (peer in peers()) {
            synchronized(peer.lock) {
                peer.pairs.clear()
                peer.disabled.clear()
                // 观察期计数也要清：漏清会让被清的两字词对只需再
                // 被观察一次就重新生效，清除不彻底
                peer.probation.clear()
                peer.loadFailed = false
                peer.dirty = false
            }
        }
    }

    private fun peers(): List<VoiceCorrectionStore> = synchronized(liveInstances) {
        liveInstances.filter { it !== this }
    }

    /** 对定稿文本应用当前全部生效规则做纠错；开关关闭时直通。 */
    fun correct(text: String): CorrectResult {
        val rules = synchronized(lock) {
            ensureLoadedLocked()
            if (!enabledField) return CorrectResult(text, emptyList())
            buildRulesLocked()
        }
        return VoiceCorrector.correct(text, rules)
    }

    fun rules(): List<CorrectionRule> = synchronized(lock) {
        ensureLoadedLocked()
        buildRulesLocked()
    }

    fun isDisabled(rightForm: String): Boolean = synchronized(lock) {
        ensureLoadedLocked()
        rightForm in disabled
    }

    /** 两字词对的观察期计数（仅内存，不落盘）：见 [learn] 的两字门槛。 */
    private val probation = HashMap<Pair<String, String>, Int>()

    /**
     * 学一条词对。词形不合规（非 2–6 字全 CJK、错形等于正形）直接忽略。
     * 正形此前被停用又被用户重新纠正到它时，视为用户改主意，重新启用。
     *
     * 两字词对加一道观察期：位置配对对两字词只需同位相同 1 字即可
     * 成立，单次观察误学面过大（句尾续写、边界巧合都能凑出词对），
     * 故两字词对须被独立观察到 [PROBATION_REQUIRED] 次才正式生效并
     * 落盘；观察计数只在内存，进程重启重新计数。三字及以上维持
     * 单次生效（同位相同下限更高，误配概率低得多）。
     */
    fun learn(wrongForm: String, rightForm: String) {
        if (wrongForm == rightForm) return
        if (!VoiceCorrector.isValidForm(wrongForm) || !VoiceCorrector.isValidForm(rightForm)) return
        synchronized(lock) {
            ensureLoadedLocked()
            if (wrongForm.length == 2 && rightForm.length == 2 &&
                wrongForm !in pairs[rightForm].orEmpty()
            ) {
                val key = wrongForm to rightForm
                val seen = (probation[key] ?: 0) + 1
                if (seen < PROBATION_REQUIRED) {
                    probation[key] = seen
                    return
                }
                probation.remove(key)
            }
            ensureLoadedLocked()
            var changed = false
            if (disabled.remove(rightForm)) changed = true
            // 错形归属唯一：同一错形此前若归在别的正形名下，先摘除，
            // 避免两条规则抢同一片段、纠正结果 flip-flop
            val it = pairs.entries.iterator()
            while (it.hasNext()) {
                val entry = it.next()
                if (entry.key == rightForm) continue
                if (entry.value.remove(wrongForm)) {
                    changed = true
                    if (entry.value.isEmpty()) it.remove()
                }
            }
            val set = pairs.getOrPut(rightForm) { LinkedHashSet() }
            if (set.add(wrongForm)) changed = true
            if (enforceCapsLocked()) changed = true
            if (changed) markDirtyLocked()
        }
    }

    /** 停用某正形的全部纠错（用户把自动纠正改回了错形）。 */
    fun disable(rightForm: String) {
        synchronized(lock) {
            ensureLoadedLocked()
            var changed = false
            if (disabled.add(rightForm)) changed = true
            if (enforceCapsLocked()) changed = true
            if (changed) markDirtyLocked()
        }
    }

    /**
     * 上限就地执行：词对总量超 [MAX_PAIRS] 时按学入顺序淘汰最旧，
     * 停用集合超 [MAX_DISABLED] 时同样淘汰最旧。返回是否发生了淘汰。
     */
    private fun enforceCapsLocked(): Boolean {
        var changed = false
        var total = 0
        for ((_, wrongs) in pairs) total += wrongs.size
        if (total > MAX_PAIRS) {
            val it = pairs.entries.iterator()
            while (total > MAX_PAIRS && it.hasNext()) {
                val entry = it.next()
                val innerIt = entry.value.iterator()
                while (total > MAX_PAIRS && innerIt.hasNext()) {
                    innerIt.next()
                    innerIt.remove()
                    total--
                    changed = true
                }
                if (entry.value.isEmpty()) it.remove()
            }
        }
        while (disabled.size > MAX_DISABLED) {
            val dit = disabled.iterator()
            dit.next()
            dit.remove()
            changed = true
        }
        return changed
    }

    private fun buildRulesLocked(): List<CorrectionRule> {
        val out = ArrayList<CorrectionRule>()
        for ((right, wrongs) in pairs) {
            if (right in disabled) continue
            wrongs.groupBy { it.length }.forEach { (_, group) ->
                VoiceCorrector.buildRule(right, group)?.let { out += it }
            }
        }
        return out
    }

    private fun ensureLoadedLocked() {
        if (loaded) return
        runCatching {
            if (file.isFile) {
                parseInto(file.readText(Charsets.UTF_8))
            } else if (file.exists()) {
                // 路径存在却不是普通文件（损坏形态）：按读失败处理
                throw java.io.IOException("voice corrections path is not a regular file")
            }
        }.onSuccess {
            loaded = true
            loadFailed = false
            enforceCapsLocked()
            if (enabledDirtyWhileUnloaded) {
                // 读失败期间改的开关已在 parseInto 里被保护（E 行
                // 未覆盖）：补一次落盘把内存值写回文件，清标记
                enabledDirtyWhileUnloaded = false
                markDirtyLocked()
            }
        }.onFailure {
            // 整体读失败：不标记 loaded（下次调用可重试），且在读
            // 成功之前禁用一切写入，防止空内存覆写磁盘历史
            loadFailed = true
        }
    }

    private fun parseInto(content: String) {
        for (line in content.lineSequence()) {
            if (line.isBlank()) continue
            val parts = line.split('\t')
            when (parts.firstOrNull()) {
                "P" -> {
                    if (parts.size != 3) continue
                    val right = parts[1]
                    val wrong = parts[2]
                    if (wrong == right) continue
                    if (!VoiceCorrector.isValidForm(right) || !VoiceCorrector.isValidForm(wrong)) continue
                    pairs.getOrPut(right) { LinkedHashSet() }.add(wrong)
                }
                "D" -> {
                    if (parts.size != 2) continue
                    val right = parts[1]
                    if (VoiceCorrector.isValidForm(right)) disabled.add(right)
                }
                "E" -> {
                    if (parts.size != 2) continue
                    // 读失败期间用户已改过开关时以内存值为准：文件
                    // 里这行是改动前写下的旧值，覆盖回去会把这次
                    // 切换回滚掉
                    if (!enabledDirtyWhileUnloaded) enabledField = parts[1] == "1"
                }
            }
        }
    }

    private fun markDirtyLocked() {
        dirty = true
        if (loadFailed) return // 读未成功前不落盘，仅留内存状态
        flushExecutor.execute {
            val snapshot: String
            val snapshotEpoch: Int
            synchronized(lock) {
                if (!dirty || loadFailed) return@execute
                dirty = false
                snapshot = serializeLocked()
                // 快照与清除代次同拍：写文件前据此判断这份快照是否
                // 拍于最近一次 clear 之前（见下方写段）
                snapshotEpoch = clearEpoch.get()
            }
            runCatching {
                // 实际写文件在跨实例写锁内串行（同文件的多个实例
                // 各有执行器，无锁会互相踩 tmp 文件）；写入前校验
                // 快照代次：clear 已自增代次时，这份清除前拍的快照
                // 一律跳过，防已清词对被在途落盘写回「复活」。
                // clear 自己的 flush 带的是新代次，正常落盘
                synchronized(fileWriteLock) {
                    if (snapshotEpoch != clearEpoch.get()) return@runCatching
                    file.parentFile?.mkdirs()
                    val tmp = File(file.parentFile, file.name + ".tmp")
                    tmp.writeText(snapshot, Charsets.UTF_8)
                    if (!tmp.renameTo(file)) {
                        runCatching { file.writeText(snapshot, Charsets.UTF_8) }
                        tmp.delete()
                    }
                }
            }
        }
    }

    private fun serializeLocked(): String {
        val out = StringBuilder()
        if (!enabledField) out.append("E\t0\n")
        // 上限已在 learn/disable/load 时就地执行，这里全量写出即可
        for ((right, wrongs) in pairs) {
            for (wrong in wrongs) {
                out.append("P\t").append(right).append('\t').append(wrong).append('\n')
            }
        }
        for (right in disabled) {
            out.append("D\t").append(right).append('\n')
        }
        return out.toString()
    }

    companion object {
        const val MAX_PAIRS = 300

        /** 两字词对正式生效所需的独立观察次数（见 [learn]）。 */
        const val PROBATION_REQUIRED = 2

        /**
         * 按文件取同进程共享实例：客户端与设置页必须用同一实例。
         * 两边各建实例时词表互不感知，后落盘的一方会把另一方学到的
         * 词对整表覆写掉（liveInstances 只同步开关与清除，救不了词表
         * 分叉）。测试与特殊场景仍可直接构造独立实例。
         */
        private val sharedByPath = HashMap<String, VoiceCorrectionStore>()

        fun shared(file: File): VoiceCorrectionStore = synchronized(sharedByPath) {
            sharedByPath.getOrPut(file.absolutePath) { VoiceCorrectionStore(file) }
        }

        /** 停用集合上限，与词对上限同量级。 */
        const val MAX_DISABLED = 300

        /** 纠错词表文件名（filesDir 下），与 SherpaSpeechClient 的取用一致。 */
        const val FILE_NAME = "voice_corrections.tsv"

        /** 同进程存活实例登记表：供 enabled/clear 跨实例同步（弱引用，不防泄漏）。 */
        private val liveInstances: MutableSet<VoiceCorrectionStore> =
            Collections.newSetFromMap(WeakHashMap())

        /**
         * 落盘写锁：同一文件的多个实例各有 flush 执行器，实际写文件
         * （tmp 写入＋rename）在此串行，避免互相踩同一 tmp 文件。
         */
        private val fileWriteLock = Any()

        /**
         * 清除代次：[clear] 先自增再清内存；flush 写文件前校验快照
         * 拍摄时的代次与当前代次一致，清除前拍的过期快照直接跳过，
         * 防止用户刚清掉的词对被在途落盘写回磁盘「复活」。clear 自
         * 身触发的 flush 在自增之后拍快照，代次一致、正常落盘。
         */
        private val clearEpoch = java.util.concurrent.atomic.AtomicInteger(0)
    }
}
