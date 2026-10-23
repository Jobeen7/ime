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

    /** 存活探针（客户端→服务端）：服务端收到立即回 MSG_PONG，不碰引擎与会话状态 */
    const val MSG_PING = 4

    /** 音频块（客户端→服务端）：录音归客户端，PCM 块随消息推给服务端解码（载荷见 KEY_AUDIO） */
    const val MSG_AUDIO = 5

    /** 丢弃会话（客户端→服务端）：服务端丢弃排队音频、不出 FINAL，只回 MSG_DONE */
    const val MSG_CANCEL = 6

    /** 推流会话已就绪（服务端→客户端）：队列与解码已就位，可以开始推音频（音频先行后不再等服务端开录） */
    const val MSG_RECORDING_STARTED = 10
    const val MSG_PARTIAL = 11
    const val MSG_FINAL = 12
    const val MSG_ERROR = 14
    const val MSG_DONE = 15

    /** 存活探针回信（服务端→客户端）：原样带回 MSG_PING 的代次 */
    const val MSG_PONG = 16

    const val KEY_TEXT = "text"

    /** MSG_AUDIO 的音频载荷：PCM16LE 单声道原始块 */
    const val KEY_AUDIO = "audio"

    // 推流音频格式（客户端录音与服务端解码共用同一口径）：
    // 16kHz、每块 640 采样（40ms）= 1280 字节
    const val SAMPLE_RATE = 16000
    const val CHUNK_SAMPLES = 640
    const val CHUNK_BYTES = CHUNK_SAMPLES * 2

    /**
     * 会话代际令牌：客户端每次 startHoldSession 生成新代次并随 START 发给服务端，
     * 服务端在该会话的全部回信（STARTED/PARTIAL/FINAL/ERROR/DONE）里原样带回。
     * 客户端只接受当前代次的回信——旧会话迟到的结果不能污染新会话。
     * 反向同理：START/STOP/CANCEL/AUDIO 一律带代次，服务端对非零且与当前
     * 会话不符的直接忽略（0 视为当前会话，仅作防御），旧会话迟到的指令
     * 与音频块不能污染新会话。
     */
    const val KEY_GEN = "gen"

    fun message(
        what: Int,
        text: String? = null,
        gen: Int = 0,
        audio: ByteArray? = null,
    ): Message {
        val msg = Message.obtain(null, what)
        val bundle = Bundle()
        var hasData = false
        if (text != null) {
            bundle.putString(KEY_TEXT, text)
            hasData = true
        }
        if (gen != 0) {
            bundle.putInt(KEY_GEN, gen)
            hasData = true
        }
        if (audio != null) {
            bundle.putByteArray(KEY_AUDIO, audio)
            hasData = true
        }
        if (hasData) msg.data = bundle
        return msg
    }
}
