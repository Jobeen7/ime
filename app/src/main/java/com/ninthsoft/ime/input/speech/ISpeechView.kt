package com.ninthsoft.ime.input.speech

import android.view.View
import androidx.annotation.ColorInt

interface ISpeechView {
    val view: View
        get() = this as View

    fun updateColors(
        @ColorInt backgroundColor: Int,
        @ColorInt waveformColor: Int,
        @ColorInt barColor: Int = waveformColor
    )

    fun setWaveformColor(@ColorInt color: Int)

    fun setVolume(volume: Int)

    fun startAnim()

    fun stopAnim()

    fun onWindowFocusChanged(hasWindowFocus: Boolean)

    fun release()
}
