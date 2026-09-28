package com.jobeen.ime.input.keyboard.key

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.PopupWindow
import android.widget.TextView
import splitties.dimensions.dp

class KeyPreviewPopup(private val context: Context) {

    private var popupWindow: PopupWindow? = null

    // 复用 contentView，避免长按预览高频创建 View 导致 GC 抖动
    private val popupSize = context.dp(52)
    private val bgDrawable = GradientDrawable().apply {
        cornerRadius = context.dp(8f)
    }
    private val contentView = TextView(context).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 30f)
        gravity = Gravity.CENTER
        typeface = Typeface.DEFAULT
        setIncludeFontPadding(false)
        background = bgDrawable
    }
    private val loc = IntArray(2)

    fun show(
        anchor: View,
        text: String,
        textColor: Int,
        bgColor: Int,
    ) {
        dismiss()

        contentView.setText(text)
        contentView.setTextColor(textColor)
        bgDrawable.setColor(bgColor)

        anchor.getLocationInWindow(loc)
        val anchorCenterX = loc[0] + anchor.width / 2
        val anchorTop = loc[1]

        val popupX = anchorCenterX - popupSize / 2
        val popupY = anchorTop - popupSize - context.dp(6)

        popupWindow = PopupWindow(
            contentView,
            popupSize,
            popupSize,
            false,
        ).apply {
            isOutsideTouchable = false
            isTouchable = false
            elevation = context.dp(8f)
            showAtLocation(
                anchor,
                Gravity.TOP or Gravity.START,
                popupX,
                popupY,
            )
        }
    }

    fun dismiss() {
        popupWindow?.dismiss()
        popupWindow = null
    }
}
