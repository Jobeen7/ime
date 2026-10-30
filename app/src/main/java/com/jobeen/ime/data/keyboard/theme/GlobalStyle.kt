// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.data.keyboard.theme

import android.content.Context
import androidx.core.content.edit
import com.jobeen.ime.skin.model.ImageAdjust
import com.jobeen.ime.skin.model.StyleField
import com.jobeen.ime.skin.runtime.SkinSnapshotProvider
import com.jobeen.ime.ui.theme.AppAppearance

/**
 * 全局按键与状态栏样式：主题／自定义双模式配置。
 *
 * 硬分层链（Lee 定规矩）：皮肤内指定值 ＞ 用户全局设置 ＞
 * 系统默认／内置主题——全局层只补皮肤留空处，绝不盖掉皮肤
 * 有值处。null＝跟随（该项不覆盖）；数值越界时读侧钳制，永不崩。
 *
 * 双模式（2026-10-29 全局样式重做）：主题全局用于内置主题生效时，
 * 自定义全局用于自定义皮肤生效时。主题集沿用旧单套键名
 * （`global_style.*`，老用户设置自动成为主题集，不做破坏性迁移）；
 * 自定义集用新前缀（`global_style.custom.*`），未编辑前全跟随，
 * 不从主题集借值（两模式互不串台）。生效判定＝当前有无快照：
 * 有快照＝自定义皮肤生效中，读自定义集；无快照＝内置主题，读主题集。
 * 快照构建／刷新侧（SkinSnapshotFactory／SkinSnapshotProvider）直接
 * 读自定义集（构建对象本身就是自定义皮肤，构建时旧快照不可信）。
 *
 * 偏好文件与应用外观共用 `appearance_settings`。“状态栏”指键盘
 * 顶部栏（候选／工具栏区），不是系统状态栏。
 */

/** 全局样式模式：主题（内置主题生效时）／自定义（自定义皮肤生效时）。 */
enum class GlobalStyleMode(val prefix: String) {
    THEME("global_style."),
    CUSTOM("global_style.custom."),
}

/** 生效模式纯判定：有快照＝自定义，无快照＝主题（可单测）。 */
fun resolveGlobalStyleMode(hasSnapshot: Boolean): GlobalStyleMode =
    if (hasSnapshot) GlobalStyleMode.CUSTOM else GlobalStyleMode.THEME
data class SurfaceOverride(
    val background: Int? = null,
    val borderWidthDp: Float? = null,
    val borderColor: Int? = null,
    val cornerRadiusDp: Float? = null,
)

/** 背景模糊三态：跟随皮肤／强制开／强制关。 */
enum class BackgroundBlurMode(val stored: String) {
    FOLLOW("follow"),
    ON("on"),
    OFF("off");

    companion object {
        fun parse(raw: String?): BackgroundBlurMode =
            entries.firstOrNull { it.stored == raw } ?: FOLLOW
    }
}

data class BackgroundBlurOverride(
    val mode: BackgroundBlurMode = BackgroundBlurMode.FOLLOW,
    val radiusDp: Float? = null,
)

data class GlobalStyle(
    val key: SurfaceOverride = SurfaceOverride(),
    val linkToolbar: Boolean = true,
    val toolbar: SurfaceOverride = SurfaceOverride(),
    val keyboardTopCornerDp: Float? = null,
    val backgroundBlur: BackgroundBlurOverride = BackgroundBlurOverride(),
    val barBackground: Int? = null,
    val barBlur: Boolean = false,
    val sharedBackground: Boolean = false,
    /** 磨砂叠加强度（0..1 分数；null＝默认 0.25，显式 0＝无叠加层）。 */
    val frostOverlayAlpha: Float? = null,
)

object GlobalStylePrefs {
    const val PREFS_NAME = AppAppearance.PREFS_NAME

    // 键后缀（两模式共用）；主题键＝旧单套键名（向后兼容），
    // 自定义键＝自定义前缀＋同后缀。
    private const val S_KEY_BG = "key_bg"
    private const val S_KEY_BORDER_WIDTH = "key_border_width"
    private const val S_KEY_BORDER_COLOR = "key_border_color"
    private const val S_KEY_RADIUS = "key_radius"
    private const val S_LINK_TOOLBAR = "link_toolbar"
    private const val S_TOOLBAR_BG = "toolbar_bg"
    private const val S_TOOLBAR_BORDER_WIDTH = "toolbar_border_width"
    private const val S_TOOLBAR_BORDER_COLOR = "toolbar_border_color"
    private const val S_TOOLBAR_RADIUS = "toolbar_radius"
    private const val S_TOP_CORNER = "top_corner"
    private const val S_BLUR_MODE = "blur_mode"
    private const val S_BLUR_RADIUS = "blur_radius"
    private const val S_BAR_BG = "bar_bg"
    private const val S_BAR_BLUR = "bar_blur"
    private const val S_SHARED_BG = "shared_bg"
    private const val S_FROST_OVERLAY = "frost_overlay"

