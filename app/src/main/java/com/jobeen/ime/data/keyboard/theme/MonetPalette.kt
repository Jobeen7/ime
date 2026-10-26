package com.jobeen.ime.data.keyboard.theme

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.os.Build
import androidx.annotation.RequiresApi

/**
 * Builds a keyboard palette from the system Material You (Monet) dynamic colors.
 * Falls back to a static Material 3 blue tone palette on devices below Android 12.
 */
object MonetPalette {
    const val ID = "monet"

    fun isDark(context: Context): Boolean =
        context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES

    fun build(context: Context): KeyboardColors.ColorScheme {
        val dark = isDark(context)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return if (dark) DarkFallback else LightFallback
        }
        return runCatching { if (dark) dark(context) else light(context) }.getOrElse { if (dark) DarkFallback else LightFallback }
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun light(context: Context): KeyboardColors.ColorScheme {
        // 键盘底板：neutral1_100，柔和浅灰底
        val background = context.getColor(android.R.color.system_neutral1_100)
        // 普通字母键：最亮表面 (neutral1_10 或 50)，在 100 底板上自然凸显
        val keyBackground = context.getColor(android.R.color.system_neutral1_10)
        val keyPressed = context.getColor(android.R.color.system_neutral2_200)
        val border = context.getColor(android.R.color.system_neutral2_200)
        // 功能键（Shift/退格/符号）：使用 accent2_100 赋予温和的莫奈次级色调
        val specialBackground = context.getColor(android.R.color.system_accent2_100)
        val specialPressed = context.getColor(android.R.color.system_accent2_200)
        val specialBorder = context.getColor(android.R.color.system_accent2_200)
        // 强调键（回车/搜索）：使用主色 accent1_600
        val accent = context.getColor(android.R.color.system_accent1_600)
        val accentPressed = context.getColor(android.R.color.system_accent1_700)
        // 文本与图标颜色
        val keyText = context.getColor(android.R.color.system_neutral1_900)
        val specialText = context.getColor(android.R.color.system_accent2_800)
        val accentText = context.getColor(android.R.color.system_accent1_0)
        val altText = context.getColor(android.R.color.system_neutral2_500)

        return KeyboardColors.ColorScheme(
            keyBackground = keyBackground,
            keyPressed = keyPressed,
            keyBorderStroke = border,
            specialKeyBackground = specialBackground,
            specialKeyPressed = specialPressed,
            specialKeyBorderStroke = specialBorder,
            accentKeyBackground = accent,
            accentKeyPressed = accentPressed,
            accentKeyBorderStroke = accent,
            keyText = keyText,
            specialKeyText = specialText,
            accentKeyText = accentText,
            altText = altText,
            background = background,
            surfaceStyle = KeyboardColors.SurfaceStyle.Raised,
            panel = KeyboardColors.ColorScheme.PanelColors(
                background = background,
                toolbarText = keyText,
                toolbarActived = accent,
                toolbarIcon = specialText,
                // 候选栏与键盘底板融为一体，避免与第一排悬浮按键粘连
                candidateBackground = background,
                candidateText = keyText,
                candidateIndex = altText,
                candidateDivider = withAlpha(100, altText),
                toolbarPressed = keyPressed,
            ),
            pinner = KeyboardColors.ColorScheme.PinnerColors(
                background = keyBackground,
                textColor = keyText,
                secondaryTextColor = altText,
            ),
            toastBackground = context.getColor(android.R.color.system_neutral2_800),
            toastText = context.getColor(android.R.color.system_neutral1_50),
        )
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun dark(context: Context): KeyboardColors.ColorScheme {
        // 键盘底板：最深层级 (neutral1_900)
        val background = context.getColor(android.R.color.system_neutral1_900)

        // 普通字母键：比底板高一阶 (neutral1_800)
        val keyBackground = context.getColor(android.R.color.system_neutral1_800)
        val keyPressed = context.getColor(android.R.color.system_neutral1_700)
        val border = context.getColor(android.R.color.system_neutral2_700)

        // 功能键：使用 accent2_700，在明度和莫奈色相上同时与普通键(800)拉开层次
        val specialBackground = context.getColor(android.R.color.system_accent2_700)
        val specialPressed = context.getColor(android.R.color.system_accent2_600)
        val specialBorder = context.getColor(android.R.color.system_accent2_600)

        // 强调键（回车/搜索）：深色模式下使用高明度低饱和的 accent1_200
        val accent = context.getColor(android.R.color.system_accent1_200)
        val accentPressed = context.getColor(android.R.color.system_accent1_300)

        // 文本与图标颜色
        val keyText = context.getColor(android.R.color.system_neutral1_100)
        val specialText = context.getColor(android.R.color.system_accent2_100)
        val accentText = context.getColor(android.R.color.system_accent1_800)
        val altText = context.getColor(android.R.color.system_neutral2_400)

        return KeyboardColors.ColorScheme(
            keyBackground = keyBackground,
            keyPressed = keyPressed,
            keyBorderStroke = border,
            specialKeyBackground = specialBackground,
            specialKeyPressed = specialPressed,
            specialKeyBorderStroke = specialBorder,
            accentKeyBackground = accent,
            accentKeyPressed = accentPressed,
            accentKeyBorderStroke = accent,
            keyText = keyText,
            specialKeyText = specialText,
            accentKeyText = accentText,
            altText = altText,
            background = background,
            surfaceStyle = KeyboardColors.SurfaceStyle.Raised,
            panel = KeyboardColors.ColorScheme.PanelColors(
                background = background,
                toolbarText = keyText,
                toolbarActived = accent,
                toolbarIcon = specialText,
                candidateBackground = background,
                candidateText = keyText,
                candidateIndex = altText,
                candidateDivider = withAlpha(100, altText),
                toolbarPressed = keyPressed,
            ),
            pinner = KeyboardColors.ColorScheme.PinnerColors(
                background = keyBackground,
                textColor = keyText,
                secondaryTextColor = altText,
            ),
            toastBackground = context.getColor(android.R.color.system_neutral2_700),
            toastText = context.getColor(android.R.color.system_neutral1_50),
        )
    }

    private fun withAlpha(alpha: Int, color: Int): Int = (color and 0x00FFFFFF) or (alpha shl 24)

    val LightFallback = KeyboardColors.ColorScheme(
        keyBackground = Color.rgb(253, 251, 255),
        keyPressed = Color.rgb(226, 226, 236),
        keyBorderStroke = Color.rgb(226, 226, 236),
        specialKeyBackground = Color.rgb(220, 226, 249),
        specialKeyPressed = Color.rgb(198, 206, 235),
        specialKeyBorderStroke = Color.rgb(198, 206, 235),
        accentKeyBackground = Color.rgb(65, 95, 145),
        accentKeyPressed = Color.rgb(51, 78, 128),
        accentKeyBorderStroke = Color.rgb(65, 95, 145),
        keyText = Color.rgb(26, 27, 32),
        specialKeyText = Color.rgb(41, 50, 72),
        accentKeyText = Color.rgb(255, 255, 255),
        altText = Color.rgb(116, 119, 127),
        background = Color.rgb(238, 240, 248),
        surfaceStyle = KeyboardColors.SurfaceStyle.Raised,
        panel = KeyboardColors.ColorScheme.PanelColors(
            background = Color.rgb(238, 240, 248),
            toolbarText = Color.rgb(26, 27, 32),
            toolbarActived = Color.rgb(65, 95, 145),
            toolbarIcon = Color.rgb(41, 50, 72),
            candidateBackground = Color.rgb(238, 240, 248),
            candidateText = Color.rgb(26, 27, 32),
            candidateIndex = Color.rgb(116, 119, 127),
            candidateDivider = Color.argb(100, 116, 119, 127),
            toolbarPressed = Color.rgb(226, 226, 236),
        ),
        pinner = KeyboardColors.ColorScheme.PinnerColors(
            background = Color.rgb(253, 251, 255),
            textColor = Color.rgb(26, 27, 32),
            secondaryTextColor = Color.rgb(116, 119, 127),
        ),
        toastBackground = Color.rgb(48, 48, 52),
        toastText = Color.rgb(242, 240, 244),
    )

    val DarkFallback = KeyboardColors.ColorScheme(
        keyBackground = Color.rgb(30, 31, 37),
        keyPressed = Color.rgb(46, 48, 54),
        keyBorderStroke = Color.rgb(46, 48, 54),
        specialKeyBackground = Color.rgb(52, 60, 82),
        specialKeyPressed = Color.rgb(68, 77, 102),
        specialKeyBorderStroke = Color.rgb(68, 77, 102),
        accentKeyBackground = Color.rgb(170, 199, 255),
        accentKeyPressed = Color.rgb(196, 212, 244),
        accentKeyBorderStroke = Color.rgb(170, 199, 255),
        keyText = Color.rgb(227, 226, 230),
        specialKeyText = Color.rgb(218, 226, 249),
        accentKeyText = Color.rgb(17, 41, 75),
        altText = Color.rgb(142, 144, 153),
        background = Color.rgb(17, 19, 24),
        surfaceStyle = KeyboardColors.SurfaceStyle.Raised,
        panel = KeyboardColors.ColorScheme.PanelColors(
            background = Color.rgb(17, 19, 24),
            toolbarText = Color.rgb(227, 226, 230),
            toolbarActived = Color.rgb(170, 199, 255),
            toolbarIcon = Color.rgb(218, 226, 249),
            candidateBackground = Color.rgb(17, 19, 24),
            candidateText = Color.rgb(227, 226, 230),
            candidateIndex = Color.rgb(142, 144, 153),
            candidateDivider = Color.argb(100, 142, 144, 153),
            toolbarPressed = Color.rgb(46, 48, 54),
        ),
        pinner = KeyboardColors.ColorScheme.PinnerColors(
            background = Color.rgb(30, 31, 37),
            textColor = Color.rgb(227, 226, 230),
            secondaryTextColor = Color.rgb(142, 144, 153),
        ),
        toastBackground = Color.rgb(46, 48, 54),
        toastText = Color.rgb(242, 240, 244),
    )
}