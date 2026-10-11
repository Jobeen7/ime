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
 * 删除口径：动手前现读光标前文本，必须与栈顶整段吻合才删——用户
 * 在上屏后打过字、改过字、移过光标都不会误伤；不吻合返回 false
 * 让调用方回落 Ctrl+Z，且不弹栈（也许只是光标暂时不在段尾）。
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

    /**
     * 尝试按栈顶整段撤销：删成功返回 true 并弹栈；任何不吻合/失败
     * 返回 false（不弹栈），调用方回落既有 Ctrl+Z 路径。
     */
    fun tryUndo(ic: InputConnection): Boolean {
        // 有选区时 getTextBeforeCursor 取的是选区之前的文本，即使与
        // 段尾吻合也可能是巧合，而 deleteSurroundingText 会动到选区
        // 附近——有选区直接不接管
        val selected = runCatching { ic.getSelectedText(0) }.getOrNull()
        if (!selected.isNullOrEmpty()) return false
        val entry = synchronized(this) {
            pruneExpired()
            entries.lastOrNull()
        } ?: return false
        val before = runCatching { ic.getTextBeforeCursor(entry.text.length, 0) }.getOrNull()
        val len = decideUndoDelete(before, entry.text)
        if (len <= 0) return false
        val deleted = runCatching { ic.deleteSurroundingText(len, 0) }.getOrDefault(false)
        if (!deleted) return false
        synchronized(this) {
            // 只在栈顶仍是同一条时才弹：校验期间可能有新段登记进来，
            // 不能把新段的账误删掉
            if (entries.lastOrNull() === entry) entries.removeLast()
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
