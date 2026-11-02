package com.jobeen.ime.input

import android.view.inputmethod.InputConnection
import com.jobeen.ime.base.speech.VoiceUndoLedger

/**
 * 打字上屏段的撤销账本 + 内嵌组字污染标记。
 *
 * 背景（2026-11-02 真机定性，.15 原样复活）：内嵌输入开时，Rime 组字
 * 串经 setComposingText 直送编辑器；下滑清组字（Reset）后，组字串
 * 的清除以 setComposingText("") 落到编辑器，被编辑器记成一次普通
 * 文本删除；此后无组字下滑若原样发 Ctrl+Z，编辑器撤销这次删除，
 * 就把清掉前的组字以已上屏文本带回来（恢复时组字属性丢失）。
 * 守卫式事后删增量已被叫停，故不在 Ctrl+Z 后面再加拦：本账本
 * 让下滑撤销根本不走到编辑器那支被污染的栈。
 *
 * 两条规则（只在污染会话内生效，未污染会话保持正式版原样）：
 * 1. 污染标记：内嵌组字串一旦送进编辑器，本会话即视为污染——
 *    组字清除（清组字、选词上屏后组字排空）都可能在编辑器撤销
 *    栈里留下一条“清前含组字文本”的删除记录；标记由
 *    KeyboardWindowView 在内嵌直送非空组字时置位，随输入框
 *    结束整表作废（与语音账本同生命周期）。
 * 2. 污染会话内的下滑撤销：先查语音账本（既有口径不变），再查
 *    本账本——命中则按上屏段整段直删（一次下滑只删最新一段，
 *    与语音“一次删完连续多段”不同，打字段按段撤销）；两账本
 *    都不命中时直接空操作，绝不回落 Ctrl+Z。未污染会话仍回
 *    落 Ctrl+Z（正式版行为，一字不动）。
 *
 * 登记口径：MessageHandler 把 EngineMessage.Commit 落屏到编辑器
 * 后登记（拦截态不落屏、不登记；空提交忽略）。恢复口径与语音
 * 账本对称：直删时把被删段连同删除点前锚点存入恢复栈，下滑恢
 * 复（Redo）时核对通过才整段写回并重新入账；新上屏清空恢复栈。
 */
object TypedUndoLedger {

    /** 账本上限：打字段短、段数多，按段撤销是近因操作，不做长历史 */
    private const val MAX_ENTRIES = 100

    /** 恢复核对时多读的前文锚点长度：与语音账本同口径（尾 16 字） */
    private const val ANCHOR_CHARS = 16

    private data class Entry(val text: String)

    private data class RedoUnit(
        val combinedText: String,
        val prefix: String,
        val entry: Entry,
    )

    private val entries = ArrayDeque<Entry>()
    private val redoUnits = ArrayDeque<RedoUnit>()

    /** 本会话内嵌组字是否送进过编辑器（送进即污染，见类注释） */
    private var composingSentToEditor = false

    /** 登记一段刚真正落到编辑器里的打字上屏文字；空串忽略 */
    @Synchronized
    fun record(text: String) {
        if (text.isEmpty()) return
        // 新编辑作废恢复未来（通用撤销语义，与语音账本同口径）
        redoUnits.clear()
        entries.addLast(Entry(text))
        while (entries.size > MAX_ENTRIES) entries.removeFirst()
    }

    /** 内嵌组字串送进编辑器时置位（污染标记，见类注释） */
    @Synchronized
    fun markComposingSent() {
        composingSentToEditor = true
    }

    /** 本会话编辑器撤销栈是否已被内嵌组字污染 */
    @Synchronized
    fun isPoisoned(): Boolean = composingSentToEditor

    /** 输入框结束时整表作废：旧框的账不能拿到新框去删 */
    @Synchronized
    fun clearAll() {
        entries.clear()
        redoUnits.clear()
        composingSentToEditor = false
    }

