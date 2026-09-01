package com.ninthsoft.ime.input.keyboard.window

import android.inputmethodservice.InputMethodService
import com.ninthsoft.ime.engine.EngineFactory
import com.ninthsoft.ime.engine.data.EngineMessage
import com.ninthsoft.ime.input.ImeInputMethodService
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
                (service as ImeInputMethodService).activeInputConnection()
                    ?.commitText(message.text, 1)
            }

            is EngineMessage.Candidates -> {
                window?.setCandidates(message.list)
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
