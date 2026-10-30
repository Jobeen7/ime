package com.jobeen.ime.base.feedback

import android.content.Context
import android.content.SharedPreferences
import android.database.ContentObserver
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.VibrationAttributes
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.view.View
import com.jobeen.ime.R
import com.jobeen.ime.data.manager.KeyboardManager
import com.jobeen.ime.skin.diag.DiagLog
import java.io.File
import timber.log.Timber

class InputFeedbacks private constructor() {
    enum class SoundEffect {
        Standard,
    }

    companion object {
        // 池状态跨线程读写：load 回调线程写 isPopLoaded、按键线
        // 程读三者发声、服务销毁线程经 release() 整体置空——补
        // @Volatile 保证释放/加载结果对按键侧守卫的可见性
        @Volatile private var soundPool: SoundPool? = null
        @Volatile private var popSoundId: Int = 0
        @Volatile private var isPopLoaded = false
        private val lock = Any()

        // ---- 皮肤音效注册表（B2 详设 §7.2）：快照切换期由
        // SkinSnapshotProvider 安装/卸载；load 异步，就绪前播
        // 出厂 pop（不进应用事务状态机，裁定 §10.1-8） ----
        @Volatile private var skinSounds: Map<String, Int> = emptyMap()
        @Volatile private var skinSoundsLoaded: Set<String> = emptySet()
        /** soundId → 资产 id（load 完成回调反查用，只在 lock 内读写）。 */
        private val pendingSkinLoads = mutableMapOf<Int, String>()
        /** 自定义音效池状态：路径变化时惰性换装，load 异步、就绪前播出厂音 */
        @Volatile private var customSoundId: Int = 0
        @Volatile private var customSoundLoadedPath: String? = null
        private var pendingCustomSampleId: Int = 0
        private var pendingCustomPath: String? = null
        private const val VIBRATION_ATTRIBUTION_TAG = "keyboard_feedback"

        // ---- 反馈设置缓存：避免每次按键重复读 SP / Binder IPC ----
        // 失效机制：App 内设置经 SharedPreferences 监听器刷新；
        // 系统触感总开关经 ContentObserver 刷新（回调在 binder 线程，只写 @Volatile 字段）
        @Volatile private var cachedVibrationEnabled: Boolean? = null
        @Volatile private var cachedFollowSystem: Boolean? = null
        @Volatile private var cachedAmplitude: Int? = null
        @Volatile private var cachedSystemHaptic: Boolean? = null
        @Volatile private var cachedSoundEnabled: Boolean? = null
        @Volatile private var cachedSoundVolume: Int? = null
        @Volatile private var cachedCustomSoundEnabled: Boolean? = null
        @Volatile private var cachedCustomSoundPath: String? = null
        @Volatile private var cachedVibrationDurationMs: Int? = null
        @Volatile private var cachedReleaseVibrationEnabled: Boolean? = null
        @Volatile private var cachedVibrator: Vibrator? = null
        private var appContextRef: Context? = null
        private var feedbackPrefs: SharedPreferences? = null
        private var hapticObserver: ContentObserver? = null

        private val feedbackPrefsListener =
            SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
                // clear() 以 key == null 回调（重置清空偏好时）：此时重读反馈域，
                // 否则已缓存的旧反馈值会在服务存活期间继续生效。
                // refreshFeedbackCache 只读反馈域四项＋系统触感开关＋振动器，
                // 不碰其他偏好域；非反馈域单键回调仍忽略，不变全量重读。
                if (key == null || key.startsWith("keyboard.feedback.")) {
                    appContextRef?.let { refreshFeedbackCache(it) }
                }
            }

