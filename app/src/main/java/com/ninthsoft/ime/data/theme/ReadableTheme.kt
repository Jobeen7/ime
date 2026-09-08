package com.ninthsoft.ime.data.theme

import com.ninthsoft.ime.data.keyboard.theme.KeyboardColors
import com.ninthsoft.ime.data.keyboard.theme.KeyboardTheme
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonPrimitive

/** 颜色值以可读的 #AARRGGBB 十六进制字符串保存；读取时同时兼容 hex 与数字。 */
object HexColorSerializer : KSerializer<Int> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("ARGBColor", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: Int) {
        encoder.encodeString("#%08X".format(value))
    }

    override fun deserialize(decoder: Decoder): Int {
        val jsonDecoder = decoder as? JsonDecoder
            ?: return decoder.decodeInt()
        val element = jsonDecoder.decodeJsonElement()
        if (element !is JsonPrimitive) {
            throw SerializationException("Invalid color: $element")
        }
        return if (element.isString) {
            parseHex(element.content)
        } else {
            element.content.toInt()
        }
    }

    private fun parseHex(raw: String): Int {
        val clean = raw.trim().removePrefix("#")
        return when (clean.length) {
            8 -> clean.toLong(16).toInt()
            6 -> (0xFF000000L or clean.toLong(16)).toInt()
            else -> throw SerializationException("Invalid hex color: $raw")
        }
    }
}

@Serializable
data class ReadableTheme(
    val id: String,
    val name: String,
    val colors: ReadableColors,
) {
    fun toKeyboardTheme(): KeyboardTheme = KeyboardTheme(
        id = id,
        name = name,
        colors = colors.toColorScheme(),
    )

    companion object {
        fun from(theme: KeyboardTheme): ReadableTheme {
            val colors = theme.colors
            return ReadableTheme(
                id = theme.id,
                name = theme.name,
                colors = ReadableColors(
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
                    panel = ReadablePanel(
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
                    pinner = ReadablePinner(
                        background = colors.pinner.background,
                        textColor = colors.pinner.textColor,
                        secondaryTextColor = colors.pinner.secondaryTextColor,
                    ),
                    toastBackground = colors.toastBackground,
                    toastText = colors.toastText,
                ),
            )
        }
    }
}

@Serializable
data class ReadableColors(
    @Serializable(with = HexColorSerializer::class) val keyBackground: Int,
    @Serializable(with = HexColorSerializer::class) val keyPressed: Int,
    @Serializable(with = HexColorSerializer::class) val keyBorderStroke: Int,
    @Serializable(with = HexColorSerializer::class) val specialKeyBackground: Int,
    @Serializable(with = HexColorSerializer::class) val specialKeyPressed: Int,
    @Serializable(with = HexColorSerializer::class) val specialKeyBorderStroke: Int,
    @Serializable(with = HexColorSerializer::class) val accentKeyBackground: Int,
    @Serializable(with = HexColorSerializer::class) val accentKeyPressed: Int,
    @Serializable(with = HexColorSerializer::class) val accentKeyBorderStroke: Int,
    @Serializable(with = HexColorSerializer::class) val keyText: Int,
    @Serializable(with = HexColorSerializer::class) val specialKeyText: Int,
    @Serializable(with = HexColorSerializer::class) val accentKeyText: Int,
    @Serializable(with = HexColorSerializer::class) val altText: Int,
    @Serializable(with = HexColorSerializer::class) val background: Int,
    val surfaceStyle: CompactSurfaceStyle = CompactSurfaceStyle.Raised,
    val cornerRadius: Float = 5f,
    val keyHMargin: Float = 3f,
    val keyVMargin: Float = 4f,
    val panel: ReadablePanel,
    val pinner: ReadablePinner,
    @Serializable(with = HexColorSerializer::class) val toastBackground: Int? = null,
    @Serializable(with = HexColorSerializer::class) val toastText: Int? = null,
) {
    fun toColorScheme(): KeyboardColors.ColorScheme {
        val panelColors = KeyboardColors.ColorScheme.PanelColors(
            background = panel.background,
            toolbarText = panel.toolbarText,
            toolbarActived = panel.toolbarActived,
            toolbarIcon = panel.toolbarIcon,
            candidateBackground = panel.candidateBackground,
            candidateText = panel.candidateText,
            candidateIndex = panel.candidateIndex,
            candidateDivider = panel.candidateDivider,
            toolbarPressed = panel.toolbarPressed,
        )
        val pinnerColors = KeyboardColors.ColorScheme.PinnerColors(
            background = pinner.background,
            textColor = pinner.textColor,
            secondaryTextColor = pinner.secondaryTextColor,
        )
        return KeyboardColors.ColorScheme(
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
            panel = panelColors,
            pinner = pinnerColors,
            toastBackground = toastBackground ?: specialKeyBackground,
            toastText = toastText ?: keyText,
        )
    }
}

@Serializable
data class ReadablePanel(
    @Serializable(with = HexColorSerializer::class) val background: Int,
    @Serializable(with = HexColorSerializer::class) val toolbarText: Int,
    @Serializable(with = HexColorSerializer::class) val toolbarActived: Int,
    @Serializable(with = HexColorSerializer::class) val toolbarIcon: Int,
    @Serializable(with = HexColorSerializer::class) val candidateBackground: Int,
    @Serializable(with = HexColorSerializer::class) val candidateText: Int,
    @Serializable(with = HexColorSerializer::class) val candidateIndex: Int,
    @Serializable(with = HexColorSerializer::class) val candidateDivider: Int,
    @Serializable(with = HexColorSerializer::class) val toolbarPressed: Int,
)

@Serializable
data class ReadablePinner(
    @Serializable(with = HexColorSerializer::class) val background: Int,
    @Serializable(with = HexColorSerializer::class) val textColor: Int,
    @Serializable(with = HexColorSerializer::class) val secondaryTextColor: Int,
)