package com.jobeen.ime.input.keyboard.key

import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextPaint
import android.text.style.MetricAffectingSpan
import android.widget.TextView
import com.jobeen.ime.data.keyboard.theme.GlobalFonts
import com.jobeen.ime.skin.model.SkinFontRanges
import com.jobeen.ime.skin.runtime.SkinSnapshotProvider

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

/**
 * 键标签分段字体挂钩（工具项 10）：对 TextView 当前文本按快照
 * fontRanges 逐段换字体（分段引擎见 SkinFontRanges.spansFor，
 * 段字族解析见 GlobalFonts.typefaceForFamily）。
 *
 * 无皮肤、无区间、全段无 family 时不动文本；family 解析不出
 * Typeface 的段保持基础字体。调用方须在图标字形贴回之前调用：
 * 图标键会整体替换文本，段跨随旧文本丢弃，不会污染字形。
 * 仅挂主标签；alt/top 小字仍走单字体（见 updateAltText 等）。
 */
fun applyKeyFontSpans(textView: TextView, style: Int) {
    val snapshot = SkinSnapshotProvider.peek() ?: return
    val ranges = snapshot.fontRanges
    if (ranges.isEmpty()) return
    val plain = textView.text?.toString() ?: return
    if (plain.isEmpty()) return
    val spans = SkinFontRanges.spansFor(plain, ranges)
    if (spans.none { it.family != null }) return
    val builder = SpannableStringBuilder(plain)
    for (span in spans) {
        val family = span.family ?: continue
        val typeface = GlobalFonts.typefaceForFamily(textView.context, family, style)
            ?: continue
        builder.setSpan(
            SpanTypefaceSpan(typeface), span.start, span.end,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
        )
    }
    textView.text = builder
}
