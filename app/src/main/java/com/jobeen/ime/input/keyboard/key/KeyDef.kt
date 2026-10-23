package com.jobeen.ime.input.keyboard.key

import android.graphics.Typeface
import android.view.View
import androidx.annotation.DrawableRes

open class KeyDef(
    val appearance: Appearance,
    val behaviors: Set<Behavior>,
    val popups: Array<Popup>? = null,
    /**
     * 稳定键 ID（皮肤 v3 定稿五.1）：只由布局登记表（[KeyIds]）在
     * 构造处标注；null = 未登记键（侧栏/动态/网格键）。只有皮肤
     * 覆写装配器、逐键编辑器与对账测试读它，渲染与手势不读——
     * 无 ID 的键行为与 v1.1.14 逐字节一致。
     */
    val id: String? = null,
) {
    sealed class Appearance(
        val percentWidth: Float,
        val variant: Variant,
        var border: Border,
        val margin: Boolean,
        val viewId: Int,
        var visibility: Int = View.VISIBLE,
        open val rowSpan: Int = 1,
        open val alignSpanEnd: Boolean = false,
    ) {
        enum class Variant { Normal, Alternative, Accent, AltForeground, None }

        enum class Border { Default, On, Off, Special }

        open class Text(
            val displayText: String,
            val textSize: Float,
            val textStyle: Int = Typeface.NORMAL,
            percentWidth: Float = 0.1f,
            variant: Variant = Variant.Normal,
            border: Border = Border.Default,
            margin: Boolean = true,
            viewId: Int = -1,
            visibility: Int = View.VISIBLE,
        ) : Appearance(percentWidth, variant, border, margin, viewId, visibility)

        class AltText(
            displayText: String,
            val altText: String,
            val altTextTranslationY: Int = 0,
            val mainTextTranslationY: Int = 0,
            val altTextSize:Float  = 11f,
            textSize: Float,
            textStyle: Int = Typeface.NORMAL,
            percentWidth: Float = 0.1f,
            variant: Variant = Variant.Normal,
            border: Border = Border.Default,
            margin: Boolean = true,
            viewId: Int = -1,
            visibility: Int = View.VISIBLE,
        ) : Text(
            displayText,
            textSize,
            textStyle,
            percentWidth,
            variant,
            border,
            margin,
            viewId,
            visibility
        )

        class SidePannel(
            override val rowSpan: Int = 3,
            val visableRow: Int = 4,
            percentWidth: Float = 0.1f,
            variant: Variant = Variant.Normal,
            border: Border = Border.Default,
            margin: Boolean = true,
            viewId: Int = -1,
        ) : Appearance(percentWidth, variant, border, margin, viewId)

        class Image(
            @DrawableRes val src: Int,
            percentWidth: Float = 0.1f,
            variant: Variant = Variant.Normal,
            border: Border = Border.Default,
            margin: Boolean = true,
            viewId: Int = -1,
            override val rowSpan: Int = 1,
            override val alignSpanEnd: Boolean = false,
        ) : Appearance(percentWidth, variant, border, margin, viewId)

        class ImageText(
            displayText: String,
            textSize: Float,
            textStyle: Int = Typeface.NORMAL,
            @DrawableRes val src: Int,
            percentWidth: Float = 0.1f,
            variant: Variant = Variant.Normal,
            border: Border = Border.Default,
            margin: Boolean = true,
            viewId: Int = -1,
        ) : Text(displayText, textSize, textStyle, percentWidth, variant, border, margin, viewId)
    }

    sealed class Behavior {
        class Press(val action: KeyboardAction) : Behavior()
        class LongPress(val action: KeyboardAction) : Behavior()
        class Repeat(val action: KeyboardAction) : Behavior()
        class Swipe(
            val upAction: KeyboardAction? = null,
            val downAction: KeyboardAction? = null,
        ) : Behavior()

        /** 空格键左右滑动移动光标（连续触发，每跨过一个阈值移动一格） */
        data object SwipeMoveCursor : Behavior()
        class DoubleTap(val action: KeyboardAction) : Behavior()
    }

    sealed class Popup {
        open class Preview(val content: String) : Popup()
        class AltPreview(content: String, val alternative: String) : Preview(content)
        class Keyboard(val label: String, val keys: List<KeyboardAction>) : Popup()
        class Menu(val items: Array<Item>) : Popup() {
            class Item(
                val label: String,
                @DrawableRes val icon: Int,
                val action: KeyboardAction,
            )
        }
    }
}
