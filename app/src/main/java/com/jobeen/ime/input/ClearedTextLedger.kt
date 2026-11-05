// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.input

import android.view.inputmethod.ExtractedText
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection
import com.jobeen.ime.base.speech.SherpaSpeechClient
import com.jobeen.ime.base.speech.VoiceUndoLedger

/**
 * 上滑清字（#deleteText）的撤销账本：把「被清掉的那段已上屏文字」
 * 记下来，下滑撤销时原样送回，不再依赖编辑器自己的 Ctrl+Z。
 *
 * 背景（2026-11-03 真机定性）：内嵌输入开着时，组字串经
 * setComposingText 直送编辑器，清组字会在编辑器撤销栈里留下一条
 * 「删除」记录。为防「清掉的组字被 Ctrl+Z 复活」，污染会话内
 * 下滑撤销已不再发 Ctrl+Z（见 [TypedUndoLedger.decideUndoFallback]）。
 * 代价是：上滑清掉的已上屏文字也没有任何账本记着，两个账本
 * （语音段、打字段）都不命中，下滑只能空操作——清掉的文字找不回来。
 *
 * 本账本补的就是这一格：清字时记，撤销时送回去。因为走的是
 * 直写而非 Ctrl+Z，污染会话里既不会让组字复活，清掉的文字也能找回。
 *
 * 登记口径：`#deleteText` 分发处（KeyActionListener）在删除**成功
 * 之后**登记，登记的是编辑器里实际被删掉的那段文字，连同删除点
 * 紧邻的前文锚点（至多 [ANCHOR_CHARS] 字）。有选区时
 * getTextBeforeCursor 取的是选区之前的文本（与 VoiceUndoLedger
 * 同一口径），正是删除点前文。
 *
 * 恢复口径：与语音账本、打字账本的恢复完全对称——按
 * [VoiceUndoLedger.decideRedoApply] 核对「删除后状态未被动过」，
 * 通过才整段写回光标处并弹出条目。任何不吻合（清完又打了字、
 * 删了字、移了光标）都不接管，交回既有落点判定。
 *
 * 与打字账本的分工：打字账本记的是「打出来、还在光标前躺着」的
 * 段，撤销是直删；本账本记的是「被上滑清掉、已经不在编辑器里」
 * 的段，撤销是写回。方向相反，不可混用。
 *
 * 光标绑定（审计 #1-9）：旧实现只比对文字锚点——同一锚点在
 * 同一编辑框里重复出现时，清掉第一处、把光标移到第二处再撤销，
 * 会把文字误插到第二处（满锚点 16 字时锚点判定本身拦不住，
 * 见 [decideRestoreApply] 的区间分支）。本账本现把三样东西
 * 记进条目，并在恢复时逐一核对：
 *
 * - 编辑器纪元（[SherpaSpeechClient.currentEditorEpoch]，登记时
 *   现取）：换框／输入结束推进纪元后，旧条目永不匹配，直接丢弃
 *   （纪元只进不退，丢了也不会再合用）。
 * - 光标位置（登记时随连接现读，见 [readPosition]）：光标前
 *   文本不足 [POSITION_CONTEXT_CHARS] 字且经 getExtractedText
 *   确认到达字段头时，读回的全长就是绝对光标位置（选区起点）；
 *   读短但确认不了字段头时（部分自绘输入框截短返回值）不绑定
 *   绝对位置、只记锚点；字段更长时绝对位置不可知，改存
 *   光标前 [POSITION_CONTEXT_CHARS] 字窗口本身作指纹。恢复时
 *   现读同一窗口比对：光标移过、选区变过、前文被编辑过都会让
 *   比对失败，不接管（条目保留，用户移回原处仍可恢复）。
 * - 选区变化通知（[onSelectionChanged]，供
 *   ImeInputMethodService.onUpdateSelection 接线）：光标位置
 *   已知的条目，任何与记录不一致的选区更新都立即作废——光标
 *   移开再移回原处也不恢复（条目已在移开时作废）。位置未知的
 *   条目（登记时没传连接）不受此通知影响：删除动作本身就会
 *   触发一次选区更新，无法区分「删除自带的更新」与「用户移
 *   光标」，清掉会让旧路径的撤销立即失效；它们仍由锚点判定兜底。
 *
 * 接线（v1.1.16.18 已完成）：登记处把当前连接传给 [record]
 * （KeyActionListener 的 #deleteText 分支与 RimeEngine.clearInternal
 * 各一处），服务在 onUpdateSelection 里调 [onSelectionChanged]。
 * 删除动作自带的选区更新报的是删除点位置，与登记位置一致，不会
 * 误作废；恢复写回后条目已弹出，后续选区更新不再影响它。
 */
