package com.jobeen.ime.base.speech

import java.io.File

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
 * - 总量封顶 [MAX_PAIRS]：在 learn/disable 的锁内按最近活动
 *   时间淘汰（见 [enforceCapsLocked]），内存与落盘口径一致；
 *   停用集合同样封顶 [MAX_DISABLED]。
 *
 * 开关：默认开；关闭后 [correct] 直通原文、会话层不再沉淀
 * （见 VoiceCorrectionSession 的判定）。开关状态随本文件以
 * `E` 行持久化。
 *
 * 实例口径：正式取用一律走 [shared] 单实例（设置页与输入法
 * 服务同进程共用同一份），直接构造仅供测试与特殊场景。
 *
 * 文件格式（每行一条，Tab 分隔）：
 * - `P\t<正形>\t<错形>\t<最近活动时间>`：一条学到的词对。旧版
 *   三列 P 行（无元信息）活动时间记为加载时刻；旧版六列 P 行
 *   （命中次数/最近命中/学入时间）按「命中过取最近命中、否则
 *   取学入」折算活动时间读入，均不丢词对；
 * - `D\t<正形>`：该正形已被停用（用户把自动纠正改回了错形）；
 * - `E\t0`：纠错总开关关闭（开启时不写此行，缺省即开）。
 *
 * 两字词对的观察期计数只在内存里（见 [learn]），不落盘，
 * 进程重启清零。
 */
class VoiceCorrectionStore(private val file: File) {

    private val lock = Any()

    /**
     * 一条词对的元信息：只留最近活动时间（学入时置为学入时刻、
     * 命中落屏后刷新，见 [recordHits]），随 P 行落盘，仅供上限
     * 淘汰排序（见 enforceCapsLocked）。
     */
    private data class PairMeta(
        var lastActivityAt: Long = 0L,
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

    /** 纠错总开关（默认开）。读取时顺带完成首次加载以拿到持久化值。 */
    val enabled: Boolean
        get() = synchronized(lock) {
            ensureLoadedLocked()
            enabledField
        }

    /** 设置总开关：本实例落盘持久化。 */
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
    }

    /** 清空全部已学词对与停用记录（用户在设置页主动清除）。 */
    fun clear() {
        synchronized(lock) {
            ensureLoadedLocked()
            pairs.clear()
            disabled.clear()
            probation.clear()
            // 主动清除是用户明确要抹掉历史，不受读失败禁写约束
            loadFailed = false
            markDirtyLocked()
        }
    }

    /**
     * 对定稿文本应用当前全部生效规则做纠错；开关关闭时直通。
     * 本函数只算不记（L3）：命中记账改由调用方在纠错结果真正
     * 落屏后显式调 [recordHits]——被纪元/密码封锁、连接失效而
     * 未落屏的结果若在此处就计数，会虚增命中并把「某时刻用过
     * 某条规则」的元数据写进密码/无痕框的定稿里。
     */
    fun correct(text: String): CorrectResult {
        val rules = synchronized(lock) {
            ensureLoadedLocked()
            if (!enabledField) return CorrectResult(text, emptyList())
            buildRulesLocked()
        }
        return VoiceCorrector.correct(text, rules)
    }

