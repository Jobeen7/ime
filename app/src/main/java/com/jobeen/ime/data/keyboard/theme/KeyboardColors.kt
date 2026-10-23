package com.jobeen.ime.data.keyboard.theme

import android.content.Context
import android.content.res.Configuration
import com.jobeen.ime.data.manager.KeyboardManager
import com.jobeen.ime.skin.model.GeometryField
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
        // 偏好（定稿三.4.2），且快照优先于「跟随系统深浅色」（B3）；
        // 无快照时走下方现行路径，行为与 v1.1.14 逐字节一致。
        val snapshot = SkinSnapshotProvider.current(context)
        if (snapshot != null) {
            val base = snapshot.colorScheme
            val presence = snapshot.geometryPresence
            return base.copy(
                cornerRadius = if (GeometryField.CORNER_RADIUS in presence) {
                    base.cornerRadius
                } else {
                    KeyboardManager.Keyboard.KeyRadius.getDp(context).toFloat()
                },
                keyHMargin = if (GeometryField.KEY_H_MARGIN in presence) {
                    base.keyHMargin
                } else {
                    KeyboardManager.Keyboard.Gap.getHorizontalDp(context).toFloat()
                },
                keyVMargin = if (GeometryField.KEY_V_MARGIN in presence) {
                    base.keyVMargin
                } else {
                    KeyboardManager.Keyboard.Gap.getVerticalDp(context).toFloat()
                },
            )
        }
        val c = themeFor(context).colors
        val userRadius = KeyboardManager.Keyboard.KeyRadius.getDp(context).toFloat()
        val userHMargin = KeyboardManager.Keyboard.Gap.getHorizontalDp(context).toFloat()
        val userVMargin = KeyboardManager.Keyboard.Gap.getVerticalDp(context).toFloat()
        return c.copy(
            cornerRadius = userRadius,
            keyHMargin = userHMargin,
            keyVMargin = userVMargin,
        )
    }

    fun themeFor(context: Context): KeyboardTheme {
        val followSystem = KeyboardManager.Keyboard.getFollowSystem(context)
        if (followSystem) {
            val isDark = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
                    Configuration.UI_MODE_NIGHT_YES
            val themeId = if (isDark) KeyboardManager.Keyboard.getDarkThemeId(context)
                else KeyboardManager.Keyboard.getLightThemeId(context)
            return KeyboardTheme.byId(themeId)
        }
        val themeId = KeyboardManager.Keyboard.getThemeId(context)
        return KeyboardTheme.byId(themeId)
    }
}
