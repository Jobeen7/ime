package com.jobeen.ime.input.panel

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.drawable.Drawable
import androidx.core.graphics.withClip
import androidx.core.graphics.withRotation
import androidx.core.graphics.withSave
import com.jobeen.ime.engine.data.EngineMessage
import com.jobeen.ime.skin.model.CandidateAreaStyleSpec

class ComposingRenderer(
    var candidates: List<EngineMessage.Candidate>,
    private val expandDrawable: Drawable?,
    var horizontalPaddingDp: Float,
    var iconScale: Float = 1f,
    var showIndex: Boolean = true,
    var showComment: Boolean = true,
    var candidateBorder: Boolean = true,
    var expandBorder: Boolean = true,
    /** 候选字背景圆角（2026-10-28 截图 6 项）：默认 6dp，与旧写死值一致。 */
    var candidateRadiusDp: Float = 6f,
    /**
     * 预测态右缘关闭键独立样式（2026-10-28 截图 6 项）：
     * null = 复用候选字背景（颜色与圆角各跟各的默认值）；
     * 非 null 时仅该按钮用独立背景色/圆角，组字态展开键不受影响
     * （组字态构造时不传这两个参数）。
     */
    var edgeBgColor: Int? = null,
    var edgeRadiusDp: Float? = null,
    /**
     * 横条候选区样式（审计 B 项）：构造期经快照传入；与
     * candidateRadiusDp 同口径——皮肤切换后下次进组字态生效。
     */
    var areaStyle: CandidateAreaStyleSpec? = null,
    override var recording: Boolean = false,
    /**
     * 横条候选上限（审计 I 项）：构造期经快照传入；null = 不限。
     * 布局与绘制皆按截断后的列表走，命中跟随 lastPills 自动
     * 受限；高亮下标 ≥ 上限时该候选不可见（旧口径：恒可见）。
     */
    var maxColumns: Int? = null,
    /**
     * 横条展开钮在左（审计 I 项）：构造期经快照传入；false = 右
     * （默认）。绘制（钮区／药丸区／渐隐／分隔线）与命中同步镜像。
     */
    var expandLeft: Boolean = false,
    /**
     * 预测态边缘钮点按即关闭（K-predict）：仅预测态渲染器置 true；
     * 组字态默认 false（旧展开／收起口径不变）。
     */
    var edgeTapClosesPredictions: Boolean = false,
    /**
     * 首选色生效范围（首选色只属于 RIME 候选首位）：组字态
     * true；预测／联想态必须传 false——预测首项不是 RIME
     * 首选候选，不得染首选色（此前共用渲染器致预测首项误染）。
     */
    var preferredEnabled: Boolean = true,
    /**
     * 取消图标（H2-2 候选栏取消按钮）：预测态边缘钮点按即
     * 关闭联想，画 × 而非展开 chevron；null 时回落展开图
     * （旧口径：行为是关闭、样子仍是展开，属无可见取消按钮）。
     * 组字态不传，边缘钮仍是展开／收起。
     */
    var cancelDrawable: Drawable? = null,
) : IRenderer {

    /** 边缘钮图标种：预测态为取消（×），组字态为展开（chevron）。 */
    enum class EdgeButtonIcon { EXPAND, CANCEL }

    /** 边缘钮图标抉择（纯函数，供单测）：关预测标志开即取消。 */
    fun edgeButtonIconKind(): EdgeButtonIcon =
        if (edgeTapClosesPredictions) EdgeButtonIcon.CANCEL else EdgeButtonIcon.EXPAND

    private fun dimColor(color: Int): Int {
        return if (recording) (color and 0x00FFFFFF) or 0x5A000000.toInt() else color
    }

    /** 区样式生效几何（审计 B 项）：左右内边距叠加到条侧边距上，上下压缩药丸高度（下限 12dp）。 */
    private fun effLeftPadPx(density: Float): Float =
        horizontalPaddingDp * density + 4f * density + (areaStyle?.insets?.left ?: 0f) * density

    private fun effRightPadPx(density: Float): Float =
        horizontalPaddingDp * density + 4f * density + (areaStyle?.insets?.right ?: 0f) * density

    private fun effPillHPx(density: Float): Float {
        val insets = areaStyle?.insets ?: return 34f * density
        return (34f - insets.top - insets.bottom).coerceAtLeast(12f) * density
    }

    /** 序号标签预生成（绘制期每帧拼 "${i+1}. " 是纯浪费） */
    private fun indexLabel(i: Int): String =
        if (i < INDEX_LABELS.size) INDEX_LABELS[i] else "${i + 1}. "

    companion object {
        private val INDEX_LABELS = Array(99) { "${it + 1}. " }
    }

    private data class PillRect(val left: Float, val right: Float, val index: Int)

    /** 布局结果：只存几何与缩放，绘制时复用成员 Paint */
    private data class PillLayout(
        val rect: PillRect,
        val scale: Float,
        val indexW: Float,
        val textW: Float,
        /** 预拼好的注释绘制串（" 注释"）：布局期拼一次，绘制期不再每帧拼串 */
        val commentText: String = "",
    )

    private var lastPills: List<PillRect> = emptyList()
    var maxScrollX: Float = 0f
    /** Rime 高亮候选在 candidates 中的下标（默认首个）；-1 = 无可见高亮。 */
    var highlightedIndex: Int = 0

    // 成员复用：draw() 内零分配
    private val indexPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val selectedTextPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val commentPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pillBgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pressedBgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val preferredBgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val preferredTextPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val preferredIndexPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val preferredCommentPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    /**
     * 当前按压的候选下标（审计 I 项）：视图 DOWN 命中后置位，
     * 抬手／取消／滑动／分离时清零；-1 = 无按压。仅绘制用，
     * 不参与命中与分发。
     */
    var pressedIndex: Int = -1
    private val edgeBgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fadePaint = Paint()
    private val bgGradPaint = Paint()

    // 单候选测宽缓存（内容级）：整表引用判等对引擎每键产出的新列表永远
    // 未命中，此前每键约 24 候选 × 3 次 measureText 全量重测。改为按
    // （文本+测宽所用字号）缓存单项宽度，相邻按键间重复出现的候选直接
    // 查表；环境（字号/密度等）变化时整表作废。LinkedHashMap 做 LRU 上限。
    private val textWidthCache = object : LinkedHashMap<String, Float>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Float>?) =
            size > 512
    }
    private val commentWidthCache = object : LinkedHashMap<String, Float>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Float>?) =
            size > 512
    }
    // 序号串只有 "1. "…"N. " 有限种，按下标缓存（随 showIndex/字号失效）
    private val indexWidthCache = HashMap<Int, Float>()

    private fun measureTextCached(paint: Paint, text: String): Float =
        textWidthCache.getOrPut(text) { paint.measureText(text) }

    private fun measureCommentCached(paint: Paint, text: String): Float =
        commentWidthCache.getOrPut(text) { paint.measureText(text) }

    // 布局缓存：candidates 引用/尺寸/字号/开关任一变化才重算
    private var cachedLayouts: List<PillLayout> = emptyList()
    private var cachedPills: List<PillRect> = emptyList()
    private var cachedMaxScrollX: Float = 0f
    private var cachedCandidates: List<EngineMessage.Candidate>? = null
    private var cachedWidth: Int = -1
    private var cachedDensity: Float = -1f
    private var cachedHPad: Float = Float.NaN
    private var cachedIndexTextSize: Float = -1f
    private var cachedTextTextSize: Float = -1f
    private var cachedShowIndex: Boolean = false
    private var cachedShowComment: Boolean = false
    private var cachedAreaStyle: CandidateAreaStyleSpec? = null
    private var cachedMaxColumns: Int? = null

    // 渐变缓存：只在尺寸/颜色变化时重建
    private var fadeKey: FadeKey? = null
    private data class FadeKey(val width: Int, val fadeStart: Float, val dividerX: Float, val bgColor: Int, val expandLeft: Boolean)
    private var bgGradKey: BgGradKey? = null
    private data class BgGradKey(val height: Int, val bgColor: Int, val keyboardBg: Int)

    override fun draw(
        canvas: Canvas, width: Int, height: Int, paints: Paints,
        scrollX: Float, isExpanded: Boolean, density: Float,
    ) {
        lastPills = emptyList()
        if (width <= 0 || height <= 0 || candidates.isEmpty()) return

        // 每帧从 paints 同步属性到复用 paint（recording 变暗直接作用于复用 paint，不再改 paints）
        indexPaint.set(paints.candidateIndexPaint)
        textPaint.set(paints.candidateTextPaint)
        selectedTextPaint.set(paints.selectedCandidateTextPaint)
        commentPaint.set(paints.candidateCommentPaint)
        pillBgPaint.set(paints.candidateBgPaint)
        pressedBgPaint.set(paints.candidateBgPaint)
        pressedBgPaint.color = paints.candidatePressedColor ?: pillBgPaint.color
        // 首选色（审计 I 项）：有值才启用（null = 关闭），画笔先取
        // 常态色打底、再覆首选色；启用标志供绘制分支判断。
        preferredBgPaint.set(paints.candidateBgPaint)
        paints.preferredBackground?.let { preferredBgPaint.color = it }
        preferredTextPaint.set(paints.candidateTextPaint)
        paints.preferredText?.let { preferredTextPaint.color = it }
        preferredIndexPaint.set(paints.candidateIndexPaint)
        paints.preferredIndex?.let { preferredIndexPaint.color = it }
        preferredCommentPaint.set(paints.candidateCommentPaint)
        paints.preferredComment?.let { preferredCommentPaint.color = it }
        if (recording) {
            indexPaint.color = dimColor(indexPaint.color)
            textPaint.color = dimColor(textPaint.color)
            selectedTextPaint.color = dimColor(selectedTextPaint.color)
            commentPaint.color = dimColor(commentPaint.color)
            pillBgPaint.color = dimColor(pillBgPaint.color)
            pressedBgPaint.color = dimColor(pressedBgPaint.color)
            preferredBgPaint.color = dimColor(preferredBgPaint.color)
            preferredTextPaint.color = dimColor(preferredTextPaint.color)
            preferredIndexPaint.color = dimColor(preferredIndexPaint.color)
            preferredCommentPaint.color = dimColor(preferredCommentPaint.color)
        }
        val indexBaseSize = indexPaint.textSize
        val textBaseSize = textPaint.textSize

        val pillH = effPillHPx(density)
        val pillY = (height - pillH) / 2f
        val pillR = candidateRadiusDp * density
        val leftPad = effLeftPadPx(density)
        val rightPad = effRightPadPx(density)
        // 候选项内边距（审计 I 项）：横条样式节点未定义时走旧写死值。
        val pillPad = (areaStyle?.pillPadDp ?: 8f) * density
        val gap = (areaStyle?.pillGapDp ?: 6f) * density

        val expandBtnW = 32f * density
        val expandBtnGap = 16f * density
        // 横条布局镜像（审计 I 项）：左钮时钮区在左、药丸区在右，
        // 其余几何（药丸起点、右边界、滚动公式）同形平移。
        val expandBtnLeft: Float
        val expandBtnRight: Float
        val pillsStart: Float
        val pillsEnd: Float
        if (expandLeft) {
            expandBtnLeft = leftPad
            expandBtnRight = leftPad + expandBtnW
            pillsStart = expandBtnRight + expandBtnGap
            pillsEnd = width.toFloat() - rightPad
        } else {
            expandBtnRight = width.toFloat() - rightPad
            expandBtnLeft = expandBtnRight - expandBtnW
            pillsEnd = expandBtnLeft - expandBtnGap
            pillsStart = leftPad
        }
        val maxPillW = pillsEnd - pillsStart

        val minTextSize = 12f * density
        val minScale = minTextSize / minOf(textBaseSize, indexBaseSize)

        // ---- 布局（命中缓存则跳过 measure） ----
        val layoutValid = candidates === cachedCandidates
            && width == cachedWidth
            && density == cachedDensity
            && horizontalPaddingDp == cachedHPad
            && indexBaseSize == cachedIndexTextSize
            && textBaseSize == cachedTextTextSize
            && showIndex == cachedShowIndex
            && showComment == cachedShowComment
            && areaStyle === cachedAreaStyle
            && maxColumns == cachedMaxColumns
        // 横条上限（审计 I 项）：布局与绘制共用截断列表；截断后
        // 位置编号（1. 2. …）与引擎下标皆与原列表前 N 项一致。
        val layoutCandidates = maxColumns?.let { candidates.take(it) } ?: candidates
        val layouts: List<PillLayout>
        if (layoutValid) {
            layouts = cachedLayouts
            lastPills = cachedPills
            maxScrollX = cachedMaxScrollX
        } else {
            // 环境（字号/密度/内边距/开关）任一变化，单项测宽缓存整体作废
            if (width != cachedWidth || density != cachedDensity
                || horizontalPaddingDp != cachedHPad
                || indexBaseSize != cachedIndexTextSize || textBaseSize != cachedTextTextSize
                || showIndex != cachedShowIndex || showComment != cachedShowComment
            ) {
                textWidthCache.clear()
                commentWidthCache.clear()
                indexWidthCache.clear()
            }
            val newLayouts = ArrayList<PillLayout>(layoutCandidates.size)
            val newPills = ArrayList<PillRect>(layoutCandidates.size)
            var x = pillsStart
            for ((i, c) in layoutCandidates.withIndex()) {
                val indexW = if (showIndex) {
                    indexWidthCache.getOrPut(i) { indexPaint.measureText("${i + 1}. ") }
                } else {
                    0f
                }
                val textW = measureTextCached(textPaint, c.text)
                val commentStr = if (showComment && c.comment.isNotEmpty()) " ${c.comment}" else ""
                val commentW =
                    if (commentStr.isNotEmpty()) measureCommentCached(commentPaint, commentStr) else 0f
                val contentW = indexW + textW + commentW + pillPad * 2
                val scale = if (contentW >= maxPillW) {
                    (maxPillW / contentW).coerceAtLeast(minScale)
                } else {
                    1f
                }
                // scale==1 是常态：缩放后宽度与未缩放完全相同，第二遍三串测宽
                // 纯浪费，直接复用第一遍结果（每候选省 3 次 measureText）
                val sIndexW: Float
                val sTextW: Float
                val sCommentW: Float
                if (scale == 1f) {
                    sIndexW = indexW
                    sTextW = textW
                    sCommentW = commentW
                } else {
                    indexPaint.textSize = indexBaseSize * scale
                    textPaint.textSize = textBaseSize * scale
                    sIndexW = if (showIndex) indexPaint.measureText("${i + 1}. ") else 0f
                    sTextW = textPaint.measureText(c.text)
                    sCommentW =
                        if (commentStr.isNotEmpty()) commentPaint.measureText(commentStr) else 0f
                    indexPaint.textSize = indexBaseSize
                    textPaint.textSize = textBaseSize
                }
                val pillW = sIndexW + sTextW + sCommentW + pillPad * 2
                val rect = PillRect(x, x + pillW, c.index)
                newPills.add(rect)
                newLayouts.add(PillLayout(rect, scale, sIndexW, sTextW, commentStr))
                x += pillW + gap
            }
            layouts = newLayouts
            lastPills = newPills
            maxScrollX = maxOf(0f, x - pillsStart - pillsEnd)
            // 写缓存
            cachedLayouts = newLayouts
            cachedPills = newPills
            cachedMaxScrollX = maxScrollX
            cachedCandidates = candidates
            cachedWidth = width
            cachedDensity = density
            cachedHPad = horizontalPaddingDp
            cachedIndexTextSize = indexBaseSize
            cachedTextTextSize = textBaseSize
            cachedShowIndex = showIndex
            cachedShowComment = showComment
            cachedAreaStyle = areaStyle
            cachedMaxColumns = maxColumns
        }

        // 分隔线与渐隐镜像（审计 I 项）：左钮时分隔线在钮区与
        // 药丸区之间、渐隐由钮侧向药丸侧淡出；右钮为旧口径。
        val dividerX = if (expandLeft) (expandBtnRight + pillsStart) / 2f
            else (pillsEnd + expandBtnLeft) / 2f
        val fadeW = 24f * density
        val fadeStart = (dividerX - fadeW).coerceAtLeast(0f)
        val fadeEnd = dividerX + fadeW
        val clipLeft = if (expandLeft) pillsStart else 0f
        val clipRight = if (expandLeft) width.toFloat() else pillsEnd

        canvas.withClip(clipLeft, 0f, clipRight, height.toFloat()) {
            withSave {
                translate(scrollX, 0f)

                // 可见区间裁剪：只绘制可见候选（lastPills 保持全量供 hitTest）
                val visLeft = clipLeft - scrollX
                val visRight = clipRight - scrollX
                for ((i, c) in layoutCandidates.withIndex()) {
                    val layout = layouts[i]
                    val pill = layout.rect
                    if (pill.right < visLeft || pill.left > visRight) continue

                    indexPaint.textSize = indexBaseSize * layout.scale
                    textPaint.textSize = textBaseSize * layout.scale
                    selectedTextPaint.textSize = textBaseSize * layout.scale
                    val textY =
                        pillY + pillH / 2f - (textPaint.descent() + textPaint.ascent()) / 2f
                    // 高亮候选（Rime 选中项）用选中文字色；其次首选
                    // 文字色（仅 i==0 且引擎选中不在首位时可见，
                    // 选中态优先于首选是既有“选中态文字色”口径），
                    // 其余用候选文字色。
                    val pillTextPaint =
                        if (i == highlightedIndex) selectedTextPaint
                        else if (preferredEnabled && i == 0 && paints.preferredText != null) preferredTextPaint
                        else textPaint
                    // 序号／注释同理：首选色只在首位、且非按压时生效；
                    // 预测态 preferredEnabled=false 时一律不染首选色。
                    val idxPaint =
                        if (preferredEnabled && i == 0 && paints.preferredIndex != null) preferredIndexPaint else indexPaint
                    val cmtPaint =
                        if (preferredEnabled && i == 0 && paints.preferredComment != null) preferredCommentPaint else commentPaint

                    // 按压反馈（审计 I 项）：按压态药丸铺按压色（缺失
                    // 回落候选底），与边框模式无关；首选底在首位、
                    // 非按压时铺（与边框模式无关）；常态仍只在边框
                    // 模式下画底（旧口径不变）。
                    if (i == pressedIndex) {
                        drawRoundRect(
                            pill.left, pillY, pill.right, pillY + pillH, pillR, pillR,
                            pressedBgPaint,
                        )
                    } else if (preferredEnabled && i == 0 && paints.preferredBackground != null) {
                        drawRoundRect(
                            pill.left, pillY, pill.right, pillY + pillH, pillR, pillR,
                            preferredBgPaint,
                        )
                    } else if (candidateBorder) {
                        drawRoundRect(
                            pill.left, pillY, pill.right, pillY + pillH, pillR, pillR,
                            pillBgPaint,
                        )
                    }

                    val drawIndex = showIndex
                    if (drawIndex) {
                        drawText(
                            indexLabel(i), pill.left + pillPad, textY, idxPaint,
                        )
                    }

                    val textX =
                        if (drawIndex) pill.left + pillPad + layout.indexW else pill.left + pillPad
                    drawText(c.text, textX, textY, pillTextPaint)

                    if (showComment && layout.commentText.isNotEmpty()) {
                        val commentX = textX + layout.textW
                        drawText(
                            layout.commentText,
                            commentX,
                            textY,
                            cmtPaint,
                        )
                    }
                }
            }

            val bgColor = paints.candidateStripBg ?: paints.bgPaint.color
            val fk = FadeKey(width, fadeStart, dividerX, bgColor, expandLeft)
            if (fk != fadeKey) {
                fadePaint.shader = if (expandLeft) {
                    LinearGradient(
                        dividerX, 0f, fadeEnd, 0f,
                        bgColor, Color.TRANSPARENT,
                        Shader.TileMode.CLAMP,
                    )
                } else {
                    LinearGradient(
                        fadeStart, 0f, dividerX, 0f,
                        Color.TRANSPARENT, bgColor,
                        Shader.TileMode.CLAMP,
                    )
                }
                fadeKey = fk
            }
            if (expandLeft) {
                drawRect(dividerX, 0f, fadeEnd, height.toFloat(), fadePaint)
            } else {
                drawRect(fadeStart, 0f, dividerX, height.toFloat(), fadePaint)
            }
        }

        val bgColor = paints.candidateStripBg ?: paints.bgPaint.color
        val bgk = BgGradKey(height, bgColor, paints.keyboardBackground)
        if (bgk != bgGradKey) {
            bgGradPaint.set(paints.bgPaint)
            bgGradPaint.shader = LinearGradient(
                0f, 0f, 0f, height.toFloat(),
                intArrayOf(bgColor, bgColor, paints.keyboardBackground),
                floatArrayOf(0f, 0.6f, 1f),
                Shader.TileMode.CLAMP,
            )
            bgGradKey = bgk
        }
        // 钮区底渐变：左钮时钮区在左（[0, pillsStart]），右钮为旧口径。
        if (expandLeft) {
            canvas.drawRect(0f, 0f, pillsStart, height.toFloat(), bgGradPaint)
        } else {
            canvas.drawRect(pillsEnd, 0f, width.toFloat(), height.toFloat(), bgGradPaint)
        }
        // S10：分隔线可见开关（默认可见）。
        if (paints.candidateDividerVisible) {
            canvas.drawLine(
                dividerX, pillY + pillH / 4f, dividerX, pillY + pillH * 3f / 4f, paints.dividerPaint
            )
        }
        if (expandBorder) {
            val edgeColor = edgeBgColor
            if (edgeColor != null) {
                edgeBgPaint.set(pillBgPaint)
                edgeBgPaint.color = dimColor(edgeColor)
                val edgeR = (edgeRadiusDp ?: candidateRadiusDp) * density
                canvas.drawRoundRect(
                    expandBtnLeft, pillY, expandBtnRight, pillY + pillH, edgeR, edgeR,
                    edgeBgPaint,
                )
            } else {
                canvas.drawRoundRect(
                    expandBtnLeft, pillY, expandBtnRight, pillY + pillH, pillR, pillR,
                    pillBgPaint,
                )
            }
        }
        val cx = expandBtnLeft + expandBtnW / 2f
        val cy = pillY + pillH / 2f
        // 取消态画 ×（缺图回落展开图）；取消图不随展开态旋转。
        val isCancel = edgeButtonIconKind() == EdgeButtonIcon.CANCEL
        val d = if (isCancel) cancelDrawable ?: expandDrawable else expandDrawable
        if (d != null) {
            d.setTint(paints.toolbarIconColor)
            val iw = d.intrinsicWidth.toFloat() * iconScale
            val ih = d.intrinsicHeight.toFloat() * iconScale
            d.setBounds(
                (cx - iw / 2f).toInt(), (cy - ih / 2f).toInt(),
                (cx + iw / 2f).toInt(), (cy + ih / 2f).toInt(),
            )
            if (isExpanded && !isCancel) {
                canvas.withRotation(180f, cx, cy) {
                    d.draw(this)
                }
            } else {
                d.draw(canvas)
            }
        }
    }

    override fun hitTest(
        x: Float, y: Float, width: Int, height: Int,
        scrollX: Float, isExpanded: Boolean, density: Float,
    ): KawaiiPanel.TouchResult? {
        if (candidates.isEmpty()) return null
        val pillH = effPillHPx(density)
        val pillY = (height - pillH) / 2f
        if (y < pillY || y > pillY + pillH) return null

        val expandBtnW = 32f * density
        val expandBtnLeft: Float
        val expandBtnRight: Float
        if (expandLeft) {
            expandBtnLeft = effLeftPadPx(density)
            expandBtnRight = expandBtnLeft + expandBtnW
        } else {
            expandBtnRight = width - effRightPadPx(density)
            expandBtnLeft = expandBtnRight - expandBtnW
        }
        if (x in expandBtnLeft..expandBtnRight) {
            // K-predict：预测态边缘钮点按即关闭联想（不再展开／收起）。
            if (edgeTapClosesPredictions) return KawaiiPanel.TouchResult.ClosePredictions
            return if (isExpanded) KawaiiPanel.TouchResult.CollapseCandidates
            else KawaiiPanel.TouchResult.ExpandCandidates
        }

        if (lastPills.isEmpty()) return null
        val adjustedX = x - scrollX
        for (pill in lastPills) {
            if (adjustedX >= pill.left && adjustedX <= pill.right) {
                val c = candidates.find { it.index == pill.index } ?: return null
                return KawaiiPanel.TouchResult.SelectCandidate(c, pill.index)
            }
        }
        return null
    }
}
