package com.jobeen.ime.input.panel.component

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.widget.LinearLayout
import android.widget.OverScroller
import android.widget.TextView
import androidx.core.graphics.withRotation
import androidx.core.graphics.withSave
import com.jobeen.ime.base.util.slideUpCollapse
import com.jobeen.ime.data.keyboard.theme.KeyboardColors
import com.jobeen.ime.data.manager.CandidateLinePolicy
import com.jobeen.ime.data.manager.CandidateManager
import com.jobeen.ime.data.manager.CandidateSpacingPolicy
import com.jobeen.ime.engine.data.CandidatePinYin
import com.jobeen.ime.engine.data.EngineMessage
import com.jobeen.ime.input.keyboard.key.KeyDef
import com.jobeen.ime.input.keyboard.key.KeyboardAction
import com.jobeen.ime.input.keyboard.key.SidePanelKeyView
import com.jobeen.ime.skin.model.AreaInsets
import com.jobeen.ime.skin.model.VerticalBottomRowNames
import com.jobeen.ime.skin.model.VerticalBottomRowSpec
import com.jobeen.ime.input.panel.panelColorScheme
import com.jobeen.ime.skin.runtime.SkinSnapshotProvider
import splitties.views.dsl.core.lParams
import splitties.views.dsl.core.matchParent
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 竖网格生效行列上限（审计 I 项）：皮肤值优先于用户行数偏好；
 * 皮肤未定义时行数走用户偏好、列数走默认 5（对端文档默认值）。
 * syncLinePolicy 与 refreshTheme 共用，单测钉优先级。
 */
internal fun effectiveGridLimits(
    skinMaxRows: Int?,
    skinMaxColumns: Int?,
    userMaxRows: Int,
): Pair<Int, Int> = (skinMaxRows ?: userMaxRows) to (skinMaxColumns ?: 5)

/** 竖网格底部钮行钮种（规范顺序：翻页上／翻页下／返回或收起／退格）。 */
internal enum class BottomRowButtonKind { PAGE_UP, PAGE_DOWN, RETURN_OR_COLLAPSE, BACKSPACE }

/** 竖网格底部钮行钮（标签＋钮种；视图层按钮种分发）。 */
internal data class BottomRowButton(val label: String, val kind: BottomRowButtonKind)

/**
 * 竖网格底部钮行钮表（纯函数，供单测）：仅声明为真的钮入表，
 * 顺序恒为规范顺序；返回钮文案双名同认（“返回”／“收起”原值，
 * 其余值回落“返回”）；无钮行（null／全否）为空表。
 */
internal fun bottomRowButtons(spec: VerticalBottomRowSpec?): List<BottomRowButton> {
    if (spec == null || !spec.hasAny) return emptyList()
    return buildList {
        if (spec.pageUp) add(BottomRowButton("▲", BottomRowButtonKind.PAGE_UP))
        if (spec.pageDown) add(BottomRowButton("▼", BottomRowButtonKind.PAGE_DOWN))
        if (spec.returnOrCollapse) {
            val label = spec.returnLabel?.takeIf { VerticalBottomRowNames.isReturnLabel(it) }
                ?: VerticalBottomRowNames.RETURN
            add(BottomRowButton(label, BottomRowButtonKind.RETURN_OR_COLLAPSE))
        }
        if (spec.backspace) add(BottomRowButton("⌫", BottomRowButtonKind.BACKSPACE))
    }
}

