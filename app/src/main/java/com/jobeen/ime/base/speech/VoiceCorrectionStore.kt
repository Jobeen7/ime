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
                markDirtyLocked()
            }
        }
        for (peer in peers()) {
            synchronized(peer.lock) { peer.enabledField = value }
        }
    }

    /** 清空全部已学词对与停用记录（用户在设置页主动清除）。 */
    fun clear() {
        synchronized(lock) {
            ensureLoadedLocked()
            pairs.clear()
            disabled.clear()
            // 主动清除是用户明确要抹掉历史，不受读失败禁写约束
            loadFailed = false
            markDirtyLocked()
        }
        for (peer in peers()) {
            synchronized(peer.lock) {
                peer.pairs.clear()
                peer.disabled.clear()
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

    /**
     * 学一条词对。词形不合规（非 2–6 字全 CJK、错形等于正形）直接忽略。
     * 正形此前被停用又被用户重新纠正到它时，视为用户改主意，重新启用。
     */
    fun learn(wrongForm: String, rightForm: String) {
        if (wrongForm == rightForm) return
        if (!VoiceCorrector.isValidForm(wrongForm) || !VoiceCorrector.isValidForm(rightForm)) return
        synchronized(lock) {
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
                    enabledField = parts[1] == "1"
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

        /** 停用集合上限，与词对上限同量级。 */
        const val MAX_DISABLED = 300

        /** 纠错词表文件名（filesDir 下），与 SherpaSpeechClient 的取用一致。 */
        const val FILE_NAME = "voice_corrections.tsv"

        /** 同进程存活实例登记表：供 enabled/clear 跨实例同步（弱引用，不防泄漏）。 */
        private val liveInstances: MutableSet<VoiceCorrectionStore> =
            Collections.newSetFromMap(WeakHashMap())
    }
}
