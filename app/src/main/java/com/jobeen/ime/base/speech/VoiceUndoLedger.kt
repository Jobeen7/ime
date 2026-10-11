package com.jobeen.ime.base.speech

import android.view.inputmethod.InputConnection

/**
 * 语音上屏段的撤销账本：退格上滑撤销（RimeEngine 的 Action.Undo）先
 * 查这里，命中就整段直删，不再把语音文字的命运交给编辑器自己的
 * Ctrl+Z 撤销栈——各家 App 对组字转正文字的记账口径不一（每段记
 * 一步、甚至不入栈），一次撤销经常删不完一整段语音。
 *
 * 登记口径：由 SherpaSpeechClient 在语音文本真正提交到编辑器之后
 * 调用 [recordCommittedSegment]，登记的是编辑器里最终落下的字符串。
 *
 * 删除口径：动手前现读光标前文本，从最新一段起逐段向前拼接
 * 校验，必须整串吻合才删——一次上滑删完光标前连续的全部语音段
 * （用户实测口径：多段语音要一次删完，不逐段滑）。用户在上屏后
 * 打过字、改过字、移过光标都会让拼接在对应段处断开：断点之后的
 * 段不删、账也不弹；全不吻合返回 false 让调用方回落 Ctrl+Z。
 */
object VoiceUndoLedger {

    /** 账本上限：只留最近几段语音，撤销是近因操作，不做长历史 */
    private const val MAX_ENTRIES = 10

    /** 条目过期：超过 10 分钟的段不再接管撤销（编辑器状态早已变迁） */
    private const val EXPIRE_MS = 10 * 60 * 1000L

    private data class Entry(val text: String, val atMs: Long)

    private val entries = ArrayDeque<Entry>()

    /** 登记一段刚真正落到编辑器里的语音文字；空串忽略 */
    @Synchronized
    fun recordCommittedSegment(text: String) {
        if (text.isEmpty()) return
        pruneExpired()
        entries.addLast(Entry(text, System.currentTimeMillis()))
        while (entries.size > MAX_ENTRIES) entries.removeFirst()
    }

    /** 输入框切换时整表作废：旧框的账不能拿到新框去删 */
    @Synchronized
    fun clearAll() {
        entries.clear()
    }

    /** 一次现读光标前文本的上限：账本至多 10 段，超出这个长度的极端长段组合不再整删 */
    private const val MAX_LOOKUP_CHARS = 4096

    /**
     * 尝试一次删完光标前连续的语音段：从最新段起逐段向前拼接校验，
     * 吻合几段删几段并弹几段；任何不吻合/失败返回 false（不弹栈），
     * 调用方回落既有 Ctrl+Z 路径。
     */
    fun tryUndo(ic: InputConnection): Boolean {
        // 有选区时 getTextBeforeCursor 取的是选区之前的文本，即使与
        // 段尾吻合也可能是巧合，而 deleteSurroundingText 会动到选区
        // 附近——有选区直接不接管
        val selected = runCatching { ic.getSelectedText(0) }.getOrNull()
        if (!selected.isNullOrEmpty()) return false
        // 快照（最新在前）＋本次现读长度：按段长累加，到上限为止
        val snapshot = synchronized(this) {
            pruneExpired()
            entries.toList().reversed()
        }
        if (snapshot.isEmpty()) return false
        var lookup = 0
        val candidates = ArrayList<Entry>()
        for (e in snapshot) {
            if (lookup + e.text.length > MAX_LOOKUP_CHARS) break
            lookup += e.text.length
            candidates.add(e)
        }
        if (candidates.isEmpty()) return false
        val before = runCatching { ic.getTextBeforeCursor(lookup, 0) }.getOrNull()
        val span = decideUndoDeleteMulti(before, candidates.map { it.text }) ?: return false
        val (count, totalLen) = span
        val deleted = runCatching { ic.deleteSurroundingText(totalLen, 0) }.getOrDefault(false)
        if (!deleted) return false
        synchronized(this) {
            // 从最新侧逐条弹：只弹仍是快照同一条的，校验期间登记的
            // 新段（不在快照里）身份不符、立即停手，不能误删新账
            for (i in 0 until count) {
                if (entries.lastOrNull() === candidates[i]) entries.removeLast() else break
            }
        }
        return true
    }

    /**
     * 纯判定：光标前文本 [beforeText] 以账本条目 [entry] 整段结尾
     * （且条目非空）→ 返回应删的 UTF-16 长度（即 entry.length）；
     * 其余情况返回 0。抽成纯函数供单测穷举，不碰 Android 类。
     */
    internal fun decideUndoDelete(beforeText: CharSequence?, entry: String): Int {
        if (entry.isEmpty()) return 0
        if (beforeText == null || beforeText.length < entry.length) return 0
        return if (beforeText.endsWith(entry)) entry.length else 0
    }

    /**
     * 纯判定（多段）：[entriesNewestFirst] 最新在前，从最新段起逐段
     * 向前拼接（旧段在前、新段在后还原成编辑器里的原文），每拼一段
     * 都要求 [beforeText] 以拼串结尾；拼到第一处不吻合为止。返回
     * （吻合段数， 应删 UTF-16 总长）；一段都不吻合返回 null。
     */
    internal fun decideUndoDeleteMulti(
        beforeText: CharSequence?,
        entriesNewestFirst: List<String>,
    ): Pair<Int, Int>? {
        if (beforeText == null || entriesNewestFirst.isEmpty()) return null
        var combined = ""
        var count = 0
        for (entry in entriesNewestFirst) {
            if (entry.isEmpty()) break
            val candidate = entry + combined
            if (candidate.length > beforeText.length) break
            if (!beforeText.endsWith(candidate)) break
            combined = candidate
            count++
        }
        return if (count > 0) count to combined.length else null
    }

    /** 清掉已过期条目（队首最旧，过期必在队首一段连续区间内） */
    private fun pruneExpired() {
        val now = System.currentTimeMillis()
        while (true) {
            val first = entries.firstOrNull() ?: return
            if (now - first.atMs <= EXPIRE_MS) return
            entries.removeFirst()
        }
    }
}
