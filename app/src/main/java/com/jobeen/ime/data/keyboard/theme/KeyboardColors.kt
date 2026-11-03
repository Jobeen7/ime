package com.jobeen.ime.data.keyboard.theme

import android.content.Context
import android.content.res.Configuration
import com.jobeen.ime.data.manager.KeyboardManager
import com.jobeen.ime.skin.library.SkinColorMode as LibraryColorMode
import com.jobeen.ime.skin.library.SkinLibraryPrefs
import com.jobeen.ime.skin.model.GeometryField
import com.jobeen.ime.skin.model.KeyboardGeometryField
import com.jobeen.ime.skin.model.StyleField
import com.jobeen.ime.skin.runtime.SkinColorMode
import com.jobeen.ime.skin.runtime.SkinSnapshotProvider
import kotlinx.serialization.Serializable

object KeyboardColors {

    @Serializable
    enum class SurfaceStyle {
        Raised,
        Flat,
    }

    @Serializable
    data class ColorScheme(
        val keyBackground: Int,
        val keyPressed: Int,
        val keyBorderStroke: Int,
        val specialKeyBackground: Int,
        val specialKeyPressed: Int,
        val specialKeyBorderStroke: Int,
        val accentKeyBackground: Int,
        val accentKeyPressed: Int,
        val accentKeyBorderStroke: Int,
        val keyText: Int,
        val specialKeyText: Int,
        val accentKeyText: Int,
        val altText: Int,
        val background: Int,
        val surfaceStyle: SurfaceStyle = SurfaceStyle.Raised,
        val cornerRadius: Float = 5f,
        /** 按键边框宽（dp；皮肤无此字段，全局值直接生效，未设为 1）。 */
        val keyBorderWidthDp: Float = 1f,
        val keyHMargin: Float = 3f,
        val keyVMargin: Float = 4f,
        val panel: PanelColors,
        val pinner: PinnerColors,
        val toastBackground: Int = specialKeyBackground,
        val toastText: Int = keyText,
    ) {
        @Serializable
        data class PanelColors(
            val background: Int,
            val toolbarText: Int,
            val toolbarActived: Int,
            val toolbarIcon: Int,
            val candidateBackground: Int,
            val candidateText: Int,
            val candidateIndex: Int,
            val candidateDivider: Int,
            val toolbarPressed: Int,
            /** 选中候选文字色：未指定时与 candidateText 同值（旧行为不变）。 */
            val selectedCandidateText: Int = candidateText,
            /**
             * 候选注释色（审计 I 项）：null = 未指定（渲染回落
             * candidateIndex；旧画笔此前从未赋值、恒为黑色，
             * 此处接线后注释色首次跟随主题，见 Paints）。
             */
            val candidateComment: Int? = null,
            /**
             * 候选按压底色（审计 I 项）：null = 未指定（渲染回落
             * candidateBackground；此前按压无任何视觉反馈）。
             */
            val candidatePressed: Int? = null,
            /**
             * 首选候选色（审计 I 项）：第 1 个候选的底／文字／
             * 注释／序号色；null = 未指定（首选着色关闭）。
             * 不设对端默认值：三参考皮肤的首选绿各不相同
             * （#23C891 / #27dfa1 / #1fb382），无统一值可取。
             */
            val preferredBackground: Int? = null,
            val preferredText: Int? = null,
            val preferredComment: Int? = null,
            val preferredIndex: Int? = null,
            /**
             * 竖网格分隔线色（审计 I 项）：null = 未指定（回落
             * candidateDivider）。横条分隔线不走此字段。
             */
            val verticalSeparator: Int? = null,
        )

        @Serializable
        data class PinnerColors(
            val background: Int,
            val textColor: Int,
            val secondaryTextColor: Int,
        ) {
            companion object {
                fun from(c: ColorScheme) = PinnerColors(
                    background = c.keyBackground,
                    textColor = c.accentKeyText,
                    secondaryTextColor = c.altText,
                )
            }
        }
    }

