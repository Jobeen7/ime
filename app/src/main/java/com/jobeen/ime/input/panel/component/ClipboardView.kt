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
    /** 排序模式下一次拖动结束（顺序确有变化）时回调：剪贴板给文本序、常用语给 id 序。 */
    var onClipboardReordered: ((List<String>) -> Unit)? = null
    var onPhrasesReordered: ((List<Long>) -> Unit)? = null
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
        // 拖选自动滚动是独立 post 链、不在 viewScope 内：窗口收起时
        // 必须收尾，否则它会继续滚动并改动选中集合
        endDragSelection(rollback = false)
        // 排序拖动同理：收尾并落库已拖出的顺序（中途关键盘不丢调整）
        if (reorderDragging) finishReorderDrag(persist = true)
        removeCallbacks(reorderAutoScrollRunnable)
        removeCallbacks(longPressRunnable)
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

    // ── 排序模式（剪贴板/常用语共用）：长按菜单进入，工具栏「完成」退出 ──
    // 模式内按住条目拖动：条目跟随手指，同组内（剪贴板按置顶与否分组）
    // 实时换位，抬手即落库。滚动只靠边缘自动滚动。与多选/编辑互斥，
    // 由面板保证不同时开启；搜索过滤中（显示的是子集）禁止进入。

    var reorderActive: Boolean = false
        private set
    private var reorderDragging = false
    private var reorderDragIndex = -1
    private var reorderGrabOffset = 0f
    private var reorderDragTop = 0f
    private var reorderLastY = 0f
    private var reorderDownLayoutY = 0f
    private var reorderOrderChanged = false
    private var reorderSnapshotEntries: List<ClipboardManager.Entry>? = null
    private var reorderSnapshotPhrases: List<PhraseManager.Phrase>? = null
    private var reorderSnapshotLayouts: List<RowLayout>? = null

    fun setReorderMode(active: Boolean) {
        if (reorderActive == active) return
        if (active && clipTab == ClipboardTab.CLIPBOARD && searchQuery.isNotBlank()) return
        if (!active) finishReorderDrag(persist = true)
        reorderActive = active
        pressedIndex = -1
        computeRowLayouts()
        invalidate()
    }

    /** 第 i 行在布局空间（onDraw 累计 y 口径）中的顶端。 */
    private fun slotTop(index: Int): Float {
        var t = 0f
        for (i in 0 until index.coerceAtMost(rowLayouts.size)) {
            t += rowLayouts[i].height + listGap
        }
        return t
    }

    /** 拖动条目当前所属的同组下标区间（剪贴板按置顶分组，常用语全表一组）。 */
    private fun reorderRangeFor(index: Int): IntRange {
        if (clipTab != ClipboardTab.CLIPBOARD) {
            return if (phrases.isEmpty()) IntRange.EMPTY else 0..phrases.lastIndex
        }
        return reorderGroupRange(displayedEntries.map { it.pinned }, index)
    }

    private fun moveReorderItem(from: Int, to: Int) {
        if (from == to) return
        if (clipTab == ClipboardTab.CLIPBOARD) {
            val m = displayedEntries.toMutableList()
            val item = m.removeAt(from); m.add(to, item)
            displayedEntries = m
            // 排序模式禁搜索，displayedEntries 与 clipboardEntries 同集同序，同步即可
            clipboardEntries = m
        } else {
            val m = phrases.toMutableList()
            val item = m.removeAt(from); m.add(to, item)
            phrases = m
        }
        val l = rowLayouts.toMutableList()
        val row = l.removeAt(from); l.add(to, row)
        rowLayouts = l
        totalContentH = totalHeightOf(l)
        reorderDragIndex = to
        reorderOrderChanged = true
        InputFeedbacks.hapticFeedback(this)
    }

    /** 按当前手指位置更新拖动态：条目跟随、越过邻位中心即换位。 */
    private fun updateReorderDrag(viewY: Float) {
        if (reorderDragIndex !in rowLayouts.indices) return
        val fingerLayoutY = scrollOffsetY + viewY - headerH - topPad
        val range = reorderRangeFor(reorderDragIndex)
        if (range.isEmpty()) return
        val rowH = rowLayouts[reorderDragIndex].height
        val groupTop = slotTop(range.first)
        val groupBottom = slotTop(range.last) + rowLayouts[range.last].height - rowH
        reorderDragTop = (fingerLayoutY - reorderGrabOffset).coerceIn(groupTop, groupBottom)
        // 目标位：被拖行中心落入的那一行
        val center = reorderDragTop + rowH / 2f
        var acc = 0f
        var target = range.last
        for (i in rowLayouts.indices) {
            val h = rowLayouts[i].height
            if (center < acc + h + listGap / 2f) { target = i; break }
            acc += h + listGap
        }
        moveReorderItem(reorderDragIndex, target.coerceIn(range))
    }

    private val reorderAutoScrollRunnable = object : Runnable {
        override fun run() {
            if (!reorderDragging) return
            val edge = 56f * density
            val step: Float = when {
                reorderLastY < edge -> {
                    val depth = (edge - reorderLastY.coerceAtLeast(0f)) / edge
                    -(4f + 12f * depth) * density
                }
                reorderLastY > height - edge -> {
                    val depth = (edge - (height - reorderLastY).coerceAtLeast(0f)) / edge
                    (4f + 12f * depth) * density
                }
                else -> 0f
            }
            if (step != 0f) {
                val maxS = maxOf(0f, totalContentH - height)
                val next = (scrollOffsetY + step).coerceIn(0f, maxS)
                if (next != scrollOffsetY) {
                    scrollOffsetY = next
                    updateReorderDrag(reorderLastY)
                    invalidate()
                }
                postDelayed(this, 16)
            }
        }
    }

    private fun finishReorderDrag(persist: Boolean) {
        removeCallbacks(reorderAutoScrollRunnable)
        if (reorderDragging && persist && reorderOrderChanged) {
            if (clipTab == ClipboardTab.CLIPBOARD) {
                onClipboardReordered?.invoke(displayedEntries.map { it.text })
            } else {
                onPhrasesReordered?.invoke(phrases.map { it.id })
            }
        }
        if (reorderDragging && !persist) {
            // 手势被打断：恢复拖动开始前的顺序快照
            reorderSnapshotEntries?.let { displayedEntries = it; clipboardEntries = it }
            reorderSnapshotPhrases?.let { phrases = it }
            reorderSnapshotLayouts?.let {
                rowLayouts = it
                totalContentH = totalHeightOf(it)
            }
        }
        reorderDragging = false
        reorderDragIndex = -1
        reorderOrderChanged = false
        reorderSnapshotEntries = null
        reorderSnapshotPhrases = null
        reorderSnapshotLayouts = null
    }

    private fun onReorderTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                scroller.forceFinished(true)
                finishReorderDrag(persist = false)
                val idx = itemIndexAt(event.y)
                if (idx in rowLayouts.indices) {
                    reorderDragIndex = idx
                    reorderDownLayoutY = scrollOffsetY + event.y - headerH - topPad
                    reorderGrabOffset = reorderDownLayoutY - slotTop(idx)
                    reorderDragTop = slotTop(idx)
                    reorderLastY = event.y
                    reorderOrderChanged = false
                    reorderSnapshotEntries = displayedEntries
                    reorderSnapshotPhrases = phrases
                    reorderSnapshotLayouts = rowLayouts
                    pressedIndex = idx
                }
                invalidate()
            }

            MotionEvent.ACTION_MOVE -> {
                reorderLastY = event.y
                if (reorderDragIndex < 0) return true
                if (!reorderDragging) {
                    val dy = abs(
                        (scrollOffsetY + event.y - headerH - topPad) - reorderDownLayoutY
                    )
                    if (dy <= touchSlop) return true
                    reorderDragging = true
                    pressedIndex = -1
                    removeCallbacks(reorderAutoScrollRunnable)
                    post(reorderAutoScrollRunnable)
                }
                updateReorderDrag(event.y)
                invalidate()
            }

            MotionEvent.ACTION_UP -> {
                finishReorderDrag(persist = true)
                pressedIndex = -1
                invalidate()
            }

            MotionEvent.ACTION_CANCEL -> {
                finishReorderDrag(persist = false)
                pressedIndex = -1
                invalidate()
            }
        }
        return true
    }

    fun setMultiSelect(active: Boolean) {
        if (multiSelectActive == active) return
        multiSelectActive = active
        if (!active) {
            endDragSelection(rollback = false)
            selectedTexts.clear()
        }
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
        // 只变勾选标记、行高不变：轻量更新各行 selected，不做全量布局重建
        refreshRowSelection()
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

    // ── 多选拖动连选（相册式） ──────────────────────────────────
    // 多选态下拖动不再滚动列表，而是连选：按下定锚点，超过滑动阈值后
    // 进入拖选，锚点到手指当前行构成区间，区间内统一设为目标状态
    // （锚点按下时未选→整趟选上，已选→整趟取消）；拖回时区间外恢复
    // 本趟开始前的快照。手指进入上/下边缘区时列表自动滚动，可连选
    // 到屏幕外。单独点按（未进入拖选）仍是切换一条。

    private var dragSelecting = false
    private var dragAnchorIndex = -1
    private var dragTargetState = true
    private var dragSnapshot: Set<String> = emptySet()
    private var dragLastY = 0f
    private var dragStartY = 0f

    private val autoScrollRunnable = object : Runnable {
        override fun run() {
            if (!dragSelecting) return
            val edge = 56f * density
            val step: Float = when {
                dragLastY < edge -> {
                    val depth = (edge - dragLastY.coerceAtLeast(0f)) / edge
                    -(4f + 12f * depth) * density
                }
                dragLastY > height - edge -> {
                    val depth = (edge - (height - dragLastY).coerceAtLeast(0f)) / edge
                    (4f + 12f * depth) * density
                }
                else -> 0f
            }
            if (step != 0f) {
                val maxS = maxOf(0f, totalContentH - height)
                val next = (scrollOffsetY + step).coerceIn(0f, maxS)
                if (next != scrollOffsetY) {
                    scrollOffsetY = next
                    applyDragSelection()
                    invalidate()
                }
                postDelayed(this, 16)
            }
        }
    }

    private fun startAutoScrollIfNeeded() {
        removeCallbacks(autoScrollRunnable)
        if (dragSelecting) post(autoScrollRunnable)
    }

    private fun stopDragAutoScroll() {
        removeCallbacks(autoScrollRunnable)
    }

    /** 按当前手指位置重算拖选区间并应用到选中集合（区间外恢复快照）。 */
    private fun applyDragSelection() {
        if (dragAnchorIndex < 0 || displayedEntries.isEmpty()) return
        var idx = dragIndexAt(dragLastY)
        if (idx < 0) {
            // 手指已拖到首行以上/末行以下（边缘自动滚动区）：钳到端点行
            idx = if (dragLastY < height / 2f) 0 else displayedEntries.lastIndex
        }
        val lo = minOf(dragAnchorIndex, idx)
        val hi = maxOf(dragAnchorIndex, idx)
        val next = HashSet(dragSnapshot)
        for (i in lo..hi) {
            val text = displayedEntries.getOrNull(i)?.text ?: continue
            if (dragTargetState) next.add(text) else next.remove(text)
        }
        if (next != selectedTexts) {
            selectedTexts.clear()
            selectedTexts.addAll(next)
            refreshRowSelection()
            InputFeedbacks.hapticFeedback(this)
            onSelectionChanged?.invoke()
        }
        invalidate()
    }

    /** 只更新各行的 selected 标记（行高不变，不做全量布局重建）。 */
    private fun refreshRowSelection() {
        rowLayouts = rowLayouts.mapIndexed { i, row ->
            val sel = multiSelectActive &&
                displayedEntries.getOrNull(i)?.text?.let { it in selectedTexts } == true
            if (row.selected == sel) row else row.copy(selected = sel)
        }
    }

    /**
     * 设定拖选锚点：目标状态按锚点当前状态取反（未选→整趟选上，已选→整趟取消）。
     * 例外：当前仅选中锚点一条（多为经长按菜单进入多选时的预选）时起拖按
     * 选取走——预选是系统替用户选的，用户落指的意图是从这里开始选，整趟
     * 变取消只会扑空；真要取消这一条点按即可。已手动选中多条时不受影响。
     */
    private fun setDragAnchor(idx: Int, y: Float) {
        dragAnchorIndex = idx
        dragStartY = y
        val text = displayedEntries.getOrNull(idx)?.text
        dragTargetState = text != null &&
            (text !in selectedTexts || selectedTexts.size == 1)
        dragSnapshot = LinkedHashSet(selectedTexts)
    }

    private fun endDragSelection(rollback: Boolean) {
        stopDragAutoScroll()
        if (dragSelecting && rollback) {
            selectedTexts.clear()
            selectedTexts.addAll(dragSnapshot)
            refreshRowSelection()
            onSelectionChanged?.invoke()
            invalidate()
        }
        dragSelecting = false
        dragAnchorIndex = -1
        dragSnapshot = emptySet()
    }

    /** 多选态手势：点按切换一条，拖动连选（见上方注释）。 */
    private fun onMultiTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                scroller.forceFinished(true)
                endDragSelection(rollback = false)
                dragStartY = event.y
                dragLastY = event.y
                isScrolling = false
                // 按下定位走严格命中（与普通点按同口径）：pressedIndex 是未
                // 进入拖选时抬手的 toggle 候选，行间空隙/空白吸附到邻行会
                // 误选；拖选区间本身的吸附仍由 applyDragSelection 里的
                // dragIndexAt 负责，不受此处口径影响
                val idx = itemIndexAt(event.y)
                pressedIndex = if (idx in rowLayouts.indices) idx else -1
                if (pressedIndex >= 0) setDragAnchor(pressedIndex, event.y)
                invalidate()
            }

            MotionEvent.ACTION_MOVE -> {
                dragLastY = event.y
                if (dragAnchorIndex < 0) {
                    // 按下时落在行外（如顶部空白、行间空隙）：手指移入行区后
                    // 补设锚点，否则整趟拖动无声失效（只有滚动、没有连选）；
                    // 补锚与 DOWN 同用严格命户口径，落在空隙不吸附邻行作锚点
                    val idx = itemIndexAt(event.y)
                    if (idx in rowLayouts.indices) setDragAnchor(idx, event.y)
                }
                if (!dragSelecting && dragAnchorIndex >= 0 &&
                    abs(event.y - dragStartY) > touchSlop
                ) {
                    dragSelecting = true
                    pressedIndex = -1
                    applyDragSelection()
                    startAutoScrollIfNeeded()
                } else if (dragSelecting) {
                    applyDragSelection()
                    startAutoScrollIfNeeded()
                }
            }

            MotionEvent.ACTION_UP -> {
                val wasDragging = dragSelecting
                endDragSelection(rollback = false)
                if (!wasDragging && pressedIndex in rowLayouts.indices) {
                    val idx = pressedIndex
                    displayedEntries.getOrNull(idx)?.let { entry ->
                        InputFeedbacks.hapticFeedback(this)
                        InputFeedbacks.soundEffect(context, InputFeedbacks.SoundEffect.Standard)
                        toggleSelected(entry)
                    }
                }
                pressedIndex = -1
                invalidate()
            }

            MotionEvent.ACTION_CANCEL -> {
                // 手势被系统打断：回滚本趟连选，避免半成品状态
                endDragSelection(rollback = true)
                pressedIndex = -1
                invalidate()
            }
        }
        return true
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

    /** reload 的后台计算结果：成员字段回主线程后一次性赋值，避免跨线程数据竞态。 */
    private class ReloadOutcome(
        val entries: List<ClipboardManager.Entry>,
        val displayed: List<ClipboardManager.Entry>,
        val phrases: List<PhraseManager.Phrase>,
        val layouts: List<RowLayout>,
    )

    private fun reload() {
        // 取消上一轮未完成的加载：避免连续 show/refresh 时旧结果覆盖新结果、滚动被重复重置
        reloadJob?.cancel()
        val tab = clipTab
        measureTextPaint.set(textPaint)
        measureIndexPaint.set(indexPaint)
        val w = width
        reloadJob = viewScope.launch {
            // 只加载当前标签页的数据（旧实现双表全量加载，另一个标签用不到也查）；
            // 断行布局计算放后台线程（条目上限可配到 500，旧实现全压主线程）。
            // 后台只算局部变量：clipboardEntries/displayedEntries/phrases/rowLayouts
            // 都在主线程被读改（setSearchQuery、绘制、点选按下标解析），后台直接写
            // 成员字段会与主线程互相覆盖、数据与布局新旧不一致
            val outcome = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                if (tab == ClipboardTab.CLIPBOARD) {
                    val entries = ClipboardManager.getEntries(context)
                    val displayed = filterEntries(entries, searchQuery)
                    ReloadOutcome(
                        entries, displayed, phrases,
                        buildRowLayouts(tab, w, measureTextPaint, measureIndexPaint, displayed, phrases),
                    )
                } else {
                    val all = PhraseManager.getAll(context)
                    ReloadOutcome(
                        clipboardEntries, displayedEntries, all,
                        buildRowLayouts(tab, w, measureTextPaint, measureIndexPaint, displayedEntries, all),
                    )
                }
            }
            // 数据即将整体替换：排序拖动态只清状态，不能走快照回滚
            // （快照是旧数据，写回会把新数据盖掉）
            if (reorderDragging) {
                removeCallbacks(reorderAutoScrollRunnable)
                reorderDragging = false
                reorderDragIndex = -1
                reorderOrderChanged = false
                reorderSnapshotEntries = null
                reorderSnapshotPhrases = null
                reorderSnapshotLayouts = null
            }
            clipboardEntries = outcome.entries
            displayedEntries = outcome.displayed
            phrases = outcome.phrases
            rowLayouts = outcome.layouts
            totalContentH = totalHeightOf(outcome.layouts)
            // 数据已换：拖选锚点下标可能失效，直接收尾（不回滚已选结果）
            if (dragSelecting) endDragSelection(rollback = false)
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
        entriesToLayout: List<ClipboardManager.Entry> = displayedEntries,
        phrasesToLayout: List<PhraseManager.Phrase> = phrases,
    ): List<RowLayout> {
        if (w <= 0) return emptyList()
        val fm = tp.fontMetrics
        val lh = fm.descent - fm.ascent
        return if (tab == ClipboardTab.CLIPBOARD) {
            // 多选态行首要让出勾选圆的位置，文本可用宽相应收窄
            val checkReserve = if (multiSelectActive) 26f * density else 0f
            // 排序模式行尾让出拖动手柄的位置
            val handleReserve = if (reorderActive) 26f * density else 0f
            val maxTextW = w - hMargin * 2 - pillPad * 2 - ip.measureText("9. ") - cloudIconSize - 2f * density - checkReserve - handleReserve
            entriesToLayout.map { entry ->
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
            val handleReserve = if (reorderActive) 26f * density else 0f
            val maxTextW = w - hMargin * 2 - pillPad * 2 - ip.measureText("9. ") - 2f * density - handleReserve
            phrasesToLayout.map { phrase ->
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

    // 行号标签缓存：onDraw 原本每帧为每个可见行新建 "N. " 字符串，
    // 标签只随下标变，按下标缓存复用，绘制结果不变
    private val indexLabelCache = ArrayList<String>()

    private fun indexLabel(index: Int): String {
        while (indexLabelCache.size <= index) {
            indexLabelCache.add("${indexLabelCache.size + 1}. ")
        }
        return indexLabelCache[index]
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
        var draggedRow: RowLayout? = null
        for ((i, row) in rowLayouts.withIndex()) {
            val h = row.height
            if (reorderDragging && i == reorderDragIndex) {
                // 被拖行最后单独画在手指位置（浮在其他行之上）
                draggedRow = row
                y += h + listGap
                continue
            }
            if (y + h < scrollOffsetY || y > scrollOffsetY + (height - headerH)) {
                y += h + listGap
                continue
            }
            drawRow(canvas, i, row, y)
            y += h + listGap
        }
        draggedRow?.let { drawRow(canvas, reorderDragIndex, it, reorderDragTop) }

        canvas.restore()
    }

    private fun drawRow(canvas: Canvas, i: Int, row: RowLayout, y: Float) {
        val h = row.height
        val left = hMargin
        val right = width - hMargin
        run {
            val pressed = i == pressedIndex || (reorderDragging && i == reorderDragIndex)
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
            val indexLabel = indexLabel(i)
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
            if (reorderActive) {
                // 排序模式：行尾拖动手柄（三条横线）
                val hx = right - pillPad - 7f * density
                val cy = y + h / 2f
                checkPaint.style = Paint.Style.STROKE
                checkPaint.color = emptyPaint.color
                checkPaint.strokeWidth = 1.6f * density
                for (k in -1..1) {
                    val ly = cy + k * 4.5f * density
                    canvas.drawLine(hx - 7f * density, ly, hx + 7f * density, ly, checkPaint)
                }
            }
        }
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
            // 空文本也要有光标，表达仍可继续输入
            editCursorPaint.strokeWidth = 1.6f * density
            canvas.drawLine(
                textX, topPad + 1f * density,
                textX, topPad + lh - 1f * density, editCursorPaint
            )
        } else {
            val cursorLine = editCursorLine()
            var y = topPad - scrollOffsetY
            for ((i, line) in editLines.withIndex()) {
                if (y + lh >= 0 && y <= height) {
                    canvas.drawText(line.text, textX, y - fm.ascent, textPaint)
                    if (i == cursorLine) {
                        val col = (editCursor - line.start).coerceIn(0, line.text.length)
                        // measureText 的区间重载与 substring 后测量逐位一致，免去每帧新建字符串
                        val cx = textX + textPaint.measureText(line.text, 0, col)
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

    /**
     * 拖选专用命中：行间空隙（listGap）不算落空，就近归到上一行；
     * 列表上下之外钳到首/末行。只用于拖选进行中的区间计算；点按、
     * 多选按下与拖选补锚都用严格的 [itemIndexAt]，避免空隙误触邻行。
     */
    private fun dragIndexAt(y: Float): Int {
        val direct = itemIndexAt(y)
        if (direct >= 0) return direct
        if (rowLayouts.isEmpty()) return -1
        val contentY = scrollOffsetY + y - headerH - topPad
        if (contentY < 0f) return 0
        var cumulative = 0f
        for (i in rowLayouts.indices) {
            cumulative += rowLayouts[i].height + listGap
            if (contentY < cumulative) return i
        }
        return rowLayouts.lastIndex
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
        }
        if (editDisplayActive) return onEditTouchEvent(event)
        if (multiSelectActive && clipTab == ClipboardTab.CLIPBOARD) return onMultiTouchEvent(event)
        if (reorderActive) return onReorderTouchEvent(event)
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

/**
 * 拖动排序时第 [index] 条所属的同组下标区间：按 pinned 标志的连续段
 * 划分（列表已按置顶在前排序，置顶段与未置顶段各自成组），拖动不得
 * 跨组——把未置顶条目拖进置顶区不等于用户想置顶它。
 */
internal fun reorderGroupRange(pinned: List<Boolean>, index: Int): IntRange {
    if (index !in pinned.indices) return IntRange.EMPTY
    val group = pinned[index]
    var lo = index
    while (lo > 0 && pinned[lo - 1] == group) lo--
    var hi = index
    while (hi < pinned.lastIndex && pinned[hi + 1] == group) hi++
    return lo..hi
}