        /** 在 ImeInputMethodService.onCreate 调用；幂等，可重复调用 */
        fun initFeedbackCache(context: Context) {
            if (feedbackPrefs != null) return
            val app = context.applicationContext
            appContextRef = app
            feedbackPrefs = app.getSharedPreferences(
                KeyboardManager.PREFS_NAME, Context.MODE_PRIVATE
            ).also { it.registerOnSharedPreferenceChangeListener(feedbackPrefsListener) }
            refreshFeedbackCache(app)
            hapticObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
                override fun onChange(selfChange: Boolean) {
                    cachedSystemHaptic = readSystemHapticEnabled(app)
                }
            }.also {
                app.contentResolver.registerContentObserver(
                    Settings.System.getUriFor(Settings.System.HAPTIC_FEEDBACK_ENABLED),
                    false, it
                )
            }
        }

        /**
         * 在 ImeInputMethodService.onDestroy 调用：反馈资源的统一
         * 销毁口——注销监听、清缓存，并连同 SoundPool 一并释放
         * （Jime-2 报告 2.4：此前 release() 零调用、池无释放路径）。
         * 服务在同进程内重建后，池由 soundEffect 的惰性重建按需拉
         * 起，不依赖 AppStartup 的一次性初始化步骤重跑。
         */
        fun releaseFeedbackCache(context: Context) {
            feedbackPrefs?.unregisterOnSharedPreferenceChangeListener(feedbackPrefsListener)
            feedbackPrefs = null
            hapticObserver?.let { context.contentResolver.unregisterContentObserver(it) }
            hapticObserver = null
            appContextRef = null
            cachedVibrationEnabled = null
            cachedFollowSystem = null
            cachedAmplitude = null
            cachedSystemHaptic = null
            cachedSoundEnabled = null
            cachedSoundVolume = null
            cachedCustomSoundEnabled = null
            cachedCustomSoundPath = null
            cachedVibrationDurationMs = null
            cachedReleaseVibrationEnabled = null
            cachedVibrator = null
            release()
        }

        private fun refreshFeedbackCache(app: Context) {
            cachedVibrationEnabled = KeyboardManager.Keyboard.Feedback.getVibrationEnabled(app)
            cachedFollowSystem = KeyboardManager.Keyboard.Feedback.getVibrationFollowSystem(app)
            cachedAmplitude = KeyboardManager.Keyboard.Feedback.getVibrationAmplitude(app)
            cachedSoundEnabled = KeyboardManager.Keyboard.Feedback.getSoundEnabled(app)
            cachedSoundVolume = KeyboardManager.Keyboard.Feedback.getSoundVolume(app)
            cachedCustomSoundEnabled = KeyboardManager.Keyboard.Feedback.isCustomSoundEnabled(app)
            cachedCustomSoundPath = KeyboardManager.Keyboard.Feedback.getCustomSoundPath(app)
            cachedVibrationDurationMs = KeyboardManager.Keyboard.Feedback.getVibrationDurationMs(app)
            cachedReleaseVibrationEnabled =
                KeyboardManager.Keyboard.Feedback.isReleaseVibrationEnabled(app)
            cachedSystemHaptic = readSystemHapticEnabled(app)
            cachedVibrator = createVibrator(app)
        }

        private fun ensureFeedbackCache(context: Context) {
            if (cachedVibrationEnabled == null) {
                synchronized(lock) {
                    if (cachedVibrationEnabled == null) {
                        // 兜底：service 未创建时（如设置页试听）也初始化并注册监听器
                        initFeedbackCache(context)
                    }
                }
            }
        }

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
                        } else if (status != 0) {
                            Timber.e("Sound load failed with status: $status")
                            synchronized(lock) {
                                if (sampleId == pendingCustomSampleId) {
                                    pendingCustomSampleId = 0
                                    pendingCustomPath = null
                                }
                            }
                        }
                        if (status == 0) {
                            val assetId = synchronized(lock) {
                                pendingSkinLoads.remove(sampleId)
                            }
                            if (assetId != null) {
                                skinSoundsLoaded = skinSoundsLoaded + assetId
                            }
                            synchronized(lock) {
                                if (sampleId == pendingCustomSampleId && pendingCustomPath != null) {
                                    customSoundLoadedPath = pendingCustomPath
                                    pendingCustomSampleId = 0
                                    pendingCustomPath = null
                                }
                            }
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
            ensureFeedbackCache(context)
            if (cachedSoundEnabled != true) return
            // 服务销毁已释放池时惰性重建：AppStartup 的初始化步
            // 骤是进程内一次性的，服务在同进程重建不会再跑，靠这
            // 里按需拉起（load 异步，本次按键可能无声、与冷启动
            // 一致）；池未释放时该检查只是一次空判
            if (soundPool == null) initSoundPool(context)
            when (effect) {
                SoundEffect.Standard -> {
                    ensureCustomSoundLoaded()
                    val vol = (cachedSoundVolume ?: 100) / 100f
                    val customId = customSoundId
                    if (cachedCustomSoundEnabled == true && customId != 0 &&
                        customSoundLoadedPath != null
                    ) {
                        soundPool?.play(customId, vol, vol, 1, 0, 1.0f)
                    } else if (isPopLoaded && popSoundId != 0) {
                        soundPool?.play(popSoundId, vol, vol, 1, 0, 1.0f)
                    }
                }
            }
        }

        /** 设置页试听：与按键音同闸（开关＋铃声模式门），所听即按键所发 */
        fun previewSoundEffect(context: Context) {
            soundEffect(context, SoundEffect.Standard)
        }

        /**
         * 自定义音效惰性换装：开关/路径由缓存驱动（偏好监听器刷新）。
         * 路径变化时卸载旧音、加载新音（load 异步，本次按键先播出厂音）；
         * 开关关闭或路径清空时卸载。
         */
        private fun ensureCustomSoundLoaded() {
            val pool = soundPool ?: return
            val enabled = cachedCustomSoundEnabled == true
            val path = cachedCustomSoundPath?.takeIf { it.isNotEmpty() }
            synchronized(lock) {
                if (!enabled || path == null) {
                    if (customSoundId != 0) {
                        runCatching { pool.unload(customSoundId) }
                        customSoundId = 0
                    }
                    customSoundLoadedPath = null
                    pendingCustomSampleId = 0
                    pendingCustomPath = null
                    return
                }
                if (customSoundLoadedPath == path || pendingCustomPath == path) return
                if (customSoundId != 0) runCatching { pool.unload(customSoundId) }
                customSoundId = 0
                customSoundLoadedPath = null
                val sampleId = runCatching { pool.load(path, 1) }.getOrDefault(0)
                if (sampleId != 0) {
                    pendingCustomSampleId = sampleId
                    pendingCustomPath = path
                }
            }
        }

        /**
         * 安装一版快照的皮肤音效（旧版整表卸载）：快照切换期由
         * 装配层调用（详设 §7.2-1）。load 异步进行，完成前该键
         * 播出厂 pop（见 [skinSoundEffect]）。
         */
        fun installSkinSounds(context: Context, files: Map<String, File>) {
            if (soundPool == null) initSoundPool(context)
            synchronized(lock) {
                val pool = soundPool
                for ((_, soundId) in skinSounds) {
                    runCatching { pool?.unload(soundId) }
                }
                skinSounds = emptyMap()
                skinSoundsLoaded = emptySet()
                pendingSkinLoads.clear()
                if (pool == null || files.isEmpty()) return
                val loaded = mutableMapOf<String, Int>()
                for ((assetId, file) in files) {
                    val soundId = runCatching { pool.load(file.absolutePath, 1) }
                        .getOrDefault(0)
                    if (soundId != 0) {
                        loaded[assetId] = soundId
                        pendingSkinLoads[soundId] = assetId
                    }
                }
                skinSounds = loaded
            }
        }

        /** 卸载全部皮肤音效（回内置默认/失效时）。 */
        fun clearSkinSounds() {
            synchronized(lock) {
                for ((_, soundId) in skinSounds) {
                    runCatching { soundPool?.unload(soundId) }
                }
                skinSounds = emptyMap()
                skinSoundsLoaded = emptySet()
                pendingSkinLoads.clear()
            }
        }

        /**
         * 逐键皮肤音（按键 DOWN 触发）：与 [soundEffect] 同闸
         * （App 开关 + 铃声模式门，B1 总闸零新增判定）；资产未
         * 就绪（load 未完成/未安装）时先播出厂 pop 并记一条
         * DEBUG，就绪后自动生效（详设 §7.2-2）。
         */
        fun skinSoundEffect(context: Context, assetId: String) {
            ensureFeedbackCache(context)
            if (cachedSoundEnabled != true) return
            if (!RingerGate.shouldPlay(context)) return
            if (soundPool == null) initSoundPool(context)
            val vol = (cachedSoundVolume ?: 100) / 100f
            val soundId = skinSounds[assetId]
            if (soundId != null && assetId in skinSoundsLoaded) {
                soundPool?.play(soundId, vol, vol, 1, 0, 1.0f)
            } else {
                DiagLog.d(
                    "audio", "sound_load_pending_fallback",
                    mapOf("assetId" to assetId),
                )
                // 皮肤音未就绪：自定义音效次之，出厂音最后
                ensureCustomSoundLoaded()
                val customId = customSoundId
                if (cachedCustomSoundEnabled == true && customId != 0 &&
                    customSoundLoadedPath != null
                ) {
                    soundPool?.play(customId, vol, vol, 1, 0, 1.0f)
                } else if (isPopLoaded && popSoundId != 0) {
                    soundPool?.play(popSoundId, vol, vol, 1, 0, 1.0f)
                }
            }
        }

        fun release() {
            synchronized(lock) {
                soundPool?.release()
                soundPool = null
                popSoundId = 0
                isPopLoaded = false
                skinSounds = emptyMap()
                skinSoundsLoaded = emptySet()
                pendingSkinLoads.clear()
                customSoundId = 0
                customSoundLoadedPath = null
                pendingCustomSampleId = 0
                pendingCustomPath = null
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
            ensureFeedbackCache(context)
            if (cachedVibrationEnabled != true) return

            val followSystem = cachedFollowSystem == true

            // 跟随系统开关：读缓存的系统触感反馈总开关（ContentObserver 实时更新）
            if (followSystem && cachedSystemHaptic != true) return

            val vibrator = cachedVibrator
            if (vibrator == null || !vibrator.hasVibrator()) return

            val duration = if (longPress) longPressDuration else pressDuration
            val hasAmplitudeControl =
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && vibrator.hasAmplitudeControl()

            // 振幅决策：
            // - 跟随系统：用 DEFAULT_AMPLITUDE + USAGE_TOUCH，强度完全交给系统触摸强度
            //   设置（如三星"振动强度→触摸互动"），框架自动缩放；
            // - 不跟随：用 App 内设置的强度（1~100 → 1~255）。
            // 两种情况都走 vibrator 直接触发——部分三星机型会在 HAL 层静默丢弃
            // performHapticFeedback 预设效果，直接 vibrate 才是可靠路径。
            val amplitude = if (followSystem) {
                VibrationEffect.DEFAULT_AMPLITUDE
            } else {
                val percent = cachedAmplitude ?: 100
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
        private fun readSystemHapticEnabled(context: Context): Boolean {
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

        private fun createVibrator(context: Context): Vibrator? {
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
            ensureFeedbackCache(view.context)
            hapticFeedback(
                view, longPress, keyUp = false,
                pressDuration = cachedVibrationDurationMs?.toLong() ?: 15L,
            )
        }

        fun hapticFeedback(view: View) {
            ensureFeedbackCache(view.context)
            hapticFeedback(
                view, false, keyUp = false,
                pressDuration = cachedVibrationDurationMs?.toLong() ?: 15L,
            )
        }

        /**
         * 松开振动：仅按键面抬起时调用，开关关时静默；其余闸口
         * （总开关／跟随系统／振动器）与按压共用主函数。
         */
        fun hapticFeedbackRelease(view: View) {
            ensureFeedbackCache(view.context)
            if (cachedReleaseVibrationEnabled != true) return
            hapticFeedback(
                view, false, keyUp = true,
                pressDuration = cachedVibrationDurationMs?.toLong() ?: 15L,
            )
        }
    }
}