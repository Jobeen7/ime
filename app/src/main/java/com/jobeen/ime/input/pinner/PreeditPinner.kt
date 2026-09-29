package com.jobeen.ime.input.pinner

import android.content.Context
import android.graphics.PixelFormat
import android.os.IBinder
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import com.jobeen.ime.data.keyboard.theme.KeyboardColors
import com.jobeen.ime.engine.data.EngineMessage

class PreeditPinner(context: Context) : IPinner {

    override val view: PreeditPinnerView = PreeditPinnerView(context)

    /**
     * 常驻窗口：addView 只在首次 show 时发生一次，之后 hide() 只把 view 设为 GONE
     * （窗口仍在 WindowManager 里），真正 removeView 只在 [detach]（键盘 view detach 时）。
     * 每次 show 若布局参数无变化则跳过 measure / updateViewLayout（Binder IPC）。
     */
    private var attached = false

    // 上次实际应用的布局参数，用于"无变化跳过 update"
    private var lastX = Int.MIN_VALUE
    private var lastY = Int.MIN_VALUE
    private var lastW = Int.MIN_VALUE
    private var lastH = Int.MIN_VALUE
    private var lastToken: IBinder? = null

    // 上次 measure 的输入与结果：items + maxWidth 都不变才跳过 measure
    //（maxWidth 随屏幕方向/锚点位置变化，onMeasure 会用它做缩放，不能只看 items）
    private var lastMeasuredItems: List<EngineMessage.DynamicPreedit.DynamicPreeditItem>? = null
    private var lastMeasuredMaxWidth: Int = -1
    private var lastPillW = 0
    private var lastPillH = 0

    init {
        refreshTheme(context)
    }

    override fun refreshTheme(context: Context) {
        val pinner = KeyboardColors.resolve(context).pinner
        val textSize = 15f * context.resources.displayMetrics.density
        view.applyTheme(pinner.background, pinner.textColor, pinner.secondaryTextColor, textSize)
        // 主题变化可能改变尺寸，失效 measure 缓存
        lastMeasuredItems = null
    }

    override fun updateDynamicPreedit(items: List<EngineMessage.DynamicPreedit.DynamicPreeditItem>) {
        view.preeditItems = items
        view.visibility = if (items.isEmpty()) View.GONE else View.VISIBLE
        if (items.isEmpty()) lastMeasuredItems = null
    }

    fun show(context: Context, windowManager: WindowManager, anchorView: View, hPad: Int) {
        val items = view.preeditItems
        if (items.isEmpty()) {
            hide()
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
        val maxWidth = availableWidth.coerceAtLeast(0)
        pinnerView.maxWidth = maxWidth

        val pillW: Int
        val pillH: Int
        if (items == lastMeasuredItems && maxWidth == lastMeasuredMaxWidth) {
            pillW = lastPillW
            pillH = lastPillH
        } else {
            pinnerView.measure(
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            )
            pillW = pinnerView.measuredWidth
            pillH = pinnerView.measuredHeight
            lastMeasuredItems = items
            lastMeasuredMaxWidth = maxWidth
            lastPillW = pillW
            lastPillH = pillH
        }
        if (pillW <= 0 || pillH <= 0) return

        val y = loc[1] - pillH
        val token = anchorView.windowToken

        if (attached
            && x == lastX && y == lastY && pillW == lastW && pillH == lastH
            && token === lastToken
        ) {
            // 布局无变化：跳过 updateViewLayout
            return
        }

        val params = WindowManager.LayoutParams().apply {
            width = pillW
            height = pillH
            this.x = x
            this.y = y
            gravity = Gravity.TOP or Gravity.START
            format = PixelFormat.TRANSLUCENT
            flags =
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
            this.token = token
            type = WindowManager.LayoutParams.TYPE_APPLICATION_PANEL
        }

        try {
            if (attached) {
                windowManager.updateViewLayout(pinnerView, params)
            } else {
                windowManager.addView(pinnerView, params)
                attached = true
            }
            lastX = x
            lastY = y
            lastW = pillW
            lastH = pillH
            lastToken = token
        } catch (_: Exception) {
            attached = false
        }
    }

    /**
     * 组字结束：只隐藏，不 removeView（窗口常驻）。
     * 调用方（updateDynamicPreedit 空 items、onDetachedFromWindow 之前）无需改动签名。
     */
    fun hide(windowManager: WindowManager) {
        hide()
    }

    private fun hide() {
        view.preeditItems = emptyList()
        view.visibility = View.GONE
        lastMeasuredItems = null
    }

    /**
     * 键盘 view detach 时调用：真正 removeView，下次 show 重新 add。
     * 调用方需把 onDetachedFromWindow 里的 preeditPinner.hide(wm) 改为 detach(wm)。
     */
    fun detach(windowManager: WindowManager) {
        hide()
        if (!attached) return
        try {
            windowManager.removeView(view)
        } catch (_: Exception) {
        }
        attached = false
        lastX = Int.MIN_VALUE
        lastY = Int.MIN_VALUE
        lastW = Int.MIN_VALUE
        lastH = Int.MIN_VALUE
        lastToken = null
    }
}