    const val KEY_KEY_BG = "global_style.key_bg"
    const val KEY_KEY_BORDER_WIDTH = "global_style.key_border_width"
    const val KEY_KEY_BORDER_COLOR = "global_style.key_border_color"
    const val KEY_KEY_RADIUS = "global_style.key_radius"
    const val KEY_LINK_TOOLBAR = "global_style.link_toolbar"
    const val KEY_TOOLBAR_BG = "global_style.toolbar_bg"
    const val KEY_TOOLBAR_BORDER_WIDTH = "global_style.toolbar_border_width"
    const val KEY_TOOLBAR_BORDER_COLOR = "global_style.toolbar_border_color"
    const val KEY_TOOLBAR_RADIUS = "global_style.toolbar_radius"
    const val KEY_TOP_CORNER = "global_style.top_corner"
    const val KEY_BLUR_MODE = "global_style.blur_mode"
    const val KEY_BLUR_RADIUS = "global_style.blur_radius"
    const val KEY_BAR_BG = "global_style.bar_bg"
    const val KEY_BAR_BLUR = "global_style.bar_blur"
    const val KEY_SHARED_BG = "global_style.shared_bg"

    /** 指定模式的完整键名（纯函数，可单测）。 */
    fun keyFor(mode: GlobalStyleMode, suffix: String): String = mode.prefix + suffix

    private val SUFFIXES: List<String> = listOf(
        S_KEY_BG, S_KEY_BORDER_WIDTH, S_KEY_BORDER_COLOR, S_KEY_RADIUS,
        S_LINK_TOOLBAR, S_TOOLBAR_BG, S_TOOLBAR_BORDER_WIDTH,
        S_TOOLBAR_BORDER_COLOR, S_TOOLBAR_RADIUS, S_TOP_CORNER,
        S_BLUR_MODE, S_BLUR_RADIUS, S_BAR_BG, S_BAR_BLUR, S_SHARED_BG,
        S_FROST_OVERLAY,
    )

    /** 全部全局样式键（两模式；服务侧监听路由用）。 */
    val ALL_KEYS: Set<String> = GlobalStyleMode.entries.flatMap { mode ->
        SUFFIXES.map { mode.prefix + it }
    }.toSet()

    /** 背景模糊相关键（两模式；窗口侧走背景重建专路，其余键走强制刷新）。 */
    val BLUR_KEYS: Set<String> = GlobalStyleMode.entries.flatMap { mode ->
        listOf(mode.prefix + S_BLUR_MODE, mode.prefix + S_BLUR_RADIUS)
    }.toSet()

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun intOrNull(
        p: android.content.SharedPreferences,
        key: String,
    ): Int? = if (p.contains(key)) p.getInt(key, 0) else null

    private fun floatOrNull(
        p: android.content.SharedPreferences,
        key: String,
    ): Float? = if (p.contains(key)) p.getFloat(key, 0f) else null

    /** 指定模式的一套配置（对话框编辑侧用；自定义集未写过的项全跟随）。 */
    fun get(context: Context, mode: GlobalStyleMode): GlobalStyle {
        val p = prefs(context)
        val k = { suffix: String -> mode.prefix + suffix }
        return GlobalStyle(
            key = SurfaceOverride(
                background = intOrNull(p, k(S_KEY_BG)),
                borderWidthDp = floatOrNull(p, k(S_KEY_BORDER_WIDTH)),
                borderColor = intOrNull(p, k(S_KEY_BORDER_COLOR)),
                cornerRadiusDp = floatOrNull(p, k(S_KEY_RADIUS)),
            ),
            linkToolbar = p.getBoolean(k(S_LINK_TOOLBAR), true),
            toolbar = SurfaceOverride(
                background = intOrNull(p, k(S_TOOLBAR_BG)),
                borderWidthDp = floatOrNull(p, k(S_TOOLBAR_BORDER_WIDTH)),
                borderColor = intOrNull(p, k(S_TOOLBAR_BORDER_COLOR)),
                cornerRadiusDp = floatOrNull(p, k(S_TOOLBAR_RADIUS)),
            ),
            keyboardTopCornerDp = floatOrNull(p, k(S_TOP_CORNER)),
            backgroundBlur = BackgroundBlurOverride(
                mode = BackgroundBlurMode.parse(p.getString(k(S_BLUR_MODE), null)),
                radiusDp = floatOrNull(p, k(S_BLUR_RADIUS)),
            ),
            barBackground = intOrNull(p, k(S_BAR_BG)),
            barBlur = p.getBoolean(k(S_BAR_BLUR), false),
            sharedBackground = p.getBoolean(k(S_SHARED_BG), false),
            frostOverlayAlpha = floatOrNull(p, k(S_FROST_OVERLAY)),
        )
    }

