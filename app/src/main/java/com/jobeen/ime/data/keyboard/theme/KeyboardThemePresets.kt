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
            // 皮肤深色按键无描边（键面纯色＋间距分隔），描边清零。
            keyBorderStroke = Color.argb(0, 0, 0, 0),
            specialKeyBackground = Color.rgb(58, 58, 60),
            specialKeyPressed = Color.rgb(72, 72, 74),
            specialKeyBorderStroke = Color.argb(0, 0, 0, 0),
            accentKeyBackground = Color.rgb(156, 130, 74),
            accentKeyPressed = Color.rgb(72, 72, 74),
            // 2026-11-05：内置描边全部去掉，回车强调键描边亦清零。
            accentKeyBorderStroke = Color.argb(0, 0, 0, 0),
            keyText = Color.rgb(255, 255, 255),
            specialKeyText = Color.rgb(255, 255, 255),
            accentKeyText = Color.rgb(255, 255, 255),
            // 皮肤深色次要文字（九键数字角标）#FFFFFF66。
            altText = Color.argb(102, 255, 255, 255),
            // 皮肤深色键盘底 #00000003，近纯黑。
            background = Color.rgb(0, 0, 0),
            panel = KeyboardColors.ColorScheme.PanelColors(
                // 与键盘底同黑，否则候选条与键区之间出一道深灰接缝；
                // 皮肤工具栏/候选面板底同样是 #00000003。
                background = Color.rgb(0, 0, 0),
                toolbarText = Color.rgb(255, 255, 255),
                toolbarActived = Color.rgb(10, 132, 255), // #0A84FF，与长按弹窗高亮同值（BaseKeyboard 的 LONG_PRESS_HIGHLIGHT_BLUE）
                toolbarIcon = Color.rgb(255, 255, 255),
                // 工具栏按压是半透明涟漪（峰值 alpha 160，见
                // KawaiiPanelView.startPressAnimation）叠在面板底 22 灰上，
                // 取键面 pressed 的 72 灰混合后对比仅 1.33、肉眼不可辨；
                // 取 specialKeyText 纯白，混合后约 7.6:1
                toolbarPressed = Color.rgb(255, 255, 255),
                candidateBackground = Color.argb(220, 22, 22, 22),
                candidateText = Color.rgb(255, 255, 255),
                candidateIndex = Color.rgb(130, 130, 130),
                candidateDivider = Color.argb(120, 130, 130, 130),
                selectedCandidateText = Color.rgb(35, 200, 145),
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
            // 2026-11-05：内置描边全部去掉（含回车强调键），三项描边透明。
            keyBorderStroke = Color.argb(0, 0, 0, 0),
            specialKeyText = Color.rgb(60, 60, 65),
            specialKeyBackground = Color.rgb(255, 255, 255),
            specialKeyPressed = Color.rgb(230, 230, 230),
            specialKeyBorderStroke = Color.argb(0, 0, 0, 0),
            accentKeyBackground = Color.rgb(156, 130, 74),
            accentKeyPressed = Color.rgb(140, 114, 62),
            accentKeyBorderStroke = Color.argb(0, 0, 0, 0),
            keyText = Color.rgb(0, 0, 0),
            accentKeyText = Color.rgb(255, 255, 255),
            altText = Color.rgb(120, 120, 125),
            background = Color.rgb(225, 226, 231),
            surfaceStyle = KeyboardColors.SurfaceStyle.Raised,
            panel = KeyboardColors.ColorScheme.PanelColors(
                background = Color.rgb(225, 226, 231),
                toolbarText = Color.rgb(20, 20, 20),
                toolbarActived = Color.rgb(10, 132, 255), // #0A84FF，与长按弹窗高亮同值（BaseKeyboard 的 LONG_PRESS_HIGHLIGHT_BLUE）
                toolbarIcon = Color.rgb(80, 80, 85),
                candidateBackground = Color.rgb(255, 255, 255),
                candidateText = Color.rgb(20, 20, 20),
                candidateIndex = Color.rgb(120, 120, 125),
                candidateDivider = Color.argb(100, 140, 140, 145),
                selectedCandidateText = Color.rgb(35, 200, 145),
                // 同暗夜：涟漪叠在面板底 225 灰上，230 与底几乎同色
                // （混合后约 1.0:1、不可见）；墨色取正文 keyText 纯黑、
                // 峰值 alpha 160，混合后约 5.8:1（alpha 120 时用深灰
                // 60,60,65 只有约 2.3:1，低于 3:1）
                toolbarPressed = Color.rgb(0, 0, 0),
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
            // 2026-11-05：内置描边全部去掉（含回车强调键），三项描边透明。
            keyBorderStroke = Color.argb(0, 0, 0, 0),
            specialKeyBackground = Color.rgb(245, 238, 230),
            specialKeyPressed = Color.rgb(230, 215, 200),
            specialKeyBorderStroke = Color.argb(0, 0, 0, 0),
            accentKeyBackground = Color.rgb(242, 100, 25),
            accentKeyPressed = Color.rgb(220, 85, 15),
            accentKeyBorderStroke = Color.argb(0, 0, 0, 0),
            keyText = Color.rgb(50, 40, 35),
            specialKeyText = Color.rgb(120, 100, 90),
            accentKeyText = Color.rgb(255, 255, 255),
            altText = Color.rgb(140, 120, 110),
            background = Color.rgb(250, 245, 240),
            surfaceStyle = KeyboardColors.SurfaceStyle.Raised,
            panel = KeyboardColors.ColorScheme.PanelColors(
                background = Color.rgb(242, 235, 228),
                toolbarText = Color.rgb(50, 40, 35),
                toolbarActived = Color.rgb(10, 132, 255), // #0A84FF，与长按弹窗高亮同值（BaseKeyboard 的 LONG_PRESS_HIGHLIGHT_BLUE）
                toolbarIcon = Color.rgb(120, 100, 90),
                candidateBackground = Color.rgb(255, 255, 255),
                candidateText = Color.rgb(50, 40, 35),
                candidateIndex = Color.rgb(140, 120, 110),
                candidateDivider = Color.argb(100, 220, 200, 185),
                selectedCandidateText = Color.rgb(35, 200, 145),
                // 同上：原值与面板底 242,235,228 几乎同色（混合后约
                // 1.1:1）；墨色取正文 keyText 50,40,35、峰值 alpha 160，
                // 混合后约 4.1:1（暖灰 120,100,90 在 alpha 120 时只有
                // 约 1.9:1，低于 3:1）
                toolbarPressed = Color.rgb(50, 40, 35),
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

    val Monet = KeyboardTheme(
        id = MonetPalette.ID,
        name = "莫奈色",
        colors = MonetPalette.LightFallback,
    )

    // 微信绿（2026-11-05：源包 upload-2.cskin「微信·绿最终版」，只搬全局色，
    // 不加自定义布局，九宫格用内置那套）。近似写明，不藏着：
    // ① 描边：源包无整圈描边，只有底边（浅 #9a9c9a／深 #1D1D1D），预设表达
    //    不了底边，三项描边照暗夜先例全部清零（含回车强调键）。
    // ② 回车绿（2026-11-06 已按源包分色，不再是全类型绿）：源包只在部分
    //    return 类型下用绿（$returnKeyType 1,4,6,7,9 走绿，其余走灰色功能键色），
    //    分色由 QwertyKeyboard.updateEditorInfo 按动作现算（绿预设＋无皮肤时生效）。
    // ③ 键盘高（2026-11-06 已带）：源包 25vh，QwertyKeyboard.HEIGHT_PERCENT_PORTRAIT
    //    ＝25，经 KeyboardWindowView.contentHeight 在无皮肤竖屏 26 键时取用。
    // ④ 长按提示符（2026-11-06 已搬 q–p／h／123 三组，见 QwertyKeyboard.buildLayout）；
    //    工具栏图片、浮动面板、数字九键与符号集合仍不搬；j／d／m 三组（脚本／
    //    sendKeys）无 Jime 等价动作，未搬；上划／下划滑动动作与大写态滑动角标未搬。
    val WeChatGreenLight = KeyboardTheme(
        id = "wechat-green-light",
        name = "26键·浅",
        colors = KeyboardColors.ColorScheme(
            keyBackground = Color.rgb(255, 255, 255),
            keyPressed = Color.rgb(171, 176, 186),
            // 源包字母键无整圈描边，只有底边 #9a9c9a，预设表达不了，照暗夜先例清零。
            keyBorderStroke = Color.argb(0, 0, 0, 0),
            specialKeyBackground = Color.argb(128, 151, 159, 175),
            specialKeyPressed = Color.argb(230, 255, 255, 255),
            specialKeyBorderStroke = Color.argb(0, 0, 0, 0),
            accentKeyBackground = Color.rgb(35, 200, 148),
            accentKeyPressed = Color.argb(230, 255, 255, 255),
            // 回车强调键描边亦清零（源包同无整圈描边）。
            accentKeyBorderStroke = Color.argb(0, 0, 0, 0),
            keyText = Color.rgb(0, 0, 0),
            specialKeyText = Color.rgb(0, 0, 0),
            accentKeyText = Color.rgb(255, 255, 255),
            altText = Color.argb(85, 0, 0, 0),
            // 源包键盘底 #D0D3DA01（alpha 01 近透明），2026-11-06 按源包值取：
            // Jime 窗口后无模糊层，键间缝与面板区将透出底下应用（对端为系统
            // 毛玻璃灰 #E1E2E7），见施工单 3 差距 (f)。
            background = Color.argb(1, 208, 211, 218),
            panel = KeyboardColors.ColorScheme.PanelColors(
                background = Color.argb(1, 208, 211, 218),
                toolbarText = Color.rgb(80, 80, 80),
                toolbarActived = Color.rgb(31, 179, 130),
                toolbarIcon = Color.rgb(80, 80, 80),
                toolbarPressed = Color.rgb(0, 0, 0),
                candidateBackground = Color.rgb(255, 255, 255),
                candidateText = Color.rgb(0, 0, 0),
                candidateIndex = Color.rgb(120, 120, 125),
                candidateDivider = Color.argb(100, 140, 140, 145),
                selectedCandidateText = Color.rgb(31, 179, 130),
            ),
            pinner = KeyboardColors.ColorScheme.PinnerColors(
                background = Color.rgb(255, 255, 255),
                textColor = Color.rgb(0, 0, 0),
                secondaryTextColor = Color.rgb(130, 130, 135),
            ),
            toastBackground = Color.rgb(45, 45, 48),
            toastText = Color.rgb(255, 255, 255),
        ),
    )

    val WeChatGreenDark = KeyboardTheme(
        id = "wechat-green-dark",
        name = "26键·深",
        colors = KeyboardColors.ColorScheme(
            keyBackground = Color.argb(101, 209, 209, 209),
            keyPressed = Color.argb(36, 209, 209, 209),
            // 源包字母键无整圈描边，只有底边 #1D1D1D，预设表达不了，照暗夜先例清零。
            keyBorderStroke = Color.argb(0, 0, 0, 0),
            specialKeyBackground = Color.argb(36, 209, 209, 209),
            specialKeyPressed = Color.argb(89, 209, 209, 209),
            specialKeyBorderStroke = Color.argb(0, 0, 0, 0),
            accentKeyBackground = Color.rgb(35, 200, 148),
            accentKeyPressed = Color.argb(89, 209, 209, 209),
            // 回车强调键描边亦清零（源包同无整圈描边）。
            accentKeyBorderStroke = Color.argb(0, 0, 0, 0),
            keyText = Color.rgb(255, 255, 255),
            specialKeyText = Color.rgb(255, 255, 255),
            accentKeyText = Color.rgb(255, 255, 255),
            altText = Color.rgb(182, 183, 185),
            // 源包键盘底 #47474701（alpha 01 近透明），2026-11-06 按源包值取；
            // Jime 窗口后无模糊层，键间缝与面板区将透出底下应用，见浅色同款注释。
            background = Color.argb(1, 71, 71, 71),
            panel = KeyboardColors.ColorScheme.PanelColors(
                background = Color.argb(1, 71, 71, 71),
                toolbarText = Color.rgb(229, 229, 229),
                toolbarActived = Color.rgb(39, 223, 161),
                toolbarIcon = Color.rgb(229, 229, 229),
                toolbarPressed = Color.rgb(255, 255, 255),
                candidateBackground = Color.argb(220, 22, 22, 22),
                candidateText = Color.rgb(255, 255, 255),
                candidateIndex = Color.rgb(130, 130, 130),
                candidateDivider = Color.argb(120, 130, 130, 130),
                selectedCandidateText = Color.rgb(39, 223, 161),
            ),
            pinner = KeyboardColors.ColorScheme.PinnerColors(
                background = Color.rgb(107, 107, 107),
                textColor = Color.rgb(255, 255, 255),
                secondaryTextColor = Color.rgb(120, 120, 120),
            ),
            toastBackground = Color.rgb(36, 36, 38),
            toastText = Color.rgb(245, 245, 247),
        ),
    )

    val ALL: List<KeyboardTheme>
        get() = listOf(
            Amoled,
            Light,
            Sunset,
            Monet,
            WeChatGreenLight,
            WeChatGreenDark,
        ) + customThemes
}