object ClearedTextLedger {

    /** 账本上限：清字是近因操作，不做长历史 */
    private const val MAX_ENTRIES = 10

    /**
     * 恢复核对时多读的前文锚点长度。
     *
     * 必须是 [VoiceUndoLedger.ANCHOR_CHARS] 的同一个值：[decideRestoreApply]
     * 委托 [VoiceUndoLedger.decideRedoApply]，后者以「锚点是否被截断」
     * 划分满锚点区间判定与短锚点严判，两处取值不同则分界错位。
     * 这里不另写字面量，直接引用同一常量。
     */
    internal const val ANCHOR_CHARS = VoiceUndoLedger.ANCHOR_CHARS

    /** 条目过期：超过 10 分钟的段不再接管撤销（编辑器状态早已变迁） */
    private const val EXPIRE_MS = 10 * 60 * 1000L

    /**
     * 单次可记的被清文字上限：超出不记（不接管撤销）。
     * 恢复是把整段重新 commitText 回编辑器，超长段既无实际撤销
     * 意义，也会让账本常驻大字符串；不记即回落既有落点（污染会话
     * 内为空操作），与记了但恢复不上是同一结果，不引入新的失败面。
     *
     * internal：清空动作（RimeEngine.clearInternal）在累计被删文本时
     * 也用同一上限提前收手，避免为一整段超长文本先攒出一个大串再丢弃。
     */
    internal const val MAX_RECORDABLE_CHARS = 4096

    /**
     * 光标位置指纹窗口（字）：登记／恢复时各读一次
     * getTextBeforeCursor(本值)。读回不足本值即到字段头，长度
     * 就是绝对光标位置；读满本值则绝对位置不可知，存窗口本身
     * 作指纹。256 字覆盖锚点（16 字）的 16 倍，误判需同一
     * 256 字窗口在另一位置完整重复；Binder 单次往返只传
     * 至多 256 字，登记／恢复各一次，不成负担。
     */
    internal const val POSITION_CONTEXT_CHARS = 256

    /**
     * 编辑器纪元来源（单测可替换）。默认读语音客户端的进程级
     * 输入框纪元（换框／输入结束时推进，与按键脚本会话复检
     * 同一份纪元，不另造代次）。读失败（单测桩环境下
     * SherpaSpeechClient 类初始化触 Android 桩）按 0 处理：
     * 登记与恢复读到同一值，纪元判定恒过，不误伤旧行为。
     */
    internal var epochProvider: () -> Long = {
        runCatching { SherpaSpeechClient.currentEditorEpoch() }.getOrDefault(0L)
    }

    private data class Entry(
        val text: String,
        val prefix: String,
        val atMs: Long,
        /** 登记时的编辑器纪元（只进不退；不匹配即会话已换）。 */
        val epoch: Long,
        /** 登记时光标／选区起点（绝对位置；不可知时为 null）。 */
        val cursorStart: Int?,
        /** 登记时选区尾（无选区时等于起点；不可知时为 null）。 */
        val cursorEnd: Int?,
        /**
         * 登记时光标前窗口指纹（仅绝对位置不可知时存；
         * 绝对位置已知时为 null，位置判定走起点／尾）。
         */
        val contextBefore: String?,
    )

    /** 登记时现读的位置快照（见 [record]）。 */
    private data class PositionMark(
        val start: Int?,
        val end: Int?,
        val contextBefore: String?,
    )

    private val entries = ArrayDeque<Entry>()

    /**
     * 登记一段刚被清掉的已上屏文字。
     * @param text 实际被删掉的文字（空串与超长段忽略）
     * @param anchorBeforeDelete 删除点紧邻的前文（删除**之前**现读的
     *   `getTextBeforeCursor`，只取尾部 [ANCHOR_CHARS] 字）
     * @param ic 当前输入连接（可空）：传了才绑定光标位置（登记时
     *   现读选区／光标前窗口）；不传时条目只有锚点，恢复走旧的
     *   锚点判定（行为不变）。登记后连接即失效，账本不持有它。
     */
    @Synchronized
    fun record(text: String, anchorBeforeDelete: String, ic: InputConnection? = null) {
        if (text.isEmpty() || text.length > MAX_RECORDABLE_CHARS) return
        pruneExpired()
        val position = ic?.let { readPosition(it) }
        entries.addLast(
            Entry(
                text = text,
                prefix = anchorBeforeDelete.takeLast(ANCHOR_CHARS),
                atMs = System.currentTimeMillis(),
                epoch = epochProvider(),
                cursorStart = position?.start,
                cursorEnd = position?.end,
                contextBefore = position?.contextBefore,
            )
        )
        while (entries.size > MAX_ENTRIES) entries.removeFirst()
    }

