package com.ninthsoft.ime.input.panel.component

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.RectF
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.Drawable
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.widget.OverScroller
import com.ninthsoft.ime.R
import com.ninthsoft.ime.base.feedback.InputFeedbacks
import com.ninthsoft.ime.data.keyboard.theme.KeyboardColors
import com.ninthsoft.ime.data.manager.ClipboardManager
import com.ninthsoft.ime.data.manager.PhraseManager
import kotlin.math.abs
import kotlin.math.roundToInt

enum class ClipboardTab { CLIPBOARD, PHRASE }

@SuppressLint("ViewConstructor", "UseCompatLoadingForDrawables")
class ClipboardView(
    context: Context,
    colors: KeyboardColors.ColorScheme,
) : ComponentView(context, colors) {

    var onItemClick: ((ClipboardManager.Entry) -> Unit)? = null
    var onItemLongClick: ((ClipboardManager.Entry, Float, Float) -> Unit)? = null
    var onPhraseClick: ((PhraseManager.Phrase) -> Unit)? = null
    var onPhraseDelete: ((PhraseManager.Phrase) -> Unit)? = null

    private val density = resources.displayMetrics.density

    private val headerH = 0f
    private val hMargin = 10f * density
    private val gap = 6f * density
    private val topPad = 6f * density
    private val listGap = 6f * density
    private val pillPad = 10f * density

    var clipTab: ClipboardTab = ClipboardTab.CLIPBOARD
    private var clipboardEntries = listOf<ClipboardManager.Entry>()
    private var phrases = listOf<PhraseManager.Phrase>()

    private data class RowLayout(
        val height: Float,
        val indexLabel: String,
        val primaryLines: List<String>,
        val secondaryLines: List<String>,
        val cloud: Boolean,
    )

    private var rowLayouts = listOf<RowLayout>()

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val segPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
    }
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val indexPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val cloudDrawable: Drawable? = context.getDrawable(R.drawable.ic_keyboard_clipboard_cloud)
    private val cloudIconSize = 14f * density

    private var pressedIndex = -1
    private var maxScroll = 0f
    private var scrollOffsetY = 0f
    private val scroller = OverScroller(context)
    private var velocityTracker: VelocityTracker? = null
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var lastTouchY = 0f
    private var isScrolling = false
    private var longPressPending = false
    private var longPressX = 0f
    private var longPressY = 0f
    private val longPressTimeout = ViewConfiguration.getLongPressTimeout()
    private val longPressRunnable = Runnable {
        if (pressedIndex in rowLayouts.indices) {
            longPressPending = false
            val x = longPressX
            val y = longPressY
            val idx = pressedIndex
            pressedIndex = -1
            invalidate()
            if (clipTab == ClipboardTab.CLIPBOARD) {
                clipboardEntries.getOrNull(idx)?.let { onItemLongClick?.invoke(it, x, y) }
            } else {
                phrases.getOrNull(idx)?.let { onPhraseDelete?.invoke(it) }
            }
        }
    }

    init {
        setBackgroundColor(colors.background)
        updateColors()
    }

    override fun refreshTheme(newColors: KeyboardColors.ColorScheme) {
        super.refreshTheme(newColors)
        setBackgroundColor(newColors.background)
        updateColors()
        invalidate()
    }

    /** 进入时调用：默认选中剪切板。 */
    override fun show() {
        reload()
        super.show()
    }

    /** 内容变化时刷新（保留当前选中的标签页）。 */
    fun refresh() {
        reload()
    }

    private fun reload() {
        clipboardEntries = ClipboardManager.getEntries(context)
        phrases = PhraseManager.getAll(context)
        resetScroll()
        computeRowLayouts()
        invalidate()
    }

    private fun resetScroll() {
        scroller.forceFinished(true)
        scrollOffsetY = 0f
    }

    private fun updateColors() {
        val scheme = KeyboardColors.resolve(context)
        val panel = scheme.panel
        trackPaint.color = panel.candidateBackground
        bgPaint.color = scheme.specialKeyBackground
        textPaint.color = scheme.specialKeyText
        textPaint.textSize = 17f * density
        indexPaint.color = scheme.keyText
        indexPaint.textSize = 13f * density
        pressPaint.color = scheme.specialKeyPressed
        cloudDrawable?.setTint(panel.candidateIndex)
    }

    private fun computeRowLayouts() {
        val w = width
        if (w <= 0) {
            rowLayouts = emptyList()
            return
        }
        rowLayouts = if (clipTab == ClipboardTab.CLIPBOARD) {
            val fm = textPaint.fontMetrics
            val lh = fm.descent - fm.ascent
            val maxTextW = w - hMargin * 2 - pillPad * 2 - indexPaint.measureText("9. ") - cloudIconSize - 2f * density
            clipboardEntries.map { entry ->
                val lines = breakText(entry.text, maxTextW, 4)
                RowLayout(
                    height = lh * lines.size + pillPad * 2,
                    indexLabel = "",
                    primaryLines = lines,
                    secondaryLines = emptyList(),
                    cloud = entry.cloud,
                )
            }
        } else {
            val fm = textPaint.fontMetrics
            val lh = fm.descent - fm.ascent
            val maxTextW = w - hMargin * 2 - pillPad * 2 - indexPaint.measureText("9. ") - 2f * density
            phrases.map { phrase ->
                val lines = breakText(phrase.text, maxTextW, 4)
                RowLayout(
                    height = lh * lines.size + pillPad * 2,
                    indexLabel = "",
                    primaryLines = lines,
                    secondaryLines = emptyList(),
                    cloud = false,
                )
            }
        }
    }

    private fun breakText(text: String, maxWidth: Float, maxLines: Int): List<String> {
        val lines = mutableListOf<String>()
        val cleanText = text.replace('\n', ' ')
        var start = 0
        while (start < cleanText.length && lines.size < maxLines) {
            val count = textPaint.breakText(cleanText, start, cleanText.length, true, maxWidth, null)
            var line = cleanText.substring(start, start + count)
            start += count
            if (start < cleanText.length && lines.size == maxLines - 1) {
                while (line.isNotEmpty() && textPaint.measureText(line + "...") > maxWidth) {
                    line = line.dropLast(1)
                }
                line += "..."
            }
            lines.add(line)
        }
        if (lines.isEmpty()) lines.add("")
        return lines
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        computeRowLayouts()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width <= 0 || height <= 0) return

        if (rowLayouts.isEmpty()) {
            val emptyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                textAlign = Paint.Align.CENTER
                textSize = 14f * density
                color = KeyboardColors.resolve(context).panel.candidateIndex
            }
            val msg = if (clipTab == ClipboardTab.CLIPBOARD) {
                context.getString(R.string.clipboard_empty)
            } else {
                context.getString(R.string.phrase_empty)
            }
            canvas.drawText(msg, width / 2f, headerH + (height - headerH) / 2f, emptyPaint)
            return
        }

        var totalContentH = topPad * 2f
        rowLayouts.forEach { totalContentH += it.height + listGap }
        maxScroll = maxOf(0f, totalContentH - (height - headerH))

        canvas.save()
        canvas.clipRect(0f, headerH, width.toFloat(), height.toFloat())
        canvas.translate(0f, headerH + topPad - scrollOffsetY)

        var y = 0f
        for ((i, row) in rowLayouts.withIndex()) {
            val h = row.height
            val left = hMargin
            val right = width - hMargin

            if (y + h < scrollOffsetY || y > scrollOffsetY + (height - headerH)) {
                y += h + listGap
                continue
            }

            val pressed = i == pressedIndex
            canvas.drawRoundRect(left, y, right, y + h, 8f * density, 8f * density,
                if (pressed) pressPaint else bgPaint)

            val textStartX = left + pillPad

            val fm = textPaint.fontMetrics
            val lh = fm.descent - fm.ascent
            val baseline = y + pillPad - fm.ascent
            val indexLabel = "${i + 1}. "
            val indexW = indexPaint.measureText(indexLabel)
            canvas.drawText(indexLabel, textStartX, baseline, indexPaint)
            for ((li, line) in row.primaryLines.withIndex()) {
                canvas.drawText(line, textStartX + indexW, baseline + lh * li, textPaint)
            }
            if (clipTab == ClipboardTab.CLIPBOARD) {
                if (row.cloud && cloudDrawable != null) {
                    val textX = textStartX + indexW
                    val iconLeft = textX + 2f * density
                    val iconTop = baseline - cloudIconSize
                    cloudDrawable.setBounds(
                        iconLeft.toInt(), iconTop.toInt(),
                        (iconLeft + cloudIconSize).toInt(), (iconTop + cloudIconSize).toInt()
                    )
                    cloudDrawable.draw(canvas)
                }
            }

            y += h + listGap
        }

        canvas.restore()
    }

    private fun itemIndexAt(y: Float): Int {
        val contentY = scrollOffsetY + y - headerH - topPad
        var cumulative = 0f
        for (i in rowLayouts.indices) {
            val h = rowLayouts[i].height
            if (contentY >= cumulative && contentY < cumulative + h) return i
            cumulative += h + listGap
        }
        return -1
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        velocityTracker?.addMovement(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                scroller.forceFinished(true)
                lastTouchY = event.y
                isScrolling = false
                velocityTracker = VelocityTracker.obtain()
                velocityTracker?.addMovement(event)

                val idx = itemIndexAt(event.y)
                if (idx in rowLayouts.indices) {
                    pressedIndex = idx
                    longPressPending = true
                    longPressX = event.x
                    longPressY = event.y
                    postDelayed(longPressRunnable, longPressTimeout.toLong())
                    invalidate()
                }
            }

            MotionEvent.ACTION_MOVE -> {
                val dy = event.y - lastTouchY
                if (!isScrolling && abs(dy) > touchSlop) {
                    if (longPressPending) {
                        removeCallbacks(longPressRunnable)
                        longPressPending = false
                    }
                    isScrolling = true
                    pressedIndex = -1
                    invalidate()
                }
                if (isScrolling) {
                    val rawOffset = scrollOffsetY - dy
                    scrollOffsetY = if (rawOffset < 0) {
                        rawOffset * 0.3f
                    } else if (rawOffset > maxScroll) {
                        maxScroll + (rawOffset - maxScroll) * 0.3f
                    } else rawOffset
                    lastTouchY = event.y
                    invalidate()
                }
            }

            MotionEvent.ACTION_UP -> {
                removeCallbacks(longPressRunnable)
                velocityTracker?.let { tracker ->
                    tracker.computeCurrentVelocity(1000)
                    val vy = tracker.yVelocity
                    if (abs(vy) > ViewConfiguration.get(context).scaledMinimumFlingVelocity) {
                        val startY = scrollOffsetY.roundToInt()
                        val velY = (-vy).toInt()
                        val maxY = maxScroll.toInt()
                        scroller.fling(0, startY, 0, velY, 0, 0, 0, maxY)
                        postInvalidateOnAnimation()
                    }
                }
                velocityTracker?.recycle()
                velocityTracker = null

                if (!isScrolling && pressedIndex >= 0 && pressedIndex < rowLayouts.size) {
                    val idx = pressedIndex
                    pressedIndex = -1
                    invalidate()
                    InputFeedbacks.hapticFeedback(this)
                    InputFeedbacks.soundEffect(context, InputFeedbacks.SoundEffect.Standard)
                    if (clipTab == ClipboardTab.CLIPBOARD) {
                        clipboardEntries.getOrNull(idx)?.let { onItemClick?.invoke(it) }
                    } else {
                        phrases.getOrNull(idx)?.let { onPhraseClick?.invoke(it) }
                    }
                }
                longPressPending = false
                pressedIndex = -1
                invalidate()
            }

            MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(longPressRunnable)
                velocityTracker?.recycle()
                velocityTracker = null
                pressedIndex = -1
                invalidate()
            }
        }
        return true
    }

    override fun computeScroll() {
        if (scroller.computeScrollOffset()) {
            scrollOffsetY = scroller.currY.toFloat().coerceIn(0f, maxScroll)
            invalidate()
            postInvalidateOnAnimation()
        }
    }
}
