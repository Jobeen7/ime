package com.ninthsoft.ime.input.keyboard.window

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.provider.Settings
import android.os.Handler
import android.os.Looper
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.ninthsoft.ime.R
import com.ninthsoft.ime.base.util.FontManager
import com.ninthsoft.ime.data.keyboard.theme.KeyboardColors
import com.ninthsoft.ime.input.ImeInputConnection
import androidx.core.graphics.withClip

/**
 * 通用内嵌输入框组件（绘制在键盘面板之上）。
 * - 顶部可选标题（可点击触发 [onTitle]）。
 * - 主体为一个圆角输入框，文本在内部自动换行（多行，长文本滚动显示）。
 * - 输入框右侧内部上下排布两个圆形按钮：上=关闭([onClose])，下=回车/确认([onConfirm])；
 *   文本内容在按钮左侧区域内换行，不会覆盖按钮。
 * - 绑定 [ImeInputConnection] 后，按光标绘制闪烁指示器。
 */
@SuppressLint("ViewConstructor")
class InputBoxLayerView(
    context: Context,
) : View(context) {

    var onConfirm: ((text: String) -> Unit)? = null
    var onClose: (() -> Unit)? = null
    var onTitle: (() -> Unit)? = null

    var title: String? = null
        set(value) {
            field = value
            invalidate()
        }

    var hint: String? = null
        set(value) {
            field = value
            invalidate()
        }

    private var colors = KeyboardColors.resolve(context)
    private var buffer: ImeInputConnection? = null

    private var cursorOn = true
    private val handler = Handler(Looper.getMainLooper())
    private val blinkInterval: Long
        get() = try {
            Settings.System.getInt(context.contentResolver, "cursor_blink_ms", 500)
                .coerceAtLeast(100).toLong()
        } catch (_: Exception) {
            500L
        }

    private fun startBlink() {
        handler.removeCallbacks(blinkRunnable)
        cursorOn = true
        handler.postDelayed(blinkRunnable, blinkInterval)
        invalidate()
    }

    private fun stopBlink() {
        handler.removeCallbacks(blinkRunnable)
    }

    private val blinkRunnable = object : Runnable {
        override fun run() {
            if (visibility != View.VISIBLE) {
                stopBlink()
                return
            }
            cursorOn = !cursorOn
            invalidate()
            handler.postDelayed(this, blinkInterval)
        }
    }

    private val density: Float get() = resources.displayMetrics.density
    private fun dp(v: Float): Float = v * density

    private var titleRect = RectF()
    private var closeRect = RectF()
    private var enterRect = RectF()
    private var inputRect = RectF()
    private var longPressTriggered = false
    private var downX = 0f
    private var downY = 0f
    private val longPressTimeout = ViewConfiguration.getLongPressTimeout().toLong()
    private var actionPopup: PopupWindow? = null
    private var cursorAnchorX = 0f
    private var cursorAnchorY = 0f
    private val textAreaRect = RectF()
    private var textLayout: StaticLayout? = null
    private var textScrollY = 0f
    private var scrollOffsetY = 0f
    private var userScrolled = false
    private var draggingText = false
    private var lastTouchY = 0f
    private val popupBackgroundColor: Int
        get() = (colors.specialKeyBackground and 0x00FFFFFF) or 0xFF000000.toInt()
    private val longPressRunnable = Runnable {
        longPressTriggered = true
        showEditActions()
    }

    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.LEFT
    }
    private val hintPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.LEFT
    }
    private val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.LEFT
        isFakeBoldText = true
        typeface = FontManager.fromAsset(context, "fonts/xiaolai-mono-regular.ttf")
    }
    private val selectionPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var closeIcon: Drawable? = null
    private var enterIcon: Drawable? = null

    fun bind(buffer: ImeInputConnection) {
        this.buffer = buffer
        buffer.addOnChangeListener {
            cursorOn = true
            userScrolled = false
            invalidate()
        }
        startBlink()
    }

    fun refreshTheme(newColors: KeyboardColors.ColorScheme) {
        colors = newColors
        invalidate()
    }

    fun show() {
        visibility = View.VISIBLE
        startBlink()
    }

    fun hide() {
        stopBlink()
        actionPopup?.dismiss()
        visibility = View.GONE
    }

    @SuppressLint("DrawAllocation")
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (visibility != View.VISIBLE) return
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0 || h <= 0) return

        // 与工具栏顶部一致的无缝背景
        canvas.drawColor(colors.panel.background)

        val outerPad = dp(12f)
        val btnD = dp(38f)
        val closeBtnD = dp(30f)
        val btnGap = dp(10f)
        val titleH = if (!title.isNullOrEmpty()) dp(28f) else 0f
        val topRowH = maxOf(titleH, closeBtnD)
        val titleGap = if (titleH > 0) dp(14f) else 0f
        val titleRowTop = outerPad
        val titleRowBottom = outerPad + topRowH

        // 关闭按钮（与标题同一行，右侧）
        val closeCx = w - outerPad - closeBtnD / 2f
        val closeCy = (titleRowTop + titleRowBottom) / 2f
        closeRect.set(
            closeCx - closeBtnD / 2f,
            closeCy - closeBtnD / 2f,
            closeCx + closeBtnD / 2f,
            closeCy + closeBtnD / 2f,
        )
        if (closeIcon == null) closeIcon =
            ContextCompat.getDrawable(context, R.drawable.ic_keyboard_close)
        drawCircleButton(
            canvas,
            closeCx,
            closeCy,
            closeBtnD / 2f,
            colors.keyBackground,
            colors.keyText,
            closeIcon
        )

        // 标题
        titleRect.set(outerPad, titleRowTop, closeRect.left - dp(8f), titleRowBottom)
        if (titleH > 0) {
            titlePaint.textSize = 18f * density
            titlePaint.color = colors.specialKeyText
            val tfm = titlePaint.fontMetrics
            val ty = (titleRowTop + titleRowBottom) / 2f - tfm.ascent / 2f - tfm.descent / 2f
            canvas.drawText(title!!, titleRect.left, ty, titlePaint)
        }

        // 输入框
        val boxLeft = outerPad
        val boxRight = w - outerPad
        val boxTop = titleRowBottom + titleGap
        val boxBottom = h - outerPad
        val boxRect = RectF(boxLeft, boxTop, boxRight, boxBottom)
        inputRect.set(boxRect)

        val boxBg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = colors.keyBackground
            style = Paint.Style.FILL
        }
        canvas.drawRoundRect(boxRect, dp(10f), dp(10f), boxBg)

        // 输入框内右侧圆形完成按钮（偏下）
        val hasText = (buffer?.text ?: "").isNotEmpty()
        val cx = boxRight - dp(12f) - btnD / 2f
        val enterCy = boxBottom - dp(14f) - btnD / 2f
        enterRect.set(cx - btnD / 2f, enterCy - btnD / 2f, cx + btnD / 2f, enterCy + btnD / 2f)
        if (enterIcon == null) enterIcon =
            ContextCompat.getDrawable(context, R.drawable.ic_keyboard_done)
        val doneBg = if (hasText) colors.accentKeyBackground else colors.keyBackground
        val doneFg = if (hasText) colors.accentKeyText else colors.specialKeyText
        drawCircleButton(canvas, cx, enterCy, btnD / 2f, doneBg, doneFg, enterIcon)

        // 文本区域（按钮左侧）
        val padX = dp(12f)
        val padY = dp(8f)
        val textLeft = boxLeft + padX
        val textRight = enterRect.left - dp(6f)
        val textTop = boxTop + padY
        val textBottom = boxBottom - padY
        val textRegion = RectF(textLeft, textTop, textRight, textBottom)
        textAreaRect.set(textRegion)

        val text = buffer?.text ?: ""
        val cursor = (buffer?.cursor ?: text.length).coerceIn(0, text.length)

        val lineH = 17f * density * 1.25f
        val caretInset = dp(2f)

        if (text.isEmpty()) {
            textLayout = null
            textScrollY = 0f
            scrollOffsetY = 0f
            userScrolled = false
            val hintText = hint ?: context.getString(R.string.phrase_input_hint)
            hintPaint.textSize = 17f * density
            hintPaint.color = colors.keyText
            hintPaint.alpha = 0x66
            val fm = hintPaint.fontMetrics
            val by = textTop + lineH / 2f - (fm.ascent + fm.descent) / 2f
            canvas.drawText(hintText, textLeft, by, hintPaint)
            hintPaint.alpha = 0xFF
            val cy = textTop + lineH / 2f
            cursorAnchorX = textLeft
            cursorAnchorY = cy
            if (cursorOn) drawCaretRect(
                canvas,
                textLeft,
                cy - lineH / 2f + caretInset,
                cy + lineH / 2f - caretInset,
            )
        } else {
            textPaint.textSize = 17f * density
            textPaint.color = colors.keyText
            val regionW = (textRegion.width()).toInt().coerceAtLeast(1)
            val layout = StaticLayout.Builder.obtain(text, 0, text.length, textPaint, regionW)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL).setLineSpacing(0f, 1.25f)
                .setIncludePad(false).build()
            textLayout = layout
            val selection = buffer?.selection
            val hasSelection = selection != null && selection.first != selection.second

            var scrollY = 0f
            val regionH = textRegion.height()
            if (layout.height > regionH) {
                val line = layout.getLineForOffset(cursor)
                val mid = (layout.getLineTop(line) + layout.getLineBottom(line)) / 2f
                val cursorScrollY = (mid - regionH / 2f).coerceIn(0f, layout.height - regionH)
                if (!userScrolled) scrollOffsetY = cursorScrollY
                scrollOffsetY = scrollOffsetY.coerceIn(0f, layout.height - regionH)
                scrollY = scrollOffsetY
            } else {
                scrollOffsetY = 0f
                userScrolled = false
            }
            textScrollY = scrollY

            canvas.withClip(textRegion) {
                translate(textRegion.left, textRegion.top - scrollY)
                if (hasSelection) {
                    val selectedRange = selection
                    val start = minOf(
                        selectedRange.first.coerceIn(0, text.length),
                        selectedRange.second.coerceIn(0, text.length)
                    )
                    val end = maxOf(
                        selectedRange.first.coerceIn(0, text.length),
                        selectedRange.second.coerceIn(0, text.length)
                    )

                    if (start < end) {
                        selectionPaint.color = colors.accentKeyBackground
                        selectionPaint.alpha = 0x99


                        for (i in start until end) {
                            // 换行符本身不绘制高亮，避免将整行扩展成高亮区域
                            if (text[i] == '\n') continue

                            val line = layout.getLineForOffset(i)
                            val lineTop = layout.getLineTop(line).toFloat()
                            val lineBottom = layout.getLineBottom(line).toFloat()
                            val lineEnd = layout.getLineEnd(line)
                            // getLineEnd() 对显式换行会包含 '\n'，
                            // 所以真正的可见文本结束位置需要排除换行符。
                            val visualEnd =
                                if (lineEnd > 0 && lineEnd <= text.length && text[lineEnd - 1] == '\n') {
                                    lineEnd - 1
                                } else {
                                    lineEnd
                                }
                            val x1 = layout.getPrimaryHorizontal(i)
                            // 当前字符是这一行最后一个可见字符时，
                            // 使用当前行的实际结束位置，而不是 i + 1 的 offset。
                            val x2 = if (i + 1 >= visualEnd) {
                                layout.getPrimaryHorizontal(visualEnd)
                            } else {
                                layout.getPrimaryHorizontal(i + 1)
                            }
                            if (x2 > x1) {
                                drawRect(
                                    x1, lineTop, x2, lineBottom, selectionPaint
                                )
                            }
                        }
                    }
                }
                layout.draw(this)
                val line = layout.getLineForOffset(cursor)
                val caretX = layout.getPrimaryHorizontal(cursor)
                val lineCenter = (layout.getLineTop(line) + layout.getLineBottom(line)) / 2f
                val top = (lineCenter - lineH / 2f).coerceAtLeast(layout.getLineTop(line).toFloat())
                val bottom =
                    (lineCenter + lineH / 2f).coerceAtMost(layout.getLineBottom(line).toFloat())
                cursorAnchorX = textRegion.left + caretX
                cursorAnchorY = textRegion.top + lineCenter - scrollY
                if (cursorOn && !hasSelection) {
                    drawCaretRect(
                        this,
                        caretX,
                        top + caretInset,
                        bottom - caretInset,
                    )
                }

                // 未上屏（composing）文本底部白色下划线（逐字符绘制以支持换行/回车）
                val comp = buffer?.composingRange
                if (comp != null) {
                    val cs = comp.first.coerceIn(0, text.length)
                    val ce = comp.second.coerceIn(cs, text.length)
                    if (ce > cs) {
                        val uColor = 0xFFFFFFFF.toInt()
                        val uH = 1f.coerceAtLeast(density.toFloat())
                        val uYOff = textPaint.fontMetrics.descent + 1.5f * density
                        val uPaint = Paint().apply { color = uColor }
                        for (i in cs until ce) {
                            if (text[i] == '\n') continue
                            val line = layout.getLineForOffset(i)
                            val lineEnd = layout.getLineEnd(line)
                            val visualEnd =
                                if (lineEnd > 0 && lineEnd <= text.length && text[lineEnd - 1] == '\n') {
                                    lineEnd - 1
                                } else {
                                    lineEnd
                                }
                            val x1 = layout.getPrimaryHorizontal(i)
                            val x2 = if (i + 1 >= visualEnd) {
                                // 自动折行时 visualEnd 属于下一行，不能再用它计算横坐标。
                                layout.getLineRight(line)
                            } else {
                                layout.getPrimaryHorizontal(i + 1)
                            }
                            if (x2 <= x1) continue
                            val y = layout.getLineBaseline(line) + uYOff
                            drawRect(x1, y, x2, y + uH, uPaint)
                        }
                    }
                }
            }
        }
    }

    private fun drawCaretRect(canvas: Canvas, x: Float, top: Float, bottom: Float) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = colors.accentKeyText
            style = Paint.Style.FILL
        }
        val caretW = 2f
        canvas.drawRect(x, top, x + caretW, bottom, paint)
    }

    private fun drawCircleButton(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        r: Float,
        bg: Int,
        fg: Int,
        icon: Drawable?,
    ) {
        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = bg
            style = Paint.Style.FILL
        }
        canvas.drawCircle(cx, cy, r, bgPaint)
        icon ?: return
        val inset = (r * 0.55f).toInt()
        icon.setTint(fg)
        icon.setBounds(
            (cx - r).toInt() + inset,
            (cy - r).toInt() + inset,
            (cx + r).toInt() - inset,
            (cy + r).toInt() - inset,
        )
        icon.draw(canvas)
    }

    private fun showEditActions() {
        actionPopup?.dismiss()
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(4f).toInt(), dp(4f).toInt(), dp(4f).toInt(), dp(4f).toInt())
            background = GradientDrawable().apply {
                setColor(this@InputBoxLayerView.popupBackgroundColor)
                cornerRadius = dp(10f)
            }
        }

        fun addAction(label: String, action: () -> Unit) {
            content.addView(TextView(context).apply {
                text = label
                setTextColor(this@InputBoxLayerView.colors.specialKeyText)
                textSize = 15f
                gravity = Gravity.CENTER
                setPadding(dp(14f).toInt(), dp(8f).toInt(), dp(14f).toInt(), dp(8f).toInt())
                setOnClickListener {
                    action()
                    actionPopup?.dismiss()
                }
            })
        }
        addAction(context.getString(R.string.paste)) {
            buffer?.performContextMenuAction(android.R.id.paste)
        }
        addAction(context.getString(R.string.newline)) {
            buffer?.commitText("\n", 1)
        }
        actionPopup = PopupWindow(
            content,
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
            true,
        ).apply {
            elevation = dp(6f)
            setBackgroundDrawable(GradientDrawable().apply {
                setColor(this@InputBoxLayerView.popupBackgroundColor)
                cornerRadius = dp(10f)
            })
            setOnDismissListener { actionPopup = null }
            content.measure(
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
            )
            val popupWidth = content.measuredWidth
            val location = IntArray(2)
            this@InputBoxLayerView.getLocationInWindow(location)
            val edgePadding = dp(8f).toInt()
            val screenWidth = resources.displayMetrics.widthPixels
            val popupX = (location[0] + cursorAnchorX - popupWidth / 2f).toInt().coerceIn(
                edgePadding, (screenWidth - popupWidth - edgePadding).coerceAtLeast(edgePadding)
            )
            val popupY = (location[1] + cursorAnchorY - content.measuredHeight - dp(2f)).toInt()
            showAtLocation(
                this@InputBoxLayerView,
                Gravity.TOP or Gravity.START,
                popupX,
                popupY,
            )
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                lastTouchY = event.y
                longPressTriggered = false
                draggingText = false
                if (inputRect.contains(downX, downY)) {
                    handler.postDelayed(longPressRunnable, longPressTimeout)
                }
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val dx = event.x - downX
                val dy = event.y - downY
                if (dx * dx + dy * dy > dp(8f) * dp(8f)) {
                    handler.removeCallbacks(longPressRunnable)
                    if (textAreaRect.contains(downX, downY) &&
                        (textLayout?.height ?: 0) > textAreaRect.height()
                    ) {
                        draggingText = true
                    }
                }
                if (draggingText) {
                    val maxScroll = maxOf(0f, (textLayout?.height ?: 0) - textAreaRect.height())
                    scrollOffsetY = (scrollOffsetY - (event.y - lastTouchY))
                        .coerceIn(0f, maxScroll)
                    textScrollY = scrollOffsetY
                    userScrolled = true
                    invalidate()
                }
                lastTouchY = event.y
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                handler.removeCallbacks(longPressRunnable)
                return true
            }
        }
        if (event.actionMasked != MotionEvent.ACTION_UP) return true
        handler.removeCallbacks(longPressRunnable)
        if (longPressTriggered || draggingText) return true
        val x = event.x
        val y = event.y
        when {
            titleRect.contains(x, y) -> onTitle?.invoke()
            closeRect.contains(x, y) -> onClose?.invoke()
            textAreaRect.contains(x, y) -> moveCursorTo(x, y)
            enterRect.contains(x, y) -> {
                val t = (buffer?.text ?: "").trim()
                if (t.isNotEmpty()) onConfirm?.invoke(t)
            }
        }
        return true
    }

    private fun moveCursorTo(x: Float, y: Float) {
        val text = buffer?.text ?: return
        val layout = textLayout ?: return
        if (text.isEmpty()) return
        val line = layout.getLineForVertical(
            (y - textAreaRect.top + textScrollY).toInt().coerceIn(0, layout.height),
        )
        val localX = (x - textAreaRect.left).coerceAtLeast(0f)
        val lineStart = layout.getLineStart(line)
        val lineEnd = layout.getLineVisibleEnd(line)
        if (lineStart >= lineEnd) return
        val offset = layout.getOffsetForHorizontal(line, localX)
            .coerceIn(lineStart, lineEnd)
        userScrolled = false
        buffer?.setSelection(offset, offset)
    }
}
