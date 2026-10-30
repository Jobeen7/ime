// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.ui.theme

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import androidx.core.content.edit
import com.jobeen.ime.data.manager.KeyboardManager

/**
 * 应用外观（设置页主题，2026-10-29 外观轮第 4 项）。
 *
 * 与库页皮肤颜色模式严格独立：本偏好只决定设置页的 Compose
 * 主题（亮／暗／动态取色），绝不影响键盘服务上下文，也绝不
 * 另设键盘昼夜开关（键盘深浅由皮肤颜色模式管）。
 * 偏好文件 `appearance_settings` 与全局样式、全局字体共用，
 * 键名前缀区分（`app.*`／`global_style.*`／`font.*`）。
 */
enum class AppAppearanceMode(val stored: String) {
    MONET("monet"),
    LIGHT("light"),
    DARK("dark");

    companion object {
        /**
         * 解析存量值：未知／空 → 莫奈色（新默认）；旧值 "auto" 同样
         * 映射为莫奈色（2026-10-30 自动改莫奈色，见 [AppAppearance.getMode]
         * 的写回迁移）。显式三项之外不存在其它取值。
         */
        fun parse(raw: String?): AppAppearanceMode =
            when (raw) {
                "monet" -> MONET
                "light" -> LIGHT
                "dark" -> DARK
                else -> MONET
            }
    }
}

object AppAppearance {
    const val PREFS_NAME = "appearance_settings"
    private const val KEY_MODE = "app.mode"

    /**
     * 读模式（含存量迁移）：存量 "auto" 读到即改写为 "monet" 并返回
     * 莫奈色；其余值按 [AppAppearanceMode.parse] 解析。迁移只写一次，
     * 之后读到的已是 "monet"，不再写。
     */
    fun getMode(context: Context): AppAppearanceMode {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_MODE, null)
        if (raw == "auto") setMode(context, AppAppearanceMode.MONET)
        return AppAppearanceMode.parse(raw)
    }

    fun setMode(context: Context, mode: AppAppearanceMode) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
            putString(KEY_MODE, mode.stored)
        }
    }

    /**
     * 映射到 ImeTheme 的 themeMode（纯函数，可 JVM 单测）：
     * 莫奈色＝跟随系统深浅（动态取色另由 [useDynamicColorFor] 开关，
     * 低版本自动回落普通亮／暗）；亮／暗固定。自动项已于 2026-10-30
     * 并入莫奈色，不再有跟随系统但不动态取色的模式。
     */
    fun themeModeFor(mode: AppAppearanceMode, isSystemDark: Boolean): Int =
        when (mode) {
            AppAppearanceMode.LIGHT -> KeyboardManager.Theme.MODE_LIGHT
            AppAppearanceMode.DARK -> KeyboardManager.Theme.MODE_DARK
            AppAppearanceMode.MONET ->
                if (isSystemDark) KeyboardManager.Theme.MODE_DARK
                else KeyboardManager.Theme.MODE_LIGHT
        }

    /**
     * 是否启用动态取色（纯函数，可 JVM 单测）：仅莫奈色且
     * 系统版本支持动态取色时为真；低版本一律为假（回落普通亮／暗）。
     */
    fun useDynamicColorFor(mode: AppAppearanceMode, sdkInt: Int): Boolean =
        mode == AppAppearanceMode.MONET && sdkInt >= Build.VERSION_CODES.S

    /**
     * 返回即刷新判定（纯函数，可 JVM 单测）：记录的模式与当前
     * 模式不同即需重建；相同不重建。只比模式本身，不动
     * themeModeFor／useDynamicColorFor 的映射逻辑。
     */
    fun appearanceModeChanged(recorded: AppAppearanceMode, current: AppAppearanceMode): Boolean =
        recorded != current

    /** 当前主题模式（含系统深浅判定）。 */
    fun themeMode(context: Context): Int {
        val dark = (context.resources.configuration.uiMode and
            Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        return themeModeFor(getMode(context), dark)
    }

    /** 当前是否启用动态取色。 */
    fun useDynamicColor(context: Context): Boolean =
        useDynamicColorFor(getMode(context), Build.VERSION.SDK_INT)
}