    /**
     * 尝试把最近一次被清掉的文字原样送回光标处。
     *
     * 有选区时直接不接管（与两个既有账本同口径）：getTextBeforeCursor
     * 取的是选区之前的文本，即便吻合也可能是巧合，而 commitText
     * 会替换选区，动到不该动的地方。
     *
     * @return true 表示已写回并弹出条目（调用方不得再发 Ctrl+Z）；
     *   false 表示不接管，调用方按既有落点判定继续。
     */
    fun tryRestore(ic: InputConnection): Boolean {
        val selected = runCatching { ic.getSelectedText(0) }.getOrNull()
        if (!selected.isNullOrEmpty()) return false
        val entry = synchronized(this) {
            pruneExpired()
            entries.lastOrNull()
        } ?: return false
        // 纪元先行（无 Binder 开销）：会话已换的条目直接丢弃，
        // 纪元只进不退，丢了也不会再合用。
        if (entry.epoch != epochProvider()) {
            synchronized(this) {
                if (entries.lastOrNull() === entry) entries.removeLast()
            }
            return false
        }
        // 光标位置核对（有绑定才验）：移过光标、换过选区、前文
        // 被编辑过都拒绝；条目保留，用户移回原处仍可恢复。
        if (!positionMatches(entry, ic)) return false
        val before = runCatching {
            ic.getTextBeforeCursor(entry.prefix.length + entry.text.length, 0)?.toString()
        }.getOrNull()
        if (!decideRestoreApply(before, entry.prefix, entry.text.length)) return false
        val committed = runCatching { ic.commitText(entry.text, 1) }.getOrDefault(false)
        if (!committed) return false
        synchronized(this) {
            // 只弹仍是栈顶的同一段：校验期间状态变了就不动账本
            if (entries.lastOrNull() === entry) entries.removeLast()
        }
        return true
    }

    /**
     * 选区／光标变化通知（供 ImeInputMethodService.onUpdateSelection
     * 接线）：位置已知的条目，选区与记录不一致即作废；位置未知
     * 的条目不动（见类文档：删除自带的选区更新无法与用户移
     * 光标区分）。无条目时直接返回，不读连接。
     */
    @Synchronized
    fun onSelectionChanged(start: Int, end: Int) {
        entries.removeAll { entry ->
            entry.cursorStart != null &&
                (entry.cursorStart != start || entry.cursorEnd != end)
        }
    }

    /** 输入框结束时整表作废：旧框的账不能拿到新框去写回 */
    @Synchronized
    fun clearAll() {
        entries.clear()
    }

    /**
     * 新编辑作废恢复未来（通用撤销语义）：编辑器侧一旦有新文字落屏，
     * 「上滑清掉的那段」就不再是最后一次编辑，不应再被下滑送回。
     * 由引擎提交路径在登记打字账本处一并调用。
     */
    @Synchronized
    fun invalidate() {
        entries.clear()
    }

    /** 当前账本条目数（诊断与单测用）。 */
    @Synchronized
    internal fun size(): Int = entries.size

    /**
     * 栈顶条目的（被清文字, 锚点）——诊断与单测用。
     * 只读取值，不暴露内部队列。
     */
    @Synchronized
    internal fun peek(): Pair<String, String>? =
        entries.lastOrNull()?.let { it.text to it.prefix }

    /**
     * 栈顶条目登记的光标／选区（起点, 尾）——单测用；
     * 未绑定位置时为 null。
     */
    @Synchronized
    internal fun peekCursor(): Pair<Int, Int>? =
        entries.lastOrNull()?.let { entry ->
            val start = entry.cursorStart ?: return@let null
            val end = entry.cursorEnd ?: return@let null
            start to end
        }

