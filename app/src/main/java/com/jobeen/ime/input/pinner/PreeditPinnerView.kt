package com.jobeen.ime.input.pinner

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.widget.OverScroller
import com.jobeen.ime.engine.data.EngineMessage
import kotlin.math.abs
import kotlin.math.roundToInt

@SuppressLint("ViewConstructor")
class PreeditPinnerView(context: Context) : View(context) {

    var preeditItems: List<EngineMessage.DynamicPreedit.DynamicPreeditItem> = emptyList()
        set(value) {
            field = value
            scrollX = 0f
            scroller.forceFinished(true)
            pendingScrollToEnd = value.isNotEmpty()
            if (value.isEmpty()) requestLayout()
            invalidate()
        }

    var maxWidth: Int = Int.MAX_VALUE
        set(value) {
            if (field != value) {
                field = value
                scrollX = 0f
                scroller.forceFinished(true)
                pendingScrollToEnd = true
                if (preeditItems.isNotEmpty()) requestLayout()
            }
        }

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.DEFAULT_BOLD
    }
    private val secondaryTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.DEFAULT_BOLD
    }
    private val pad = 10f * context.resources.displayMetrics.density
    private val density = context.resources.displayMetrics.density

    private var pendingScrollToEnd = false
    private var contentWidth = 0f
    // onMeasure 算好的逐项绘制宽度，供 onDraw 复用
    private var itemWidths: FloatArray? = null
    private var scrollX = 0f
    private var textScale = 1f
    private val scrolledTextPaint = Paint()
    private val scrolledSecondaryTextPaint = Paint()
    private val scroller = OverScroller(context)
    private var velocityTracker: VelocityTracker? = null
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val minFlingVelocity = ViewConfiguration.get(context).scaledMinimumFlingVelocity
    private var lastTouchX = 0f
    private var isDragging = false
    private val minScale = (10f * density) / textPaint.textSize


    fun applyTheme(bgColor: Int, textColor: Int, secondaryTextColor: Int, textSize: Float) {
        bgPaint.color = bgColor
        textPaint.color = textColor
        textPaint.textSize = textSize
        secondaryTextPaint.color = secondaryTextColor
        secondaryTextPaint.textSize = textSize - 2
        scrolledTextPaint.set(textPaint)
        scrolledSecondaryTextPaint.set(secondaryTextPaint)
        invalidate()
    }

    private fun paintFor(item: EngineMessage.DynamicPreedit.DynamicPreeditItem): Paint =
        if (item.type == EngineMessage.DynamicPreedit.DynamicPreeditType.Normal) textPaint
        else secondaryTextPaint

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val items = preeditItems
        if (items.isEmpty()) {
            setMeasuredDimension(0, 0)
            return
        }
        val pillH = 30f * density

        var totalW = 0f
        for (item in items) {
            totalW += paintFor(item).measureText(item.text)
        }
        contentWidth = totalW + pad * 2

        if (contentWidth > maxWidth) {
            val fitScale = (maxWidth - pad * 2f) / totalW
            textScale = fitScale.coerceAtLeast(minScale).coerceAtMost(1f)
            scrolledTextPaint.textSize = textPaint.textSize * textScale
            scrolledSecondaryTextPaint.textSize = secondaryTextPaint.textSize * textScale
            var scaledW = 0f
            for (item in items) {
                scaledW += scrolledPaintFor(item).measureText(item.text)
            }
            contentWidth = scaledW + pad * 2
        } else {
            textScale = 1f
            scrolledTextPaint.textSize = textPaint.textSize
            scrolledSecondaryTextPaint.textSize = secondaryTextPaint.textSize
        }

        // 最终绘制宽度逐项算好存下：onDraw 此前对同一批 item 再逐个
        // measureText 一遍（onMeasure 已测过两遍），纯重复
        itemWidths = FloatArray(items.size) { i ->
            scrolledPaintFor(items[i]).measureText(items[i].text)
        }

        val displayW = (contentWidth.roundToInt()).coerceAtMost(maxWidth)
        setMeasuredDimension(displayW, pillH.roundToInt())

        if (pendingScrollToEnd) {
            pendingScrollToEnd = false
            scrollX = (contentWidth - displayW).coerceAtLeast(0f)
        }
    }

    private fun scrolledPaintFor(item: EngineMessage.DynamicPreedit.DynamicPreeditItem): Paint =
        if (item.type == EngineMessage.DynamicPreedit.DynamicPreeditType.Normal) scrolledTextPaint
        else scrolledSecondaryTextPaint

    override fun onDraw(canvas: Canvas) {
        val items = preeditItems
        if (items.isEmpty()) return
        val r = 8f * density

        canvas.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), r, r, bgPaint)

        canvas.save()
        canvas.clipRect(0f, 0f, width.toFloat(), height.toFloat())
        canvas.translate(-scrollX, 0f)

        val textY = height / 2f - (textPaint.descent() + textPaint.ascent()) / 2f
        val widths = itemWidths
        var x = pad
        for ((i, item) in items.withIndex()) {
            val paint = scrolledPaintFor(item)
            canvas.drawText(item.text, x, textY, paint)
            // 宽度优先用 onMeasure 的缓存；尺寸对不上（数据刚变更）时回退实测
            x += if (widths != null && widths.size == items.size) widths[i]
            else paint.measureText(item.text)
        }

        canvas.restore()
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val maxScroll = (contentWidth - width).coerceAtLeast(0f)
        if (maxScroll <= 0f) {
            // 内容在手势中途缩短到不可滚动时提前返回：已 obtain 的 tracker
            // 必须先回收，此前直接 return 会让 obtain/recycle 不配对
            velocityTracker?.recycle()
            velocityTracker = null
            isDragging = false
            return false
        }

        velocityTracker?.addMovement(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                scroller.forceFinished(true)
                lastTouchX = event.x
                isDragging = false
                velocityTracker = VelocityTracker.obtain()
                velocityTracker?.addMovement(event)
            }

            MotionEvent.ACTION_MOVE -> {
                val dx = lastTouchX - event.x
                if (!isDragging && abs(dx) > touchSlop) {
                    isDragging = true
                    parent?.requestDisallowInterceptTouchEvent(true)
                }
                if (isDragging) {
                    scrollX = (scrollX + dx).coerceIn(0f, maxScroll)
                    lastTouchX = event.x
                    invalidate()
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                velocityTracker?.let { tracker ->
                    tracker.computeCurrentVelocity(1000)
                    val vx = tracker.xVelocity
                    if (isDragging && abs(vx) > minFlingVelocity) {
                        scroller.fling(
                            scrollX.roundToInt(), 0,
                            (-vx).toInt(), 0,
                            0, maxScroll.roundToInt(),
                            0, 0
                        )
                        postInvalidateOnAnimation()
                    }
                }
                velocityTracker?.recycle()
                velocityTracker = null
                isDragging = false
                parent?.requestDisallowInterceptTouchEvent(false)
            }
        }
        return isDragging || maxScroll > 0f
    }

    override fun computeScroll() {
        if (scroller.computeScrollOffset()) {
            scrollX = scroller.currX.toFloat().coerceIn(0f, (contentWidth - width).coerceAtLeast(0f))
            postInvalidateOnAnimation()
        }
    }
}
