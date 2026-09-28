package com.jobeen.ime.input.keyboard.window

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.widget.TextView
import com.jobeen.ime.data.keyboard.theme.KeyboardColors
import androidx.core.view.isVisible

class ImeToastView(context: Context) : androidx.appcompat.widget.AppCompatTextView(context) {
    private val handler = Handler(Looper.getMainLooper())
    private val hideRunnable = Runnable { visibility = GONE }

    init {
        gravity = Gravity.CENTER
        textSize = 16f
        minHeight = dp(48)
        setPadding(dp(16), dp(10), dp(16), dp(10))
        compoundDrawablePadding = dp(10)
        elevation = dp(6).toFloat()
        visibility = GONE
    }

    // 图标延迟到首次显示时加载，避免构造时阻塞主线程做 PackageManager binder 调用
    private var iconLoaded = false
    private fun ensureIcon() {
        if (iconLoaded) return
        iconLoaded = true
        context.applicationInfo.loadIcon(context.packageManager).apply {
            setBounds(0, 0, dp(28), dp(28))
            setCompoundDrawables(this, null, null, null)
        }
    }

    fun showToast(message: CharSequence, colors: KeyboardColors.ColorScheme) {
        ensureIcon()
        applyColors(colors)
        text = message
        visibility = VISIBLE
        bringToFront()
        handler.removeCallbacks(hideRunnable)
        handler.postDelayed(hideRunnable, 2_000L)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun opaque(color: Int): Int = (color and 0x00FFFFFF) or 0xFF000000.toInt()

    fun refreshTheme(colors: KeyboardColors.ColorScheme) {
        if (isVisible) applyColors(colors)
    }

    private fun applyColors(colors: KeyboardColors.ColorScheme) {
        background = GradientDrawable().apply {
            setColor(opaque(colors.toastBackground))
            cornerRadius = dp(18).toFloat()
        }
        setTextColor(colors.toastText)
    }
}