    fun resolve(context: Context): ColorScheme {
        // v3 分流（详设 §2.7，新增分支、旧路径不动）：存在已验证的 v3
        // 快照时由快照构造配色——几何按 presence 决定读快照值还是全局
        // 偏好（定稿三.4.2）。B3 旧裁定「快照优先于跟随系统深浅色」
        // 已于 2026-10-27 第一组修订：一包双色板跟随系统——模式
        // （SkinColorMode：跟随系统/浅色/深色）决定快照取浅色板
        // 还是深色板；旧单色板皮肤无深色板，恒取浅色板，与修订前一致。
        // 无快照时走下方现行路径，行为与 v1.1.14 逐字节一致。
        //
        // 全局样式层（2026-10-29 外观轮）：皮肤内指定值 ＞ 全局设置
        // ＞ 系统默认／内置主题——全局只覆盖皮肤未指定的字段
        // （颜色看样式 presence，圆角看几何 presence），其余不动。
        val global = GlobalStylePrefs.get(context)
        val snapshot = SkinSnapshotProvider.current(context)
        if (snapshot != null) {
            val isDark = SkinColorMode.isDark(context)
            val base = snapshot.effectiveColorScheme(isDark)
            val presence = snapshot.geometryPresence
            val keyboardPresence = snapshot.keyboardGeometry.presence
            val stylePresence = if (isDark) {
                snapshot.darkStylePresence ?: snapshot.stylePresence
            } else {
                snapshot.stylePresence
            }
            val skinHasRadius = GeometryField.CORNER_RADIUS in presence ||
                KeyboardGeometryField.KEY_RADIUS in keyboardPresence
            // 圆角/间距最终优先级：geometry > appearance > App 设置。
            // 构建器已按此优先级把胜出值写进 base；此处只需判断
            // “皮肤是否指定了该项”（两级任一 presence），未指定读偏好。
            return applyGlobalStyle(
                base.copy(
                    cornerRadius = if (skinHasRadius) {
                        base.cornerRadius
                    } else {
                        GlobalStyleResolver.keyCornerRadius(global, false)
                            ?: KeyboardManager.Keyboard.KeyRadius.getDp(context).toFloat()
                    },
                    keyHMargin = if (GeometryField.KEY_H_MARGIN in presence ||
                        KeyboardGeometryField.KEY_H_GAP in keyboardPresence
                    ) {
                        base.keyHMargin
                    } else {
                        KeyboardManager.Keyboard.Gap.getHorizontalDp(context).toFloat()
                    },
                    keyVMargin = if (GeometryField.KEY_V_MARGIN in presence ||
                        KeyboardGeometryField.KEY_V_GAP in keyboardPresence
                    ) {
                        base.keyVMargin
                    } else {
                        KeyboardManager.Keyboard.Gap.getVerticalDp(context).toFloat()
                    },
                ),
                global, stylePresence,
            )
        }
        val theme = themeFor(context)
        // 莫奈色是动态主题：Android 12+ 跟随系统 Material You 取色，
        // 低版本或取色失败时 MonetPalette 内部回落静态色板。
        val c = if (theme.id == MonetPalette.ID) MonetPalette.build(context) else theme.colors
        val userRadius = KeyboardManager.Keyboard.KeyRadius.getDp(context).toFloat()
        val userHMargin = KeyboardManager.Keyboard.Gap.getHorizontalDp(context).toFloat()
        val userVMargin = KeyboardManager.Keyboard.Gap.getVerticalDp(context).toFloat()
        return applyGlobalStyle(
            c.copy(
                cornerRadius = GlobalStyleResolver.keyCornerRadius(global, false)
                    ?: userRadius,
                keyHMargin = userHMargin,
                keyVMargin = userVMargin,
            ),
            global, emptySet(),
        )
    }

