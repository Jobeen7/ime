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
 *
 * 恢复口径（与撤销对称）：账本直删绕开了编辑器的撤销栈，下滑
 * 恢复（Ctrl+Shift+Z）对这笔删除无账可恢复——故撤销成功时把删掉
 * 的整串连同删除点前的锚点（至多 [ANCHOR_CHARS] 字）存入恢复栈；
 * 恢复时按统一后缀判定核对：光标前窗口以锚点结尾、且窗口长度
 * 落在锚点长与「锚点长＋被删串长」之间（删除后未被动过的状态
 * 必然如此，见 [decideRedoApply]），才整串写回并重新入账。新的
 * 语音上屏清空恢复栈（新编辑作废恢复未来，与通用撤销语义一致）。
 */
object VoiceUndoLedger {

    /** 账本上限：只留最近几段语音，撤销是近因操作，不做长历史 */
    private const val MAX_ENTRIES = 10

    /** 条目过期：超过 10 分钟的段不再接管撤销（编辑器状态早已变迁） */
    private const val EXPIRE_MS = 10 * 60 * 1000L

    private data class Entry(val text: String, val atMs: Long)

    /**
     * 一次账本撤销的恢复单元：被删的整串 [combinedText]、删除点前
     * 的锚点 [prefix]（只存窗口尾部至多 [ANCHOR_CHARS] 字，恢复时
     * 按后缀核对删除点未被动过，见 [buildRedoPrefix]）、被弹出的
     * 账本条目本体（恢复成功后按原时间戳重新入账）。
     */
    private data class RedoUnit(
        val combinedText: String,
        val prefix: String,
        val entries: List<Entry>,
    )

    private val entries = ArrayDeque<Entry>()
    private val redoUnits = ArrayDeque<RedoUnit>()

    /** 登记一段刚真正落到编辑器里的语音文字；空串忽略 */
    @Synchronized
    fun recordCommittedSegment(text: String) {
        if (text.isEmpty()) return
        // 新编辑作废恢复未来（通用撤销语义）：此前撤销存下的恢复
        // 单元不再对应当前编辑脉络
        redoUnits.clear()
        pruneExpired()
        entries.addLast(Entry(text, System.currentTimeMillis()))
        while (entries.size > MAX_ENTRIES) entries.removeFirst()
    }

    /** 输入框切换时整表作废：旧框的账不能拿到新框去删 */
    @Synchronized
    fun clearAll() {
        entries.clear()
        redoUnits.clear()
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
        // 现读窗口在段长之外多带一小段前文锚点：删除成功后这段锚点
        // 连同被删整串一起存进恢复单元，下滑恢复时据此核对光标位置
        // （拼接判定只看尾部，多读不影响判定结果）
        val before = runCatching {
            ic.getTextBeforeCursor(lookup + ANCHOR_CHARS, 0)?.toString()
        }.getOrNull()
        val span = decideUndoDeleteMulti(before, candidates.map { it.text }) ?: return false
        val (count, totalLen) = span
        val deleted = runCatching { ic.deleteSurroundingText(totalLen, 0) }.getOrDefault(false)
        if (!deleted) return false
        val window = before.orEmpty()
        synchronized(this) {
            // 从最新侧逐条弹：只弹仍是快照同一条的，校验期间登记的
            // 新段（不在快照里）身份不符、立即停手，不能误删新账
            val popped = ArrayList<Entry>()
            for (i in 0 until count) {
                val top = entries.lastOrNull()
                if (top === candidates[i]) {
                    entries.removeLast()
                    popped.add(top)
                } else break
            }
            // 存恢复单元：被删整串＋删除点前的锚点（只取尾部至多
            // ANCHOR_CHARS 字：恢复判定是后缀判定，存全量前文只会
            // 让判定背上数千字无关文本——其间改一字即拒恢复、且
            // 复读长度随之膨胀）。实际弹出的条目按时间序随单元
            // 保存，恢复成功后重新入账；弹出中途被新账打断时单元
            // 只带已弹部分
            redoUnits.addLast(
                RedoUnit(
                    combinedText = window.takeLast(totalLen),
                    prefix = buildRedoPrefix(window, totalLen),
                    entries = popped.reversed(),
                )
            )
            while (redoUnits.size > MAX_ENTRIES) redoUnits.removeFirst()
        }
        return true
    }

