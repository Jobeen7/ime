package com.jobeen.ime.data.manager

import android.content.Context
import androidx.core.content.edit
import com.jobeen.ime.data.keyboard.theme.KeyboardTheme

object KeyboardManager {
    const val PREFS_NAME = "keyboard_settings"
    private const val DEFAULT_KEYBOARD_HEIGHT = 24
    private const val DEFAULT_KEYBOARD_HEIGHT_LANDSCAPE = 44
    private const val DEFAULT_PADDING_DP = 4

    object Theme {
        private const val PREFIX = "theme"
        const val MODE_SYSTEM = 0
        const val MODE_LIGHT = 1
        const val MODE_DARK = 2

        /**
         * 应用主题已删除（恒跟随系统）：旧手动值（LIGHT/DARK）在此
         * 一次性迁回跟随系统，幂等。迁移落在本共享读入口而非某
         * 个 Activity：全部页面都经 getMode 读模式，直启的其它页
         * （任务恢复、键盘内直启等，不经过 MainActivity）首次读取
         * 即已迁完，不再短暂沿用旧模式。
         */
        fun migrateToSystem(context: Context) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            if (prefs.getInt("$PREFIX.mode", MODE_SYSTEM) != MODE_SYSTEM) {
                setMode(context, MODE_SYSTEM)
            }
        }

        fun getMode(context: Context): Int {
            migrateToSystem(context)
            return MODE_SYSTEM
        }

