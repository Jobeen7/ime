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

class ComposingRenderer(
    var candidates: List<EngineMessage.Candidate>,
    private val expandDrawable: Drawable?,
    var horizontalPaddingDp: Float,
    var iconScale: Float = 1f,
    var showIndex: Boolean = true,
    var showComment: Boolean = true,
    var candidateBorder: Boolean = true,
    var expandBorder: Boolean = true,
    override var recording: Boolean = false,
) : IRenderer {

    private fun dimColor(color: Int): Int {
        return if (recording) (color and 0x00FFFFFF) or 0x5A000000.toInt() else color
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

    // 成员复用：draw() 内零分配
    private val indexPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pillBgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
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

    // 渐变缓存：只在尺寸/颜色变化时重建
    private var fadeKey: FadeKey? = null
    private data class FadeKey(val width: Int, val fadeStart: Float, val dividerX: Float, val bgColor: Int)
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
        pillBgPaint.set(paints.candidateBgPaint)
        if (recording) {
            indexPaint.color = dimColor(indexPaint.color)
            textPaint.color = dimColor(textPaint.color)
            pillBgPaint.color = dimColor(pillBgPaint.color)
        }
        val indexBaseSize = indexPaint.textSize
        val textBaseSize = textPaint.textSize

        val pillH = 34f * density
        val pillY = (height - pillH) / 2f
        val pillR = 6f * density
        val hPad = horizontalPaddingDp * density
        val sidePad = hPad + 4f * density
        val pillPad = 8f * density
        val gap = 6f * density

        val expandBtnW = 32f * density
        val expandBtnGap = 16f * density
        val expandBtnRight = width.toFloat() - sidePad
        val expandBtnLeft = expandBtnRight - expandBtnW
        val pillsEnd = expandBtnLeft - expandBtnGap
        val maxPillW = pillsEnd - sidePad

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
            val newLayouts = ArrayList<PillLayout>(candidates.size)
            val newPills = ArrayList<PillRect>(candidates.size)
            var x = sidePad
            for ((i, c) in candidates.withIndex()) {
                val indexW = if (showIndex) {
                    indexWidthCache.getOrPut(i) { indexPaint.measureText("${i + 1}. ") }
                } else {
                    0f
                }
                val textW = measureTextCached(textPaint, c.text)
                val commentStr = if (showComment && c.comment.isNotEmpty()) " ${c.comment}" else ""
                val commentW =
                    if (commentStr.isNotEmpty()) measureCommentCached(indexPaint, commentStr) else 0f
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
                    sIndexW = indexPaint.measureText(indexStr)
                    sTextW = textPaint.measureText(c.text)
                    sCommentW =
                        if (commentStr.isNotEmpty()) indexPaint.measureText(commentStr) else 0f
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
            maxScrollX = maxOf(0f, x - sidePad - pillsEnd)
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
        }

        val dividerX = (pillsEnd + expandBtnLeft) / 2f
        val fadeW = 24f * density
        val fadeStart = (dividerX - fadeW).coerceAtLeast(0f)

        canvas.withClip(0f, 0f, pillsEnd, height.toFloat()) {
            withSave {
                translate(scrollX, 0f)

                // 可见区间裁剪：只绘制可见候选（lastPills 保持全量供 hitTest）
                val visLeft = -scrollX
                val visRight = pillsEnd - scrollX
                for ((i, c) in candidates.withIndex()) {
                    val layout = layouts[i]
                    val pill = layout.rect
                    if (pill.right < visLeft || pill.left > visRight) continue

                    indexPaint.textSize = indexBaseSize * layout.scale
                    textPaint.textSize = textBaseSize * layout.scale
                    val textY =
                        pillY + pillH / 2f - (textPaint.descent() + textPaint.ascent()) / 2f

                    if (candidateBorder) {
                        drawRoundRect(
                            pill.left, pillY, pill.right, pillY + pillH, pillR, pillR,
                            pillBgPaint,
                        )
                    }

                    val drawIndex = showIndex
                    if (drawIndex) {
                        drawText(
                            indexLabel(i), pill.left + pillPad, textY, indexPaint,
                        )
                    }

                    val textX =
                        if (drawIndex) pill.left + pillPad + layout.indexW else pill.left + pillPad
                    drawText(c.text, textX, textY, textPaint)

                    if (showComment && layout.commentText.isNotEmpty()) {
                        val commentX = textX + layout.textW
                        drawText(
                            layout.commentText,
                            commentX,
                            textY,
                            indexPaint,
                        )
                    }
                }
            }

            val bgColor = paints.bgPaint.color
            val fk = FadeKey(width, fadeStart, dividerX, bgColor)
            if (fk != fadeKey) {
                fadePaint.shader = LinearGradient(
                    fadeStart, 0f, dividerX, 0f,
                    Color.TRANSPARENT, bgColor,
                    Shader.TileMode.CLAMP,
                )
                fadeKey = fk
            }
            drawRect(fadeStart, 0f, dividerX, height.toFloat(), fadePaint)
        }

        val bgColor = paints.bgPaint.color
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
        canvas.drawRect(pillsEnd, 0f, width.toFloat(), height.toFloat(), bgGradPaint)
        canvas.drawLine(
            dividerX, pillY + pillH / 4f, dividerX, pillY + pillH * 3f / 4f, paints.dividerPaint
        )
        if (expandBorder) {
            canvas.drawRoundRect(
                expandBtnLeft, pillY, expandBtnRight, pillY + pillH, pillR, pillR,
                pillBgPaint,
            )
        }
        val cx = expandBtnLeft + expandBtnW / 2f
        val cy = pillY + pillH / 2f
        val d = expandDrawable
        if (d != null) {
            d.setTint(paints.toolbarIconColor)
            val iw = d.intrinsicWidth.toFloat() * iconScale
            val ih = d.intrinsicHeight.toFloat() * iconScale
            d.setBounds(
                (cx - iw / 2f).toInt(), (cy - ih / 2f).toInt(),
                (cx + iw / 2f).toInt(), (cy + ih / 2f).toInt(),
            )
            if (isExpanded) {
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
        val pillH = 34f * density
        val pillY = (height - pillH) / 2f
        if (y < pillY || y > pillY + pillH) return null

        val expandRightMargin = horizontalPaddingDp * density + 4f * density
        val expandBtnW = 32f * density
        val expandBtnLeft = width - expandRightMargin - expandBtnW
        val expandBtnRight = width - expandRightMargin
        if (x in expandBtnLeft..expandBtnRight) {
            return if (isExpanded) KawaiiPanel.TouchResult.CollapseCandidates
            else KawaiiPanel.TouchResult.ExpandCandidates
        }

        if (lastPills.isEmpty()) return null
        val adjustedX = x - scrollX
        for (pill in lastPills) {
            if (adjustedX >= pill.left && adjustedX <= pill.right) {
                val c = candidates.find { it.index == pill.index } ?: return null
                return KawaiiPanel.TouchResult.SelectCandidate(c)
            }
        }
        return null
    }
}
