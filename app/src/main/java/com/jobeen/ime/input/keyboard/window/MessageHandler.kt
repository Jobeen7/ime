package com.jobeen.ime.input.keyboard.window

import android.inputmethodservice.InputMethodService
import android.view.KeyEvent
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection
import com.jobeen.ime.engine.data.EngineMessage
import com.jobeen.ime.engine.data.PairCommitQueue
import com.jobeen.ime.input.ClearedTextLedger
import com.jobeen.ime.input.ImeInputMethodService
import com.jobeen.ime.input.TypedUndoLedger
import com.jobeen.ime.skin.diag.DiagLog

class MessageHandler(
    private val service: InputMethodService,
) {
    private var window: KeyboardWindow? = null

    fun attach(window: KeyboardWindow) {
        this.window = window
    }

    suspend fun handle(message: EngineMessage) {
        when (message) {
            is EngineMessage.Commit -> {
                // P4 分段探针（提交段）：量 Commit 分支同步处理的耗时；
                // 拦截态早返回不产生样本（拦截的提交本就不走提交处理）。
                val commitStart = com.jobeen.ime.skin.perf.PerfMonitor.stageStart()
                // 成对提交的光标回退量：每次 Commit 处理必出队一次
                // （拦截态同样出队丢弃），正常提交出队得 0、无操作。
                val cursorBack = PairCommitQueue.poll()
                // 剪贴板搜索态：上屏文本改道进搜索查询，不写入目标应用
                if (window?.interceptCommit(message.text) == true) return
                // 性能埋点（2026-10-29 item-1 B）：提交终点，与
                // BaseKeyboard.onAction 的按键起点配对（拦截态不记）。
                com.jobeen.ime.skin.perf.PerfMonitor.noteCommit()
                val ic = (service as ImeInputMethodService).activeInputConnection()
                // 提交落屏回执（I-M5）：commitText 返回值是提交是否
                // 真正落屏的唯一回执——连接已死／只读框时返回 false，
                // 此时记撤销账、作废清字账、记统计都会把一段从未落屏
                // 的文字当成已上屏；只在 committed 时做这三件事。
                // 抛错同样按未提交处理（旧实现无捕获，连接异常会把
                // 整条消息处理打断在半路）。
                val committed = ic != null &&
                    runCatching { ic.commitText(message.text, 1) }.getOrDefault(false)
                val effects = commitEffects(committed, message.text)
                // 输入统计（2026-10-29 item-1 C）：提交次数＋字数
                // （空提交由 noteCommit 内部忽略；拦截态已在上方 return，不记）。
                if (effects.noteStats) {
                    com.jobeen.ime.input.stats.InputStats.noteCommit(message.text)
                }
                // 打字上屏入账（拦截态已在上方 return，不落屏不登记；
                // 空提交由 record 忽略）：下滑撤销在污染会话内凭此
                // 整段直删，不再把命运交给编辑器撤销栈。
                if (effects.recordUndo) TypedUndoLedger.record(message.text)
                // 新文字落屏即作废「上滑清掉的文字」的恢复未来（通用
                // 撤销语义）：清完又打了字，那段就不再是最后一次编辑，
                // 下滑不该再把旧文字插回当前光标处。
                if (effects.invalidateCleared) ClearedTextLedger.invalidate()
                // 成对提交的光标回退（I-L5）：按键返回值逐键校验，
                // 编辑器不响应方向键时走 setSelection 兜底（光标须
                // 停在两符号之间）；两路都走不完才记日志，不猜位置。
                if (cursorBack > 0 && committed && ic != null) {
                    val moved = moveCursorBackByKeys(ic, cursorBack)
                    val remaining = cursorBack - moved
                    if (remaining > 0 && !moveCursorBackBySelection(ic, remaining)) {
                        DiagLog.e(
                            "SkinDiag", "pair_cursor_back_failed",
                            mapOf(
                                "cursorBack" to cursorBack.toString(),
                                "moved" to moved.toString(),
                            ),
                        )
                    }
                }
                // 连发 Commit（如连打上屏）不必每条都同步探针：与选区路径
                // 共用 24ms 合并通道，窗口内只探最后一次（探针每次直探，
                // 无帧缓存可命中旧结果，见清点 S14）
                (service as ImeInputMethodService).scheduleNotifyInputChanged()
                com.jobeen.ime.skin.perf.PerfMonitor.noteStage(
                    com.jobeen.ime.skin.perf.PerfMonitor.Event.KEY_COMMIT, commitStart,
                )
            }

            is EngineMessage.Candidates -> {
                // total=-1 表示首屏取满、总数未知（后面还有）；确切总数时
                // 整表按 list.size 与 total 比。追加页把 total 原样交给面板，
                // 由面板按合并后的总条数判定是否到底
                // 追加页同样带版本号（I-S2：引擎侧 revision 取批次号，
                // 与首屏的 requestId 同源）；窗口层凭它丢弃迟到旧页，
                // 不再只传 list/total。
                if (message.append) {
                    window?.appendCandidates(message.list, message.total, message.revision)
                } else {
                    val hasMore = message.total < 0 || message.list.size < message.total
                    window?.setCandidates(
                        message.list,
                        hasMore,
                        message.revision,
                        message.highlighted,
                    )
                }
            }

            // EngineMessage.Depoly 不在此处理：部署完成的方案刷新由
            // KeyboardStateManager.handleEngineMessage 单点承担（清点
            // Q9 已合并双通道，避免同一 Finish 两条订阅各刷一遍）

            is EngineMessage.PossibleCandidatePinYin -> {
                window?.onPossibleCandidatePinYin(message.possibleCandidatePinYins)
            }

            is EngineMessage.DynamicPreedit -> {
                window?.updateDynamicPreedit(message.preedits)
            }

            else -> {}
        }
    }
}