    /**
     * 全局样式覆盖（纯组合，可单测逻辑见 GlobalStyleResolver）：
     * 颜色只覆盖皮肤未指定字段；边框宽无皮肤字段，全局值恒生效
     * （未设为 1dp，与旧硬编码一致）；栏背景同理。
     */
    private fun applyGlobalStyle(
        scheme: ColorScheme,
        global: GlobalStyle,
        stylePresence: Set<StyleField>,
    ): ColorScheme {
        return scheme.copy(
            keyBackground = GlobalStyleResolver.keyBackground(global, stylePresence)
                ?: scheme.keyBackground,
            specialKeyBackground = GlobalStyleResolver.specialKeyBackground(global, stylePresence)
                ?: scheme.specialKeyBackground,
            accentKeyBackground = GlobalStyleResolver.accentKeyBackground(global, stylePresence)
                ?: scheme.accentKeyBackground,
            keyBorderStroke = GlobalStyleResolver.keyBorderStroke(global, stylePresence)
                ?: scheme.keyBorderStroke,
            specialKeyBorderStroke = GlobalStyleResolver.specialKeyBorderStroke(global, stylePresence)
                ?: scheme.specialKeyBorderStroke,
            accentKeyBorderStroke = GlobalStyleResolver.accentKeyBorderStroke(global, stylePresence)
                ?: scheme.accentKeyBorderStroke,
            keyBorderWidthDp = GlobalStyleResolver.clampBorderWidth(global.key.borderWidthDp),
            panel = scheme.panel.copy(
                background = GlobalStyleResolver.barBackground(global, stylePresence)
                    ?: scheme.panel.background,
            ),
        )
    }

    /**
     * 内置主题深浅的唯一决定因素（2026-11-03：只留颜色模式三项）。
     * 卡片只选主题家族（默认双槽／莫奈色／导入固定主题），深浅一律由
     * 颜色模式决定，默认与自定义皮肤同口径（自定义皮肤见快照分支的
     * SkinColorMode.isDark，与此同源）：
     * - LIGHT/DARK：强制取对应浅色／深色槽位（莫奈色与导入固定主题
     *   在此亦被强制到槽位，现行行为保留）；
     * - SYSTEM：主题 id 在浅／深槽位对内时按系统深浅取槽位（选了
     *   跟随就强制跟随，旧的顶栏关跟随固定住默认对的遗留状态不再
     *   生效）；主题 id 在槽位对之外时返回固定主题本身（莫奈色调
     *   色板自身按系统深浅分支；单色板导入主题无深浅变体，保持原样）。
     * 纯函数，可 JVM 单测；旧的跟随开关（KeyboardManager follow_system）
     * 不再被本函数读取，仅作遗留存储。
     */
    fun effectiveBuiltinThemeId(
        mode: LibraryColorMode,
        themeId: String,
        lightThemeId: String,
        darkThemeId: String,
        isSystemDark: Boolean,
    ): String = when (mode) {
        LibraryColorMode.LIGHT -> lightThemeId
        LibraryColorMode.DARK -> darkThemeId
        LibraryColorMode.SYSTEM -> when (themeId) {
            lightThemeId, darkThemeId ->
                if (isSystemDark) darkThemeId else lightThemeId
            else -> themeId
        }
    }

    fun themeFor(context: Context): KeyboardTheme {
        // v3 快照分支在 resolve() 中优先于本函数；无快照时走下方
        // 颜色模式唯一决定逻辑（见 effectiveBuiltinThemeId）。
        val isDark = context.resources.configuration.uiMode and
            Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        val themeId = effectiveBuiltinThemeId(
            SkinLibraryPrefs.getColorMode(context),
            KeyboardManager.Keyboard.getThemeId(context),
            KeyboardManager.Keyboard.getLightThemeId(context),
            KeyboardManager.Keyboard.getDarkThemeId(context),
            isDark,
        )
        return KeyboardTheme.byId(themeId)
    }
}
