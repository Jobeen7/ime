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
 * - 总量封顶 [MAX_PAIRS]：在 learn/disable 的锁内按生命周期淘汰
 *   （见 [enforceCapsLocked] 的排序口径），内存与落盘口径一致；
 *   停用集合同样封顶 [MAX_DISABLED]。
 *
 * 开关：默认开；关闭后 [correct] 直通原文、会话层不再沉淀
 * （见 VoiceCorrectionSession 的判定）。开关状态随本文件以
 * `E` 行持久化。设置页与输入法服务在同一进程内各持一个实例，
 * 实例间经 [liveInstances] 同步 enabled 与 clear，避免一处改了
 * 另一处还拿着旧状态。
 *
 * 文件格式（每行一条，Tab 分隔）：
 * - `P\t<正形>\t<错形>\t<命中次数>\t<最近命中时间>\t<学入时间>`：
 *   一条学到的词对及其生命周期元信息。旧版三列 P 行（无元信息）
 *   按命中 0、时间 0 读入，不丢词对；
 * - `D\t<正形>`：该正形已被停用（用户把自动纠正改回了错形）；
 * - `O\t<错形>\t<正形>\t<已观察次数>`：两字词对的观察期计数
 *   （落盘持久化，进程重启不清零，见 [learn]）；
 * - `E\t0`：纠错总开关关闭（开启时不写此行，缺省即开）。
 */
class VoiceCorrectionStore(private val file: File) {

    private val lock = Any()

    /**
     * 一条词对的生命周期元信息（淘汰排序与命中统计用，随 P 行落盘）。
     *
     * [lastHitAt] 为 0 表示从未命中，此时以 [learnedAt] 充当最近活动
     * 时间参与淘汰排序（见 enforceCapsLocked）。
     */
    private data class PairMeta(
        var hitCount: Int = 0,
        var lastHitAt: Long = 0L,
        val learnedAt: Long = 0L,
    )

