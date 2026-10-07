package com.jobeen.ime.input.panel.component

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
import com.jobeen.ime.R
import com.jobeen.ime.base.feedback.InputFeedbacks
import com.jobeen.ime.data.keyboard.theme.KeyboardColors
import com.jobeen.ime.data.manager.ClipboardManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import com.jobeen.ime.data.manager.PhraseManager
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

    private var viewScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        // ClipboardView 是常驻 View，会随输入窗口 detach/attach；detach 时旧 scope 已取消，
        // 必须重建，否则重新挂载后剪贴板与常用语都无法再异步加载。
        viewScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    }

    override fun onDetachedFromWindow() {
        viewScope.cancel()
        super.onDetachedFromWindow()
    }

    private val headerH = 0f
    private val hMargin = 10f * density
    private val gap = 6f * density
    private val topPad = 6f * density
    private val listGap = 6f * density
    private val pillPad = 10f * density

    var clipTab: ClipboardTab = ClipboardTab.CLIPBOARD
    private var clipboardEntries = listOf<ClipboardManager.Entry>()
    private var phrases = listOf<PhraseManager.Phrase>()

    /** 搜索查询（仅剪贴板标签）：非空时列表只显示文本包含查询的条目。 */
    var searchQuery: String = ""
        private set

    // 当前实际显示的剪贴板条目（查询过滤后）；行布局与点击/长按下标都以此为准，
    // 查询为空时与 clipboardEntries 相同
    private var displayedEntries = listOf<ClipboardManager.Entry>()

    private data class RowLayout(
        val height: Float,
        val indexLabel: String,
        val primaryLines: List<String>,
        val secondaryLines: List<String>,
        val cloud: Boolean,
        val pinned: Boolean = false,
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
    private val pinBarPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    // 置顶标记色（强调色），随主题在 updateColors 刷新
    private var accentColor = 0

    private val emptyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
    }
    // 全部行的总内容高：布局重建时算好，onDraw 不再每帧累加
    private var totalContentH = 0f
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
                displayedEntries.getOrNull(idx)?.let { onItemLongClick?.invoke(it, x, y) }
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

    private var reloadJob: kotlinx.coroutines.Job? = null

    // 布局测宽专用 Paint（在主线程从绘制 Paint 同步参数后交给后台线程用，
    // 避免后台与 onDraw 共用同一个 Paint 实例）
    private val measureTextPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    private val measureIndexPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)

    private fun reload() {
        // 取消上一轮未完成的加载：避免连续 show/refresh 时旧结果覆盖新结果、滚动被重复重置
        reloadJob?.cancel()
        val tab = clipTab
        measureTextPaint.set(textPaint)
        measureIndexPaint.set(indexPaint)
        val w = width
        reloadJob = viewScope.launch {
            // 只加载当前标签页的数据（旧实现双表全量加载，另一个标签用不到也查）；
            // 断行布局计算放后台线程（条目上限可配到 500，旧实现全压主线程）
            val layouts = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                if (tab == ClipboardTab.CLIPBOARD) {
                    clipboardEntries = ClipboardManager.getEntries(context)
                    displayedEntries = filterEntries(clipboardEntries, searchQuery)
                } else {
                    phrases = PhraseManager.getAll(context)
                }
                buildRowLayouts(tab, w, measureTextPaint, measureIndexPaint)
            }
            rowLayouts = layouts
            totalContentH = totalHeightOf(layouts)
            resetScroll()
            invalidate()
        }
    }

    private fun filterEntries(
        entries: List<ClipboardManager.Entry>,
        query: String,
    ): List<ClipboardManager.Entry> {
        val q = query.trim()
        if (q.isEmpty()) return entries
        return entries.filter { it.text.contains(q, ignoreCase = true) }
    }

    /** 更新搜索查询并即时按新查询重排当前列表（主线程调用）。 */
    fun setSearchQuery(query: String) {
        if (searchQuery == query) return
        searchQuery = query
        if (clipTab != ClipboardTab.CLIPBOARD) return
        displayedEntries = filterEntries(clipboardEntries, query)
        computeRowLayouts()
        resetScroll()
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
        accentColor = scheme.accentKeyBackground
        pinBarPaint.color = accentColor

        emptyPaint.color = panel.candidateIndex
        cloudDrawable?.setTint(panel.candidateIndex)
    }

    private fun computeRowLayouts() {
        val w = width
        if (w <= 0) {
            rowLayouts = emptyList()
            totalContentH = 0f
            return
        }
        rowLayouts = buildRowLayouts(clipTab, w, textPaint, indexPaint)
        totalContentH = totalHeightOf(rowLayouts)
    }

    private fun totalHeightOf(layouts: List<RowLayout>): Float {
        var total = topPad * 2f
        layouts.forEach { total += it.height + listGap }
        return total
    }

    private fun buildRowLayouts(
        tab: ClipboardTab,
        w: Int,
        tp: android.graphics.Paint,
        ip: android.graphics.Paint,
    ): List<RowLayout> {
        if (w <= 0) return emptyList()
        val fm = tp.fontMetrics
        val lh = fm.descent - fm.ascent
        return if (tab == ClipboardTab.CLIPBOARD) {
            val maxTextW = w - hMargin * 2 - pillPad * 2 - ip.measureText("9. ") - cloudIconSize - 2f * density
            displayedEntries.map { entry ->
                val lines = breakText(tp, entry.text, maxTextW, 4)
                RowLayout(
                    height = lh * lines.size + pillPad * 2,
                    indexLabel = "",
                    primaryLines = lines,
                    secondaryLines = emptyList(),
                    cloud = entry.cloud,
                    pinned = entry.pinned,
                )
            }
        } else {
            val maxTextW = w - hMargin * 2 - pillPad * 2 - ip.measureText("9. ") - 2f * density
            phrases.map { phrase ->
                val lines = breakText(tp, phrase.text, maxTextW, 4)
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

    private fun breakText(
        paint: android.graphics.Paint,
        text: String,
        maxWidth: Float,
        maxLines: Int,
    ): List<String> {
        val lines = mutableListOf<String>()
        val cleanText = text.replace('\n', ' ')
        var start = 0
        while (start < cleanText.length && lines.size < maxLines) {
            val count = paint.breakText(cleanText, start, cleanText.length, true, maxWidth, null)
            var line = cleanText.substring(start, start + count)
            start += count
            if (start < cleanText.length && lines.size == maxLines - 1) {
                // 省略号截断用 breakText 一次算出可容纳字符数：旧实现逐字
                // dropLast + measureText，长文本时接近 O(n²)
                val ellipsis = "..."
                val fit = paint.breakText(line, true, maxWidth - paint.measureText(ellipsis), null)
                line = line.substring(0, fit.coerceIn(0, line.length)) + ellipsis
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
            // 空态 Paint 此前每帧新建并现解析主题色；提为字段，颜色随主题刷新
            emptyPaint.textSize = 14f * density
            val msg = if (clipTab == ClipboardTab.CLIPBOARD) {
                if (searchQuery.isNotBlank()) {
                    context.getString(R.string.clipboard_search_empty)
                } else {
                    context.getString(R.string.clipboard_empty)
                }
            } else {
                context.getString(R.string.phrase_empty)
            }
            canvas.drawText(msg, width / 2f, headerH + (height - headerH) / 2f, emptyPaint)
            return
        }

        // totalContentH 在布局重建时已算好缓存，不再每帧全量累加
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
            if (row.pinned) {
                // 置顶标记：行左内侧一条强调色竖条
                canvas.drawRoundRect(
                    left + 3f * density, y + 7f * density,
                    left + 6f * density, y + h - 7f * density,
                    1.5f * density, 1.5f * density, pinBarPaint
                )
            }

            val textStartX = left + pillPad

            val fm = textPaint.fontMetrics
            val lh = fm.descent - fm.ascent
            val baseline = y + pillPad - fm.ascent
            val indexLabel = "${i + 1}. "
            val indexW = indexPaint.measureText(indexLabel)
            if (row.pinned) {
                val normalColor = indexPaint.color
                indexPaint.color = accentColor
                canvas.drawText(indexLabel, textStartX, baseline, indexPaint)
                indexPaint.color = normalColor
            } else {
                canvas.drawText(indexLabel, textStartX, baseline, indexPaint)
            }
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
                        displayedEntries.getOrNull(idx)?.let { onItemClick?.invoke(it) }
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
            postInvalidateOnAnimation()
        }
    }
}
