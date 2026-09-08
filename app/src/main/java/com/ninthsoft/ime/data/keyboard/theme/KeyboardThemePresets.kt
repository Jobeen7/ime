package com.ninthsoft.ime.data.keyboard.theme

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
            keyBackground = Color.argb(220, 46, 46, 46),
            keyPressed = Color.rgb(58, 58, 58),
            keyBorderStroke = Color.argb(40, 255, 255, 255),
            specialKeyBackground = Color.argb(120, 46, 46, 46),
            specialKeyPressed = Color.argb(120, 72, 72, 72),
            specialKeyBorderStroke = Color.argb(120, 46, 46, 46),
            accentKeyBackground = Color.rgb(36, 86, 88),
            accentKeyPressed = Color.rgb(52, 102, 104),
            accentKeyBorderStroke = Color.rgb(36, 86, 88),
            keyText = Color.rgb(220, 220, 220),
            specialKeyText = Color.rgb(155, 155, 155),
            accentKeyText = Color.rgb(220, 220, 220),
            altText = Color.rgb(130, 130, 130),
            background = Color.rgb(10, 10, 10),
            panel = KeyboardColors.ColorScheme.PanelColors(
                background = Color.rgb(10, 10, 10),
                toolbarText = Color.rgb(220, 220, 220),
                toolbarActived = Color.rgb(80, 200, 205),
                toolbarIcon = Color.rgb(170, 170, 170),
                toolbarPressed = Color.rgb(220, 220, 220),
                candidateBackground = Color.argb(220, 22, 22, 22),
                candidateText = Color.rgb(220, 220, 220),
                candidateIndex = Color.rgb(130, 130, 130),
                candidateDivider = Color.argb(120, 130, 130, 130),
            ),
            pinner = KeyboardColors.ColorScheme.PinnerColors(
                background = Color.rgb(26, 26, 26),
                textColor = Color.rgb(220, 220, 220),
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
            keyPressed = Color.rgb(224, 224, 229),
            keyBorderStroke = Color.rgb(210, 210, 215),
            specialKeyText = Color.rgb(60, 60, 65),
            specialKeyBackground = Color.rgb(230, 230, 230),
            specialKeyPressed = Color.rgb(190, 190, 195),
            specialKeyBorderStroke = Color.rgb(210, 210, 215),
            accentKeyBackground = Color.rgb(0, 122, 255),
            accentKeyPressed = Color.rgb(0, 100, 220),
            accentKeyBorderStroke = Color.rgb(0, 122, 255),
            keyText = Color.rgb(0, 0, 0),
            accentKeyText = Color.rgb(255, 255, 255),
            altText = Color.rgb(120, 120, 125),
            background = Color.rgb(242, 242, 242),
            surfaceStyle = KeyboardColors.SurfaceStyle.Raised,
            panel = KeyboardColors.ColorScheme.PanelColors(
                background = Color.rgb(235, 235, 235),
                toolbarText = Color.rgb(20, 20, 20),
                toolbarActived = Color.rgb(0, 100, 220),
                toolbarIcon = Color.rgb(80, 80, 85),
                candidateBackground = Color.rgb(255, 255, 255),
                candidateText = Color.rgb(20, 20, 20),
                candidateIndex = Color.rgb(120, 120, 125),
                candidateDivider = Color.argb(100, 140, 140, 145),
                toolbarPressed = Color.rgb(224, 224, 229),
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
                toolbarPressed = Color.rgb(230, 215, 200),
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