    /** 正形 → 错形 → 元信息（两层都保持学入顺序）。 */
    private val pairs = LinkedHashMap<String, LinkedHashMap<String, PairMeta>>()
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
        val result = VoiceCorrector.correct(text, rules)
        if (result.applied.isNotEmpty()) recordHits(result.applied)
        return result
    }

    /**
     * 命中记账（正向确认）：只有 [correct] 实际执行了替换才计，
     * 仅路过未替换不计。归因口径：
     * - 被替换的片段恰好是一条已学错形：该词对命中次数 +1、
     *   最近命中时间刷新；
     * - 片段是字位字集泛化组合出的新错形（没被逐字教过）：这次
     *   命中由同正形、同长度的整组词对共同构成，只刷新整组的
     *   最近命中时间（保鲜整组），不虚增任一条的命中次数。
     *
     * 调用频率为每次语音定稿/结算至多一轮（correct 的调用点都是
     * 定稿路径，非流式预览），整文件至多 [MAX_PAIRS] 行，全量
     * 序列化成本可忽略，故命中即经既有路径落盘，不做节流；淘
     * 汰判定读的是锁内实时计数，与落盘时机无关，一致性天然成立。
     */
    private fun recordHits(applied: List<AppliedCorrection>) {
        val now = System.currentTimeMillis()
        synchronized(lock) {
            var changed = false
            for (hit in applied) {
                val wrongs = pairs[hit.rightForm] ?: continue
                val meta = wrongs[hit.wrongForm]
                if (meta != null) {
                    meta.hitCount++
                    meta.lastHitAt = now
                    changed = true
                } else {
                    for ((wrong, m) in wrongs) {
                        if (wrong.length == hit.wrongForm.length && m.lastHitAt != now) {
                            m.lastHitAt = now
                            changed = true
                        }
                    }
                }
            }
            if (changed) markDirtyLocked()
        }
    }

    fun rules(): List<CorrectionRule> = synchronized(lock) {
        ensureLoadedLocked()
        buildRulesLocked()
    }

    fun isDisabled(rightForm: String): Boolean = synchronized(lock) {
        ensureLoadedLocked()
        rightForm in disabled
    }

    /**
     * 两字词对的观察期计数（错形 to 正形 → 已观察次数），随文件以
     * O 行落盘持久化、进程重启不清零：见 [learn] 的两字门槛。
     * 保持插入顺序，上限 [MAX_PROBATION] 超出时淘汰最旧的未达成项，
     * 防止只观察过一次便再不出现的词对在文件里无限累积。
     */
    private val probation = LinkedHashMap<Pair<String, String>, Int>()

    /**
     * 学一条词对。词形不合规（非 2–6 字全 CJK、错形等于正形）直接忽略。
     * 正形此前被停用又被用户重新纠正到它时，视为用户改主意，重新启用。
     *
     * 两字词对加一道观察期：位置配对对两字词只需同位相同 1 字即可
     * 成立，单次观察误学面过大（句尾续写、边界巧合都能凑出词对），
     * 故两字词对须被独立观察到 [PROBATION_REQUIRED] 次才正式生效并
     * 落盘；观察计数随文件 O 行落盘，进程重启不清零。三字及以上
     * 维持单次生效（同位相同下限更高，误配概率低得多）。
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
                    trimProbationLocked()
                    // 观察计数也要落盘（O 行），否则进程重启后这次
                    // 观察白费；读失败期间 markDirtyLocked 内部会
                    // 自行跳过落盘，内存计数仍在
                    markDirtyLocked()
                    return
                }
                probation.remove(key)
            }
            ensureLoadedLocked()
            var changed = false
            if (disabled.remove(rightForm)) changed = true
            // 错形归属唯一：同一错形此前若归在别的正形名下，先摘除，
            // 避免两条规则抢同一片段、纠正结果 flip-flop；摘除即
            // 视为在新正形下重新学入，元信息（命中/时间）随之重置
            val it = pairs.entries.iterator()
            while (it.hasNext()) {
                val entry = it.next()
                if (entry.key == rightForm) continue
                if (entry.value.remove(wrongForm) != null) {
                    changed = true
                    if (entry.value.isEmpty()) it.remove()
                }
            }
            val wrongs = pairs.getOrPut(rightForm) { LinkedHashMap() }
            // 已学过的词对重复学入不重置元信息：命中史是用户用出
            // 来的，不能因重复纠正动作被清零
            if (wrongForm !in wrongs) {
                wrongs[wrongForm] = PairMeta(learnedAt = System.currentTimeMillis())
                changed = true
            }
            if (enforceCapsLocked()) changed = true
            if (changed) markDirtyLocked()
        }
    }

    /** 观察期表上限就地执行：超出时淘汰插入最旧的未达成项。 */
    private fun trimProbationLocked() {
        val it = probation.entries.iterator()
        while (probation.size > MAX_PROBATION && it.hasNext()) {
            it.next()
            it.remove()
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
     * 上限就地执行。词对淘汰排序口径（生命周期淘汰，替代旧「按
     * 学入顺序挤最旧」：常用的老规则被挤掉正是要改掉的行为）：
     * - 活动时间 = 最近命中时间；从未命中的以学入时间充当；
     * - 按活动时间升序（最久没活动的先淘汰）、命中次数升序
     *   （同等活动时间下命中少的先淘汰）排序，同分保持学入顺序；
     * - 已停用正形名下的词对不参与这轮排序（它们的存废由停用
     *   语义决定）；仅当未停用候选删完仍超限时，才按学入顺序
     *   删停用词对兜底，保证上限是硬约束。
     * 停用集合超 [MAX_DISABLED] 时仍按学入顺序淘汰最旧。
     * 返回是否发生了淘汰。
     */
    private fun enforceCapsLocked(): Boolean {
        var changed = false
        var total = 0
        for ((_, wrongs) in pairs) total += wrongs.size
        if (total > MAX_PAIRS) {
            var toRemove = total - MAX_PAIRS
            val candidates = ArrayList<Triple<String, String, PairMeta>>()
            for ((right, wrongs) in pairs) {
                if (right in disabled) continue
                for ((wrong, meta) in wrongs) candidates += Triple(right, wrong, meta)
            }
            // sortedWith 稳定排序：同分项保持 candidates 的学入
            // （迭代）顺序，淘汰结果确定性可复现
            val sorted = candidates.sortedWith(
                compareBy(
                    { activityOf(it.third) },
                    { it.third.hitCount },
                )
            )
            for ((right, wrong, _) in sorted) {
                if (toRemove <= 0) break
                val wrongs = pairs[right] ?: continue
                if (wrongs.remove(wrong) != null) {
                    toRemove--
                    changed = true
                    if (wrongs.isEmpty()) pairs.remove(right)
                }
            }
            if (toRemove > 0) {
                val it = pairs.entries.iterator()
                while (toRemove > 0 && it.hasNext()) {
                    val entry = it.next()
                    if (entry.key !in disabled) continue
                    val innerIt = entry.value.entries.iterator()
                    while (toRemove > 0 && innerIt.hasNext()) {
                        innerIt.next()
                        innerIt.remove()
                        toRemove--
                        changed = true
                    }
                    if (entry.value.isEmpty()) it.remove()
                }
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

    /** 淘汰排序用的活动时间：命中过用最近命中时间，未命中用学入时间。 */
    private fun activityOf(meta: PairMeta): Long =
        if (meta.lastHitAt > 0) meta.lastHitAt else meta.learnedAt

    private fun buildRulesLocked(): List<CorrectionRule> {
        val out = ArrayList<CorrectionRule>()
        for ((right, wrongs) in pairs) {
            if (right in disabled) continue
            wrongs.keys.groupBy { it.length }.forEach { (_, group) ->
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
                    // 三列是旧版格式（无元信息），按全 0 缺省读入；
                    // 六列是现行格式。元信息字段解析失败只把该字段
                    // 按 0 处理，不丢词对（数值损坏不该连坐规则）
                    if (parts.size != 3 && parts.size != 6) continue
                    val right = parts[1]
                    val wrong = parts[2]
                    if (wrong == right) continue
                    if (!VoiceCorrector.isValidForm(right) || !VoiceCorrector.isValidForm(wrong)) continue
                    val meta = if (parts.size == 6) {
                        PairMeta(
                            hitCount = (parts[3].toIntOrNull() ?: 0).coerceAtLeast(0),
                            lastHitAt = (parts[4].toLongOrNull() ?: 0L).coerceAtLeast(0L),
                            learnedAt = (parts[5].toLongOrNull() ?: 0L).coerceAtLeast(0L),
                        )
                    } else {
                        PairMeta()
                    }
                    // 文件里同词对重复出现（外部拼接等）保留先到的一份
                    pairs.getOrPut(right) { LinkedHashMap() }.putIfAbsent(wrong, meta)
                }
                "D" -> {
                    if (parts.size != 2) continue
                    val right = parts[1]
                    if (VoiceCorrector.isValidForm(right)) disabled.add(right)
                }
                "O" -> {
                    // 观察期计数行：只对两字词对有意义；次数须在
                    // [1, PROBATION_REQUIRED) 内（达到门槛的早已转
                    // 正式词对，残留的达标行视为坏行跳过）
                    if (parts.size != 4) continue
                    val wrong = parts[1]
                    val right = parts[2]
                    if (wrong == right) continue
                    if (wrong.length != 2 || right.length != 2) continue
                    if (!VoiceCorrector.isValidForm(right) || !VoiceCorrector.isValidForm(wrong)) continue
                    val count = parts[3].toIntOrNull() ?: continue
                    if (count !in 1 until PROBATION_REQUIRED) continue
                    // 词对已正式生效时观察计数无意义（学入时已摘除，
                    // 文件里只可能是旧残留），不恢复
                    if (wrong in pairs[right].orEmpty()) continue
                    val key = wrong to right
                    // 重复行取较大值：两份计数都是真实观察，不相加
                    // （无法区分同一观察被记录两次与两次独立观察）
                    if (count > (probation[key] ?: 0)) probation[key] = count
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
            for ((wrong, meta) in wrongs) {
                out.append("P\t").append(right).append('\t').append(wrong)
                    .append('\t').append(meta.hitCount)
                    .append('\t').append(meta.lastHitAt)
                    .append('\t').append(meta.learnedAt)
                    .append('\n')
            }
        }
        for (right in disabled) {
            out.append("D\t").append(right).append('\n')
        }
        for ((key, count) in probation) {
            out.append("O\t").append(key.first).append('\t').append(key.second)
                .append('\t').append(count).append('\n')
        }
        return out.toString()
    }

    companion object {
        const val MAX_PAIRS = 300

        /** 两字词对正式生效所需的独立观察次数（见 [learn]）。 */
        const val PROBATION_REQUIRED = 2

        /** 观察期表上限：防只观察一次便再不出现的词对无限累积。 */
        const val MAX_PROBATION = 600

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
