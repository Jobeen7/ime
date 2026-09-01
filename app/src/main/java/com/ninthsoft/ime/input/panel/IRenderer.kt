package com.ninthsoft.ime.input.panel

import android.graphics.Canvas

interface IRenderer {
    var recording: Boolean

    fun draw(
        canvas: Canvas, width: Int, height: Int, paints: Paints,
        scrollX: Float = 0f, isExpanded: Boolean = false, density: Float = 1f,
    )

    fun hitTest(
        x: Float, y: Float, width: Int, height: Int,
        scrollX: Float = 0f, isExpanded: Boolean = false, density: Float = 1f,
    ): KawaiiPanel.TouchResult?
}
