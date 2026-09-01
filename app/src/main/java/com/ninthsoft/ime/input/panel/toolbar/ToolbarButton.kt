package com.ninthsoft.ime.input.panel.toolbar

import android.graphics.Canvas
import android.graphics.drawable.Drawable
import androidx.core.graphics.withRotation
import com.ninthsoft.ime.input.panel.Paints
import androidx.core.graphics.withScale
import com.ninthsoft.ime.input.panel.PanelAction

sealed class ToolbarButton {
    abstract val action: PanelAction
    abstract fun draw(canvas: Canvas, cx: Float, cy: Float, paints: Paints, density: Float)
}

class TextButton(
    val label: String,
    override val action: PanelAction,
) : ToolbarButton() {
    override fun draw(canvas: Canvas, cx: Float, cy: Float, paints: Paints, density: Float) {
        val textY = cy - (paints.toolbarTextPaint.descent() + paints.toolbarTextPaint.ascent()) / 2f
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
