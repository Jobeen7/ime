package com.jobeen.ime.base.feedback

import android.media.AudioManager
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 铃声模式门（全部按键音）：仅响铃模式发声。 */
class RingerGateTest {

    @Test
    fun normalModePlays() {
        assertTrue(RingerGate.shouldPlay(AudioManager.RINGER_MODE_NORMAL))
    }

    @Test
    fun vibrateAndSilentModesMute() {
        assertFalse(RingerGate.shouldPlay(AudioManager.RINGER_MODE_VIBRATE))
        assertFalse(RingerGate.shouldPlay(AudioManager.RINGER_MODE_SILENT))
    }
}