    /** 生效的一套配置（渲染侧用）：有快照读自定义集，无快照读主题集。 */
    fun get(context: Context): GlobalStyle =
        get(context, resolveGlobalStyleMode(SkinSnapshotProvider.peek() != null))

    /** 整套写入指定模式（对话框“应用”用；一次 edit，写透全部字段）。 */
    fun put(context: Context, mode: GlobalStyleMode, style: GlobalStyle) {
        val k = { suffix: String -> mode.prefix + suffix }
        prefs(context).edit {
            putOrRemoveInt(k(S_KEY_BG), style.key.background)
            putOrRemoveFloat(k(S_KEY_BORDER_WIDTH), style.key.borderWidthDp)
            putOrRemoveInt(k(S_KEY_BORDER_COLOR), style.key.borderColor)
            putOrRemoveFloat(k(S_KEY_RADIUS), style.key.cornerRadiusDp)
            putBoolean(k(S_LINK_TOOLBAR), style.linkToolbar)
            putOrRemoveInt(k(S_TOOLBAR_BG), style.toolbar.background)
            putOrRemoveFloat(k(S_TOOLBAR_BORDER_WIDTH), style.toolbar.borderWidthDp)
            putOrRemoveInt(k(S_TOOLBAR_BORDER_COLOR), style.toolbar.borderColor)
            putOrRemoveFloat(k(S_TOOLBAR_RADIUS), style.toolbar.cornerRadiusDp)
            putOrRemoveFloat(k(S_TOP_CORNER), style.keyboardTopCornerDp)
            putString(k(S_BLUR_MODE), style.backgroundBlur.mode.stored)
            putOrRemoveFloat(k(S_BLUR_RADIUS), style.backgroundBlur.radiusDp)
            putOrRemoveInt(k(S_BAR_BG), style.barBackground)
            putBoolean(k(S_BAR_BLUR), style.barBlur)
            putBoolean(k(S_SHARED_BG), style.sharedBackground)
            putOrRemoveFloat(k(S_FROST_OVERLAY), style.frostOverlayAlpha)
        }
    }

    fun putSurface(
        context: Context,
        bgKey: String,
        widthKey: String,
        colorKey: String,
        radiusKey: String,
        surface: SurfaceOverride,
    ) {
        prefs(context).edit {
            putOrRemoveInt(bgKey, surface.background)
            putOrRemoveFloat(widthKey, surface.borderWidthDp)
            putOrRemoveInt(colorKey, surface.borderColor)
            putOrRemoveFloat(radiusKey, surface.cornerRadiusDp)
        }
    }

    private fun android.content.SharedPreferences.Editor.putOrRemoveInt(key: String, value: Int?) {
        if (value == null) remove(key) else putInt(key, value)
    }

    private fun android.content.SharedPreferences.Editor.putOrRemoveFloat(key: String, value: Float?) {
        if (value == null) remove(key) else putFloat(key, value)
    }

    fun setLinkToolbar(context: Context, value: Boolean) {
        prefs(context).edit { putBoolean(KEY_LINK_TOOLBAR, value) }
    }

    fun setTopCorner(context: Context, value: Float?) {
        prefs(context).edit { putOrRemoveFloat(KEY_TOP_CORNER, value) }
    }

    fun setBlur(context: Context, mode: BackgroundBlurMode, radiusDp: Float?) {
        prefs(context).edit {
            putString(KEY_BLUR_MODE, mode.stored)
            putOrRemoveFloat(KEY_BLUR_RADIUS, radiusDp)
        }
    }

    fun setBar(context: Context, background: Int?, blur: Boolean, shared: Boolean) {
        prefs(context).edit {
            putOrRemoveInt(KEY_BAR_BG, background)
            putBoolean(KEY_BAR_BLUR, blur)
            putBoolean(KEY_SHARED_BG, shared)
        }
    }
}

/** 全局样式纯解析（可 JVM 单测；presence＝皮肤显式指定的样式字段集）。 */
object GlobalStyleResolver {
    const val BORDER_WIDTH_MIN_DP = 0f
    const val BORDER_WIDTH_MAX_DP = 4f
    const val RADIUS_MIN_DP = 0f
    const val RADIUS_MAX_DP = 32f
    const val DEFAULT_BORDER_WIDTH_DP = 1f
    const val DEFAULT_BLUR_RADIUS_DP = 16f

