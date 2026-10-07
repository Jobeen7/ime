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
    /** 多选态下选中集合变化时回调（面板据此刷新工具栏计数）。 */
    var onSelectionChanged: (() -> Unit)? = null
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

    // ── 多选态（仅剪贴板标签）：选中集合以条目文本为键（与数据层业务键一致） ──
    var multiSelectActive: Boolean = false
        private set
    private val selectedTexts = LinkedHashSet<String>()
    val selectedCount: Int get() = selectedTexts.size

    fun selectedSnapshot(): Set<String> = LinkedHashSet(selectedTexts)

    fun setMultiSelect(active: Boolean) {
        if (multiSelectActive == active) return
        multiSelectActive = active
        if (!active) selectedTexts.clear()
        computeRowLayouts()
        invalidate()
    }

    /** 进入多选并预选一条（长按菜单「多选」的入口语义）。 */
    fun beginMultiSelect(entry: ClipboardManager.Entry) {
        selectedTexts.clear()
        selectedTexts.add(entry.text)
        multiSelectActive = true
        computeRowLayouts()
        invalidate()
        onSelectionChanged?.invoke()
    }

    fun toggleSelected(entry: ClipboardManager.Entry) {
        if (!selectedTexts.add(entry.text)) selectedTexts.remove(entry.text)
        computeRowLayouts()
        invalidate()
        onSelectionChanged?.invoke()
    }

    // ── 条目编辑显示模式 ────────────────────────────────────────
    // 编辑态下列表区改画条目全文（多行可滚）+ 光标，点按定位光标；
    // 与列表显示互斥（editDisplayActive 优先），数据仍由面板持有，
    // 这里只负责呈现与把点击换算成字符下标回调出去。

    var onEditCursorMoved: ((Int) -> Unit)? = null

    var editDisplayActive: Boolean = false
        private set
    private var editText: String = ""
    private var editCursor: Int = 0

    private data class EditLine(val start: Int, val text: String)

    private var editLines = listOf<EditLine>()
    private var editTotalH = 0f
    private val editHintPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val editCursorPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    fun setEditContent(text: String, cursor: Int) {
        editText = text
        editCursor = cursor.coerceIn(0, text.length)
        editDisplayActive = true
        rebuildEditLines()
        ensureCursorVisible()
        invalidate()
    }

    fun clearEditDisplay() {
        if (!editDisplayActive) return
        editDisplayActive = false
        editText = ""
        editCursor = 0
        editLines = emptyList()
        computeRowLayouts()
        resetScroll()
        invalidate()
    }

    private fun editLineHeight(): Float {
        val fm = textPaint.fontMetrics
        return fm.descent - fm.ascent
    }

    private fun rebuildEditLines() {
        val w = width
        if (w <= 0) {
            editLines = emptyList()
            editTotalH = 0f
            return
        }
        val maxTextW = w - hMargin * 2 - pillPad * 2
        val lines = mutableListOf<EditLine>()
        var segStart = 0
        // 按段落逐段断行：段内换行由 breakText 处理，'\n' 本身占一个字符位
        while (true) {
            val nl = editText.indexOf('\n', segStart)
            val segEnd = if (nl >= 0) nl else editText.length
            val seg = editText.substring(segStart, segEnd)
            val segLines = breakText(textPaint, seg, maxTextW, Int.MAX_VALUE)
            var lineStart = segStart
            for (line in segLines) {
                lines.add(EditLine(lineStart, line))
                lineStart += line.length
            }
            if (nl < 0) break
            segStart = nl + 1
            if (segStart > editText.length) break
            if (segStart == editText.length) {
                // 文本以 '\n' 结尾：尾部还有一个空行承载光标
                lines.add(EditLine(segStart, ""))
                break
            }
        }
        if (lines.isEmpty()) lines.add(EditLine(0, ""))
        editLines = lines
        editTotalH = topPad * 2 + lines.size * editLineHeight()
    }

    /** 光标所在行下标：cursor 落在行内或正好行尾（含段尾 '\n' 位）都归该行。 */
    private fun editCursorLine(): Int {
        for (i in editLines.indices) {
            val line = editLines[i]
            if (editCursor <= line.start + line.text.length) return i
        }
        return editLines.lastIndex.coerceAtLeast(0)
    }

    private fun ensureCursorVisible() {
        if (editLines.isEmpty() || height <= 0) return
        val lh = editLineHeight()
        val cursorTop = topPad + editCursorLine() * lh
        val cursorBottom = cursorTop + lh
        val viewH = height.toFloat()
        if (cursorTop < scrollOffsetY) {
            scrollOffsetY = cursorTop - topPad
        } else if (cursorBottom > scrollOffsetY + viewH) {
            scrollOffsetY = cursorBottom - viewH + topPad
        }
        val maxS = maxOf(0f, editTotalH - viewH)
        scrollOffsetY = scrollOffsetY.coerceIn(0f, maxS)
    }

    /** 把点击坐标换算成字符下标：y 定行，x 用逐字测宽找最近字界。 */
    private fun editIndexAt(x: Float, y: Float): Int {
        if (editLines.isEmpty()) return 0
        val lh = editLineHeight()
        val contentY = scrollOffsetY + y - topPad
        val li = (contentY / lh).toInt().coerceIn(0, editLines.lastIndex)
        val line = editLines[li]
        val textX = hMargin + pillPad
        var acc = 0f
        var best = 0
        var bestDist = Float.MAX_VALUE
        for (i in 0..line.text.length) {
            val dist = abs(x - (textX + acc))
            if (dist < bestDist) {
                bestDist = dist
                best = i
            }
            if (i < line.text.length) acc += textPaint.measureText(line.text[i].toString())
        }
        return line.start + best
    }

    /** 列表异步重载后调用：清掉已不存在条目的残留选中。 */
    private fun pruneSelection() {
        if (selectedTexts.isEmpty()) return
        val alive = clipboardEntries.mapTo(HashSet()) { it.text }
        if (selectedTexts.retainAll(alive)) onSelectionChanged?.invoke()
    }

    private data class RowLayout(
        val height: Float,
        val indexLabel: String,
        val primaryLines: List<String>,
        val secondaryLines: List<String>,
        val cloud: Boolean,
        val pinned: Boolean = false,
        val selected: Boolean = false,
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
    // 多选勾选：圆圈描边/填充与对勾线共用（颜色随主题在 updateColors 刷新）
    private val checkPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    // 置顶标记色（强调色），随主题在 updateColors 刷新
    private var accentColor = 0
    private var checkBgColor = 0

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
            // 编辑显示期间列表数据可照常更新，但滚动位置归编辑区，不能被重置
            if (!editDisplayActive) resetScroll()
            if (tab == ClipboardTab.CLIPBOARD) pruneSelection()
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
        checkBgColor = scheme.keyBackground
        editCursorPaint.color = accentColor
        editHintPaint.color = panel.candidateIndex
        editHintPaint.textSize = 14f * density

        emptyPaint.color = panel.candidateIndex
        cloudDrawable?.setTint(panel.candidateIndex)
    }

    private fun computeRowLayouts() {
        // 编辑显示模式下尺寸变化只需重排编辑行，列表行等退出编辑时再建
        if (editDisplayActive) {
            rebuildEditLines()
            return
        }
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
            // 多选态行首要让出勾选圆的位置，文本可用宽相应收窄
            val checkReserve = if (multiSelectActive) 26f * density else 0f
            val maxTextW = w - hMargin * 2 - pillPad * 2 - ip.measureText("9. ") - cloudIconSize - 2f * density - checkReserve
            displayedEntries.map { entry ->
                val lines = breakText(tp, entry.text, maxTextW, 4)
                RowLayout(
                    height = lh * lines.size + pillPad * 2,
                    indexLabel = "",
                    primaryLines = lines,
                    secondaryLines = emptyList(),
                    cloud = entry.cloud,
                    pinned = entry.pinned,
                    selected = multiSelectActive && entry.text in selectedTexts,
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

        if (editDisplayActive) {
            drawEditContent(canvas)
            return
        }

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
            if (multiSelectActive && clipTab == ClipboardTab.CLIPBOARD) {
                // 行首勾选圆：选中=强调色填充+对勾，未选=描边空心
                val ccx = left + pillPad + 8f * density
                val ccy = y + h / 2f
                val cr = 8f * density
                if (row.selected) {
                    checkPaint.style = Paint.Style.FILL
                    checkPaint.color = accentColor
                    canvas.drawCircle(ccx, ccy, cr, checkPaint)
                    checkPaint.style = Paint.Style.STROKE
                    checkPaint.color = checkBgColor
                    checkPaint.strokeWidth = 1.8f * density
                    canvas.drawLine(
                        ccx - 4.2f * density, ccy + 0.2f * density,
                        ccx - 1.4f * density, ccy + 3.2f * density, checkPaint
                    )
                    canvas.drawLine(
                        ccx - 1.4f * density, ccy + 3.2f * density,
                        ccx + 4.6f * density, ccy - 3.4f * density, checkPaint
                    )
                } else {
                    checkPaint.style = Paint.Style.STROKE
                    checkPaint.color = emptyPaint.color
                    checkPaint.strokeWidth = 1.4f * density
                    canvas.drawCircle(ccx, ccy, cr, checkPaint)
                }
            }
            if (row.pinned) {
                // 置顶标记：行左内侧一条强调色竖条
                canvas.drawRoundRect(
                    left + 3f * density, y + 7f * density,
                    left + 6f * density, y + h - 7f * density,
                    1.5f * density, 1.5f * density, pinBarPaint
                )
            }

            val textStartX = left + pillPad +
                (if (multiSelectActive && clipTab == ClipboardTab.CLIPBOARD) 26f * density else 0f)

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

    /** 编辑模式绘制：全文逐行 + 光标竖线；空文本画提示。 */
    private fun drawEditContent(canvas: Canvas) {
        maxScroll = maxOf(0f, editTotalH - height)
        val lh = editLineHeight()
        val fm = textPaint.fontMetrics
        val textX = hMargin + pillPad
        canvas.save()
        canvas.clipRect(0f, 0f, width.toFloat(), height.toFloat())
        if (editText.isEmpty()) {
            canvas.drawText(
                context.getString(R.string.clipboard_edit_hint),
                textX, topPad - fm.ascent, editHintPaint
            )
        } else {
            val cursorLine = editCursorLine()
            var y = topPad - scrollOffsetY
            for ((i, line) in editLines.withIndex()) {
                if (y + lh >= 0 && y <= height) {
                    canvas.drawText(line.text, textX, y - fm.ascent, textPaint)
                    if (i == cursorLine) {
                        val col = (editCursor - line.start).coerceIn(0, line.text.length)
                        val cx = textX + textPaint.measureText(line.text.substring(0, col))
                        editCursorPaint.strokeWidth = 1.6f * density
                        canvas.drawLine(cx, y + 1f * density, cx, y + lh - 1f * density, editCursorPaint)
                    }
                }
                y += lh
            }
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
        if (editDisplayActive) return onEditTouchEvent(event)
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
                    // 多选态不挂长按：长按触发会清掉 pressedIndex，
                    // 使抬手时的勾选切换落空（点得稍久就"点不上"）
                    if (!multiSelectActive) {
                        longPressPending = true
                        longPressX = event.x
                        longPressY = event.y
                        postDelayed(longPressRunnable, longPressTimeout.toLong())
                    }
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
                        displayedEntries.getOrNull(idx)?.let { entry ->
                            // 多选态点行只切换勾选，绝不上屏
                            if (multiSelectActive) toggleSelected(entry) else onItemClick?.invoke(entry)
                        }
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

    /**
     * 编辑模式手势：拖动滚动全文，抬手未滚动则按落点定位光标。
     * 与列表手势同构，但没有长按菜单与点选上屏。
     */
    private fun onEditTouchEvent(event: MotionEvent): Boolean {
        velocityTracker?.addMovement(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                scroller.forceFinished(true)
                lastTouchY = event.y
                isScrolling = false
                velocityTracker = VelocityTracker.obtain()
                velocityTracker?.addMovement(event)
            }

            MotionEvent.ACTION_MOVE -> {
                val dy = event.y - lastTouchY
                if (!isScrolling && abs(dy) > touchSlop) isScrolling = true
                if (isScrolling) {
                    val editMax = maxOf(0f, editTotalH - height)
                    val rawOffset = scrollOffsetY - dy
                    scrollOffsetY = if (rawOffset < 0) {
                        rawOffset * 0.3f
                    } else if (rawOffset > editMax) {
                        editMax + (rawOffset - editMax) * 0.3f
                    } else rawOffset
                    lastTouchY = event.y
                    invalidate()
                }
            }

            MotionEvent.ACTION_UP -> {
                velocityTracker?.let { tracker ->
                    tracker.computeCurrentVelocity(1000)
                    val vy = tracker.yVelocity
                    val editMax = maxOf(0f, editTotalH - height)
                    if (abs(vy) > ViewConfiguration.get(context).scaledMinimumFlingVelocity) {
                        scroller.fling(
                            0, scrollOffsetY.roundToInt(), 0, (-vy).toInt(),
                            0, 0, 0, editMax.toInt()
                        )
                        postInvalidateOnAnimation()
                    }
                }
                velocityTracker?.recycle()
                velocityTracker = null
                if (!isScrolling) {
                    InputFeedbacks.hapticFeedback(this)
                    onEditCursorMoved?.invoke(editIndexAt(event.x, event.y))
                }
                isScrolling = false
            }

            MotionEvent.ACTION_CANCEL -> {
                velocityTracker?.recycle()
                velocityTracker = null
                isScrolling = false
            }
        }
        return true
    }
}