    /**
     * 命中记账：由调用方在 [correct] 的结果真正落屏、且当前框
     * 可学习（非密码/非无痕）时调用（落屏点在 SherpaSpeechClient
     * 的 finishSession 与 settlePreviousSession 两结算尾）。只做
     * 一件事：被替换的片段恰好是一条已学错形时，刷新该词对的
     * 最近活动时间（供上限淘汰排序——常用词对不被先挤掉）。不
     * 计数、不做字集泛化归因：泛化组合出的新错形不对应任何已
     * 存词对，不刷新任何元信息。
     */
    fun recordHits(applied: List<AppliedCorrection>) {
        val now = System.currentTimeMillis()
        synchronized(lock) {
            var changed = false
            for (hit in applied) {
                val meta = pairs[hit.rightForm]?.get(hit.wrongForm) ?: continue
                if (meta.lastActivityAt != now) {
                    meta.lastActivityAt = now
                    changed = true
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
     * 两字词对的观察期计数（错形 to 正形 → 已观察次数）：只在
     * 内存里，进程重启清零（见 [learn] 的两字门槛）。保持插入
     * 顺序，上限 [MAX_PROBATION] 超出时淘汰最旧的未达成项，防
     * 止只观察过一次便再不出现的词对在内存里无限累积。
     */
    private val probation = LinkedHashMap<Pair<String, String>, Int>()

    /**
     * 学一条词对。词形不合规（非 2–6 字全 CJK、错形等于正形）直接忽略。
     * 正形此前被停用又被用户重新纠正到它时，视为用户改主意，重新启用。
     *
     * 两字词对加一道观察期：位置配对对两字词只需同位相同 1 字即可
     * 成立，单次观察误学面过大（句尾续写、边界巧合都能凑出词对），
     * 故两字词对须被独立观察到 [PROBATION_REQUIRED] 次才正式生效
     * 并落盘；观察计数只在内存里，进程重启后重新观察。三字及
     * 以上维持单次生效（同位相同下限更高，误配概率低得多）。
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
                    // 先摘再放刷新插入顺序：重复观察过的项不该
                    // 还按首次插入的位置被 trim 当最旧淘汰。观察
                    // 计数不落盘，本支无其余状态变更，不触发落盘
                    probation.remove(key)
                    probation[key] = seen
                    trimProbationLocked()
                    return
                }
                probation.remove(key)
            }
            ensureLoadedLocked()
            var changed = false
            if (disabled.remove(rightForm)) changed = true
            // 错形归属唯一：同一错形此前若归在别的正形名下，先摘除，
            // 避免两条规则抢同一片段、纠正结果 flip-flop；摘除即
            // 视为在新正形下重新学入，活动时间随之重置
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
            // 已学过的词对重复学入不重置活动时间：活动史是用户用
            // 出来的，不能因重复纠正动作被清零
            if (wrongForm !in wrongs) {
                wrongs[wrongForm] = PairMeta(lastActivityAt = System.currentTimeMillis())
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
     * 上限就地执行。词对淘汰排序口径（替代旧「按学入顺序挤
     * 最旧」：常用的老规则被挤掉正是要改掉的行为）：
     * - 按最近活动时间升序（最旧的先淘汰）排序：活动时间学入
     *   时置为学入时刻、命中落屏后由 [recordHits] 刷新；同值
     *   保持学入顺序；
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
            // sortedWith 稳定排序：同值项保持 candidates 的学入
            // （迭代）顺序，淘汰结果确定性可复现
            val sorted = candidates.sortedWith(
                compareBy { it.third.lastActivityAt }
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
        // 本次加载时刻：旧三列行的学入时间与未来时间戳钳制都以
        // 它为基准（见 P 行处理）
        val loadNow = System.currentTimeMillis()
        for (line in content.lineSequence()) {
            if (line.isBlank()) continue
            val parts = line.split('\t')
            when (parts.firstOrNull()) {
                "P" -> {
                    // 四列是现行格式（活动时间一列）。三列是旧版
                    // 格式（无元信息）：活动时间记为本次加载时刻
                    // ——按 0 读入会让全部旧词对的活动时间垫底、
                    // 升级后首轮淘汰整批先死，旧规则得不到任何存
                    // 量保护。六列是更旧格式（命中次数/最近命中/
                    // 学入时间）：活动时间按「命中过取最近命中、
                    // 否则取学入」折算，命中次数直接丢弃。活动时
                    // 间解析失败按 0 处理，不丢词对（数值损坏不
                    // 该连坐规则）。时间戳一律钳到 [0, loadNow]：
                    // 设备时钟曾被调到未来时写下的时间戳若原样
                    // 读入，其活动时间永远最大、永不淘汰
                    if (parts.size != 3 && parts.size != 4 && parts.size != 6) continue
                    val right = parts[1]
                    val wrong = parts[2]
                    if (wrong == right) continue
                    if (!VoiceCorrector.isValidForm(right) || !VoiceCorrector.isValidForm(wrong)) continue
                    val activity = when (parts.size) {
                        4 -> (parts[3].toLongOrNull() ?: 0L).coerceIn(0L, loadNow)
                        6 -> {
                            val lastHit = (parts[4].toLongOrNull() ?: 0L).coerceIn(0L, loadNow)
                            val learned = (parts[5].toLongOrNull() ?: 0L).coerceIn(0L, loadNow)
                            if (lastHit > 0) lastHit else learned
                        }
                        else -> loadNow
                    }
                    val meta = PairMeta(lastActivityAt = activity)
                    // 文件里同词对重复出现（外部拼接等）保留先到的一份
                    pairs.getOrPut(right) { LinkedHashMap() }.putIfAbsent(wrong, meta)
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
            synchronized(lock) {
                if (!dirty || loadFailed) return@execute
                dirty = false
                snapshot = serializeLocked()
            }
            // 写文件在本实例的单线程执行器内串行：快照在任务运行
            // 时才拍，clear() 后入队的任务拍到的必然是清除后状态；
            // 清除前入队的任务先执行完、其后清除的落盘再覆写，
            // 不存在旧快照把已清词对写回的时序
            runCatching {
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

    private fun serializeLocked(): String {
        val out = StringBuilder()
        if (!enabledField) out.append("E\t0\n")
        // 上限已在 learn/disable/load 时就地执行，这里全量写出即可
        for ((right, wrongs) in pairs) {
            for ((wrong, meta) in wrongs) {
                out.append("P\t").append(right).append('\t').append(wrong)
                    .append('\t').append(meta.lastActivityAt)
                    .append('\n')
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

        /** 观察期表上限：防只观察一次便再不出现的词对无限累积。 */
        const val MAX_PROBATION = 600

        /**
         * 按文件取同进程共享实例：客户端与设置页必须用同一实例。
         * 两边各建实例时词表互不感知，后落盘的一方会把另一方学到的
         * 词对整表覆写掉，故正式取用一律走这里。测试与特殊场景仍
         * 可直接构造独立实例。
         */
        private val sharedByPath = HashMap<String, VoiceCorrectionStore>()

        fun shared(file: File): VoiceCorrectionStore = synchronized(sharedByPath) {
            sharedByPath.getOrPut(file.absolutePath) { VoiceCorrectionStore(file) }
        }

        /** 停用集合上限，与词对上限同量级。 */
        const val MAX_DISABLED = 300

        /** 纠错词表文件名（filesDir 下），与 SherpaSpeechClient 的取用一致。 */
        const val FILE_NAME = "voice_corrections.tsv"
    }
}
