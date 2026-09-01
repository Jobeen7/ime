package com.ninthsoft.ime.base.speech

import android.os.Bundle
import android.os.Message

/**
 * Message protocol between the main process ([SherpaSpeechClient]) and the
 * isolated `:speech` process ([SpeechRecognitionService]).
 */
object SpeechIpc {
    const val MSG_LOAD = 1
    const val MSG_START = 2
    const val MSG_STOP = 3

    const val MSG_RECORDING_STARTED = 10
    const val MSG_PARTIAL = 11
    const val MSG_FINAL = 12
    const val MSG_AMPLITUDE = 13
    const val MSG_ERROR = 14
    const val MSG_DONE = 15

    const val KEY_TEXT = "text"
    const val KEY_AMPLITUDE = "amplitude"

    fun message(what: Int, text: String? = null, amplitude: Float = 0f): Message {
        val msg = Message.obtain(null, what)
        if (text != null) {
            msg.data = Bundle().apply { putString(KEY_TEXT, text) }
        } else if (what == MSG_AMPLITUDE) {
            msg.data = Bundle().apply { putFloat(KEY_AMPLITUDE, amplitude) }
        }
        return msg
    }
}