        fun setMode(context: Context, mode: Int) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
                putInt("$PREFIX.mode", mode)
            }
        }
    }

    object Keyboard {
        private const val PREFIX = "keyboard"
        const val KEY_HEIGHT = "$PREFIX.height"
        const val KEY_HEIGHT_LANDSCAPE = "$PREFIX.height_landscape"
        const val KEY_IGNORE_INSETS = "$PREFIX.ignore_insets"
        const val KEY_THEME = "$PREFIX.theme"
        const val KEY_FOLLOW_SYSTEM = "$PREFIX.follow_system"
        const val KEY_LIGHT_THEME = "$PREFIX.light_theme"
        const val KEY_DARK_THEME = "$PREFIX.dark_theme"

        fun getHeightPercent(context: Context): Int {
            return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getInt("$PREFIX.height", DEFAULT_KEYBOARD_HEIGHT)
        }

        fun setHeightPercent(context: Context, percent: Int) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
                putInt("$PREFIX.height", percent)
            }
        }

        fun getHeightPercentLandscape(context: Context): Int {
            return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getInt("$PREFIX.height_landscape", DEFAULT_KEYBOARD_HEIGHT_LANDSCAPE)
        }

        fun setHeightPercentLandscape(context: Context, percent: Int) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
                putInt("$PREFIX.height_landscape", percent)
            }
        }

        fun getIgnoreInsets(context: Context): Boolean {
            return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean("$PREFIX.ignore_insets", false)
        }

        fun setIgnoreInsets(context: Context, ignore: Boolean) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
                putBoolean("$PREFIX.ignore_insets", ignore)
            }
        }

        fun getFollowSystem(context: Context): Boolean {
            migrateRemovedBuiltin(context)
            // 新装无存值时默认跟随系统（已存 true/false 的用户走存值，不受影响）。
            return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_FOLLOW_SYSTEM, true)
        }

        fun setFollowSystem(context: Context, followSystem: Boolean) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
                putBoolean(KEY_FOLLOW_SYSTEM, followSystem)
            }
        }

        fun getThemeId(context: Context): String {
            migrateRemovedBuiltin(context)
            return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString("$PREFIX.theme", KeyboardTheme.DEFAULT_ID) ?: KeyboardTheme.DEFAULT_ID
        }

        fun setThemeId(context: Context, themeId: String) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
                putString("$PREFIX.theme", themeId)
            }
        }

        fun getLightThemeId(context: Context): String {
            migrateRemovedBuiltin(context)
            return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(KEY_LIGHT_THEME, KeyboardTheme.LIGHT_DEFAULT_ID)
                    ?: KeyboardTheme.LIGHT_DEFAULT_ID
        }

        fun setLightThemeId(context: Context, themeId: String) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
                putString(KEY_LIGHT_THEME, themeId)
            }
        }

        fun getDarkThemeId(context: Context): String {
            migrateRemovedBuiltin(context)
            return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(KEY_DARK_THEME, KeyboardTheme.DARK_DEFAULT_ID)
                    ?: KeyboardTheme.DARK_DEFAULT_ID
        }

        fun setDarkThemeId(context: Context, themeId: String) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
                putString(KEY_DARK_THEME, themeId)
            }
        }

        /**
         * 已删内置主题迁移（2026-10-29 批量第 6 项：落日删除）：
         * 幂等、仅在存有已删 id 时写回。主主题 id 为落日 → 改跟随
         * 开、主主题复位暗夜（主主题也必须复位，否则日后关跟随时
         * 会经 byId 复活已删主题）；浅/深槽位为落日 → 只复位该槽位。
         * 从不覆盖有效的非落日槽位（陈旧落日主主题＋现成跟随对不
         * 应被改写）。非跟随的暗夜/素白用户不强制迁移（存值仍可
         * 解析，库页默认卡对其显示生效中）。四个读入口各调一次：
         * 库页与 KeyboardColors.resolve 经此读入口统一覆盖，服务与
         * 界面行为一致；无落日存值时仅几次读。
         */
        private fun migrateRemovedBuiltin(context: Context) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val themeId = prefs.getString("$PREFIX.theme", KeyboardTheme.DEFAULT_ID)
            val lightId = prefs.getString(KEY_LIGHT_THEME, KeyboardTheme.LIGHT_DEFAULT_ID)
            val darkId = prefs.getString(KEY_DARK_THEME, KeyboardTheme.DARK_DEFAULT_ID)
            if (themeId != "sunset" && lightId != "sunset" && darkId != "sunset") return
            prefs.edit {
                if (themeId == "sunset") {
                    putBoolean(KEY_FOLLOW_SYSTEM, true)
                    putString("$PREFIX.theme", KeyboardTheme.DARK_DEFAULT_ID)
                }
                if (lightId == "sunset") {
                    putString(KEY_LIGHT_THEME, KeyboardTheme.LIGHT_DEFAULT_ID)
                }
                if (darkId == "sunset") {
                    putString(KEY_DARK_THEME, KeyboardTheme.DARK_DEFAULT_ID)
                }
            }
        }

        object Padding {
            private const val PREFIX = "keyboard.padding"
            const val KEY_HORIZONTAL = "$PREFIX.horizontal"
            const val KEY_BOTTOM = "$PREFIX.bottom"

            fun getHorizontalDp(context: Context): Int {
                return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .getInt("$PREFIX.horizontal", DEFAULT_PADDING_DP)
            }

            fun setHorizontalDp(context: Context, dp: Int) {
                context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
                    putInt("$PREFIX.horizontal", dp)
                }
            }

            fun getBottomDp(context: Context): Int {
                return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .getInt("$PREFIX.bottom", DEFAULT_PADDING_DP)
            }

            fun setBottomDp(context: Context, dp: Int) {
                context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
                    putInt("$PREFIX.bottom", dp)
                }
            }
        }

        object Feedback {
            private const val PREFIX = "keyboard.feedback"

            fun getVibrationEnabled(context: Context): Boolean {
                return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .getBoolean("$PREFIX.vibration", true)
            }

            fun setVibrationEnabled(context: Context, enabled: Boolean) {
                context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
                    putBoolean("$PREFIX.vibration", enabled)
                }
            }

            /** 按键振动是否跟随系统振动设置（默认开启） */
            fun getVibrationFollowSystem(context: Context): Boolean {
                return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .getBoolean("$PREFIX.vibration_follow_system", true)
            }

            fun setVibrationFollowSystem(context: Context, enabled: Boolean) {
                context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
                    putBoolean("$PREFIX.vibration_follow_system", enabled)
                }
            }

            /**
             * 不跟随系统时的振动强度（1~100），默认 100。
             * 跟随系统开启时强度由系统触摸强度设置决定，此值不生效。
             */
            fun getVibrationAmplitude(context: Context): Int {
                return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .getInt("$PREFIX.vibration_amplitude", 100).coerceIn(1, 100)
            }

            fun setVibrationAmplitude(context: Context, percent: Int) {
                context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
                    putInt("$PREFIX.vibration_amplitude", percent.coerceIn(1, 100))
                }
            }

            fun getSoundEnabled(context: Context): Boolean {
                return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .getBoolean("$PREFIX.sound", true)
            }

            fun setSoundEnabled(context: Context, enabled: Boolean) {
                context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
                    putBoolean("$PREFIX.sound", enabled)
                }
            }

            /** 按键音量（0~100%，默认 100）：作用于出厂音与自定义音效 */
            fun getSoundVolume(context: Context): Int {
                return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .getInt("$PREFIX.sound_volume", 100).coerceIn(0, 100)
            }

            fun setSoundVolume(context: Context, percent: Int) {
                context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
                    putInt("$PREFIX.sound_volume", percent.coerceIn(0, 100))
                }
            }

            /** 自定义按键音开关（默认关闭）：开启且已选文件时播自选音效，否则播出厂音 */
            fun isCustomSoundEnabled(context: Context): Boolean {
                return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .getBoolean("$PREFIX.custom_sound", false)
            }

            fun setCustomSoundEnabled(context: Context, enabled: Boolean) {
                context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
                    putBoolean("$PREFIX.custom_sound", enabled)
                }
            }

            /** 自定义音效文件路径（应用私有目录内文件绝对路径，空 = 未选择） */
            fun getCustomSoundPath(context: Context): String {
                return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .getString("$PREFIX.custom_sound_path", "") ?: ""
            }

            fun setCustomSoundPath(context: Context, path: String) {
                context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
                    putString("$PREFIX.custom_sound_path", path)
                }
            }

            /** 按压振动时长（毫秒，默认 15；松开振动共用此时长） */
            fun getVibrationDurationMs(context: Context): Int {
                return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .getInt("$PREFIX.vibration_duration", 15).coerceIn(5, 100)
            }

            fun setVibrationDurationMs(context: Context, ms: Int) {
                context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
                    putInt("$PREFIX.vibration_duration", ms.coerceIn(5, 100))
                }
            }

            /** 松开振动（默认关闭）：抬起时补一次按压时长振动，仅按键面参与 */
            fun isReleaseVibrationEnabled(context: Context): Boolean {
                return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .getBoolean("$PREFIX.release_vibration", false)
            }

            fun setReleaseVibrationEnabled(context: Context, enabled: Boolean) {
                context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
                    putBoolean("$PREFIX.release_vibration", enabled)
                }
            }
        }

        object Gesture {
            private const val PREFIX = "keyboard.gesture"

            fun getLongPressTimeoutMs(context: Context): Int {
                return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .getInt("$PREFIX.long_press_timeout", 250).coerceIn(100, 1000)
            }

            fun setLongPressTimeoutMs(context: Context, ms: Int) {
                context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
                    putInt("$PREFIX.long_press_timeout", ms.coerceIn(100, 1000))
                }
            }

            fun getRepeatIntervalMs(context: Context): Int {
                return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .getInt("$PREFIX.repeat_interval", 100).coerceIn(50, 500)
            }

            fun setRepeatIntervalMs(context: Context, ms: Int) {
                context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
                    putInt("$PREFIX.repeat_interval", ms.coerceIn(50, 500))
                }
            }

            /** 双击判定窗口（默认 250ms = 旧长按复用口径，不改旧手感） */
            fun getDoubleTapTimeoutMs(context: Context): Int {
                return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .getInt("$PREFIX.double_tap_timeout", 250).coerceIn(100, 1000)
            }

            fun setDoubleTapTimeoutMs(context: Context, ms: Int) {
                context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
                    putInt("$PREFIX.double_tap_timeout", ms.coerceIn(100, 1000))
                }
            }

            /** 滑动速度门限（px/s，0 = 不限制 = 旧行为）：低于此平均速度的滑动不产生步进 */
            fun getMinSwipeVelocityPxPerSec(context: Context): Int {
                return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .getInt("$PREFIX.min_swipe_velocity", 0).coerceIn(0, 5000)
            }

            fun setMinSwipeVelocityPxPerSec(context: Context, pxPerSec: Int) {
                context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
                    putInt("$PREFIX.min_swipe_velocity", pxPerSec.coerceIn(0, 5000))
                }
            }

            /** 滑动步长：空格慢滑移光标时每步进移动的字符数（默认 1） */
            fun getSlideStepChars(context: Context): Int {
                return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .getInt("$PREFIX.slide_step_chars", 1).coerceIn(1, 5)
            }

            fun setSlideStepChars(context: Context, chars: Int) {
                context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
                    putInt("$PREFIX.slide_step_chars", chars.coerceIn(1, 5))
                }
            }
        }

        object Display {
            private const val PREFIX = "keyboard.display"

            /** 隐藏按键符号：图标键的符号字形/工厂图标不绘制（键面空白，仍可点按） */
            fun isHideSymbol(context: Context): Boolean {
                return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .getBoolean("$PREFIX.hide_symbol", false)
            }

            fun setHideSymbol(context: Context, hide: Boolean) {
                context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
                    putBoolean("$PREFIX.hide_symbol", hide)
                }
            }

            /** 隐藏按键提示：按键上的内置副文字（如九宫格字母）不绘制 */
            fun isHideHint(context: Context): Boolean {
                return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .getBoolean("$PREFIX.hide_hint", false)
            }

            fun setHideHint(context: Context, hide: Boolean) {
                context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
                    putBoolean("$PREFIX.hide_hint", hide)
                }
            }
        }

        object Gap {
            private const val PREFIX = "keyboard.gap"
            const val KEY_HORIZONTAL = "$PREFIX.horizontal"
            const val KEY_VERTICAL = "$PREFIX.vertical"

            private const val DEFAULT_HORIZONTAL_DP = 4
            private const val DEFAULT_VERTICAL_DP = 3

            fun getHorizontalDp(context: Context): Int {
                return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .getInt("$PREFIX.horizontal", DEFAULT_HORIZONTAL_DP)
            }

            fun setHorizontalDp(context: Context, dp: Int) {
                context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
                    putInt("$PREFIX.horizontal", dp)
                }
            }

            fun getVerticalDp(context: Context): Int {
                return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .getInt("$PREFIX.vertical", DEFAULT_VERTICAL_DP)
            }

            fun setVerticalDp(context: Context, dp: Int) {
                context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
                    putInt("$PREFIX.vertical", dp)
                }
            }
        }

        object KeyRadius {
            const val KEY = "keyboard.key_radius"
            private const val DEFAULT_RADIUS_DP = 9

            fun getDp(context: Context): Int {
                return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .getInt(KEY, DEFAULT_RADIUS_DP)
            }

            fun setDp(context: Context, dp: Int) {
                context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
                    putInt(KEY, dp)
                }
            }
        }

        object RippleEffect {
            const val KEY = "keyboard.ripple_effect"

            fun isEnabled(context: Context): Boolean {
                return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .getBoolean(KEY, false)
            }

            fun setEnabled(context: Context, enabled: Boolean) {
                context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
                    putBoolean(KEY, enabled)
                }
            }
        }

        object KeyBorderStroke {
            const val KEY = "keyboard.key_border_stroke"

            fun isEnabled(context: Context): Boolean {
                return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .getBoolean(KEY, true)
            }

            fun setEnabled(context: Context, enabled: Boolean) {
                context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
                    putBoolean(KEY, enabled)
                }
            }
        }

        object ExpandBorder {
            const val KEY = "keyboard.expand_borders"

            fun isEnabled(context: Context): Boolean {
                return !context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .getBoolean(KEY, false)
            }

            fun setEnabled(context: Context, enabled: Boolean) {
                context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
                    putBoolean(KEY, !enabled)
                }
            }
        }

        /**
         * 滑动触发距离（2026-10-28 截图 6 项）：全局设置，dp；
         * 值越小越容易触发滑动（越灵敏）。皮肤级/逐键值优先于此。
         * 读回时钳制到有效区间；默认 36dp，与旧写死行为一致。
         */
        object SwipeThreshold {
            const val KEY = "keyboard.swipe_threshold_dp"
            const val DEFAULT_DP = 36f
            const val MIN_DP = 8f
            const val MAX_DP = 160f

            fun getDp(context: Context): Float {
                return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .getFloat(KEY, DEFAULT_DP)
                    .coerceIn(MIN_DP, MAX_DP)
            }

            fun setDp(context: Context, dp: Float) {
                context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
                    putFloat(KEY, dp.coerceIn(MIN_DP, MAX_DP))
                }
            }
        }

        /**
         * 符号自动成对（2026-10-28 截图 6 项；2026-10-28 改默认开）：
         * 默认开。用户可在设置页“符号自动成对”开关随时关闭；
         * 已手动开关过的用户以存储值为准，不受默认值影响。
         * 密码框下强制禁用（门在提交侧，不依赖本开关值）。
         */
        object SymbolPairing {
            const val KEY = "keyboard.symbol_pairing_enabled"

            fun isEnabled(context: Context): Boolean {
                return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .getBoolean(KEY, true)
            }

            fun setEnabled(context: Context, enabled: Boolean) {
                context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
                    putBoolean(KEY, enabled)
                }
            }
        }

        /**
         * 助记提示（2026-10-29 item-1 拟态）：默认开。关 = 皮肤
         * 附加小字（底部 subLabel、顶部 subLabelTop）不渲染；出厂
         * 副显（如九宫格字母）不受影响（那是键内容，不是提示）。
         * 装配期压制（冷路径），开关变化经窗口刷新重建键盘。
         */
        object Mnemonic {
            const val KEY = "keyboard.mnemonic_enabled"

            fun isEnabled(context: Context): Boolean {
                return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .getBoolean(KEY, true)
            }

            fun setEnabled(context: Context, enabled: Boolean) {
                context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
                    putBoolean(KEY, enabled)
                }
            }
        }

        /**
         * 装饰效果（2026-10-29 item-1 拟态）：默认开。关 = 拟态
         * 双阴影与前景叠加层不渲染；键色、文字、动作不受影响。
         * 阴影在视图层读本开关，前景层在装配期压制。
         */
        object Decoration {
            const val KEY = "keyboard.decoration_enabled"

            fun isEnabled(context: Context): Boolean {
                return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .getBoolean(KEY, true)
            }

            fun setEnabled(context: Context, enabled: Boolean) {
                context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
                    putBoolean(KEY, enabled)
                }
            }
        }
    }
}
