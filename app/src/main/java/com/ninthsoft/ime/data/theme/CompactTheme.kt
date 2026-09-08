package com.ninthsoft.ime.data.theme

import com.ninthsoft.ime.data.keyboard.theme.KeyboardColors
import com.ninthsoft.ime.data.keyboard.theme.KeyboardTheme
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class CompactTheme(
    @SerialName("i") val id: String,
    @SerialName("n") val name: String,
    @SerialName("c") val colors: CompactColors,
) {
    fun toKeyboardTheme(): KeyboardTheme = KeyboardTheme(
        id = id,
        name = name,
        colors = colors.toColorScheme(),
    )

    companion object {
        fun from(theme: KeyboardTheme): CompactTheme {
            val colors = theme.colors
            return CompactTheme(
                id = theme.id,
                name = theme.name,
                colors = CompactColors(
                    keyBackground = colors.keyBackground,
                    keyPressed = colors.keyPressed,
                    keyBorderStroke = colors.keyBorderStroke,
                    specialKeyBackground = colors.specialKeyBackground,
                    specialKeyPressed = colors.specialKeyPressed,
                    specialKeyBorderStroke = colors.specialKeyBorderStroke,
                    accentKeyBackground = colors.accentKeyBackground,
                    accentKeyPressed = colors.accentKeyPressed,
                    accentKeyBorderStroke = colors.accentKeyBorderStroke,
                    keyText = colors.keyText,
                    specialKeyText = colors.specialKeyText,
                    accentKeyText = colors.accentKeyText,
                    altText = colors.altText,
                    background = colors.background,
                    surfaceStyle = if (colors.surfaceStyle == KeyboardColors.SurfaceStyle.Flat) {
                        CompactSurfaceStyle.Flat
                    } else {
                        CompactSurfaceStyle.Raised
                    },
                    cornerRadius = colors.cornerRadius,
                    keyHMargin = colors.keyHMargin,
                    keyVMargin = colors.keyVMargin,
                    panel = CompactPanel(
                        background = colors.panel.background,
                        toolbarText = colors.panel.toolbarText,
                        toolbarActived = colors.panel.toolbarActived,
                        toolbarIcon = colors.panel.toolbarIcon,
                        candidateBackground = colors.panel.candidateBackground,
                        candidateText = colors.panel.candidateText,
                        candidateIndex = colors.panel.candidateIndex,
                        candidateDivider = colors.panel.candidateDivider,
                        toolbarPressed = colors.panel.toolbarPressed,
                    ),
                    pinner = CompactPinner(
                        background = colors.pinner.background,
                        textColor = colors.pinner.textColor,
                        secondaryTextColor = colors.pinner.secondaryTextColor,
                    ),
                    toastBackground = colors.toastBackground,
                    toastText = colors.toastText,
                )
            )
        }
    }
}

@Serializable
data class CompactColors(
    @SerialName("kb") val keyBackground: Int,
    @SerialName("kp") val keyPressed: Int,
    @SerialName("kbs") val keyBorderStroke: Int,
    @SerialName("skb") val specialKeyBackground: Int,
    @SerialName("skp") val specialKeyPressed: Int,
    @SerialName("skbs") val specialKeyBorderStroke: Int,
    @SerialName("akb") val accentKeyBackground: Int,
    @SerialName("akp") val accentKeyPressed: Int,
    @SerialName("akbs") val accentKeyBorderStroke: Int,
    @SerialName("kt") val keyText: Int,
    @SerialName("skt") val specialKeyText: Int,
    @SerialName("akt") val accentKeyText: Int,
    @SerialName("alt") val altText: Int,
    @SerialName("bg") val background: Int,
    @SerialName("ss") val surfaceStyle: CompactSurfaceStyle = CompactSurfaceStyle.Raised,
    @SerialName("cr") val cornerRadius: Float = 5f,
    @SerialName("hm") val keyHMargin: Float = 3f,
    @SerialName("vm") val keyVMargin: Float = 4f,
    @SerialName("p") val panel: CompactPanel,
    @SerialName("pn") val pinner: CompactPinner,
    @SerialName("tb") val toastBackground: Int? = null,
    @SerialName("tt") val toastText: Int? = null,
) {
    fun toColorScheme(): KeyboardColors.ColorScheme = KeyboardColors.ColorScheme(
        keyBackground = keyBackground,
        keyPressed = keyPressed,
        keyBorderStroke = keyBorderStroke,
        specialKeyBackground = specialKeyBackground,
        specialKeyPressed = specialKeyPressed,
        specialKeyBorderStroke = specialKeyBorderStroke,
        accentKeyBackground = accentKeyBackground,
        accentKeyPressed = accentKeyPressed,
        accentKeyBorderStroke = accentKeyBorderStroke,
        keyText = keyText,
        specialKeyText = specialKeyText,
        accentKeyText = accentKeyText,
        altText = altText,
        background = background,
        surfaceStyle = if (surfaceStyle == CompactSurfaceStyle.Flat) {
            KeyboardColors.SurfaceStyle.Flat
        } else {
            KeyboardColors.SurfaceStyle.Raised
        },
        cornerRadius = cornerRadius,
        keyHMargin = keyHMargin,
        keyVMargin = keyVMargin,
        panel = KeyboardColors.ColorScheme.PanelColors(
            background = panel.background,
            toolbarText = panel.toolbarText,
            toolbarActived = panel.toolbarActived,
            toolbarIcon = panel.toolbarIcon,
            candidateBackground = panel.candidateBackground,
            candidateText = panel.candidateText,
            candidateIndex = panel.candidateIndex,
            candidateDivider = panel.candidateDivider,
            toolbarPressed = panel.toolbarPressed,
        ),
        pinner = KeyboardColors.ColorScheme.PinnerColors(
            background = pinner.background,
            textColor = pinner.textColor,
            secondaryTextColor = pinner.secondaryTextColor,
        ),
        toastBackground = toastBackground ?: specialKeyBackground,
        toastText = toastText ?: keyText,
    )
}

@Serializable
enum class CompactSurfaceStyle {
    Raised,
    Flat,
}

@Serializable
data class CompactPanel(
    @SerialName("bg") val background: Int,
    @SerialName("tt") val toolbarText: Int,
    @SerialName("ta") val toolbarActived: Int,
    @SerialName("ti") val toolbarIcon: Int,
    @SerialName("cb") val candidateBackground: Int,
    @SerialName("ct") val candidateText: Int,
    @SerialName("ci") val candidateIndex: Int,
    @SerialName("cd") val candidateDivider: Int,
    @SerialName("tp") val toolbarPressed: Int,
)

@Serializable
data class CompactPinner(
    @SerialName("bg") val background: Int,
    @SerialName("tc") val textColor: Int,
    @SerialName("stc") val secondaryTextColor: Int,
)