package com.ninthsoft.ime.input.dialog

import android.content.Context
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.ninthsoft.ime.data.keyboard.theme.KeyboardColors
import splitties.dimensions.dp
import splitties.views.dsl.core.Ui
import splitties.views.dsl.core.matchParent
import splitties.views.dsl.core.textView

class SchemaPickerEntryUi(
    override val ctx: Context,
    colors: KeyboardColors.ColorScheme,
) : Ui {

    private val checkIndicator: TextView
    private val nameText: TextView

    override val root = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        layoutParams = ViewGroup.LayoutParams(matchParent, ctx.dp(48))
        setPadding(ctx.dp(18), 0, ctx.dp(18), 0)
        isClickable = true
        isFocusable = true
    }

    init {
        checkIndicator = root.textView {
            text = "○"
            textSize = 16f
            setTextColor(colors.accentKeyBackground)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(ctx.dp(36), matchParent)
        }

        nameText = root.textView {
            textSize = 16f
            setTextColor(colors.keyText)
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, matchParent, 1f).apply {
                marginStart = ctx.dp(4)
            }
        }
    }

    fun bind(name: String, checked: Boolean) {
        nameText.text = name
        checkIndicator.text = if (checked) "●" else "○"
        root.isSelected = checked
    }
}
