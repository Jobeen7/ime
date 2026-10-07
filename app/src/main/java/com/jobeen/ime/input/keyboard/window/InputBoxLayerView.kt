package com.jobeen.ime.input.keyboard.window

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
import com.jobeen.ime.R
import com.jobeen.ime.base.util.FontManager
import com.jobeen.ime.data.keyboard.theme.KeyboardColors
import com.jobeen.ime.input.ImeInputConnection
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
    // 缓存光标闪烁间隔，避免每 500ms 一次 Settings IPC 查询
    private val blinkInterval: Long by lazy {
        try {
            Settings.System.getInt(context.contentResolver, "cursor_blink_ms", 500)
                .coerceAtLeast(100).toLong()
        } catch (_: Exception) {
            500L
        }
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
    // onDraw 复用对象：旧实现每帧新建多个 Paint/RectF，且光标闪烁的静止期
    // 也每秒全量重建 StaticLayout；布局按 (文本, 区域宽) 缓存，变化才重建
    private val boxBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val underlinePaint = Paint()
    private val boxRectScratch = RectF()
    private val textRegionScratch = RectF()
    private var layoutCacheText: String? = null
    private var layoutCacheWidth: Int = -1
    private var closeIcon: Drawable? = null
    private var enterIcon: Drawable? = null

    private var changeListener: (() -> Unit)? = null

    fun bind(buffer: ImeInputConnection) {
        // 先移除旧监听器，避免多次 bind 导致监听器累积
        changeListener?.let { this.buffer?.removeOnChangeListener(it) }
        this.buffer = buffer
        val listener: () -> Unit = {
            cursorOn = true
            userScrolled = false
            invalidate()
        }
        changeListener = listener
        buffer.addOnChangeListener(listener)
        startBlink()
    }

    override fun onDetachedFromWindow() {
        changeListener?.let { buffer?.removeOnChangeListener(it) }
        changeListener = null
        stopBlink()
        super.onDetachedFromWindow()
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
        val boxRect = boxRectScratch.apply { set(boxLeft, boxTop, boxRight, boxBottom) }
        inputRect.set(boxRect)

        boxBgPaint.color = colors.keyBackground
        canvas.drawRoundRect(boxRect, dp(10f), dp(10f), boxBgPaint)

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
        val textRegion = textRegionScratch.apply { set(textLeft, textTop, textRight, textBottom) }
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
            // 布局缓存：文本与区域宽未变时复用（光标闪烁帧不再全量重排）
            val cached = textLayout
            val layout = if (cached != null && layoutCacheText == text && layoutCacheWidth == regionW) {
                cached
            } else {
                StaticLayout.Builder.obtain(text, 0, text.length, textPaint, regionW)
                    .setAlignment(Layout.Alignment.ALIGN_NORMAL).setLineSpacing(0f, 1.25f)
                    .setIncludePad(false).build().also {
                        textLayout = it
                        layoutCacheText = text
                        layoutCacheWidth = regionW
                    }
            }
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

                        // 按行段绘制（此前逐字循环查布局，全选长文本时每帧
                        // 上千次查询；行数远小于字数，视觉结果一致）
                        val firstLine = layout.getLineForOffset(start)
                        val lastLine = layout.getLineForOffset(end)
                        for (line in firstLine..lastLine) {
                            val lineStart = layout.getLineStart(line)
                            val lineEnd = layout.getLineEnd(line)
                            // getLineEnd() 对显式换行会包含 '\n'，
                            // 真正的可见文本结束位置需要排除换行符
                            val visualEnd =
                                if (lineEnd > 0 && lineEnd <= text.length && text[lineEnd - 1] == '\n') {
                                    lineEnd - 1
                                } else {
                                    lineEnd
                                }
                            val segStart = maxOf(start, lineStart)
                            val segEnd = minOf(end, visualEnd)
                            if (segStart >= segEnd) continue
                            val x1 = layout.getPrimaryHorizontal(segStart)
                            val x2 = layout.getPrimaryHorizontal(segEnd)
                            if (x2 > x1) {
                                drawRect(
                                    x1,
                                    layout.getLineTop(line).toFloat(),
                                    x2,
                                    layout.getLineBottom(line).toFloat(),
                                    selectionPaint,
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
                        val uH = 1f.coerceAtLeast(density.toFloat())
                        val uYOff = textPaint.fontMetrics.descent + 1.5f * density
                        val uPaint = underlinePaint.apply { color = 0xFFFFFFFF.toInt() }
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

    // 绘制期复用 Paint：drawCaretRect/drawCircleButton 此前每次调用现建
    private val caretPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val buttonBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    private fun drawCaretRect(canvas: Canvas, x: Float, top: Float, bottom: Float) {
        caretPaint.color = colors.accentKeyText
        val caretW = 2f
        canvas.drawRect(x, top, x + caretW, bottom, caretPaint)
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
        buttonBgPaint.color = bg
        canvas.drawCircle(cx, cy, r, buttonBgPaint)
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
