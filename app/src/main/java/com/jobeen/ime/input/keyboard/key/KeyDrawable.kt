package com.jobeen.ime.input.keyboard.key

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.LayerDrawable
import androidx.annotation.ColorInt

fun radiusDrawable(
    r: Float,
    @ColorInt color: Int = Color.WHITE,
): Drawable = GradientDrawable().apply {
    setColor(color)
    cornerRadius = r
}

fun insetRadiusDrawable(
    hInset: Int,
    vInset: Int,
    r: Float = 0f,
    @ColorInt color: Int = Color.WHITE,
): Drawable = InsetDrawable(
    radiusDrawable(r, color),
    hInset, vInset, hInset, vInset,
)

fun insetOvalDrawable(
    hInset: Int,
    vInset: Int,
    @ColorInt color: Int = Color.WHITE,
): Drawable = InsetDrawable(
    GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
    },
    hInset, vInset, hInset, vInset,
)

fun shadowedKeyBackgroundDrawable(
    @ColorInt bkgColor: Int,
    @ColorInt shadowColor: Int,
    radius: Float,
    shadowWidth: Int,
    hMargin: Int,
    vMargin: Int,
): Drawable = LayerDrawable(
    arrayOf(
        radiusDrawable(radius, shadowColor),
        radiusDrawable(radius, bkgColor),
    ),
).apply {
    setLayerInset(0, hMargin, vMargin, hMargin, vMargin - shadowWidth)
    setLayerInset(1, hMargin, vMargin, hMargin, vMargin)
}

fun flatKeyBackgroundDrawable(
    @ColorInt bkgColor: Int,
    @ColorInt strokeColor: Int,
    radius: Float,
    strokeWidth: Int,
    hMargin: Int,
    vMargin: Int,
): Drawable = LayerDrawable(
    arrayOf(
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radius
            setColor(bkgColor)
            setStroke(strokeWidth, strokeColor)
        },
    ),
).apply {
    setLayerInset(0, hMargin, vMargin, hMargin, vMargin)
}

fun borderedKeyBackgroundDrawable(
    @ColorInt bkgColor: Int,
    @ColorInt strokeColor: Int,
    radius: Float,
    strokeWidth: Int,
    hMargin: Int,
    vMargin: Int,
): Drawable = LayerDrawable(
    arrayOf(
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radius
            setColor(bkgColor)
            setStroke(strokeWidth, strokeColor)
        },
    ),
).apply {
    setLayerInset(0, hMargin, vMargin, hMargin, vMargin)
}

/**
 * 拟态双阴影键面（2026-10-29 item-1）：左上亮影／右下暗影／主形
 * 三层。两影层为半透明圆角矩形相对主形反向偏移 [offsetPx]；
 * 柔和来自半透明色＋小偏移，不做逐像素模糊（每键仅多两个
 * GradientDrawable，零位图分配、零热路径成本；偏移超键间距
 * 时内缩钳制为 0，影层贴边、绝不崩）。
 */
fun dualShadowKeyBackgroundDrawable(
    @ColorInt bkgColor: Int,
    @ColorInt strokeColor: Int,
    radius: Float,
    strokeWidth: Int,
    hMargin: Int,
    vMargin: Int,
    @ColorInt lightColor: Int,
    @ColorInt darkColor: Int,
    offsetPx: Int,
): Drawable {
    val o = offsetPx.coerceAtLeast(0)
    val main = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = radius
        setColor(bkgColor)
        setStroke(strokeWidth, strokeColor)
    }
    val light = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = radius
        setColor(lightColor)
    }
    val dark = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = radius
        setColor(darkColor)
    }
    return LayerDrawable(arrayOf(light, dark, main)).apply {
        // 左上亮：左/上内缩减小、右/下内缩增大 → 整体向左上偏移。
        setLayerInset(
            0,
            (hMargin - o).coerceAtLeast(0), (vMargin - o).coerceAtLeast(0),
            hMargin + o, vMargin + o,
        )
        // 右下暗：反向偏移。
        setLayerInset(
            1,
            hMargin + o, vMargin + o,
            (hMargin - o).coerceAtLeast(0), (vMargin - o).coerceAtLeast(0),
        )
        setLayerInset(2, hMargin, vMargin, hMargin, vMargin)
    }
}

fun highlightMaskDrawable(
    @ColorInt color: Int,
    bordered: Boolean,
    hMargin: Int,
    vMargin: Int,
    radius: Float,
): Drawable = if (bordered) {
    insetRadiusDrawable(hMargin, vMargin, radius, color)
} else {
    InsetDrawable(ColorDrawable(color), hMargin, vMargin, hMargin, vMargin)
}
