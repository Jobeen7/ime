package com.jobeen.ime.input.panel.toolbar

import android.graphics.Canvas
import android.graphics.drawable.Drawable
import androidx.core.graphics.withRotation
import com.jobeen.ime.input.panel.Paints
import androidx.core.graphics.withScale
import com.jobeen.ime.input.panel.PanelAction

sealed class ToolbarButton {
    abstract val action: PanelAction
    abstract fun draw(canvas: Canvas, cx: Float, cy: Float, paints: Paints, density: Float)

    /**
     * 工具栏按钮底（2026-10-29 外观轮全局样式）：全局值未设时
     * 不画（现行透明行为）；设了则在内容外扩 7dp 处画圆角底＋边框。
     */
    protected fun drawButtonBackground(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        halfW: Float,
        halfH: Float,
        paints: Paints,
        density: Float,
    ) {
        val bg = paints.toolbarButtonBg ?: return
        val pad = 7f * density
        val left = cx - halfW - pad
        val top = cy - halfH - pad
        val right = cx + halfW + pad
        val bottom = cy + halfH + pad
        val radius = paints.toolbarButtonRadiusDp * density
        paints.toolbarButtonBgPaint.color = bg
        canvas.drawRoundRect(left, top, right, bottom, radius, radius, paints.toolbarButtonBgPaint)
        if (paints.toolbarButtonBorderPaint.strokeWidth > 0f) {
            canvas.drawRoundRect(
                left, top, right, bottom, radius, radius, paints.toolbarButtonBorderPaint,
            )
        }
    }
}

class TextButton(
    val label: String,
    override val action: PanelAction,
) : ToolbarButton() {
    override fun draw(canvas: Canvas, cx: Float, cy: Float, paints: Paints, density: Float) {
        val textY = cy - (paints.toolbarTextPaint.descent() + paints.toolbarTextPaint.ascent()) / 2f
        val halfW = paints.toolbarTextPaint.measureText(label) / 2f
        val halfH = (paints.toolbarTextPaint.descent() - paints.toolbarTextPaint.ascent()) / 2f
        drawButtonBackground(canvas, cx, cy, halfW, halfH, paints, density)
        canvas.drawText(label, cx, textY, paints.toolbarTextPaint)
    }
}

class ImageButton(
    private val drawable: Drawable?,
    override val action: PanelAction,
    private val rotation: Float = 0f,
    private val mirrorX: Boolean = false,
    private val iconScale: Float = 1f,
) : ToolbarButton() {
    override fun draw(canvas: Canvas, cx: Float, cy: Float, paints: Paints, density: Float) {
        val d = drawable ?: return
        val iw = d.intrinsicWidth.toFloat() * iconScale
        val ih = d.intrinsicHeight.toFloat() * iconScale
        drawButtonBackground(canvas, cx, cy, iw / 2f, ih / 2f, paints, density)
        d.setTint(paints.toolbarIconColor)
        d.setBounds(
            (cx - iw / 2f).toInt(), (cy - ih / 2f).toInt(),
            (cx + iw / 2f).toInt(), (cy + ih / 2f).toInt(),
        )
        if (mirrorX) {
            canvas.withScale(-1f, 1f, cx, cy) {
                d.draw(this)
            }
        } else if (rotation != 0f) {
            canvas.withRotation(rotation, cx, cy) { d.draw(this) }
        } else {
            d.draw(canvas)
        }
    }
}

class ToggleImageButton(
    private val normalDrawable: Drawable?,
    private val toggledDrawable: Drawable?,
    override val action: PanelAction,
    private val toggledRotation: Float = 0f,
    var isToggled: Boolean = false,
    private val iconScale: Float = 1f,
) : ToolbarButton() {
    override fun draw(canvas: Canvas, cx: Float, cy: Float, paints: Paints, density: Float) {
        val d = if (isToggled) toggledDrawable else normalDrawable
        if (d == null) return
        val iw = d.intrinsicWidth.toFloat() * iconScale
        val ih = d.intrinsicHeight.toFloat() * iconScale
        drawButtonBackground(canvas, cx, cy, iw / 2f, ih / 2f, paints, density)
        d.setTint(paints.toolbarIconColor)
        d.setBounds(
            (cx - iw / 2f).toInt(), (cy - ih / 2f).toInt(),
            (cx + iw / 2f).toInt(), (cy + ih / 2f).toInt(),
        )
        if (isToggled && toggledRotation != 0f) {
            canvas.withRotation(toggledRotation, cx, cy) { d.draw(this) }
        } else {
            d.draw(canvas)
        }
    }
}
