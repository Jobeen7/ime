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
 * - 任何 IO 异常都被吞掉（runCatching），绝不向上抛；
 * - 总量封顶 [MAX_PAIRS]，落盘时按写入顺序淘汰最旧词对。
 *
 * 文件格式（每行一条，Tab 分隔）：
 * - `P\t<正形>\t<错形>`：一条学到的词对；
 * - `D\t<正形>`：该正形已被停用（用户把自动纠正改回了错形）。
 */
class VoiceCorrectionStore(private val file: File) {

    private val lock = Any()

    /** 正形 → 错形集合（保持学入顺序）。 */
    private val pairs = LinkedHashMap<String, LinkedHashSet<String>>()
    private val disabled = LinkedHashSet<String>()
    private var loaded = false
    private var dirty = false

    private val flushExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "voice-correction-flush").apply { isDaemon = true }
    }

    /** 对定稿文本应用当前全部生效规则做纠错。 */
    fun correct(text: String): CorrectResult {
        val rules = synchronized(lock) {
            ensureLoadedLocked()
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
            val set = pairs.getOrPut(rightForm) { LinkedHashSet() }
            if (set.add(wrongForm)) changed = true
            if (changed) markDirtyLocked()
        }
    }

    /** 停用某正形的全部纠错（用户把自动纠正改回了错形）。 */
    fun disable(rightForm: String) {
        synchronized(lock) {
            ensureLoadedLocked()
            if (disabled.add(rightForm)) markDirtyLocked()
        }
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
        loaded = true
        runCatching {
            if (file.isFile) {
                parseInto(file.readText(Charsets.UTF_8))
            }
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
            }
        }
    }

    private fun markDirtyLocked() {
        dirty = true
        flushExecutor.execute {
            val snapshot: String
            synchronized(lock) {
                if (!dirty) return@execute
                dirty = false
                snapshot = serializeLocked()
            }
            runCatching {
                file.parentFile?.mkdirs()
                file.writeText(snapshot, Charsets.UTF_8)
            }
        }
    }

    private fun serializeLocked(): String {
        val out = StringBuilder()
        // 总量封顶：按学入顺序只保留最后 MAX_PAIRS 条词对
        val allPairs = ArrayList<Pair<String, String>>()
        for ((right, wrongs) in pairs) {
            for (wrong in wrongs) allPairs += right to wrong
        }
        val kept = if (allPairs.size > MAX_PAIRS) {
            allPairs.subList(allPairs.size - MAX_PAIRS, allPairs.size)
        } else {
            allPairs
        }
        for ((right, wrong) in kept) {
            out.append("P\t").append(right).append('\t').append(wrong).append('\n')
        }
        for (right in disabled) {
            out.append("D\t").append(right).append('\n')
        }
        return out.toString()
    }

    companion object {
        const val MAX_PAIRS = 300
    }
}
