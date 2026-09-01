package com.ninthsoft.ime.input.pinner

import android.content.Context
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import com.ninthsoft.ime.data.keyboard.theme.KeyboardColors
import com.ninthsoft.ime.engine.data.EngineMessage

class PreeditPinner(context: Context) : IPinner {

    override val view: PreeditPinnerView = PreeditPinnerView(context)

    private var shown = false

    init {
        refreshTheme(context)
    }

    override fun refreshTheme(context: Context) {
        val pinner = KeyboardColors.resolve(context).pinner
        val textSize = 15f * context.resources.displayMetrics.density
        view.applyTheme(pinner.background, pinner.textColor, pinner.secondaryTextColor, textSize)
    }

    override fun updateDynamicPreedit(items: List<EngineMessage.DynamicPreedit.DynamicPreeditItem>) {
        view.preeditItems = items
        view.visibility = if (items.isEmpty()) View.GONE else View.VISIBLE
    }

    fun show(context: Context, windowManager: WindowManager, anchorView: View, hPad: Int) {
        if (view.preeditItems.isEmpty()) {
            hide(windowManager)
            return
        }
        val pinnerView = view
        pinnerView.visibility = View.VISIBLE
        val density = context.resources.displayMetrics.density
        val screenWidth = context.resources.displayMetrics.widthPixels
        val rightMargin = (8f * density).toInt()

        val loc = IntArray(2)
        anchorView.getLocationOnScreen(loc)
        val x = loc[0] + hPad
        val availableWidth = screenWidth - rightMargin - x
        pinnerView.maxWidth = availableWidth.coerceAtLeast(0)

        pinnerView.measure(
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        val pillW = pinnerView.measuredWidth
        val pillH = pinnerView.measuredHeight
        if (pillW <= 0 || pillH <= 0) return

        val y = loc[1] - pillH

        val params = WindowManager.LayoutParams().apply {
            width = pillW
            height = pillH
            this.x = x
            this.y = y
            gravity = Gravity.TOP or Gravity.START
            format = PixelFormat.TRANSLUCENT
            flags =
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
            token = anchorView.windowToken
            type = WindowManager.LayoutParams.TYPE_APPLICATION_PANEL
        }

        try {
            if (shown) {
                windowManager.updateViewLayout(pinnerView, params)
            } else {
                windowManager.addView(pinnerView, params)
                shown = true
            }
        } catch (_: Exception) {
        }
    }

    fun hide(windowManager: WindowManager) {
        view.preeditItems = emptyList()
        view.visibility = View.GONE
        if (!shown) return
        try {
            windowManager.removeView(view)
        } catch (_: Exception) {
        }
        shown = false
    }
}
