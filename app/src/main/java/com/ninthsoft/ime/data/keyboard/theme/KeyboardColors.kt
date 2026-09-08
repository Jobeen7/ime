package com.ninthsoft.ime.data.keyboard.theme

import android.content.Context
import android.content.res.Configuration
import com.ninthsoft.ime.data.manager.KeyboardManager
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
        ) {
            companion object {
                fun from(c: ColorScheme) = PanelColors(
                    background = c.background,
                    toolbarText = c.keyText,
                    toolbarActived = c.accentKeyBackground,
                    toolbarIcon = c.specialKeyText,
                    candidateBackground = c.specialKeyBackground,
                    candidateText = c.keyText,
                    candidateIndex = c.altText,
                    candidateDivider = c.altText,
                    toolbarPressed = c.keyPressed,
                )
            }
        }

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
