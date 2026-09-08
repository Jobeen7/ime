package com.ninthsoft.ime.data.manager

import android.content.Context
import androidx.core.content.edit
import com.ninthsoft.ime.data.keyboard.theme.KeyboardTheme

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

        fun getMode(context: Context): Int {
            return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getInt("$PREFIX.mode", MODE_SYSTEM)
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
            return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_FOLLOW_SYSTEM, false)
        }

        fun setFollowSystem(context: Context, followSystem: Boolean) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
                putBoolean(KEY_FOLLOW_SYSTEM, followSystem)
            }
        }

        fun getThemeId(context: Context): String {
            return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString("$PREFIX.theme", KeyboardTheme.DEFAULT_ID) ?: KeyboardTheme.DEFAULT_ID
        }

        fun setThemeId(context: Context, themeId: String) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
                putString("$PREFIX.theme", themeId)
            }
        }

        fun getLightThemeId(context: Context): String {
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
            return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(KEY_DARK_THEME, KeyboardTheme.DARK_DEFAULT_ID)
                    ?: KeyboardTheme.DARK_DEFAULT_ID
        }

        fun setDarkThemeId(context: Context, themeId: String) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
                putString(KEY_DARK_THEME, themeId)
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

            fun getSoundEnabled(context: Context): Boolean {
                return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .getBoolean("$PREFIX.sound", true)
            }

            fun setSoundEnabled(context: Context, enabled: Boolean) {
                context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
                    putBoolean("$PREFIX.sound", enabled)
                }
            }
        }

        object Gap {
            private const val PREFIX = "keyboard.gap"
            const val KEY_HORIZONTAL = "$PREFIX.horizontal"
            const val KEY_VERTICAL = "$PREFIX.vertical"

            private const val DEFAULT_HORIZONTAL_DP = 3
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
            private const val DEFAULT_RADIUS_DP = 14

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
    }
}
