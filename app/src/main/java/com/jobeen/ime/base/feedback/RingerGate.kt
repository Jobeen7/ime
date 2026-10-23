package com.jobeen.ime.base.feedback

import android.content.Context
import android.media.AudioManager

/**
 * 铃声模式门（皮肤 v3 裁定：随 Batch 1 落地，作用于全部按键音）：
 * 仅铃声模式为「响铃」（RINGER_MODE_NORMAL）时发声；静音/振动
 * 模式下按键音停发，与系统及主流输入法口径一致。取不到
 * AudioManager 时放行（保旧行为，不因异常静默）。
 */
object RingerGate {

    /** 纯判定（可单测）：响铃模式才发声。 */
    fun shouldPlay(ringerMode: Int): Boolean =
        ringerMode == AudioManager.RINGER_MODE_NORMAL

    fun shouldPlay(context: Context): Boolean {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            ?: return true
        return shouldPlay(audioManager.ringerMode)
    }
}
