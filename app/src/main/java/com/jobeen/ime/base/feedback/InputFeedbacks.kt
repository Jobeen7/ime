package com.jobeen.ime.base.feedback

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.Build
import android.os.VibrationEffect
import android.os.VibrationAttributes
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.view.View
import com.jobeen.ime.R
import com.jobeen.ime.data.manager.KeyboardManager
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
        ) {
            val context = view.context
            if (!KeyboardManager.Keyboard.Feedback.getVibrationEnabled(context)) return

            val followSystem = KeyboardManager.Keyboard.Feedback.getVibrationFollowSystem(context)

            // —— 合并方案 ——
            // Xime 的做法：跟随系统开关。读系统触感反馈总开关，关了就不振。
            if (followSystem && !isSystemHapticEnabled(context)) return

            val vibrator = getVibrator(context)
            if (vibrator == null || !vibrator.hasVibrator()) return

            val duration = if (longPress) longPressDuration else pressDuration
            val hasAmplitudeControl =
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && vibrator.hasAmplitudeControl()

            // 振幅决策（合并方案的核心）：
            // - 跟随系统：用 DEFAULT_AMPLITUDE + USAGE_TOUCH，强度完全交给系统触摸强度
            //   设置（如三星"振动强度→触摸互动"），框架自动缩放；
            // - 不跟随：用 App 内设置的强度（1~100 → 1~255）。
            // 两种情况都走 vibrator 直接触发——部分三星机型会在 HAL 层静默丢弃
            // performHapticFeedback 预设效果，直接 vibrate 才是可靠路径。
            val amplitude = if (followSystem) {
                VibrationEffect.DEFAULT_AMPLITUDE
            } else {
                val percent = KeyboardManager.Keyboard.Feedback.getVibrationAmplitude(context)
                ((percent / 100f) * 255).toInt().coerceIn(1, 255)
            }

            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    val effect = if (hasAmplitudeControl && amplitude != 0) {
                        VibrationEffect.createOneShot(duration, amplitude)
                    } else {
                        VibrationEffect.createOneShot(duration, VibrationEffect.DEFAULT_AMPLITUDE)
                    }
                    // API 33+：带 USAGE_TOUCH 属性，系统按触摸强度设置自动缩放振幅
                    if (Build.VERSION.SDK_INT >= 33) {
                        val attrs = VibrationAttributes.Builder()
                            .setUsage(VibrationAttributes.USAGE_TOUCH)
                            .build()
                        vibrator.vibrate(effect, attrs)
                    } else {
                        vibrator.vibrate(effect)
                    }
                } else {
                    @Suppress("DEPRECATION") vibrator.vibrate(duration)
                }

                Timber.d(
                    "haptic feedback success (longPress=$longPress, duration=$duration)"
                )
            } catch (e: Exception) {
                Timber.e(e, "haptic feedback failed")
            }
        }

        /** 读取系统触感反馈总开关（设置→声音和振动→触控反馈） */
        private fun isSystemHapticEnabled(context: Context): Boolean {
            return try {
                Settings.System.getInt(
                    context.contentResolver,
                    Settings.System.HAPTIC_FEEDBACK_ENABLED,
                    1
                ) == 1
            } catch (e: Exception) {
                Timber.w(e, "Failed to read system haptic setting, defaulting to enabled")
                true
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