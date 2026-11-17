package com.jobeen.ime.input.keyboard.key

import android.graphics.Typeface
import android.text.TextPaint
import android.text.style.MetricAffectingSpan

/**
 * 承载 Typeface 对象的段跨（工具项 10）：系统 TypefaceSpan 只接受
 * 字族名，内嵌字体须以对象形式贴段，故自带此跨。绘制与度量均
 * 应用同一字体；与基础字体的样式差（粗/斜）按常规伪造补齐。
 */
class SpanTypefaceSpan(private val typeface: Typeface) : MetricAffectingSpan() {
    override fun updateDrawState(tp: TextPaint) = apply(tp)
    override fun updateMeasureState(tp: TextPaint) = apply(tp)

    private fun apply(paint: TextPaint) {
        val oldStyle = paint.typeface?.style ?: 0
        val fake = oldStyle and typeface.style.inv()
        if (fake and Typeface.BOLD != 0) paint.isFakeBoldText = true
        if (fake and Typeface.ITALIC != 0) paint.textSkewX = -0.25f
        paint.typeface = typeface
    }
}

