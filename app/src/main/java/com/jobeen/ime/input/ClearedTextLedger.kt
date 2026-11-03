// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.input

import android.view.inputmethod.InputConnection
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

    private data class Entry(
        val text: String,
        val prefix: String,
        val atMs: Long,
    )

    private val entries = ArrayDeque<Entry>()

    /**
     * 登记一段刚被清掉的已上屏文字。
     * @param text 实际被删掉的文字（空串与超长段忽略）
     * @param anchorBeforeDelete 删除点紧邻的前文（删除**之前**现读的
     *   `getTextBeforeCursor`，只取尾部 [ANCHOR_CHARS] 字）
     */
    @Synchronized
    fun record(text: String, anchorBeforeDelete: String) {
        if (text.isEmpty() || text.length > MAX_RECORDABLE_CHARS) return
        pruneExpired()
        entries.addLast(
            Entry(
                text = text,
                prefix = anchorBeforeDelete.takeLast(ANCHOR_CHARS),
                atMs = System.currentTimeMillis(),
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
