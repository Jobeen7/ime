package com.jobeen.ime.data.keyboard.theme

import android.graphics.Color

object KeyboardThemePresets {

    @Volatile
    var customThemes: List<KeyboardTheme> = emptyList()
        private set

    fun setCustomThemes(themes: List<KeyboardTheme>) {
        customThemes = themes
    }

    val Amoled = KeyboardTheme(
        id = "amoled",
        name = "暗夜",
        colors = KeyboardColors.ColorScheme(
            keyBackground = Color.rgb(58, 58, 60),
            keyPressed = Color.rgb(72, 72, 74),
            keyBorderStroke = Color.argb(40, 255, 255, 255),
            specialKeyBackground = Color.rgb(58, 58, 60),
            specialKeyPressed = Color.rgb(72, 72, 74),
            specialKeyBorderStroke = Color.argb(120, 46, 46, 46),
            accentKeyBackground = Color.rgb(156, 130, 74),
            accentKeyPressed = Color.rgb(72, 72, 74),
            accentKeyBorderStroke = Color.rgb(156, 130, 74),
            keyText = Color.rgb(255, 255, 255),
            specialKeyText = Color.rgb(255, 255, 255),
            accentKeyText = Color.rgb(255, 255, 255),
            altText = Color.rgb(130, 130, 130),
            background = Color.rgb(22, 22, 22),
            panel = KeyboardColors.ColorScheme.PanelColors(
                background = Color.rgb(22, 22, 22),
                toolbarText = Color.rgb(255, 255, 255),
                toolbarActived = Color.rgb(156, 130, 74),
                toolbarIcon = Color.rgb(255, 255, 255),
                // 工具栏按压是半透明涟漪（alpha≤120）叠在面板底 22 灰上，
                // 取键面 pressed 的 72 灰混合后对比仅 1.33、肉眼不可辨；
                // 与 PanelColors.from 派生口径一致取 specialKeyText
                // 纯白，混合后约 3.9:1
                toolbarPressed = Color.rgb(255, 255, 255),
                candidateBackground = Color.argb(220, 22, 22, 22),
                candidateText = Color.rgb(255, 255, 255),
                candidateIndex = Color.rgb(130, 130, 130),
                candidateDivider = Color.argb(120, 130, 130, 130),
            ),
            pinner = KeyboardColors.ColorScheme.PinnerColors(
                background = Color.rgb(22, 22, 22),
                textColor = Color.rgb(255, 255, 255),
                secondaryTextColor = Color.rgb(120, 120, 120),
            ),
            toastBackground = Color.rgb(36, 36, 38),
            toastText = Color.rgb(245, 245, 247),
        ),
    )

    val Light = KeyboardTheme(
        id = "light",
        name = "素白",
        colors = KeyboardColors.ColorScheme(
            keyBackground = Color.rgb(255, 255, 255),
            keyPressed = Color.rgb(230, 230, 230),
            keyBorderStroke = Color.rgb(210, 210, 215),
            specialKeyText = Color.rgb(60, 60, 65),
            specialKeyBackground = Color.rgb(255, 255, 255),
            specialKeyPressed = Color.rgb(230, 230, 230),
            specialKeyBorderStroke = Color.rgb(210, 210, 215),
            accentKeyBackground = Color.rgb(156, 130, 74),
            accentKeyPressed = Color.rgb(140, 114, 62),
            accentKeyBorderStroke = Color.rgb(156, 130, 74),
            keyText = Color.rgb(0, 0, 0),
            accentKeyText = Color.rgb(255, 255, 255),
            altText = Color.rgb(120, 120, 125),
            background = Color.rgb(225, 226, 231),
            surfaceStyle = KeyboardColors.SurfaceStyle.Raised,
            panel = KeyboardColors.ColorScheme.PanelColors(
                background = Color.rgb(225, 226, 231),
                toolbarText = Color.rgb(20, 20, 20),
                toolbarActived = Color.rgb(156, 130, 74),
                toolbarIcon = Color.rgb(80, 80, 85),
                candidateBackground = Color.rgb(255, 255, 255),
                candidateText = Color.rgb(20, 20, 20),
                candidateIndex = Color.rgb(120, 120, 125),
                candidateDivider = Color.argb(100, 140, 140, 145),
                // 同暗夜：涟漪叠在面板底 225 灰上，230 与底几乎同色
                // （混合后约 1.0:1、不可见）；取功能键文字的深灰，
                // 混合后约 2.3:1
                toolbarPressed = Color.rgb(60, 60, 65),
            ),
            pinner = KeyboardColors.ColorScheme.PinnerColors(
                background = Color.rgb(255, 255, 255),
                textColor = Color.rgb(20, 20, 20),
                secondaryTextColor = Color.rgb(130, 130, 135),
            ),
            toastBackground = Color.rgb(45, 45, 48),
            toastText = Color.rgb(255, 255, 255),
        ),
    )

    val Sunset = KeyboardTheme(
        id = "sunset",
        name = "落日",
        colors = KeyboardColors.ColorScheme(
            keyBackground = Color.rgb(245, 238, 230),
            keyPressed = Color.rgb(230, 215, 200),
            keyBorderStroke = Color.rgb(230, 215, 200),
            specialKeyBackground = Color.rgb(245, 238, 230),
            specialKeyPressed = Color.rgb(230, 215, 200),
            specialKeyBorderStroke = Color.rgb(230, 215, 200),
            accentKeyBackground = Color.rgb(242, 100, 25),
            accentKeyPressed = Color.rgb(220, 85, 15),
            accentKeyBorderStroke = Color.rgb(242, 100, 25),
            keyText = Color.rgb(50, 40, 35),
            specialKeyText = Color.rgb(120, 100, 90),
            accentKeyText = Color.rgb(255, 255, 255),
            altText = Color.rgb(140, 120, 110),
            background = Color.rgb(250, 245, 240),
            surfaceStyle = KeyboardColors.SurfaceStyle.Raised,
            panel = KeyboardColors.ColorScheme.PanelColors(
                background = Color.rgb(242, 235, 228),
                toolbarText = Color.rgb(50, 40, 35),
                toolbarActived = Color.rgb(242, 100, 25),
                toolbarIcon = Color.rgb(120, 100, 90),
                candidateBackground = Color.rgb(255, 255, 255),
                candidateText = Color.rgb(50, 40, 35),
                candidateIndex = Color.rgb(140, 120, 110),
                candidateDivider = Color.argb(100, 220, 200, 185),
                // 同上：原值与面板底 242,235,228 几乎同色（混合后约
                // 1.1:1）；取功能键文字的暖灰，混合后约 1.9:1
                toolbarPressed = Color.rgb(120, 100, 90),
            ),
            pinner = KeyboardColors.ColorScheme.PinnerColors(
                background = Color.rgb(245, 238, 230),
                textColor = Color.rgb(50, 40, 35),
                secondaryTextColor = Color.rgb(120, 100, 90),
            ),
            toastBackground = Color.rgb(60, 50, 45),
            toastText = Color.rgb(255, 255, 255),
        ),
    )

    val ALL: List<KeyboardTheme>
        get() = listOf(
            Amoled,
            Light,
            Sunset,
        ) + customThemes
}