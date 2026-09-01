package com.ninthsoft.ime.input.panel.component

import android.annotation.SuppressLint
import android.content.Context
import android.view.View
import android.widget.FrameLayout
import com.ninthsoft.ime.base.util.slideDownExpand
import com.ninthsoft.ime.data.keyboard.theme.KeyboardColors

@SuppressLint("ViewConstructor")
open class ComponentView(
    context: Context,
    protected var colors: KeyboardColors.ColorScheme,
) : FrameLayout(context) {

    init {
        visibility = View.GONE
        setBackgroundColor(colors.panel.background)
    }

    open fun refreshTheme(newColors: KeyboardColors.ColorScheme) {
        colors = newColors
        setBackgroundColor(newColors.panel.background)
    }

    open fun show() {
        if (visibility != View.VISIBLE) {
            bringToFront()
            slideDownExpand()
        }
    }

    open fun hide() {
        animate().cancel()
        visibility = View.GONE
    }
}