/** Commit 提交记账决策（纯决策，可单测；I-M5）：只有真正落屏的提交才记数／入账／作废清字账。 */
internal data class CommitEffects(
    val noteStats: Boolean,
    val recordUndo: Boolean,
    val invalidateCleared: Boolean,
)

internal fun commitEffects(committed: Boolean, text: String): CommitEffects =
    CommitEffects(
        noteStats = committed,
        recordUndo = committed,
        invalidateCleared = committed && text.isNotEmpty(),
    )

/**
 * 成对提交的光标回退：按键路径（I-L5）。逐键发 DPAD_LEFT 的 DOWN／UP；
 * DOWN 返回 false（编辑器不响应方向键）即停，不再空发后续按键；
 * DOWN 成功即计一步（方向键位移发生在 DOWN），UP 只求配对、不计成败。
 * 返回实际走完的步数；调用方凭余步走 setSelection 兜底。
 */
internal fun moveCursorBackByKeys(ic: InputConnection, count: Int): Int {
    var moved = 0
    repeat(count) {
        val downOk = runCatching {
            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_LEFT))
        }.getOrDefault(false)
        if (!downOk) return moved
        runCatching {
            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_LEFT))
        }
        moved++
    }
    return moved
}

/**
 * 成对提交的光标回退：选区兜底（I-L5）。按键路径走不完时按 live
 * 选区起点直放光标；选区未折叠（提交后应无选区）或读不到选区时
 * 返回 false、调用方只记日志，不猜位置。
 */
internal fun moveCursorBackBySelection(ic: InputConnection, count: Int): Boolean {
    val extracted = runCatching {
        ic.getExtractedText(ExtractedTextRequest(), 0)
    }.getOrNull() ?: return false
    val start = extracted.selectionStart
    val end = extracted.selectionEnd
    if (start < 0 || start != end) return false
    val target = (start - count).coerceAtLeast(0)
    return runCatching { ic.setSelection(target, target) }.getOrDefault(false)
}