@SuppressLint("ViewConstructor")
class CandidateGridView(
    context: Context,
    colors: KeyboardColors.ColorScheme,
    var onCandidateSelected: ((EngineMessage.Candidate) -> Unit)? = null,
    var onSidePanelAction: ((KeyboardAction) -> Unit)? = null,
    var subscribePossibleCandidatePinYin: Boolean = true,
    var maxVisibleRow: Int = 5,
    var maxVisibleColumn: Int = 5,   // 保留并真正使用
) : ComponentView(context, colors) {

    /**
     * 候选栏行策略（2026-10-29 批量第 4 项）：show/updateCandidates
     * 时按偏好同步；父容器布局时读这组缓存（不走偏好 I/O）。
     */
    var lineLimitEnabled: Boolean = true
    var lineMax: Int = 5
    var lineAutoShrink: Boolean = true
    var fullDisplay: Boolean = true
    /**
     * 候选间距／缩放（H2-3）：syncLinePolicy 按偏好同步；未设定
     * 间距时 null，refreshAreaGeometry 回落皮肤值／竖网格默认 8dp；
     * 缩放默认 1.0（旧行为）。
     */
    var userWordSpaceDp: Float? = null
    var candidateScale: Float = 1f

    /** 按偏好同步行策略（候选到达时调用，偏好变化下次候选到达生效） */
    private fun syncLinePolicy() {
        lineLimitEnabled = CandidateManager.isLineLimitEnabled(context)
        lineMax = CandidateManager.getMaxLines(context)
        lineAutoShrink = CandidateManager.isAutoShrinkEnabled(context)
        fullDisplay = CandidateManager.isFullDisplayEnabled(context)
        // 候选间距／缩放（H2-3）：与行策略同 funnel 同步；变化时
        // 立即重算画布几何（未布局时 refreshAreaGeometry 空转守卫）。
        val newSpacing = CandidateManager.getCandidateSpacingDp(context)
        val newScale = CandidateManager.getCandidateScale(context)
        if (newSpacing != userWordSpaceDp || newScale != candidateScale) {
            userWordSpaceDp = newSpacing
            candidateScale = newScale
            gridCanvas.refreshAreaGeometry()
        }
        // 皮肤行列上限优先（审计 I 项）：皮肤未定义时回落用户偏好／默认。
        val limits = effectiveGridLimits(
            skinMaxRows,
            skinMaxColumns,
            if (lineLimitEnabled) lineMax else Int.MAX_VALUE,
        )
        maxVisibleRow = limits.first
        maxVisibleColumn = limits.second
    }

    /** 内容总行数（网格画布已算好的行布局） */
    fun contentRowCount(): Int = gridCanvas.contentRows()

    /** 自然行高 = 网格最小行高 32dp（与画布 onSizeChanged 同值） */
    fun naturalRowHeightPx(): Int =
        (32f * resources.displayMetrics.density).roundToInt()

    /**
     * 竖网格底部钮行（对端 verticalCandidatesLayout 第二行）：
     * refreshTheme 从快照同步；null／全否 = 无钮行。钮行占网格
     * 内高（窗口 gridH 不变，画布让出一自然行），故窗口无需改动。
     */
    var bottomRowSpec: VerticalBottomRowSpec? = null

    /** 底部钮行高度：有钮行时一自然行，否则 0。 */
    fun bottomRowHeightPx(): Int =
        if (bottomRowSpec != null) naturalRowHeightPx() else 0

    private val bottomStrip = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        visibility = View.GONE
    }

    /** 按当前钮行规范重建钮条（钮表纯函数见 [bottomRowButtons]）。 */
    private fun rebuildBottomStrip() {
        val buttons = bottomRowButtons(bottomRowSpec)
        bottomStrip.removeAllViews()
        if (buttons.isEmpty()) {
            bottomStrip.visibility = View.GONE
            return
        }
        // 主题色先取局部变量：GradientDrawable.apply 内自带 colors
        // （渐变色数组）会遮蔽外层主题 colors，须避开该遮蔽。
        val keyBg = colors.keyBackground
        val keyPressed = colors.keyPressed
        val keyText = colors.keyText
        bottomStrip.visibility = View.VISIBLE
        buttons.forEach { button ->
            val bg = GradientDrawable().apply {
                setColor(keyBg)
                cornerRadius = candidateRadiusDp * resources.displayMetrics.density
            }
            val buttonView = TextView(context).apply {
                text = button.label
                gravity = Gravity.CENTER
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                setIncludeFontPadding(false)
                isClickable = true
                isFocusable = false
                setTextColor(keyText)
                background = bg
                setOnTouchListener { _, event ->
                    // 按压态反馈：按下换按键色，抬手／取消恢复
                    // （返回 false，Click 照常触发）。
                    when (event.actionMasked) {
                        MotionEvent.ACTION_DOWN -> bg.setColor(keyPressed)
                        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                            bg.setColor(keyBg)
                    }
                    false
                }
                setOnClickListener {
                    when (button.kind) {
                        BottomRowButtonKind.PAGE_UP -> gridCanvas.pageUp()
                        BottomRowButtonKind.PAGE_DOWN -> gridCanvas.pageDown()
                        BottomRowButtonKind.RETURN_OR_COLLAPSE ->
                            onSidePanelAction?.invoke(
                                KeyboardAction.CandidatesBarToggleAction,
                            )
                        BottomRowButtonKind.BACKSPACE ->
                            onSidePanelAction?.invoke(KeyboardAction.BackspaceAction)
                    }
                }
            }
            bottomStrip.addView(
                buttonView,
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f),
            )
        }
    }

    // ── 布局数据类 ──
    private class WordPos(
        val row: Int, val xStart: Float, val width: Float,        // 分配后的显示宽度
        val extraWide: Boolean   // 是否超宽独占一行
    )

    private var allCandidates: List<EngineMessage.Candidate> = emptyList()
    var onCandidatesReordered: ((List<EngineMessage.Candidate>) -> Unit)? = null

    /**
     * 候选字背景圆角（2026-10-28 截图 6 项）：dp；refreshTheme 时
     * 从当前皮肤快照同步，无皮肤时 6（旧写死值）。网格高亮矩形
     * 与药丸圆角共用此值。
     */
    var candidateRadiusDp: Float = 6f
    /**
     * 竖网格候选区样式（审计 B 项）：refreshTheme 从快照同步；
     * insets 使画布内缩（命中同步偏移），background 覆盖网格底。
     */
    var areaInsets: AreaInsets = AreaInsets()
    var areaBackground: Int? = null
    /**
     * 候选项内边距（审计 I 项）：refreshTheme 从竖网格样式节点同步；
     * null = 旧写死值（cellPad 6dp、词间距 8dp、最小行高 32dp）。
     */
    var cellPadDp: Float? = null
    var minWordSpaceDp: Float? = null
    var minRowHeightDp: Float? = null
    /**
     * 竖网格行列上限（审计 I 项）：refreshTheme 从竖网格候选节点
     * 同步；null = 未定义（syncLinePolicy 回落用户偏好／默认 5）。
     */
    var skinMaxRows: Int? = null
    var skinMaxColumns: Int? = null
    /**
     * 竖网格侧边栏显示（审计 I 项）：refreshTheme 从竖网格布局
     * 节点同步；false = 网格占满宽（侧边栏 GONE + 宽 0）。
     */
    var showSidePanel: Boolean = true
    var onDragComplete: ((List<EngineMessage.Candidate>) -> Unit)? = null
    var onWordForget: ((EngineMessage.Candidate, Float, Float) -> Unit)? = null
    /**
     * 候选菜单回调（审计 I 项）：皮肤定义菜单时网格长按抬手
     * 走此回调而非 onWordForget；未定义时 onWordForget 不变。
     */
    var onCandidateMenu: ((
        EngineMessage.Candidate,
        List<com.jobeen.ime.skin.model.CandidateMenuItem>,
    ) -> Unit)? = null

    /**
     * 候选在本机是否可显示：只判定单个 CJK 表意字——用网格实际绘制的
     * Paint 查字体字形，设备字体无字形的扩展区生僻字（渲染成方框）判
     * 为不可显示。多字词、符号、emoji、字母数字一律不动（词组即使含
     * 个别生僻字也是可辨认的整体，且不在方框问题的主体范围内）。
     */
    fun isDisplayable(candidate: EngineMessage.Candidate): Boolean {
        val text = candidate.text
        if (text.codePointCount(0, text.length) != 1) return true
        if (text.codePointAt(0) < 0x3400) return true
        return gridCanvas.hasGlyph(text)
    }

    /** 引擎侧是否还有未取的候选（面板在每次整表/追加更新时下发） */
    var hasMoreCandidates: Boolean = false
    /** 滚动接近底部且 hasMoreCandidates 时触发，由面板转给引擎补取下一页 */
    var onNeedMoreCandidates: (() -> Unit)? = null
    /** 本批已发出补取请求、等列表更新后复位，避免同一位置反复触发 */
    private var needMoreRequested: Boolean = false
    private var needMoreCheckPosted: Boolean = false

    private val sidePanelKey = SidePanelKeyView(
        context, colors,
        KeyDef.Appearance.SidePannel(
            rowSpan = 4,
            visableRow = 5,
            margin = false,
            variant = KeyDef.Appearance.Variant.None,
            border = KeyDef.Appearance.Border.Off
        ),
    ).apply { setOnItemActionListener { action -> onSidePanelAction?.invoke(action) } }

    private val sidePanelPunctuationItems: List<KeyDef>

    // ── 画布核心 ──
    private val gridCanvas = object : View(context) {
        private var pressedIndex = -1
        private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val sepPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        private val pressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

        private var rowH = 0f
        private var cellPad = 0f
        private var minWordSpace = 0f
        /** 区样式生效尺寸（审计 B 项）：可用宽高，尺寸未到时为 0（调用方回落视图尺寸）。 */
        private var effW = 0f
        private var effH = 0f
        /** 首选色（审计 I 项）：updateColors 从 panel 同步；null = 关闭。 */
        private var preferredBg: Int? = null
        private var preferredText: Int? = null
        /**
         * 首选色生效范围：RIME 候选 true；预测／联想展开时由
         * show() 置 false——预测首项不是 RIME 首选候选，不得染
         * 首选色（与横条渲染器同口径）。
         */
        var preferredEnabled: Boolean = true

        var positions = emptyList<WordPos>()
            private set

        // 布局期测得的文本宽度缓存：绘制/命中测试直接查表，
        // 不再每帧对每个候选重复 measureText
        private var textWidths = FloatArray(0)

        // 每行第一个候选在 positions 中的下标：绘制从可见行开始、命中测试只扫一行
        private var rowStartIndex = IntArray(0)

        private val rowScrollX = mutableMapOf<Int, Float>()
        // 当前手势的指针 id：只追踪第一根落指，其他指针不得串扰坐标与收尾
        private var activePointerId = -1

        private var downX = 0f
        private var horizontalDrag = -1

        private var visibleRows = 0
        private val scroller = OverScroller(context)
        private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
        private val minFlingVelocity = ViewConfiguration.get(context).scaledMinimumFlingVelocity
        private val maxFlingVelocity = ViewConfiguration.get(context).scaledMaximumFlingVelocity
        private var velocityTracker: VelocityTracker? = null
        private var scrollOffsetY = 0f
        private var lastY = 0f
        private var downY = 0f
        private var downIndex = -1
        private var dragging = false
        private var longPressTriggered = false
        private var longPressMoved = false
        private var longPressIndex = -1
        private var dragIndex = -1

        /**
         * 拖拽中的候选本体：下标只是它的当前位置。候选列表整表替换
         * 时按本体重新定位，找不到（已被新列表淘汰）才取消拖拽——
         * 只按下标跟踪会让同位置的另一个候选被误当成拖拽对象拿去
         * 重排，改错条目。
         */
        private var dragItem: EngineMessage.Candidate? = null
        private var dragTargetIndex = -1
        private var dragFingerX = 0f
        private var dragFingerY = 0f
        private val longPressRunnable = Runnable {
            longPressTriggered = true
            longPressIndex = if (!dragging && horizontalDrag < 0) downIndex else -1
            invalidate()
        }
        private var stretch = 0f
        private var stretchAnimator: ValueAnimator? = null
        private var shakePhase = 0f
        private var shakeRunnable: Runnable? = null
        private var shakeMaxAngle = 2f
        private val overScrollLimit get() = max(height * 0.45f, 1f)

        // 缓存：旧实现每次访问都全量扫描 positions 求最大行（拖动 MOVE 与
        // computeScroll 每帧多次访问）；布局或高度变化时才重算
        private var maxScrollCache: Float? = null

        private val maxScroll
            get(): Float {
                maxScrollCache?.let { return it }
                val totalRows = positions.maxOfOrNull { it.row }?.plus(1) ?: visibleRows
                return ((totalRows * rowH - height).coerceAtLeast(0f)).also { maxScrollCache = it }
            }

        fun updateColors(pc: KeyboardColors.ColorScheme.PanelColors) {
            bgPaint.color = areaBackground ?: pc.background
            textPaint.color = pc.candidateText
            // 竖分隔线色（审计 I 项）：缺失回落 candidateDivider；
            // 横条分隔线（Paints.dividerPaint）不走此字段。
            sepPaint.color = pc.verticalSeparator ?: pc.candidateDivider
            // 按压色（审计 I 项）：缺失回落候选底（旧口径）；此前
            // pressPaint 只用于拖动目标，现兼按压格底。
            pressPaint.color = pc.candidatePressed ?: pc.candidateBackground
            // 首选色（审计 I 项）：null = 关闭（网格无引擎选中态，
            // 首位文字色直接取首选色，无优先级冲突）。
            preferredBg = pc.preferredBackground
            preferredText = pc.preferredText
        }

        /** 内容总行数：行布局已算好时 = 最大行号 + 1，未算好时 0 */
        fun contentRows(): Int = positions.maxOfOrNull { it.row }?.plus(1) ?: 0

        fun recomputeLayout() {
            maxScrollCache = null
            if (width <= 0 || allCandidates.isEmpty()) {
                positions = emptyList()
                textWidths = FloatArray(0)
                rowStartIndex = IntArray(0)
                return
            }

            val rowWidth = if (effW > 0f) effW else width.toFloat()
            // 文本宽度只在这里测一次，绘制与命中测试复用缓存
            textWidths = FloatArray(allCandidates.size) {
                textPaint.measureText(allCandidates[it].text)
            }
            // 每个候选词原始宽度 = 文本宽度 + 左右内边距
            val rawWidths = FloatArray(allCandidates.size) {
                textWidths[it] + cellPad * 2f
            }

            val tempPositions = mutableListOf<WordPos>()
            var i = 0
            var row = 0

            while (i < allCandidates.size) {
                val firstW = rawWidths[i]
                // 超宽词：独自一行，可水平滚动
                if (firstW > rowWidth) {
                    tempPositions.add(WordPos(row, 0f, firstW, true))
                    i++
                    row++
                    continue
                }

                // 收集一行内尽可能多的词，但不超过 maxVisibleColumn 个
                val lineIndices = mutableListOf(i)
                var accumulatedRaw = firstW
                i++
                while (i < allCandidates.size && lineIndices.size < maxVisibleColumn) {
                    val curW = rawWidths[i]
                    if (accumulatedRaw + minWordSpace + curW <= rowWidth) {
                        lineIndices.add(i)
                        accumulatedRaw += curW
                        i++
                    } else {
                        break
                    }
                }

                // 均匀分配剩余空间到每个词块
                val n = lineIndices.size
                val extraEach = (rowWidth - accumulatedRaw) / n
                val avgW = rowWidth / n
                // 预判膨胀后的总宽是否超标
                var inflatedTotal = 0f
                for (idx in lineIndices) {
                    inflatedTotal += if (rawWidths[idx] < avgW) avgW else rawWidths[idx]
                }
                val canInflate = inflatedTotal <= rowWidth

                var x = 0f
                for (idx in lineIndices) {
                    var w = rawWidths[idx] + extraEach
                    if (canInflate && rawWidths[idx] < avgW) {
                        w = avgW
                    }
                    tempPositions.add(WordPos(row, x, w, false))
                    x += w
                }
                row++
            }

            positions = tempPositions
            // 行首下标表：行数 = 最大行号 + 1
            val rowCount = (tempPositions.maxOfOrNull { it.row } ?: -1) + 1
            rowStartIndex = IntArray(rowCount) { positions.size }
            for (i in tempPositions.indices) {
                val r = tempPositions[i].row
                if (i < rowStartIndex[r]) rowStartIndex[r] = i
            }
            rowScrollX.clear()
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            maxScrollCache = null
            val d = resources.displayMetrics.density
            sepPaint.strokeWidth = 1f * d
            updateColors(colors.panel)
            refreshAreaGeometry()
            resetScroll()
        }

        /**
         * 区样式生效尺寸（审计 B 项）：画布可用宽高 = 视图尺寸
         * 减区 insets；行高与可见行数按可用高算，布局按可用宽算。
         * onSizeChanged 与 refreshTheme（insets 变化但尺寸不变时）
         * 共用；不碰滚动（主题刷新不重置滚动位置）。
         */
        fun refreshAreaGeometry() {
            val w = width
            val h = height
            if (w <= 0 || h <= 0) return
            val d = resources.displayMetrics.density
            // 候选字号（H2-3）：缩放后字号单源在此写（onSizeChanged
            // 同走这里；1.0 时与旧写死值逐值一致）。
            textPaint.textSize = CandidateSpacingPolicy.scaledTextSizeDp(
                com.jobeen.ime.input.panel.Paints.CANDIDATE_TEXT_DP, candidateScale,
            ) * d
            // 候选项内边距单源：尺寸变化与主题刷新都走这里重算，
            // 避免 onSizeChanged 与 refreshTheme 各写一份漂移。
            cellPad = (cellPadDp ?: 6f) * d
            // 候选间距（H2-3）：用户显式值 > 皮肤值 > 竖网格默认 8dp。
            minWordSpace = CandidateSpacingPolicy.resolveSpacing(
                userWordSpaceDp, minWordSpaceDp, 8f,
            ) * d
            effW = (w - (areaInsets.left + areaInsets.right) * d).coerceAtLeast(0f)
            effH = (h - (areaInsets.top + areaInsets.bottom) * d).coerceAtLeast(0f)
            val minRowH = (minRowHeightDp ?: 32f) * d
            visibleRows = (effH / minRowH).toInt().coerceIn(1, maxVisibleRow)
            rowH = if (visibleRows > 0) effH / visibleRows else 0f
            maxScrollCache = null
            recomputeLayout()
            invalidate()
        }

        /** 单行候选绘制（含拖动抖动旋转）：两行排版时每行各调一次。 */
        private fun drawCandidateLine(
            canvas: Canvas,
            text: String,
            x: Float,
            y: Float,
            w: Float,
            shakeAngle: Float,
        ) {
            if (shakeAngle != 0f) {
                canvas.withRotation(shakeAngle, x + w / 2f, y) {
                    drawText(text, x, y, textPaint)
                }
            } else {
                canvas.drawText(text, x, y, textPaint)
            }
        }

        override fun onDraw(canvas: Canvas) {
            if (width <= 0 || height <= 0) return
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)

            // 区样式内缩（审计 B 项）：内容在 (leftPx, topPx) 平移后的
            // 网格坐标系内绘制，裁剪到可用区；拖动图一并在系内画。
            val insetDensity = resources.displayMetrics.density
            val leftPx = areaInsets.left * insetDensity
            val topPx = areaInsets.top * insetDensity
            val rowWidth = if (effW > 0f) effW else width.toFloat()
            val h = if (effH > 0f) effH else height.toFloat()

            val firstRow = (scrollOffsetY / rowH).toInt().coerceAtLeast(0)
            val yOff = -(scrollOffsetY - firstRow * rowH) + stretch
            // 一帧内恒定：行基线偏移在循环外算一次（此前每候选重复求值）
            val textCenterDy = (textPaint.descent() + textPaint.ascent()) / 2f
            // 行高（基准字号）：两行缩放的上限据此算
            val baseLineH = textPaint.descent() - textPaint.ascent()
            val densityF = resources.displayMetrics.density

            canvas.withSave {
                clipRect(leftPx, topPx, leftPx + rowWidth, topPx + h)
                translate(leftPx, topPx)
                canvas.withSave {
                    clipRect(0f, 0f, rowWidth, h)
                val startIdx = rowStartIndex.getOrElse(firstRow) { 0 }
                for (i in startIdx until positions.size) {
                    val pos = positions[i]
                    if (pos.row < firstRow) continue
                    val y = (pos.row - firstRow) * rowH + yOff
                    if (y > h) break

                    // 被拖动项仅隐藏内容，其右侧分割线仍需绘制
                    if (i != dragIndex) {
                        val c = allCandidates[i]
                        // 首选文字色（审计 I 项）：本格画完即还原，
                        // 避免 textPaint 的色污染后续格。
                        val savedTextColor = textPaint.color
                        if (i == 0 && preferredEnabled) preferredText?.let { textPaint.color = it }
                        val tw = textWidths.getOrElse(i) { 0f }
                        val isTarget = i == dragTargetIndex && dragIndex >= 0

                        // 同一 sin 值两处用，只算一遍（此前每候选每帧算两次）
                        val shakeSin = if (dragIndex >= 0) {
                            kotlin.math.sin(shakePhase + i * 1.9f)
                        } else 0f
                        val shakeOff = shakeSin * 0.8f * densityF
                        val shakeAngle = shakeSin * shakeMaxAngle

                        // 按压反馈（审计 I 项）：pressedIndex 此前只被
                        // 追踪、从未绘制；现以按压色铺满本格（文字前画）。
                        // 首选底次之（首位、非按压时）；两者皆无则不铺底。
                        if (i == pressedIndex) {
                            val d = resources.displayMetrics.density
                            canvas.drawRoundRect(
                                pos.xStart + shakeOff, y, pos.xStart + pos.width + shakeOff, y + rowH,
                                candidateRadiusDp * d, candidateRadiusDp * d, pressPaint,
                            )
                        } else if (i == 0 && preferredEnabled && preferredBg != null) {
                            val d = resources.displayMetrics.density
                            val oldPressColor = pressPaint.color
                            pressPaint.color = preferredBg!!
                            canvas.drawRoundRect(
                                pos.xStart + shakeOff, y, pos.xStart + pos.width + shakeOff, y + rowH,
                                candidateRadiusDp * d, candidateRadiusDp * d, pressPaint,
                            )
                            pressPaint.color = oldPressColor
                        }

                        if (pos.extraWide) {
                            val sx = rowScrollX[pos.row] ?: 0f
                            canvas.withSave {
                                clipRect(0f, y, rowWidth, y + rowH)
                                if (isTarget) {
                                    val d = resources.displayMetrics.density
                                    canvas.drawRoundRect(
                                        0f, y, rowWidth, y + rowH, candidateRadiusDp * d, candidateRadiusDp * d, pressPaint
                                    )
                                }
                                val textX = cellPad - sx + shakeOff
                                val textY = y + rowH / 2f - textCenterDy
                                if (shakeAngle != 0f) {
                                    val cx = textX + tw / 2f
                                    val cy = textY
                                    canvas.withRotation(shakeAngle, cx, cy) {
                                        drawText(c.text, textX, textY, textPaint)
                                    }
                                } else {
                                    drawText(c.text, textX, textY, textPaint)
                                }
                            }
                        } else {
                            val rectLeft = pos.xStart + shakeOff
                            val rectRight = pos.xStart + pos.width + shakeOff
                            if (isTarget) {
                                val d = resources.displayMetrics.density
                                canvas.drawRoundRect(
                                    rectLeft, y, rectRight, y + rowH, candidateRadiusDp * d, candidateRadiusDp * d, pressPaint
                                )
                            }
                            // 完整显示开：整词原样画（格宽已按文本伸展）。
                            // 完整显示关：长候选先缩字号再两行，不截断
                            // （超宽独行词走横向滚动，不进这里）。
                            if (fullDisplay) {
                                val textX = (pos.xStart + shakeOff + (pos.width - tw) / 2f)
                                    .coerceAtLeast(rectLeft)
                                val textY = y + rowH / 2f - textCenterDy
                                drawCandidateLine(canvas, c.text, textX, textY, tw, shakeAngle)
                            } else {
                                val avail = pos.width - cellPad * 2f
                                // 两行须塞进单行格：下限字号按行高再钳一次
                                // （行高 32dp、行高 21.5dp 时下限约 0.7；行矮
                                // 时下限再降，底线 0.5，保证两行能塞下）
                                val twoLineCap = if (baseLineH > 0f) rowH / (2f * baseLineH) else 0.7f
                                val floor = minOf(0.7f, twoLineCap).coerceAtLeast(0.5f)
                                val layout = CandidateLinePolicy.layoutLongCandidate(
                                    c.text, avail, textPaint::measureText, minScale = floor,
                                )
                                val savedSize = textPaint.textSize
                                textPaint.textSize = savedSize * layout.scale
                                val lineH = baseLineH * layout.scale
                                val centerDyS = textCenterDy * layout.scale
                                layout.lines.forEachIndexed { li, line ->
                                    val lw = textPaint.measureText(line)
                                    val lx = (pos.xStart + shakeOff + (pos.width - lw) / 2f)
                                        .coerceAtLeast(rectLeft)
                                    val ly = if (layout.lines.size == 1) {
                                        y + rowH / 2f - centerDyS
                                    } else {
                                        y + rowH / 2f + (li - 0.5f) * lineH - centerDyS
                                    }
                                    drawCandidateLine(canvas, line, lx, ly, lw, shakeAngle)
                                }
                                textPaint.textSize = savedSize
                            }
                        }
                        textPaint.color = savedTextColor
                    }

                    if (i + 1 < positions.size && !pos.extraWide) {
                        val nextPos = positions[i + 1]
                        if (nextPos.row == pos.row) {
                            val sepX = pos.xStart + pos.width
                            val cy = y + rowH / 2f
                            val lh = textPaint.textSize * 0.8f
                            canvas.drawLine(sepX, cy - lh / 2f, sepX, cy + lh / 2f, sepPaint)
                        }
                    }
                }
            }

                if (dragIndex >= 0 && dragIndex in allCandidates.indices) {
                    val c = allCandidates[dragIndex]
                    val dragW = textWidths.getOrElse(dragIndex) { 0f } + cellPad * 2
                    // 超宽候选的拖动图有效宽度钳到视口宽：起点计算与实际
                    // 绘制宽度同用钳后值。dragLeft/dragTop 经 dragOrigin
                    // 计算，尺寸先钳到视口、coerceIn 区间恒非空（旧写法
                    // dragW > width 时上限为负、必抛 IllegalArgumentException）
                    // 手指坐标是视图坐标，进平移后的网格系须减内缩偏移。
                    val effectiveDragW = minOf(dragW, rowWidth)
                    val dragLeft = dragOrigin(dragFingerX - leftPx, dragW, rowWidth)
                    val dragTop = dragOrigin(dragFingerY - topPx, rowH, h)

                    val d = resources.displayMetrics.density
                    val oldColor = bgPaint.color
                    bgPaint.color = sepPaint.color
                    val oldAlpha = bgPaint.alpha
                    bgPaint.alpha = 160
                    canvas.drawRoundRect(
                        dragLeft, dragTop, dragLeft + effectiveDragW, dragTop + rowH, candidateRadiusDp * d, candidateRadiusDp * d, bgPaint
                    )
                    bgPaint.alpha = oldAlpha
                    bgPaint.color = oldColor

                    canvas.drawText(
                        c.text,
                        dragLeft + cellPad,
                        dragTop + rowH / 2f - textCenterDy,
                        textPaint
                    )
                }
            }

            // 补取判定不在绘制调用栈内直接回调 listener：post 到帧后执行，
            // 数据请求与绘制事务解耦，避免改绘制逻辑时踩回调重入
            if (!needMoreCheckPosted) {
                needMoreCheckPosted = true
                post {
                    needMoreCheckPosted = false
                    checkNeedMore()
                }
            }
        }

        /**
         * 分页补取触发：滚动进入距底一行半以内、且引擎侧还有未取候选时
         * 请求下一页。绘制末尾统一判定，覆盖拖动与 fling 两条滚动路径；
         * needMoreRequested 防同一批次内重复请求，列表更新时复位。
         */
        private fun checkNeedMore() {
            if (!hasMoreCandidates || needMoreRequested) return
            val limit = maxScroll
            // 内容填不满一屏（limit=0）时同样要补取：首屏候选恰好一屏高
            // 时用户根本滚不动，不能等"滚动到底"才触发。补取回来列表
            // 更新会复位请求标志并重绘，此处再判定——如此续取到内容
            // 可滚动或引擎到底（hasMoreCandidates 转 false）为止
            if (limit <= 0f || scrollOffsetY >= limit - rowH * 1.5f) {
                needMoreRequested = true
                onNeedMoreCandidates?.invoke()
            }
        }

        // 字形覆盖只随字体变；本视图生命周期内 textPaint 只改颜色/字号、
        // 从不换字体，结果可按文本缓存。面板每轮整表过滤都会逐字走到
        // 这里，常用字第二次起直接命中缓存，不再走字体回退查询
        private val glyphCache = HashMap<String, Boolean>()

        /** 用实际绘制的 Paint 查字形（系统默认字体链，与真实渲染一致） */
        fun hasGlyph(text: String): Boolean {
            if (glyphCache.size > 4096) glyphCache.clear()
            return glyphCache.getOrPut(text) { textPaint.hasGlyph(text) }
        }

        private fun hitTest(x: Float, y: Float): Int {
            if (positions.isEmpty() || rowH <= 0f) return -1
            // 区样式内缩（审计 B 项）：命中坐标先减内缩偏移，
            // 落在可用区外的一律不命中（与绘制裁剪一致）。
            val d = resources.displayMetrics.density
            val ax = x - areaInsets.left * d
            val ay = y - areaInsets.top * d
            val availW = if (effW > 0f) effW else width.toFloat()
            val availH = if (effH > 0f) effH else height.toFloat()
            if (ax < 0f || ay < 0f || ax > availW || ay > availH) return -1
            // 由纵坐标直接定位行，只扫该行候选（原实现每个 MOVE 事件全量线性扫描）
            val adjustedY = ay + scrollOffsetY
            val row = (adjustedY / rowH).toInt()
            if (row < 0 || row >= rowStartIndex.size) return -1
            var i = rowStartIndex[row]
            if (i >= positions.size) return -1
            while (i < positions.size && positions[i].row == row) {
                val pos = positions[i]
                if (pos.extraWide) return i
                if (ax in pos.xStart..(pos.xStart + pos.width)) return i
                i++
            }
            return -1
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    if (activePointerId != -1) return true
                    activePointerId = event.getPointerId(0)
                    parent.requestDisallowInterceptTouchEvent(true)
                    if (!scroller.isFinished) scroller.abortAnimation()

                    velocityTracker = VelocityTracker.obtain()
                    velocityTracker?.addMovement(event)
                    downY = event.y
                    lastY = event.y
                    downX = event.x
                    downIndex = hitTest(event.x, event.y)
                    pressedIndex = downIndex
                    horizontalDrag = -1
                    dragging = false
                    longPressTriggered = false
                    longPressMoved = false
                    longPressIndex = -1
                    invalidate()
                    postDelayed(
                        longPressRunnable, ViewConfiguration.getLongPressTimeout().toLong()
                    )
                }

                MotionEvent.ACTION_MOVE -> {
                    velocityTracker?.addMovement(event)
                    val pIdx = event.findPointerIndex(activePointerId)
                    if (pIdx < 0) return true
                    val ex = event.getX(pIdx)
                    val ey = event.getY(pIdx)
                    val dy = lastY - ey
                    val dx = ex - downX
                    val absDy = abs(ey - downY)
                    val absDx = abs(ex - downX)

                    if (dragIndex >= 0) {
                        dragFingerX = ex
                        dragFingerY = ey
                        val hit = hitTest(ex, ey)
                        dragTargetIndex = if (hit >= 0 && hit != dragIndex) hit else dragIndex
                        invalidate()
                        lastY = ey
                        return true
                    }

                    if (horizontalDrag >= 0) {
                        val pos = positions.getOrNull(horizontalDrag)
                        if (pos != null && pos.extraWide) {
                            val tw = textWidths.getOrElse(horizontalDrag) { 0f }
                            val maxSx = (tw - (width - cellPad * 2f)).coerceAtLeast(0f)
                            val old = rowScrollX[pos.row] ?: 0f
                            rowScrollX[pos.row] = (old + (downX - ex)).coerceIn(0f, maxSx)
                            downX = ex
                            invalidate()
                        }
                        lastY = ey
                        return true
                    }

                    if (longPressTriggered) {
                        val moved = absDy > touchSlop || absDx > touchSlop
                        if (moved) longPressMoved = true
                        if (moved && longPressIndex >= 0) {
                            dragIndex = longPressIndex
                            // 拖动图从本帧起就按手指位置画：不同步的话
                            // 进入拖动的第一帧（及停住不动的抖动帧）仍用
                            // 上一次拖动的落点，首次拖动时画在 (0,0)
                            dragFingerX = ex
                            dragFingerY = ey
                            dragItem = allCandidates.getOrNull(longPressIndex)
                            dragTargetIndex = longPressIndex
                            pressedIndex = -1
                            longPressTriggered = false
                            removeCallbacks(longPressRunnable)
                            startShake()
                            invalidate()
                        }
                        lastY = ey
                        return true
                    }

                    if (!dragging && !longPressTriggered) {
                        if (absDy > touchSlop && absDy >= absDx) {
                            dragging = true
                            // 已进入滚动：长按计时必须取消，否则滚动中途长按触发会把手势劫持成拖拽排序
                            removeCallbacks(longPressRunnable)
                            pressedIndex = -1
                            stretch = 0f
                            if (!scroller.isFinished) scroller.abortAnimation()
                        } else if (absDx > touchSlop && absDx > absDy) {
                            val pos = positions.getOrNull(pressedIndex)
                            if (pos != null && pos.extraWide) {
                                horizontalDrag = pressedIndex
                                removeCallbacks(longPressRunnable)
                                pressedIndex = -1
                                invalidate()
                            }
                        }
                    }

                    if (dragging) {
                        dragBy(dy)
                        invalidate()
                    } else if (horizontalDrag < 0 && !longPressTriggered) {
                        val n = hitTest(ex, ey)
                        if (n != pressedIndex) {
                            pressedIndex = n
                            invalidate()
                        }
                    }

                    lastY = ey
                }

                MotionEvent.ACTION_UP -> {
                    activePointerId = -1
                    handleUp(event)
                }

                MotionEvent.ACTION_POINTER_UP -> {
                    if (event.getPointerId(event.actionIndex) == activePointerId) {
                        activePointerId = -1
                        handleUp(event)
                    }
                }

                MotionEvent.ACTION_CANCEL -> {
                    activePointerId = -1
                    recycleVelocityTracker()
                    removeCallbacks(longPressRunnable)
                    stopShake()
                    dragIndex = -1
                        dragItem = null
                    dragTargetIndex = -1
                    pressedIndex = -1
                    horizontalDrag = -1
                    dragging = false
                    longPressTriggered = false
                    longPressMoved = false
                    springBackIfNeeded()
                    invalidate()
                    parent.requestDisallowInterceptTouchEvent(false)
                }
            }
            return true
        }

        /**
         * 候选列表被替换后校准拖拽跟踪：按拖拽本体在新列表中重新
         * 定位（整表替换后下标多半已指向别的候选，只按下标跟踪会
         * 在抬手时把错的条目拿去重排）；本体已不在新列表中才取消
         * 本次拖拽。由外层在替换 allCandidates 后调用。
         */
        fun resetDragIfOutOfBounds() {
            if (dragIndex < 0) return
            val item = dragItem
            val newIndex = if (item != null) {
                allCandidates.indexOfFirst { it == item }
            } else {
                -1
            }
            if (newIndex >= 0) {
                dragIndex = newIndex
                if (dragTargetIndex !in allCandidates.indices) {
                    dragTargetIndex = newIndex
                }
            } else {
                dragIndex = -1
                dragItem = null
                dragTargetIndex = -1
                stopShake()
                invalidate()
            }
        }

        /** 主动抬起的统一收尾（UP / 追踪指针的 POINTER_UP 共用）；体内坐标无关，只用速度与状态 */
        private fun handleUp(event: MotionEvent) {
                    velocityTracker?.addMovement(event)
                    velocityTracker?.computeCurrentVelocity(1000, maxFlingVelocity.toFloat())
                    val velocityY = velocityTracker?.yVelocity ?: 0f
                    recycleVelocityTracker()
                    removeCallbacks(longPressRunnable)

                    if (dragIndex >= 0 && dragIndex !in allCandidates.indices) {
                        // 拖拽期间候选列表已被替换且原下标越界：放弃本次
                        // 重排（removeAt 必崩），复位拖拽状态后按普通收尾退出
                        dragIndex = -1
                        dragItem = null
                        dragTargetIndex = -1
                        stopShake()
                        invalidate()
                        parent.requestDisallowInterceptTouchEvent(false)
                        return
                    }

                    if (dragIndex >= 0) {
                        if (dragTargetIndex in allCandidates.indices && dragTargetIndex != dragIndex) {
                            val mutable = allCandidates.toMutableList()
                            val item = mutable.removeAt(dragIndex)
                            mutable.add(dragTargetIndex, item)
                            allCandidates = mutable
                            recomputeLayout()
                            this@CandidateGridView.onCandidatesReordered?.invoke(allCandidates)
                        }
                        this@CandidateGridView.onDragComplete?.invoke(allCandidates)
                        dragIndex = -1
                        dragItem = null
                        dragTargetIndex = -1
                        stopShake()
                        invalidate()
                        parent.requestDisallowInterceptTouchEvent(false)
                        return
                    }

                    if (longPressTriggered) {
                        pressedIndex = -1
                        longPressTriggered = false
                        // 防御性复位：长按分支提前 return，不能把滚动状态留到下一个手势
                        if (dragging) {
                            dragging = false
                            springBackIfNeeded()
                        }
                        if (!longPressMoved && longPressIndex >= 0 && longPressIndex in allCandidates.indices) {
                            val candidate = allCandidates[longPressIndex]
                            // 候选菜单（审计 I 项）：皮肤定义菜单时走菜单，
                            // 菜单可自含遗忘，功能不丢；未定义时旧遗忘确认不变。
                            val menu = SkinSnapshotProvider.peek()?.candidateContextMenu.orEmpty()
                            if (menu.isNotEmpty()) {
                                this@CandidateGridView.onCandidateMenu?.invoke(candidate, menu)
                            } else {
                                val firstRow = (scrollOffsetY / rowH).toInt().coerceAtLeast(0)
                                val yOff = -(scrollOffsetY - firstRow * rowH) + stretch
                                val pos = positions[longPressIndex]
                                val rowY = (pos.row - firstRow) * rowH + yOff
                                val rightEdge = if (pos.extraWide) width.toFloat() else pos.xStart + pos.width
                                val d = resources.displayMetrics.density
                                val wordVisibleRow = pos.row - firstRow
                                val above = wordVisibleRow >= visibleRows - 2
                                this@CandidateGridView.onWordForget?.invoke(
                                    candidate,
                                    (rightEdge - 40f * d).coerceAtLeast(4f * d),
                                    if (above) rowY - 96f * d else rowY + rowH + 4f * d
                                )
                            }
                        }
                        invalidate()
                        parent.requestDisallowInterceptTouchEvent(false)
                        return
                    }

                    if (horizontalDrag >= 0) {
                        horizontalDrag = -1
                    } else if (dragging) {
                        dragging = false
                        springBackIfNeeded()
                        if (abs(velocityY) >= minFlingVelocity) fling(-velocityY.toInt())
                    } else {
                        val i = pressedIndex
                        pressedIndex = -1
                        invalidate()
                        if (i in allCandidates.indices) {
                            onCandidateSelected?.invoke(allCandidates[i])
                        }
                    }

                    parent.requestDisallowInterceptTouchEvent(false)
        }

        private fun startShake() {
            shakePhase = 0f
            val view = this
            shakeRunnable = object : Runnable {
                override fun run() {
                    shakePhase = (shakePhase + 0.18f) % (Math.PI.toFloat() * 2f)
                    invalidate()
                    view.postOnAnimation(this)
                }
            }
            postOnAnimation(shakeRunnable)
        }

        private fun stopShake() {
            shakeRunnable?.let { removeCallbacks(it) }
            shakeRunnable = null
            shakePhase = 0f
        }

        override fun onDetachedFromWindow() {
            // 本 View 自有异步资源收尾：待触发的长按投递、抖动帧循环、
            // 回弹动画在分离后都不应再驱动已脱窗的 View
            removeCallbacks(longPressRunnable)
            stopShake()
            stretchAnimator?.cancel()
            stretchAnimator = null
            // 手势进行中被摘除时 UP/CANCEL 送达不到，按压/拖拽状态会
            // 残留到重挂后串扰下一趟手势：按 ACTION_CANCEL 同款字段集
            // 复位（动画与投递已在上方收尾，这里只清手势状态）
            activePointerId = -1
            recycleVelocityTracker()
            dragIndex = -1
            dragItem = null
            dragTargetIndex = -1
            pressedIndex = -1
            horizontalDrag = -1
            dragging = false
            longPressTriggered = false
            longPressMoved = false
            super.onDetachedFromWindow()
        }

        private fun dragBy(deltaY: Float) {
            val proposed = scrollOffsetY + deltaY
            when {
                proposed < 0f -> {
                    scrollOffsetY = 0f
                    stretch = rubberBand(-proposed)
                }

                proposed > maxScroll -> {
                    scrollOffsetY = maxScroll
                    stretch = -rubberBand(proposed - maxScroll)
                }

                else -> {
                    scrollOffsetY = proposed
                    stretch = 0f
                }
            }
        }

        private fun rubberBand(d: Float): Float {
            val limit = overScrollLimit
            return limit * d / (limit + d)
        }

        fun clampScroll() {
            scrollOffsetY = scrollOffsetY.coerceIn(0f, maxScroll)
        }

        /**
         * 底部钮行整页滚动：按可见行数上下翻页，钳制到
         * [0, maxScroll]；绘制末尾统一判定补取，下翻到底
         * 时自然触发 [onNeedMoreCandidates]。
         */
        fun pageUp() {
            scrollOffsetY = (scrollOffsetY - visibleRows * rowH).coerceAtLeast(0f)
            invalidate()
        }

        fun pageDown() {
            scrollOffsetY = (scrollOffsetY + visibleRows * rowH).coerceAtMost(maxScroll)
            invalidate()
        }

        private fun springBackIfNeeded(): Boolean {
            val maxScrollInt = maxScroll.toInt()
            val currentScroll = scrollOffsetY.toInt()

            if (stretch != 0f) {
                animateStretchBack()
                return true
            }
            if (currentScroll < 0 || currentScroll > maxScrollInt) {
                scroller.springBack(0, currentScroll, 0, 0, 0, maxScrollInt)
                postInvalidateOnAnimation()
                return true
            }
            clampScroll()
            return false
        }

        private fun animateStretchBack() {
            stretchAnimator?.cancel()
            val start = stretch
            stretchAnimator = ValueAnimator.ofFloat(start, 0f).apply {
                duration = 260
                addUpdateListener {
                    stretch = it.animatedValue as Float
                    invalidate()
                }
                start()
            }
        }

        private fun fling(velocityY: Int) {
            val maxScrollInt = maxScroll.toInt()
            scroller.fling(0, scrollOffsetY.toInt(), 0, velocityY, 0, 0, 0, maxScrollInt)
            postInvalidateOnAnimation()
        }

        private fun recycleVelocityTracker() {
            velocityTracker?.recycle()
            velocityTracker = null
        }

        override fun computeScroll() {
            if (dragging) return
            if (scroller.computeScrollOffset()) {
                scrollOffsetY = scroller.currY.toFloat()
                if (scroller.isFinished) {
                    clampScroll()
                }
                invalidate()
            }
        }

        fun resetScroll() {
            if (!scroller.isFinished) scroller.abortAnimation()
            scrollOffsetY = 0f
            stretch = 0f
        }

    }

    // ── init ──

    init {
        isClickable = true
        sidePanelPunctuationItems = listOf(".", "?", "!", "@", "/", "-").map { ch ->
            KeyDef(
                appearance = KeyDef.Appearance.Text(
                    displayText = ch,
                    textSize = 15f,
                    percentWidth = 0.5f,
                    margin = false,
                    variant = KeyDef.Appearance.Variant.Alternative
                ),
                behaviors = setOf(KeyDef.Behavior.Press(KeyboardAction.CommitAction(ch))),
            )
        }
        sidePanelKey.updateItems(sidePanelPunctuationItems)

        addView(sidePanelKey, lParams(0, matchParent))
        addView(gridCanvas, lParams(0, matchParent))
        addView(bottomStrip, lParams(0, matchParent))
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val tw = MeasureSpec.getSize(widthMeasureSpec)
        val th = MeasureSpec.getSize(heightMeasureSpec)
        // 侧边栏隐藏（审计 I 项）时宽 0、网格占满宽；显示时旧口径 15%。
        val sw = if (showSidePanel) (tw * 0.15f).toInt() else 0
        val gw = tw - sw
        // 底部钮行占网格内高：画布与侧栏各让出一自然行，钮条全宽在底。
        val stripH = bottomRowHeightPx()
        val canvasH = (th - stripH).coerceAtLeast(0)
        sidePanelKey.measure(mES(sw, MeasureSpec.EXACTLY), mES(canvasH, MeasureSpec.EXACTLY))
        gridCanvas.measure(mES(gw, MeasureSpec.EXACTLY), mES(canvasH, MeasureSpec.EXACTLY))
        bottomStrip.measure(mES(tw, MeasureSpec.EXACTLY), mES(stripH, MeasureSpec.EXACTLY))
        setMeasuredDimension(tw, th)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val h = b - t
        val sw = if (showSidePanel) ((r - l) * 0.15f).toInt() else 0
        val stripH = bottomRowHeightPx()
        val canvasH = (h - stripH).coerceAtLeast(0)
        sidePanelKey.layout(0, 0, sw, canvasH)
        gridCanvas.layout(sw, 0, r - l, canvasH)
        if (stripH > 0) bottomStrip.layout(0, canvasH, r - l, h)
    }

    private fun mES(size: Int, mode: Int) = MeasureSpec.makeMeasureSpec(size, mode)

    fun refreshTheme(context: Context, type: String? = null) {
        val colors = panelColorScheme(context, type)
        refreshTheme(colors)
        val snapshot = SkinSnapshotProvider.peek()
        candidateRadiusDp = snapshot?.candidateRadiusDp ?: 6f
        // 区样式（审计 B 项）：insets 变化但尺寸不变时，onSizeChanged
        // 不跑，须显式重算画布几何（不重置滚动）。G2：类型区域集
        // 写了竖网格区样式时优先，否则回落包级。
        areaInsets = snapshot?.verticalCandidatesStyleFor(type)?.insets ?: AreaInsets()
        areaBackground = snapshot?.verticalCandidatesStyleFor(type)?.background
        cellPadDp = snapshot?.verticalCandidatesStyleFor(type)?.cellPadDp
        minWordSpaceDp = snapshot?.verticalCandidatesStyleFor(type)?.minWordSpaceDp
        minRowHeightDp = snapshot?.verticalCandidatesStyleFor(type)?.minRowHeightDp
        skinMaxRows = snapshot?.verticalMaxRows
        skinMaxColumns = snapshot?.verticalMaxColumns
        // 底部钮行：规范变化时重建钮条并重新布局（网格内高重分）；
        // 颜色变化时同样重建（钮色取当前主题色）。
        val newBottomRowSpec = snapshot?.verticalBottomRow?.takeIf { it.hasAny }
        val bottomSpecChanged = newBottomRowSpec != bottomRowSpec
        bottomRowSpec = newBottomRowSpec
        rebuildBottomStrip()
        if (bottomSpecChanged) requestLayout()
        // 行列上限立即生效（不等下批候选到达才 syncLinePolicy）。
        syncLinePolicy()
        // 侧边栏有无立即生效：可见性 + 重新布局（网格占满宽）。
        val newShowSidePanel = snapshot?.verticalSidePanel ?: true
        if (newShowSidePanel != showSidePanel) {
            showSidePanel = newShowSidePanel
            sidePanelKey.visibility = if (showSidePanel) android.view.View.VISIBLE
                else android.view.View.GONE
            requestLayout()
        }

        gridCanvas.updateColors(colors.panel)
        gridCanvas.refreshAreaGeometry()
        sidePanelKey.refreshTheme(colors)
        invalidate()
    }

    /** 收起动画进行中：此间 visibility 仍为 VISIBLE，show() 需特殊处理 */
    private var collapseAnimating = false

    fun show(list: List<EngineMessage.Candidate>, preferredEnabled: Boolean = true) {
        syncLinePolicy()
        gridCanvas.preferredEnabled = preferredEnabled
        allCandidates = list
        // 列表已替换：拖拽中整表更新时旧下标可能越界，先就地复位拖拽
        gridCanvas.resetDragIfOutOfBounds()
        needMoreRequested = false
        gridCanvas.recomputeLayout()
        gridCanvas.resetScroll()
        if (collapseAnimating) {
            // 收起动画未结束时 visibility 仍是 VISIBLE，ComponentView.show()
            // 的守卫会直接空转，而旧动画收尾还会清空刚写入的候选：
            // 先取消动画（slideUpCollapse 的 cancelled 守卫会跳过收尾清空）、
            // 复位缩放，直接置为展开态
            collapseAnimating = false
            animate().cancel()
            animate().setListener(null)
            scaleY = 1f
            bringToFront()
            visibility = View.VISIBLE
        } else {
            super.show()
        }
        // 隐藏期间积压的拼音侧栏数据在展开时才真正构建应用
        pendingPinYinData?.let { applyPossibleCandidatePinYin(it) }
    }

    fun updateCandidates(list: List<EngineMessage.Candidate>) {
        syncLinePolicy()
        allCandidates = list
        // 列表已替换：拖拽中整表更新时旧下标可能越界，先就地复位拖拽
        gridCanvas.resetDragIfOutOfBounds()
        // 列表已更新（新批次或追加页到达）：允许再次在滚到底时请求下一页
        needMoreRequested = false
        gridCanvas.recomputeLayout()
        gridCanvas.clampScroll()
        gridCanvas.invalidate()
    }

    override fun hide() {
        collapseAnimating = true
        slideUpCollapse {
            collapseAnimating = false
            allCandidates = emptyList()
            // 列表清空同样是替换：残留拖拽下标一并复位
            gridCanvas.resetDragIfOutOfBounds()
            gridCanvas.resetScroll()
        }
    }

    private var pendingPinYinData: List<CandidatePinYin>? = null

    fun onPossibleCandidatePinYin(data: List<CandidatePinYin>) {
        if (!subscribePossibleCandidatePinYin) return
        pendingPinYinData = data
        // 网格隐藏时只记数据不构建：旧实现每键都为不可见的侧栏全量重建 KeyDef
        if (visibility != View.VISIBLE) return
        applyPossibleCandidatePinYin(data)
    }

    private fun applyPossibleCandidatePinYin(data: List<CandidatePinYin>) {
        if (data.isEmpty()) {
            sidePanelKey.updateItems(sidePanelPunctuationItems)
        } else {
            sidePanelKey.updateItems(PinYinKeyDefCache.get(data))
        }
    }
}