    /**
     * 尝试一次删完光标前最新一段打字上屏：只删最新一段（单段口
     * 径），任何不吻合/失败返回 false（不弹栈），调用方按污染
     * 与否决定空操作还是回落 Ctrl+Z。
     */
    fun tryUndo(ic: InputConnection): Boolean {
        // 有选区时 getTextBeforeCursor 取的是选区之前的文本，即使与
        // 段尾吻合也可能是巧合，而 deleteSurroundingText 会动到选区
        // 附近——有选区直接不接管（与语音账本同口径）
        val selected = runCatching { ic.getSelectedText(0) }.getOrNull()
        if (!selected.isNullOrEmpty()) return false
        val entry = synchronized(this) { entries.lastOrNull() } ?: return false
        val before = runCatching {
            ic.getTextBeforeCursor(entry.text.length + ANCHOR_CHARS, 0)?.toString()
        }.getOrNull()
        val len = decideTypedUndoDelete(before, entry.text)
        if (len == 0) return false
        val deleted = runCatching { ic.deleteSurroundingText(len, 0) }.getOrDefault(false)
        if (!deleted) return false
        val window = before.orEmpty()
        synchronized(this) {
            // 只弹仍是栈顶的同一条：校验期间登记的新段身份不符
            // 立即停手，不能误删新账（与语音账本同口径）
            if (entries.lastOrNull() === entry) entries.removeLast()
            redoUnits.addLast(
                RedoUnit(
                    combinedText = window.takeLast(len),
                    prefix = VoiceUndoLedger.buildRedoPrefix(window, len),
                    entry = entry,
                )
            )
            while (redoUnits.size > MAX_ENTRIES) redoUnits.removeFirst()
        }
        return true
    }

    /**
     * 尝试恢复最近一次账本撤销删掉的一段：恢复栈顶单元的前文核对
     * 通过（删除后编辑状态未被动过）才把整段写回光标处并重新入
     * 账；任何不吻合/失败返回 false（不弹恢复栈），调用方回落
     * Ctrl+Shift+Z。
     */
    fun tryRedo(ic: InputConnection): Boolean {
        val selected = runCatching { ic.getSelectedText(0) }.getOrNull()
        if (!selected.isNullOrEmpty()) return false
        val unit = synchronized(this) { redoUnits.lastOrNull() } ?: return false
        val before = runCatching {
            ic.getTextBeforeCursor(unit.prefix.length + unit.combinedText.length, 0)?.toString()
        }.getOrNull()
        if (!decideTypedRedoApply(before, unit.prefix, unit.combinedText.length)) return false
        val committed = runCatching { ic.commitText(unit.combinedText, 1) }.getOrDefault(false)
        if (!committed) return false
        synchronized(this) {
            // 只弹仍是栈顶的同一单元：校验期间状态变了就不动恢复栈
            if (redoUnits.lastOrNull() === unit) redoUnits.removeLast()
            // 被恢复的段重新入账，使恢复后还能再次被下滑撤销接管
            entries.addLast(unit.entry)
            while (entries.size > MAX_ENTRIES) entries.removeFirst()
        }
        return true
    }

    /**
     * 纯判定：光标前文本 [beforeText] 以账本条目 [entry] 整段结尾
     * （且条目非空）→ 返回应删的 UTF-16 长度（即 entry.length）；
     * 其余情况返回 0。规则与语音账本单段判定同一条，抽成纯函数
     * 供单测穷举，不碰 Android 类。
     */
    internal fun decideTypedUndoDelete(beforeText: CharSequence?, entry: String): Int =
        VoiceUndoLedger.decideUndoDelete(beforeText, entry)

    /**
     * 纯判定（恢复）：与语音账本恢复判定同一条（见
     * VoiceUndoLedger.decideRedoApply 的满锚点/短锚点分界），抽成
     * 纯函数供单测穷举，不碰 Android 类。
     */
    internal fun decideTypedRedoApply(
        beforeText: CharSequence?,
        prefix: String,
        combinedLength: Int,
    ): Boolean = VoiceUndoLedger.decideRedoApply(beforeText, prefix, combinedLength)

    /** 下滑撤销（无组字时）的落点：账本命中、发 Ctrl+Z、空操作 */
    enum class UndoFallback { DONE, SEND_CTRL_Z, NOOP }

    /**
     * 纯判定：下滑撤销落点真值表（RimeEngine Undo 分支即按此表
     * 执行，单测直接覆盖全表）。
     * - 语音账本或打字账本命中 → DONE（已直删，不再发键）；
     * - 两账本都不命中且会话被污染 → NOOP（绝不把 Ctrl+Z 发进
     *   被污染的编辑器撤销栈，那正是清掉的组字复活的路径）；
     * - 两账本都不命中且会话未污染 → SEND_CTRL_Z（正式版原样）。
     */
    internal fun decideUndoFallback(
        voiceHit: Boolean,
        typedHit: Boolean,
        poisoned: Boolean,
    ): UndoFallback = when {
        voiceHit || typedHit -> UndoFallback.DONE
        poisoned -> UndoFallback.NOOP
        else -> UndoFallback.SEND_CTRL_Z
    }
}
