package com.jobeen.ime.skin.runtime

import android.content.Context
import android.content.res.Configuration
import com.jobeen.ime.data.manager.KeyboardManager
import com.jobeen.ime.skin.diag.DiagLog

/**
 * v3 皮肤颜色模式（2026-10-27 第一组，B3 裁定修订）：
 *
 * 旧 B3：自定义皮肤应用时跟随系统被禁用（快照单色板优先于
 * 跟随系统深浅色，见 KeyboardColors.resolve 旧注释）。
 * 修订后：一包双色板跟随系统——皮肤文件可同时携带浅色板
 * （styles / derivation）与深色板（stylesDark / derivationDark），
 * 本模式决定快照取哪一板；无深色板的旧皮肤恒取浅色板，
 * 行为与修订前一致（向后兼容，见 SkinSnapshot.effectiveColorScheme）。
 *
 * 模式取值（皮肤库页 ⋮ 菜单「颜色模式」的后端契约，菜单 UI
 * 由库页分流实现；本文件是键盘侧读取该选择的唯一后端）：
 * - SYSTEM：跟随系统（默认；系统深色 → 深色板，浅色 → 浅色板）；
 * - LIGHT：恒浅色板；DARK：恒深色板（无深色板时回落浅色板）。
 *
 * 存储契约（与库页分流共用，集成时不得再分叉）：
 * SharedPreferences 文件 `skin_library`（与库目录同名、不同命名
 * 空间），键 [KEY]（`color_mode`），值小写 `system` / `light` /
 * `dark`（库页 SkinLibraryPrefs 的存储口径）。键盘侧读、库页侧
 * 写同一文件同一键，库页菜单改动即为键盘模式改动，不存在第二份
 * 选择。本对象与库页的同名枚举（skin.library.SkinColorMode）是
 * 两层类型、同一存储字符串：UI 层用枚举映射 Compose 主题，键盘
 * 层用本对象决定快照取板；合并时两者都保留，删任何一方都必须
 * 先把另一方的读写接到同一文件同一键上。
 *
 * 迁移（仅本分流 e620106 旧键）：旧实现曾把模式写在
 * KeyboardManager 偏好文件（键 `skin.color_mode`、值大写枚举名）。
 * [get] 在 `skin_library` 无值时读旧键：旧值有效则一次性 adopt
 * 到新存储（写小写值，旧键保留不删——只读迁移，不破坏回退）；
 * 旧值未知则按 SYSTEM 处理并记日志。已用新存储的用户不受影响。
 *
 * 刷新链路：ImeInputMethodService 同时监听 `skin_library` 偏好
 * 文件，键变化经 onConfigChanged 转 KeyboardWindowView 的
 * scheduleRefreshColors（resolve 重新取板，快照本身不重建）。
 * 未知存储值 → 按 SYSTEM 处理并记一条日志（只记存储值本身，
 * 不含任何输入/候选内容；同值同进程只记一次，避免刷新路径刷屏）。
 */
object SkinColorMode {

    /** 模式存储文件（库页分流的 SkinLibraryPrefs 同文件）。 */
    const val PREFS_NAME = "skin_library"

    /** 模式存储键（库页菜单分流与本后端共用的契约键名）。 */
    const val KEY = "color_mode"

    /** e620106 旧存储：仅作一次性 adopt 迁移的读源，不再写入。 */
    private val LEGACY_PREFS_NAME = KeyboardManager.PREFS_NAME
    private const val LEGACY_KEY = "skin.color_mode"

    enum class Mode { SYSTEM, LIGHT, DARK }

    /** 模式 → 库页口径的存储字符串（小写）。 */
    fun stored(mode: Mode): String = when (mode) {
        Mode.SYSTEM -> "system"
        Mode.LIGHT -> "light"
        Mode.DARK -> "dark"
    }

    @Volatile
    private var lastLoggedUnknown: String? = null

    /**
     * 存储值 → 模式；接受库页小写与旧实现大写两种拼写，
     * 未知值按 SYSTEM 处理并记日志（元数据 only）。
     */
    fun fromStored(raw: String?): Mode = when (raw?.lowercase()) {
        null, "system" -> Mode.SYSTEM
        "light" -> Mode.LIGHT
        "dark" -> Mode.DARK
        else -> {
            if (lastLoggedUnknown != raw) {
                lastLoggedUnknown = raw
                DiagLog.w(
                    "runtime", "skin_color_mode_invalid",
                    mapOf("stored" to raw),
                )
            }
            Mode.SYSTEM
        }
    }

    /** 纯函数：模式 + 系统深浅 → 是否取深色板（可直接单测）。 */
    fun isDark(mode: Mode, systemNight: Boolean): Boolean = when (mode) {
        Mode.DARK -> true
        Mode.LIGHT -> false
        Mode.SYSTEM -> systemNight
    }

    /**
     * 纯函数：设置页跟随开关 → 皮肤颜色模式（2026-10-28 跟随系统统一）。
     * 开 = 跟随系统；关 = 冻结为关那一刻系统深浅对应的固定色板
     * （此后系统深浅变化不再切换皮肤色板，直到开关重新打开）。
     */
    fun fromFollowToggle(checked: Boolean, systemDark: Boolean): Mode = when {
        checked -> Mode.SYSTEM
        systemDark -> Mode.DARK
        else -> Mode.LIGHT
    }

    fun get(context: Context): Mode {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY, null)
        if (raw != null) return fromStored(raw)
        // 一次性 adopt：旧键有有效值时搬到新存储，旧键不动。
        val legacyRaw = context.getSharedPreferences(LEGACY_PREFS_NAME, Context.MODE_PRIVATE)
            .getString(LEGACY_KEY, null)
        val legacyMode = legacyRaw?.let { fromStored(it) }
        if (legacyRaw != null && legacyMode != null &&
            (legacyRaw.lowercase() == "light" || legacyRaw.lowercase() == "dark")
        ) {
            prefs.edit().putString(KEY, stored(legacyMode)).apply()
            DiagLog.i(
                "runtime", "skin_color_mode_migrated",
                mapOf("from" to LEGACY_KEY),
            )
        }
        return legacyMode ?: Mode.SYSTEM
    }

    fun set(context: Context, mode: Mode) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY, stored(mode)).apply()
    }

    /** 当前配置下是否取深色板（SYSTEM 时读系统 uiMode）。 */
    fun isDark(context: Context): Boolean {
        val systemNight = context.resources.configuration.uiMode and
            Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        return isDark(get(context), systemNight)
    }
}
