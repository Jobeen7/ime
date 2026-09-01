package com.ninthsoft.ime.input.panel

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import kotlin.math.max
import android.graphics.drawable.Drawable
import androidx.core.graphics.withClip
import androidx.core.graphics.withRotation
import androidx.core.graphics.withSave
import com.ninthsoft.ime.engine.data.EngineMessage

class ComposingRenderer(
    var candidates: List<EngineMessage.Candidate>,
    private val expandDrawable: Drawable?,
    var horizontalPaddingDp: Float,
    var iconScale: Float = 1f,
    var showIndex: Boolean = true,
    var showComment: Boolean = true,
    var borderless: Boolean = false,
    var expandBorderless: Boolean = false,
    override var recording: Boolean = false,
) : IRenderer {

    private fun dimColor(color: Int): Int {
        return if (recording) (color and 0x00FFFFFF) or 0x5A000000.toInt() else color
    }

    private data class PillRect(val left: Float, val right: Float, val index: Int)

    private var lastPills: List<PillRect> = emptyList()
    var maxScrollX: Float = 0f

    override fun draw(
        canvas: Canvas, width: Int, height: Int, paints: Paints,
        scrollX: Float, isExpanded: Boolean, density: Float,
    ) {
        lastPills = emptyList()
        if (width <= 0 || height <= 0 || candidates.isEmpty()) return

        val savedBg = paints.candidateBgPaint.color
        val savedText = paints.candidateTextPaint.color
        val savedIndex = paints.candidateIndexPaint.color
        if (recording) {
            paints.candidateBgPaint.color = dimColor(savedBg)
            paints.candidateTextPaint.color = dimColor(savedText)
            paints.candidateIndexPaint.color = dimColor(savedIndex)
        }

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
        val minScale = minTextSize / minOf(
            paints.candidateTextPaint.textSize,
            paints.candidateIndexPaint.textSize,
        )

        data class PillLayout(
            val rect: PillRect,
            val indexPaint: Paint,
            val textPaint: Paint,
            val indexW: Float,
            val textW: Float,
        )

        val layouts = mutableListOf<PillLayout>()
        val pills = mutableListOf<PillRect>()
        var x = sidePad
        for ((i, c) in candidates.withIndex()) {
            val indexStr = if (showIndex) "${i + 1}. " else ""
            val indexW = paints.candidateIndexPaint.measureText(indexStr)
            val textW = paints.candidateTextPaint.measureText(c.text)
            val commentStr = if (showComment && c.comment.isNotEmpty()) " ${c.comment}" else ""
            val commentW =
                if (commentStr.isNotEmpty()) paints.candidateIndexPaint.measureText(commentStr) else 0f
            val contentW = indexW + textW + commentW + pillPad * 2
            val scale = if (contentW >= maxPillW) {
                (maxPillW / contentW).coerceAtLeast(minScale)
            } else {
                1f
            }
            val indexPaint = Paint(paints.candidateIndexPaint).apply {
                textSize = paints.candidateIndexPaint.textSize * scale
            }
            val textPaint = Paint(paints.candidateTextPaint).apply {
                textSize = paints.candidateTextPaint.textSize * scale
            }
            val sIndexW = indexPaint.measureText(indexStr)
            val sTextW = textPaint.measureText(c.text)
            val sCommentW = if (commentStr.isNotEmpty()) indexPaint.measureText(commentStr) else 0f
            val pillW = sIndexW + sTextW + sCommentW + pillPad * 2
            pills.add(PillRect(x, x + pillW, c.index))
            layouts.add(
                PillLayout(
                    PillRect(x, x + pillW, c.index), indexPaint, textPaint, sIndexW, sTextW,
                )
            )
            x += pillW + gap
        }
        lastPills = pills

        maxScrollX = maxOf(0f, x - sidePad - pillsEnd)

        val dividerX = (pillsEnd + expandBtnLeft) / 2f
        val fadeW = 24f * density
        val fadeStart = (dividerX - fadeW).coerceAtLeast(0f)
        val fadePaint = Paint()

        canvas.withClip(0f, 0f, pillsEnd, height.toFloat()) {
            withSave {
                translate(scrollX, 0f)

                for ((i, c) in candidates.withIndex()) {
                    val layout = layouts[i]
                    val pill = layout.rect
                    val textY =
                        pillY + pillH / 2f - (layout.textPaint.descent() + layout.textPaint.ascent()) / 2f

                    if (!borderless) {
                        drawRoundRect(
                            pill.left, pillY, pill.right, pillY + pillH, pillR, pillR,
                            paints.candidateBgPaint,
                        )
                    }

                    val drawIndex = showIndex
                    if (drawIndex) {
                        drawText(
                            "${i + 1}. ", pill.left + pillPad, textY, layout.indexPaint,
                        )
                    }

                    val textX =
                        if (drawIndex) pill.left + pillPad + layout.indexW else pill.left + pillPad
                    drawText(c.text, textX, textY, layout.textPaint)

                    if (showComment && c.comment.isNotEmpty()) {
                        val commentX = textX + layout.textW
                        drawText(
                            " ${c.comment}",
                            commentX,
                            textY,
                            layout.indexPaint,
                        )
                    }
                }
            }

            fadePaint.shader = LinearGradient(
                fadeStart, 0f, dividerX, 0f,
                Color.TRANSPARENT, paints.bgPaint.color,
                Shader.TileMode.CLAMP,
            )
            drawRect(fadeStart, 0f, dividerX, height.toFloat(), fadePaint)
        }

        val bgGradPaint = Paint(paints.bgPaint).apply {
            shader = LinearGradient(
                0f, 0f, 0f, height.toFloat(),
                intArrayOf(paints.bgPaint.color, paints.bgPaint.color, paints.keyboardBackground),
                floatArrayOf(0f, 0.6f, 1f),
                Shader.TileMode.CLAMP,
            )
        }
        canvas.drawRect(pillsEnd, 0f, width.toFloat(), height.toFloat(), bgGradPaint)
        canvas.drawLine(
            dividerX, pillY + pillH / 4f, dividerX, pillY + pillH * 3f / 4f, paints.dividerPaint
        )
        if (!expandBorderless) {
            canvas.drawRoundRect(
                expandBtnLeft, pillY, expandBtnRight, pillY + pillH, pillR, pillR,
                paints.candidateBgPaint,
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

        if (recording) {
            paints.candidateBgPaint.color = savedBg
            paints.candidateTextPaint.color = savedText
            paints.candidateIndexPaint.color = savedIndex
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