    /**
     * 纯判定：删除后状态是否仍未被改动过。
     *
     * 满锚点/短锚点的分界与区间判定直接复用语音账本的
     * [VoiceUndoLedger.decideRedoApply]，语义完全一致。
     *
     * 唯一不同是空锚点这一支：清掉的是「字段开头起的文字」（最常见
     * 的就是全选后上滑清空，删除点前没有任何前文，锚点为空串）。
     * 此时光标停在字段开头，getTextBeforeCursor 在空字段上部分实现
     * 返回 null 而非空串——若沿用 decideRedoApply 的 null→false，
     * 「全选清空」这条最常用路径会永远恢复不上。空锚点下 null 与
     * 空串语义等价（都表示光标前什么都没有），故合并处理。
     */
    internal fun decideRestoreApply(
        beforeText: CharSequence?,
        prefix: String,
        clearedLength: Int,
    ): Boolean {
        if (prefix.isEmpty()) return beforeText.isNullOrEmpty()
        return VoiceUndoLedger.decideRedoApply(beforeText, prefix, clearedLength)
    }

    /**
     * 登记时现读光标位置（有界一次 Binder 往返，外加一次字段头确认）。
     *
     * 有选区时 getTextBeforeCursor 取的是选区之前的文本，故
     * before 长即选区起点；选区尾 = 起点 + 选区文本长。
     * 读失败（连接异常）返回全 null——不绑定、不误判。
     * 读到 null 按空串计（字段头，部分实现在空字段上返 null，
     * 与空锚点口径一致）。
     *
     * 读短时区分「确知到达字段头」与「只返回短窗口」：部分自绘
     * 输入框会把 getTextBeforeCursor 截断到小于请求值，此时
     * 「读短即字段头」的推断不成立，绝对位置会被低估，条目会被
     * 提前作废（撤销静默失效）。读短时须经 [isAtFieldHead] 用
     * getExtractedText 的绝对选区起点核对，确认不了字段头时
     * 不绑定绝对位置、只记锚点（onSelectionChanged 不作废、
     * 恢复走锚点判定）；读满 256 字时按现有分支存窗口指纹。
     */
    private fun readPosition(ic: InputConnection): PositionMark {
        val beforeResult = runCatching {
            ic.getTextBeforeCursor(POSITION_CONTEXT_CHARS, 0)?.toString()
        }
        if (beforeResult.isFailure) return PositionMark(null, null, null)
        val before = beforeResult.getOrNull().orEmpty()
        if (before.length >= POSITION_CONTEXT_CHARS) {
            return PositionMark(null, null, before)
        }
        val selected = runCatching { ic.getSelectedText(0)?.toString() }.getOrNull().orEmpty()
        return if (isAtFieldHead(ic, before.length)) {
            val start = before.length
            val end = start + selected.length
            PositionMark(start, end, null)
        } else {
            PositionMark(null, null, null)
        }
    }

    /**
     * 字段头确认：getExtractedText 返回的文本自字段头起
     * （startOffset == 0）且选区起点恰等于读回的光标前长度时，
     * 读回的短窗口就是字段头到光标的全部文字，绝对位置可信。
     * 选区起点在两种口径下都与 startOffset == 0 时的绝对光标
     * 一致，故只认 startOffset == 0 的情形；读失败／返回 null／
     * 文本为空／文本非自字段头起／选区起点对不上时一律按
     * 「不能确认」处理（安全方向：不绑定，退回锚点判定）。
     */
    private fun isAtFieldHead(ic: InputConnection, beforeLength: Int): Boolean {
        val extracted = runCatching {
            ic.getExtractedText(ExtractedTextRequest(), 0)
        }.getOrNull() ?: return false
        if (extracted.text == null) return false
        if (extracted.startOffset != 0) return false
        val start = extracted.selectionStart
        if (start < 0) return false
        return start == beforeLength
    }

    /**
     * 恢复时核对光标位置（有绑定才验，无绑定恒过、走锚点判定）。
     *
     * - 绝对位置已知：当前选区起点／尾必须与记录一致。恢复入口
     *   已拒有选区情形，故此处尾恒等于起点；起点一致即光标未动。
     * - 只有窗口指纹：当前光标前窗口必须与记录时完全一致。
     *   光标移过、选区变过、前文被编辑过都会改变窗口。
     * 读失败按不匹配处理（状态不明时不写回，安全方向）。
     */
    private fun positionMatches(entry: Entry, ic: InputConnection): Boolean {
        if (entry.cursorStart == null && entry.contextBefore == null) return true
        val mark = readPosition(ic)
        if (entry.cursorStart != null) {
            return mark.start == entry.cursorStart && mark.end == entry.cursorEnd
        }
        val current = mark.contextBefore ?: return false
        return current == entry.contextBefore
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
