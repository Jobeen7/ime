package com.jobeen.ime.input.keyboard.window

import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.PopupWindow
import android.widget.TextView
import com.jobeen.ime.base.feedback.InputFeedbacks
import com.jobeen.ime.data.keyboard.theme.KeyboardColors
import com.jobeen.ime.input.keyboard.key.KeyboardAction
import com.jobeen.ime.skin.icon.MaterialSymbols
import com.jobeen.ime.skin.model.SkinAction
import com.jobeen.ime.skin.runtime.KeyOverrideApplier
import com.jobeen.ime.skin.runtime.SkinColorMode
import com.jobeen.ime.skin.symbolbar.SymbolBarCell
import com.jobeen.ime.skin.symbolbar.SymbolBarConfig
import com.jobeen.ime.skin.symbolbar.SymbolBarKey
import com.jobeen.ime.skin.symbolbar.SymbolBarPanel
import com.jobeen.ime.skin.symbolbar.SymbolBarPosition
import com.jobeen.ime.skin.symbolbar.bubbleDisplayOrder
import com.jobeen.ime.skin.symbolbar.bubbleIndexForDisplacement
import com.jobeen.ime.skin.symbolbar.symbolBarCells
import com.jobeen.ime.skin.symbolbar.symbolOpensRight

/**
 * 应用级符号栏视图（对端符号栏指南实现）。
 *
 * 10 格等宽：关闭格 + 8 符号格 + 类型切换格（closeButtonOnLeft 决定
 * 两端格的左右，符号格顺序恒为 1–8）。符号格点按即执行中心动作；
 * 长按（250ms，与按键长按一致）在邻格下方弹出气泡行，手指位移选格、
 * 松手上屏、竖滑超一栏高取消高亮；无角内容时不长按（点按即中心动作）。
 * 气泡浮出瞬间指位为位移原点，首格即最近格，松手无位移也上屏首格。
 */
