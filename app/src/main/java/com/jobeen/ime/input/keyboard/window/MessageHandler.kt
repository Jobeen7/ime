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
                (service as ImeInputMethodService).activeInputConnection()
                    ?.commitText(message.text, 1)
                service.notifyInputChanged()
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
