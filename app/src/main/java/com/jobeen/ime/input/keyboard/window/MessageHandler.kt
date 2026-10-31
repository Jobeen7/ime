package com.jobeen.ime.input.keyboard.window

import android.inputmethodservice.InputMethodService
import android.view.KeyEvent
import com.jobeen.ime.engine.EngineFactory
import com.jobeen.ime.engine.data.EngineMessage
import com.jobeen.ime.engine.data.PairCommitQueue
import com.jobeen.ime.input.ImeInputMethodService
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
                // 成对提交的光标回退量：每次 Commit 处理必出队一次
                // （拦截态同样出队丢弃），正常提交出队得 0、无操作。
                val cursorBack = PairCommitQueue.poll()
                // 剪贴板搜索态：上屏文本改道进搜索查询，不写入目标应用
                if (window?.interceptCommit(message.text) == true) return
                // 性能埋点（2026-10-29 item-1 B）：提交终点，与
                // BaseKeyboard.onAction 的按键起点配对（拦截态不记）。
                com.jobeen.ime.skin.perf.PerfMonitor.noteCommit()
                // 输入统计（2026-10-29 item-1 C）：提交次数＋字数
                // （空提交不记；拦截态已在上方 return，不记）。
                com.jobeen.ime.input.stats.InputStats.noteCommit(message.text)
                val ic = (service as ImeInputMethodService).activeInputConnection()
                ic?.commitText(message.text, 1)
                if (cursorBack > 0 && ic != null) {
                    try {
                        repeat(cursorBack) {
                            ic.sendKeyEvent(
                                KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_LEFT),
                            )
                            ic.sendKeyEvent(
                                KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_LEFT),
                            )
                        }
                    } catch (e: Exception) {
                        DiagLog.e(
                            "SkinDiag", "pair_cursor_back_failed",
                            mapOf(
                                "cursorBack" to cursorBack.toString(),
                                "error" to e.javaClass.simpleName,
                            ),
                            e,
                        )
                    }
                }
                // 连发 Commit（如连打上屏）不必每条都同步探针：与选区路径
                // 共用 24ms 合并通道，窗口内只探最后一次（探针每次直探，
                // 无帧缓存可命中旧结果，见清点 S14）
                (service as ImeInputMethodService).scheduleNotifyInputChanged()
            }

            is EngineMessage.Candidates -> {
                // total=-1 表示首屏取满、总数未知（后面还有）；确切总数时
                // 整表按 list.size 与 total 比。追加页把 total 原样交给面板，
                // 由面板按合并后的总条数判定是否到底
                if (message.append) {
                    window?.appendCandidates(message.list, message.total)
                } else {
                    val hasMore = message.total < 0 || message.list.size < message.total
                    window?.setCandidates(message.list, hasMore, message.revision)
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