class SymbolBarView(
    context: Context,
    private val colors: KeyboardColors.ColorScheme,
    private val onAction: (KeyboardAction) -> Unit,
    private val onClose: () -> Unit,
    private val onTypeSelected: (String) -> Unit,
) : LinearLayout(context) {

    var barHeightPx: Int = 0
        private set

    private var config: SymbolBarConfig? = null
    private var panel: SymbolBarPanel? = null
    private var currentType: String = ""
    private var typeCell: TextView? = null

    fun bind(config: SymbolBarConfig, panel: SymbolBarPanel, currentType: String) {
        this.config = config
        this.panel = panel
        this.currentType = currentType
        removeAllViews()
        orientation = HORIZONTAL
        val density = resources.displayMetrics.density
        barHeightPx = (config.heightDp * density + 0.5f).toInt()
        val isDark = SkinColorMode.isDark(context)
        val barBg = config.background?.let { if (isDark) it.dark else it.light } ?: colors.background
        setBackgroundColor(barBg)
        for (cell in symbolBarCells(config.closeButtonOnLeft)) {
            val view = when (cell) {
                SymbolBarCell.Close -> buildCloseCell()
                SymbolBarCell.TypeSwitch -> buildTypeCell()
                is SymbolBarCell.Symbol -> SymbolBarKeyCell(
                    context,
                    panel.keys[cell.index],
                    cell.index,
                    config,
                    isDark,
                )
            }
            addView(view, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
        }
    }

    private fun dp(value: Float): Int =
        (value * resources.displayMetrics.density + 0.5f).toInt()

    private fun buildCloseCell(): FrameLayout {
        val cfg = config ?: return FrameLayout(context)
        val cell = FrameLayout(context)
        cell.setPadding(dp(cfg.cellInsetsDp.left), dp(cfg.cellInsetsDp.top), dp(cfg.cellInsetsDp.right), dp(cfg.cellInsetsDp.bottom))
        val size = dp(28f)
        val dot = TextView(context).apply {
            text = "✕"
            setTextColor(0xFFFFFFFF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(CLOSE_RED)
            }
        }
        cell.addView(dot, FrameLayout.LayoutParams(size, size, Gravity.CENTER))
        cell.setOnClickListener { onClose() }
        return cell
    }

    private fun buildTypeCell(): TextView {
        val cfg = config ?: return TextView(context)
        val cell = TextView(context).apply {
            text = currentType
            setTextColor(0xFFFFFFFF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            gravity = Gravity.CENTER
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setBackgroundColor(TYPE_BLUE)
            setPadding(dp(cfg.cellInsetsDp.left), dp(cfg.cellInsetsDp.top), dp(cfg.cellInsetsDp.right), dp(cfg.cellInsetsDp.bottom))
        }
        typeCell = cell
        cell.setOnClickListener { showTypeMenu(cell) }
        return cell
    }

    private fun showTypeMenu(anchor: View) {
        val cfg = config ?: return
        val menu = PopupMenu(context, anchor)
        for (type in cfg.types) {
            menu.menu.add(type).apply {
                isCheckable = true
                isChecked = (type == currentType)
            }
        }
        menu.setOnMenuItemClickListener { item ->
            onTypeSelected(item.title.toString())
            true
        }
        menu.show()
    }

    /**
     * 符号格：中心大字 + 四角小字 + 全隐角时的右下小三角；点按中心、
     * 长按气泡。气泡为不可触摸的 PopupWindow（手指无需移上气泡）。
     */
    private inner class SymbolBarKeyCell(
        context: Context,
        private val key: SymbolBarKey,
        private val symbolIndex: Int,
        private val cfg: SymbolBarConfig,
        private val isDark: Boolean,
    ) : FrameLayout(context) {

        private val normalBg: Int =
            if (isDark) key.normalBackground.dark else key.normalBackground.light
        private val highlightBg: Int =
            if (isDark) key.highlightBackground.dark else key.highlightBackground.light
        private val gridBg: Int =
            if (isDark) key.gridBackground.dark else key.gridBackground.light
        private val gridSelectedBg: Int =
            if (isDark) key.gridSelectedBackground.dark else key.gridSelectedBackground.light
        private val gridFgNormal: Int =
            (if (isDark) key.gridNormalForeground?.dark else key.gridNormalForeground?.light)
                ?: colors.keyText
        private val gridFgHighlight: Int =
            (if (isDark) key.gridHighlightForeground?.dark else key.gridHighlightForeground?.light)
                ?: colors.keyText

        private var centerView: TextView? = null
        private val cornerViews = ArrayList<Pair<TextView, SymbolBarPosition>>()

        private var downRawX = 0f
        private var downRawY = 0f
        private var lastRawX = 0f
        private var lastRawY = 0f
        private var pressActive = false
        private var longPressPosted = false
        private var bubble: PopupWindow? = null
        private var bubbleCells: List<TextView> = emptyList()
        private var bubbleOrdered: List<SymbolBarPosition> = emptyList()
        private var bubbleOpenRight = true
        private var bubbleOriginX = 0f
        private var bubbleOriginY = 0f
        private var bubbleSelected = -1
        private var bubbleCancelled = false

        private val slop = ViewConfiguration.get(context).scaledTouchSlop
        private val longPressRunnable = Runnable { openBubble() }

        init {
            setPadding(dp(cfg.cellInsetsDp.left), dp(cfg.cellInsetsDp.top), dp(cfg.cellInsetsDp.right), dp(cfg.cellInsetsDp.bottom))
            setBackgroundColor(normalBg)
            centerView = buildTextView(key.center, CENTER_SP)
            centerView?.let { addView(it, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER)) }
            addCorner(key.topLeft, Gravity.TOP or Gravity.START)
            addCorner(key.topRight, Gravity.TOP or Gravity.END)
            addCorner(key.bottomLeft, Gravity.BOTTOM or Gravity.START)
            addCorner(key.bottomRight, Gravity.BOTTOM or Gravity.END)
            if (key.showsLongPressHint) {
                val hintColor = if (isDark) cfg.longPressHintColor.dark else cfg.longPressHintColor.light
                val triangle = View(context).apply {
                    background = TriangleDrawable(hintColor)
                }
                addView(triangle, LayoutParams(dp(8f), dp(8f), Gravity.BOTTOM or Gravity.END))
            }
        }

        private fun addCorner(pos: SymbolBarPosition?, gravity: Int) {
            if (pos == null || !pos.hasContent) return
            val view = buildTextView(pos, CORNER_SP) ?: return
            view.visibility = if (pos.showOnKey) View.VISIBLE else View.GONE
            addView(view, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, gravity))
            cornerViews += view to pos
        }

        private fun buildTextView(pos: SymbolBarPosition, defaultSp: Float): TextView? {
            val (text, isIcon) = pos.display()
            return TextView(context).apply {
                this.text = text
                if (isIcon) typeface = MaterialSymbols.typeface(context)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, pos.fontSizeSp ?: defaultSp)
                setTextColor(normalFg(pos))
                translationX = dp(pos.offsetXDp ?: 0f).toFloat()
                translationY = dp(pos.offsetYDp ?: 0f).toFloat()
            }
        }

        private fun normalFg(pos: SymbolBarPosition): Int =
            (if (isDark) pos.normalColor?.dark else pos.normalColor?.light) ?: colors.keyText

        private fun highlightFg(pos: SymbolBarPosition): Int =
            (if (isDark) pos.highlightColor?.dark else pos.highlightColor?.light) ?: normalFg(pos)

        private fun setPressedVisual(pressed: Boolean) {
            setBackgroundColor(if (pressed) highlightBg else normalBg)
            centerView?.setTextColor(if (pressed) highlightFg(key.center) else normalFg(key.center))
            for ((view, pos) in cornerViews) {
                view.setTextColor(if (pressed) highlightFg(pos) else normalFg(pos))
            }
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    pressActive = true
                    setPressedVisual(true)
                    downRawX = event.rawX
                    downRawY = event.rawY
                    lastRawX = downRawX
                    lastRawY = downRawY
                    if (key.orderedCorners().isNotEmpty()) {
                        longPressPosted = true
                        postDelayed(longPressRunnable, LONG_PRESS_TIMEOUT_MS)
                    }
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    lastRawX = event.rawX
                    lastRawY = event.rawY
                    if (bubble != null) {
                        updateBubbleSelection()
                    } else if (longPressPosted) {
                        if (kotlin.math.abs(event.rawX - downRawX) > slop ||
                            kotlin.math.abs(event.rawY - downRawY) > slop
                        ) {
                            cancelPendingPress()
                        }
                    }
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    removeCallbacks(longPressRunnable)
                    longPressPosted = false
                    if (bubble != null) {
                        val selected = bubbleSelected
                        dismissBubble()
                        if (selected >= 0) {
                            bubbleOrdered.getOrNull(selected)?.action?.let {
                                onAction(KeyOverrideApplier.toRuntime(it))
                            }
                        }
                    } else if (pressActive) {
                        key.center.action?.let { onAction(KeyOverrideApplier.toRuntime(it)) }
                    }
                    pressActive = false
                    setPressedVisual(false)
                    return true
                }
                MotionEvent.ACTION_CANCEL -> {
                    removeCallbacks(longPressRunnable)
                    longPressPosted = false
                    dismissBubble()
                    pressActive = false
                    setPressedVisual(false)
                    return true
                }
            }
            return super.onTouchEvent(event)
        }

        override fun onDetachedFromWindow() {
            removeCallbacks(longPressRunnable)
            dismissBubble()
            super.onDetachedFromWindow()
        }

        private fun cancelPendingPress() {
            removeCallbacks(longPressRunnable)
            longPressPosted = false
            pressActive = false
            setPressedVisual(false)
        }

        private fun openBubble() {
            longPressPosted = false
            if (!isAttachedToWindow) return
            val ordered = key.orderedCorners()
            if (ordered.isEmpty()) return
            bubbleOrdered = ordered
            bubbleOpenRight = symbolOpensRight(symbolIndex)
            val display = bubbleDisplayOrder(ordered, bubbleOpenRight)
            val cellW = width.coerceAtLeast(1)
            val barH = barHeightPx.coerceAtLeast(1)
            val container = LinearLayout(context).apply { orientation = HORIZONTAL }
            val cells = ArrayList<TextView>()
            for (pos in display) {
                val (text, isIcon) = pos.display()
                val cell = TextView(context).apply {
                    this.text = text
                    if (isIcon) typeface = MaterialSymbols.typeface(context)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, pos.fontSizeSp ?: CORNER_SP)
                    gravity = Gravity.CENTER
                    setBackgroundColor(gridBg)
                    setTextColor(gridFgNormal)
                    translationX = dp(pos.offsetXDp ?: 0f).toFloat()
                    translationY = dp(pos.offsetYDp ?: 0f).toFloat()
                }
                container.addView(cell, LinearLayout.LayoutParams(cellW, barH))
                cells += cell
            }
            bubbleCells = cells
            val loc = IntArray(2)
            getLocationInWindow(loc)
            val barLoc = IntArray(2)
            (parent as? View)?.getLocationInWindow(barLoc)
            val windowWidth = resources.displayMetrics.widthPixels
            val bubbleW = cellW * display.size
            var x = if (bubbleOpenRight) loc[0] + width else loc[0] - bubbleW
            x = x.coerceIn(0, maxOf(0, windowWidth - bubbleW))
            val y = barLoc[1] + barH
            val window = PopupWindow(container, bubbleW, barH, false).apply {
                isTouchable = false
            }
            bubble = window
            bubbleOriginX = lastRawX
            bubbleOriginY = lastRawY
            bubbleSelected = 0
            bubbleCancelled = false
            refreshBubbleSelection(haptic = true)
            window.showAtLocation(this, Gravity.NO_GRAVITY, x, y)
        }

        private fun updateBubbleSelection() {
            if (bubbleCancelled) return
            if (kotlin.math.abs(lastRawY - bubbleOriginY) > barHeightPx) {
                if (bubbleSelected >= 0) {
                    bubbleSelected = -1
                    bubbleCancelled = true
                    refreshBubbleSelection(haptic = true)
                }
                return
            }
            val idx = bubbleIndexForDisplacement(
                lastRawX - bubbleOriginX,
                width.toFloat(),
                bubbleOrdered.size,
                bubbleOpenRight,
            ) ?: return
            if (idx != bubbleSelected) {
                bubbleSelected = idx
                refreshBubbleSelection(haptic = true)
            }
        }

        private fun refreshBubbleSelection(haptic: Boolean) {
            bubbleCells.forEachIndexed { displayIdx, view ->
                val orderedIdx = if (bubbleOpenRight) displayIdx else bubbleCells.size - 1 - displayIdx
                val selected = orderedIdx == bubbleSelected
                view.setBackgroundColor(if (selected) gridSelectedBg else gridBg)
                view.setTextColor(if (selected) gridFgHighlight else gridFgNormal)
            }
            if (haptic) InputFeedbacks.hapticFeedback(this)
        }

        private fun dismissBubble() {
            bubble?.dismiss()
            bubble = null
            bubbleCells = emptyList()
            bubbleSelected = -1
            bubbleCancelled = false
        }
    }

    /** 右下小三角（全隐角但有长按内容时的提示）。 */
    private class TriangleDrawable(private val color: Int) : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            style = Paint.Style.FILL
        }

        override fun draw(canvas: Canvas) {
            val b = bounds
            val path = android.graphics.Path().apply {
                moveTo(b.right.toFloat(), b.bottom.toFloat())
                lineTo(b.left.toFloat(), b.bottom.toFloat())
                lineTo(b.right.toFloat(), b.top.toFloat())
                close()
            }
            canvas.drawPath(path, paint)
        }

        override fun setAlpha(alpha: Int) {
            paint.alpha = alpha
        }

        override fun setColorFilter(colorFilter: ColorFilter?) {
            paint.colorFilter = colorFilter
        }

        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }

    companion object {
        // 固定语义色（非主题色）：关闭红、类型切换蓝。
        private const val CLOSE_RED = 0xFFFF3B30.toInt()
        private const val TYPE_BLUE = 0xFF007AFF.toInt()
        private const val CENTER_SP = 18f
        private const val CORNER_SP = 11f
        private const val LONG_PRESS_TIMEOUT_MS = 250L
    }
}