    /** 恢复核对时多读的前文锚点长度：足够定位删除点，又不至把大量无关前文纳入校验 */
    private const val ANCHOR_CHARS = 16

    /**
     * 纯函数：由撤销时的现读窗口 [window] 与应删总长 [totalLen]
     * 构造恢复单元的锚点——窗口去掉尾部被删整串后，只保留最后
     * 至多 [ANCHOR_CHARS] 字。锚点是删除点紧邻的前文：恢复判定
     * 只需核对它仍位于光标前窗口的尾部（见 [decideRedoApply]），
     * 更早的前文与删除点状态无关，不进恢复单元。
     */
    internal fun buildRedoPrefix(window: String, totalLen: Int): String =
        window.dropLast(totalLen).takeLast(ANCHOR_CHARS)

    /**
     * 尝试恢复最近一次账本撤销删掉的整串：恢复栈顶单元的前文核对
     * 通过（删除后编辑状态未被动过）才把整串写回光标处并重新入账；
     * 任何不吻合/失败返回 false（不弹恢复栈），调用方回落
     * Ctrl+Shift+Z。
     */
    fun tryRedo(ic: InputConnection): Boolean {
        val selected = runCatching { ic.getSelectedText(0) }.getOrNull()
        if (!selected.isNullOrEmpty()) return false
        val unit = synchronized(this) { redoUnits.lastOrNull() } ?: return false
        val before = runCatching {
            ic.getTextBeforeCursor(unit.prefix.length + unit.combinedText.length, 0)?.toString()
        }.getOrNull()
        if (!decideRedoApply(before, unit.prefix, unit.combinedText.length)) return false
        val committed = runCatching { ic.commitText(unit.combinedText, 1) }.getOrDefault(false)
        if (!committed) return false
        synchronized(this) {
            // 只弹仍是栈顶的同一单元：校验期间状态变了就不动恢复栈
            if (redoUnits.lastOrNull() === unit) redoUnits.removeLast()
            // 被恢复的段按原时间戳重新入账（已过期的不再入），使
            // 恢复后还能再次被上滑撤销接管
            val now = System.currentTimeMillis()
            for (e in unit.entries) {
                if (now - e.atMs <= EXPIRE_MS) entries.addLast(e)
            }
            while (entries.size > MAX_ENTRIES) entries.removeFirst()
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

    /**
     * 纯判定（恢复）：[beforeText] 是按「锚点长＋被删串长」现读的
     * 光标前窗口，[prefix] 是删除点前的锚点（至多 [ANCHOR_CHARS]
     * 字）。统一后缀判定：窗口以锚点结尾、且长度落在
     * [锚点长, 锚点长＋被删串长] 区间内才接管。
     *
     * 未被动过的状态必然落在该区间：删除后光标前恰剩「锚点及更早
     * 的内容」，按锚点长＋被删串长现读，读回的或是全量（字段短，
     * 窗口＝全部前文）、或是读满（字段长，窗口尾部仍是锚点）；
     * 此前只认「恰等于锚点」与「恰好读满」两个端点，撤销窗口之外
     * 残留的前文长度介于其间时（部分读回）会误拒，现统一为区间
     * 判定。安全性不减：删除点之后打字会让窗口尾部变成新打的字、
     * 删字会吃掉锚点尾部或让窗口短于锚点，两种情形后缀都不再
     * 吻合。[prefix] 为空时只认窗口全空（删除点就在字段开头，
     * 锚点无从附着，只能严判）。
     */
    internal fun decideRedoApply(
        beforeText: CharSequence?,
        prefix: String,
        combinedLength: Int,
    ): Boolean {
        if (beforeText == null) return false
        if (prefix.isEmpty()) return beforeText.isEmpty()
        return beforeText.length >= prefix.length &&
            beforeText.length <= prefix.length + combinedLength &&
            beforeText.endsWith(prefix)
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