    fun clampBorderWidth(value: Float?): Float =
        value?.coerceIn(BORDER_WIDTH_MIN_DP, BORDER_WIDTH_MAX_DP)
            ?: DEFAULT_BORDER_WIDTH_DP

    fun clampRadius(value: Float?): Float? =
        value?.coerceIn(RADIUS_MIN_DP, RADIUS_MAX_DP)

    /** 按键背景：皮肤指定则 null（不覆盖），否则全局值。 */
    fun keyBackground(style: GlobalStyle, stylePresence: Set<StyleField>): Int? =
        if (StyleField.KEY_BACKGROUND in stylePresence) null else style.key.background

    fun specialKeyBackground(style: GlobalStyle, stylePresence: Set<StyleField>): Int? =
        if (StyleField.SPECIAL_KEY_BACKGROUND in stylePresence) null
        else style.key.background

    fun accentKeyBackground(style: GlobalStyle, stylePresence: Set<StyleField>): Int? =
        if (StyleField.ACCENT_KEY_BACKGROUND in stylePresence) null
        else style.key.background

    fun keyBorderStroke(style: GlobalStyle, stylePresence: Set<StyleField>): Int? =
        if (StyleField.KEY_BORDER_STROKE in stylePresence) null else style.key.borderColor

    fun specialKeyBorderStroke(style: GlobalStyle, stylePresence: Set<StyleField>): Int? =
        if (StyleField.SPECIAL_KEY_BORDER_STROKE in stylePresence) null
        else style.key.borderColor

    fun accentKeyBorderStroke(style: GlobalStyle, stylePresence: Set<StyleField>): Int? =
        if (StyleField.ACCENT_KEY_BORDER_STROKE in stylePresence) null
        else style.key.borderColor

    /** 按键圆角：皮肤指定（任一几何 presence）则 null，否则全局值。 */
    fun keyCornerRadius(style: GlobalStyle, skinHasRadius: Boolean): Float? =
        if (skinHasRadius) null else clampRadius(style.key.cornerRadiusDp)

    /** 磨砂叠加强度生效值：null＝默认 0.25；显式 0＝无叠加层；越界钳制到 0..1。 */
    fun effectiveFrostOverlay(style: GlobalStyle): Float? =
        (style.frostOverlayAlpha ?: 0.25f).coerceIn(0f, 1f).takeIf { it > 0f }

    /** 工具栏表面：跟随按键时取按键生效值，否则取工具栏自有值（工具栏背景无皮肤字段可冲突）。 */
    fun toolbarSurface(style: GlobalStyle, keySurface: SurfaceOverride): SurfaceOverride =
        if (style.linkToolbar) {
            keySurface
        } else {
            style.toolbar
        }

    /** 栏背景：皮肤指定面板背景则 null，否则全局值。 */
    fun barBackground(style: GlobalStyle, stylePresence: Set<StyleField>): Int? =
        if (StyleField.PANEL_BACKGROUND in stylePresence) null else style.barBackground

    /**
     * 背景模糊生效值（纯函数；构建器与运行期重建共用同一口径）：
     * 降级关模糊时一律为 0（保内存，优先于全局强制开）且调节去模糊；
     * 强制关＝0 且调节去模糊；强制开＝全局半径（未设时取调节模糊，
     * 再未设取文件模糊，再未设取默认半径）；跟随＝现行口径
     * （调节模糊优先于文件模糊）。
     * 返回 blur 半径与调节后 imageAdjust（blur 已按同一口径改写）。
     */
    fun effectiveBlur(
        specBlurRadius: Float,
        specAdjust: ImageAdjust?,
        blurDisabled: Boolean,
        override: BackgroundBlurOverride,
    ): Pair<Float, ImageAdjust?> {
        if (blurDisabled) {
            val stripped = specAdjust?.copy(blurRadiusDp = null)?.takeIf { it.hasEffect() }
            return 0f to stripped
        }
        return when (override.mode) {
            BackgroundBlurMode.OFF -> {
                val stripped = specAdjust?.copy(blurRadiusDp = null)?.takeIf { it.hasEffect() }
                0f to stripped
            }
            BackgroundBlurMode.ON -> {
                val radius = clampRadius(override.radiusDp)
                    ?: specAdjust?.blurRadiusDp
                    ?: specBlurRadius.takeIf { it > 0f }
                    ?: DEFAULT_BLUR_RADIUS_DP
                val adjust = (specAdjust ?: ImageAdjust()).copy(blurRadiusDp = radius)
                    .takeIf { it.hasEffect() }
                radius to adjust
            }
            BackgroundBlurMode.FOLLOW -> {
                val radius = specAdjust?.blurRadiusDp ?: specBlurRadius
                radius to specAdjust
            }
        }
    }
}
