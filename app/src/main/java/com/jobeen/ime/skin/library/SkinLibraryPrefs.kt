package com.jobeen.ime.skin.library

import android.content.Context
import androidx.core.content.edit
import com.jobeen.ime.data.manager.KeyboardManager

/**
 * 皮肤库页颜色模式与开发模式开关（第三组：库页改版＋开发模式）。
 *
 * 颜色模式取值（与另一分流约定的后端接口口径一致）：
 * SYSTEM（跟随系统）/ LIGHT（浅色）/ DARK（深色），持久化在独立
 * 偏好文件 `skin_library`（键 `color_mode`），不复用
 * [KeyboardManager.Theme] 的 mode 键——后者当前被有意锁定为恒跟随
 * 系统（getMode 恒返 SYSTEM 并迁移旧值），本分流不擅自恢复已被
 * 删除的全局主题设置。当前生效范围：① 皮肤库页与开发屏的 Compose
 * 主题（经 ImeTheme 的 themeMode）；② 无 v3 快照时的内置键盘主题
 * （经 KeyboardColors.themeFor：LIGHT/DARK 强制取对应主题 id，
 * SYSTEM 走现行跟随系统路径）；有快照的自定义皮肤仍以快照配色
 * 优先（KeyboardColors.resolve 的快照分支），不受模式影响。
 *
 * 后端分流接管时：若双色板以后落地，自定义皮肤的浅色/深色两套
 * 配色由快照侧按模式选取，本偏好值即为模式源；迁移时必须把
 * 此处已持久化的用户选择带过去，不得静默丢弃。
 *
 * 开发模式开关（键 `dev_mode_enabled`）：仅控制开发屏的“已开启”
 * 标记与入口状态，不控制任何校验、安全门——开发屏内一切操作仍走
 * 正常校验与导入管线（见 SkinDevScreen 头部的“不做什么”）。
 */
enum class SkinColorMode(val stored: String) {
    SYSTEM("system"),
    LIGHT("light"),
    DARK("dark");

    /** 映射到 ImeTheme 的 themeMode（即 KeyboardManager.Theme 的 MODE_* 常量值）。 */
    fun toThemeMode(): Int = when (this) {
        SYSTEM -> KeyboardManager.Theme.MODE_SYSTEM
        LIGHT -> KeyboardManager.Theme.MODE_LIGHT
        DARK -> KeyboardManager.Theme.MODE_DARK
    }

    /** 在给定系统深浅下的实际深色与否（纯函数，可单测）。 */
    fun effectiveDark(isSystemDark: Boolean): Boolean = when (this) {
        SYSTEM -> isSystemDark
        LIGHT -> false
        DARK -> true
    }

    companion object {
        fun parse(raw: String?): SkinColorMode =
            entries.firstOrNull { it.stored == raw } ?: SYSTEM
    }
}

object SkinLibraryPrefs {
    private const val PREFS_NAME = "skin_library"
    private const val KEY_COLOR_MODE = "color_mode"
    private const val KEY_DEV_MODE = "dev_mode_enabled"

    fun getColorMode(context: Context): SkinColorMode {
        val raw = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_COLOR_MODE, null)
        return SkinColorMode.parse(raw)
    }

    fun setColorMode(context: Context, mode: SkinColorMode) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
            putString(KEY_COLOR_MODE, mode.stored)
        }
    }

    fun isDevMode(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_DEV_MODE, false)

    fun setDevMode(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
            putBoolean(KEY_DEV_MODE, enabled)
        }
    }
}
