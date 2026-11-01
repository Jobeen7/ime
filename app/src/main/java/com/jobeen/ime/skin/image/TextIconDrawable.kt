package com.jobeen.ime.skin.image

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.Drawable

/**
 * 文字图标：以系统字体在边界内绘制 1～多行文字（对端 cnen/encn 的等价形式，
 * 原图不进包）。每行字号按边界高度比例取，各行整体垂直居中、水平居中。
 * 无固定内在尺寸（调用方定尺寸）；渲染本身走真机验收。
 */
class TextIconDrawable(
    /** 各行（文本＋颜色＋字号占比）；公开只读，供单测核对等价内容。 */
    val lines: List<Line>,
) : Drawable() {

    data class Line(
        val text: String,
        val color: Int,
        /** 字号占边界高度的比例。 */
        val sizeFraction: Float,
        val typeface: Typeface = Typeface.DEFAULT,
    )

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.isEmpty || lines.isEmpty()) return
        val metrics = lines.map { line ->
            paint.textSize = b.height() * line.sizeFraction
            paint.typeface = line.typeface
            paint.fontMetrics
        }
        val totalHeight = metrics.fold(0f) { acc, fm -> acc + (fm.descent - fm.ascent) }
        var top = b.centerY() - totalHeight / 2f
        for ((index, line) in lines.withIndex()) {
            val fm = metrics[index]
            paint.textSize = b.height() * line.sizeFraction
            paint.typeface = line.typeface
            paint.color = line.color
            canvas.drawText(line.text, b.centerX().toFloat(), top - fm.ascent, paint)
            top += fm.descent - fm.ascent
        }
    }

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
        invalidateSelf()
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    companion object {
        /** 对端 cnen 等价：黑字双行“中／EN”（原图 72×72 字形层，底为键背景）。 */
        fun cnen(): TextIconDrawable = TextIconDrawable(
            listOf(
                Line("中", Color.BLACK, 0.50f),
                Line("EN", Color.BLACK, 0.28f),
            ),
        )

        /** 对端 encn 等价：绿字“英”（原图主色 #0FBC7A）。 */
        fun encn(): TextIconDrawable = TextIconDrawable(
            listOf(
                Line("英", Color.parseColor("#0FBC7A"), 0.66f),
            ),
        )
    }
}
