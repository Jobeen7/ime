package com.jobeen.ime.input.keyboard.window

import android.inputmethodservice.InputMethodService
import com.jobeen.ime.engine.EngineFactory
import com.jobeen.ime.engine.data.EngineMessage
import com.jobeen.ime.input.ImeInputMethodService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber

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
                // 剪贴板搜索态：上屏文本改道进搜索查询，不写入目标应用
                if (window?.interceptCommit(message.text) == true) return
                (service as ImeInputMethodService).activeInputConnection()
                    ?.commitText(message.text, 1)
                service.notifyInputChanged()
            }

            is EngineMessage.Candidates -> {
                // total=-1 表示首屏取满、总数未知（后面还有）；确切总数时
                // 整表按 list.size 与 total 比。追加页把 total 原样交给面板，
                // 由面板按合并后的总条数判定是否到底
                if (message.append) {
                    window?.appendCandidates(message.list, message.total)
                } else {
                    val hasMore = message.total < 0 || message.list.size < message.total
                    window?.setCandidates(message.list, hasMore)
                }
            }

            is EngineMessage.Depoly -> {
                Timber.d("EngineMessage.Depoly")
                if (message.state == EngineMessage.Depoly.State.Finish) {
                    window?.onDepolyFinished()
                }
            }

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
