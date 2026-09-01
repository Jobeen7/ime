package com.ninthsoft.ime.base.feedback

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.HapticFeedbackConstants
import android.view.View
import com.ninthsoft.ime.R
import com.ninthsoft.ime.data.manager.KeyboardManager
import timber.log.Timber

class InputFeedbacks private constructor() {
    enum class SoundEffect {
        Standard,
    }

    companion object {
        private var soundPool: SoundPool? = null
        private var popSoundId: Int = 0
        private var isPopLoaded = false
        private val lock = Any()
        private const val VIBRATION_ATTRIBUTION_TAG = "keyboard_feedback"

        fun initSoundPool(context: Context) {
            if (soundPool != null && isPopLoaded) return
            synchronized(lock) {
                if (soundPool != null) return
                try {
                    val audioAttributes =
                        AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()
                    val pool =
                        SoundPool.Builder().setMaxStreams(5).setAudioAttributes(audioAttributes)
                            .build()
                    pool.setOnLoadCompleteListener { _, sampleId, status ->
                        if (status == 0 && sampleId == popSoundId) {
                            isPopLoaded = true
                            Timber.d("Pop sound loaded successfully.")
                        } else {
                            Timber.e("Pop sound load failed with status: $status")
                        }
                    }
                    val appContext = context.applicationContext
                    popSoundId = pool.load(appContext, R.raw.pop, 1)
                    soundPool = pool
                } catch (e: Exception) {
                    Timber.e(e, "Failed to initialize SoundPool")
                }
            }
        }

        fun soundEffect(context: Context, effect: SoundEffect) {
            if (!KeyboardManager.Keyboard.Feedback.getSoundEnabled(context)) return
            when (effect) {
                SoundEffect.Standard -> {
                    if (isPopLoaded && popSoundId != 0) {
                        soundPool?.play(popSoundId, 1.0f, 1.0f, 1, 0, 1.0f)
                    }
                }
            }
        }

        fun release() {
            synchronized(lock) {
                soundPool?.release()
                soundPool = null
                popSoundId = 0
                isPopLoaded = false
            }
        }

        fun hapticFeedback(
            view: View,
            longPress: Boolean = false,
            keyUp: Boolean = false,
            pressDuration: Long = 15L,
            longPressDuration: Long = 30L,
            pressAmplitude: Int = 255,
            longPressAmplitude: Int = 255
        ) {
            val context = view.context
            if (!KeyboardManager.Keyboard.Feedback.getVibrationEnabled(context)) return

            val vibrator = getVibrator(context)
            if (vibrator == null || !vibrator.hasVibrator()) return

            val duration = if (longPress) longPressDuration else pressDuration
            val amplitude = if (longPress) longPressAmplitude else pressAmplitude
            val hasAmplitudeControl =
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && vibrator.hasAmplitudeControl()

            try {
                if (duration != 0L) {
                    if (hasAmplitudeControl && amplitude != 0) {
                        vibrator.vibrate(
                            VibrationEffect.createOneShot(duration, amplitude)
                        )
                    } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        vibrator.vibrate(
                            VibrationEffect.createOneShot(
                                duration, VibrationEffect.DEFAULT_AMPLITUDE
                            )
                        )
                    } else {
                        @Suppress("DEPRECATION") vibrator.vibrate(duration)
                    }
                } else {
                    val hfc = if (longPress) {
                        HapticFeedbackConstants.LONG_PRESS
                    } else if (keyUp && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                        HapticFeedbackConstants.KEYBOARD_RELEASE
                    } else {
                        HapticFeedbackConstants.KEYBOARD_TAP
                    }

                    val flags = HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                        view.performHapticFeedback(hfc, flags)
                    } else {
                        @Suppress("DEPRECATION") view.performHapticFeedback(hfc)
                    }
                }

                Timber.d(
                    "haptic feedback success (longPress=$longPress, duration=$duration)"
                )
            } catch (e: Exception) {
                Timber.e(e, "haptic feedback failed")
            }
        }

        private fun getVibrator(context: Context): Vibrator? {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val attributionContext = context.createAttributionContext(
                    VIBRATION_ATTRIBUTION_TAG
                )
                val vibratorManager = attributionContext.getSystemService(
                    Context.VIBRATOR_MANAGER_SERVICE
                ) as? VibratorManager
                return vibratorManager?.defaultVibrator
            }

            @Suppress("DEPRECATION") return context.getSystemService(
                Context.VIBRATOR_SERVICE
            ) as? Vibrator
        }

        fun hapticFeedback(view: View, longPress: Boolean) {
            hapticFeedback(view, longPress, keyUp = false)
        }

        fun hapticFeedback(view: View) {
            hapticFeedback(view, false, keyUp = false)
        }
    }
}