package com.jobeen.ime.base.speech

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

    /**
     * 会话代际令牌：客户端每次 startHoldSession 生成新代次并随 START 发给服务端，
     * 服务端在该会话的全部回信（STARTED/PARTIAL/FINAL/ERROR/DONE）里原样带回。
     * 客户端只接受当前代次的回信——旧会话迟到的结果不能污染新会话。
     */
    const val KEY_GEN = "gen"

    fun message(what: Int, text: String? = null, amplitude: Float = 0f, gen: Int = 0): Message {
        val msg = Message.obtain(null, what)
        val bundle = Bundle()
        var hasData = false
        if (text != null) {
            bundle.putString(KEY_TEXT, text)
            hasData = true
        } else if (what == MSG_AMPLITUDE) {
            bundle.putFloat(KEY_AMPLITUDE, amplitude)
            hasData = true
        }
        if (gen != 0) {
            bundle.putInt(KEY_GEN, gen)
            hasData = true
        }
        if (hasData) msg.data = bundle
        return msg
    }
}