/**
 * 拖动图在单轴上的绘制起点：以手指位置为中心、钳制在视口内。
 * 有效尺寸先钳到视口大小（min(size, viewport)），coerceIn 上限
 * viewport - effective 因此恒 >= 0、区间永不为空；尺寸不超过视口
 * 时与朴素居中钳制逐值一致。纯函数，供边界单测。
 */
internal fun dragOrigin(finger: Float, size: Float, viewport: Float): Float {
    val effective = minOf(size, viewport)
    return (finger - effective / 2f).coerceIn(0f, viewport - effective)
}

/**
 * 拼音侧栏 KeyDef 构建缓存：同一批数据会被候选网格侧栏与键盘（T9）侧栏两个
 * 消费方各请求一次，两处构建结果完全一致。按数据引用缓存一份共用，避免每键
 * 重复全量构建。仅在主线程的 UI 回调里访问，无需同步。
 */
internal object PinYinKeyDefCache {
    private var lastData: List<CandidatePinYin>? = null
    private var lastDefs: List<KeyDef> = emptyList()

    fun get(data: List<CandidatePinYin>): List<KeyDef> {
        if (data === lastData) return lastDefs
        val defs = data.map { pinYin ->
            KeyDef(
                appearance = KeyDef.Appearance.Text(
                    displayText = pinYin.pinYin,
                    textSize = 15f,
                    percentWidth = 0.5f,
                    margin = false
                ),
                behaviors = setOf(
                    KeyDef.Behavior.Press(
                        KeyboardAction.SelectCandidatePinYin(
                            pinYin = pinYin
                        )
                    )
                ),
            )
        }
        lastData = data
        lastDefs = defs
        return defs
    }
}