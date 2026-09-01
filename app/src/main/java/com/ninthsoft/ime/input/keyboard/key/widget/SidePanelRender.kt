/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2026 Fcitx5 for Android Contributors
 */
package com.ninthsoft.ime.input.keyboard.key.widget

import android.annotation.SuppressLint
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import androidx.annotation.ColorInt
import com.ninthsoft.ime.data.keyboard.theme.KeyboardColors
import com.ninthsoft.ime.input.keyboard.key.KeyboardAction
import com.ninthsoft.ime.input.keyboard.key.KeyDef
import com.ninthsoft.ime.input.keyboard.key.KeyDef.Appearance.Variant
import kotlin.math.max
import androidx.core.graphics.withSave
import timber.log.Timber

abstract class SidePanelRender(
    protected var theme: KeyboardColors.ColorScheme,
    private val density: Float,
    visibleItemCount: Int,
    protected val appearance: KeyDef.Appearance,
    var style: Style = Style.fromTheme(theme, appearance),
) {

    fun updateTheme(theme: KeyboardColors.ColorScheme) {
        this.theme = theme
        this.style = Style.fromTheme(theme, appearance)
    }
    data class Style(
        @ColorInt val backgroundColor: Int,
        @ColorInt val panelColor: Int,
        @ColorInt val pressedItemColor: Int,
        @ColorInt val scrollbarColor: Int,
        val cornerRadiusDp: Float = 8f,
        val scrollbarInsetDp: Float = 3f,
        val scrollbarWidthDp: Float = 1.5f,
        val scrollbarTrackInsetDp: Float = 8f,
        val scrollbarMinThumbHeightDp: Float = 8f,
        val textHeightRatio: Float = 0.42f,
    ) {
        companion object {
            fun fromTheme(theme: KeyboardColors.ColorScheme, appearance: KeyDef.Appearance) = Style(
                backgroundColor = theme.background,
                panelColor = when (appearance.variant) {
                    Variant.Normal, Variant.AltForeground -> theme.keyBackground
                    Variant.Alternative -> theme.specialKeyBackground
                    Variant.Accent -> theme.accentKeyBackground
                    Variant.None -> theme.background
                }, pressedItemColor = when (appearance.variant) {
                    Variant.Normal, Variant.AltForeground -> theme.keyPressed
                    Variant.Alternative -> theme.specialKeyPressed
                    Variant.Accent -> theme.accentKeyPressed
                    Variant.None -> theme.specialKeyPressed
                }, scrollbarColor = theme.keyText
            )
        }
    }

    data class Item(
        val label: String,
        val textSize: Float,
        val textStyle: Int,
        @ColorInt val textColor: Int,
        val action: KeyboardAction?,
    )

    private val panelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        style = Paint.Style.FILL
    }
    private val scrollbarPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val scrollbarRect = RectF()

    var items: List<Item> = emptyList()
        private set

    var visibleItemCount: Int = visibleItemCount.coerceAtLeast(1)
        private set

    fun updateItems(items: List<Item>) {
        this.items = items
    }

    fun updateVisibleItemCount(count: Int) {
        visibleItemCount = count.coerceAtLeast(1)
    }

    fun itemHeight(panelHeight: Float): Float = panelHeight / visibleItemCount

    fun contentHeight(panelHeight: Float): Float = itemHeight(panelHeight) * items.size

    fun maxScroll(panelHeight: Float): Float = max(0f, contentHeight(panelHeight) - panelHeight)

    fun itemIndexAt(panel: RectF, y: Float, scrollOffset: Float): Int {
        val itemHeight = itemHeight(panel.height())
        if (itemHeight <= 0f || !panel.contains(panel.centerX(), y)) return -1
        return ((y - panel.top + scrollOffset) / itemHeight).toInt().takeIf { it in items.indices }
            ?: -1
    }

    fun draw(
        canvas: Canvas,
        panel: RectF,
        scrollOffset: Float,
        pressedIndex: Int,
        stretch: Float = 0f,
        pressedAlpha: Int = 0,
        selectedIndex: Int = -1,
    ) {
        if (panel.isEmpty) return
        drawPanel(canvas, panel)
        drawItems(canvas, panel, scrollOffset, pressedIndex, stretch, pressedAlpha, selectedIndex)
        drawScrollbar(canvas, panel, scrollOffset)
    }

    private fun drawPanel(canvas: Canvas, panel: RectF) {
        val radius = cornerRadius()
        panelPaint.color = style.panelColor
        if (radius > 0f) {
            canvas.drawRoundRect(panel, radius, radius, panelPaint)
        } else {
            canvas.drawRect(panel, panelPaint)
        }
    }

    protected open fun cornerRadius(): Float = dp(style.cornerRadiusDp)

    protected open fun scrollbarInset(): Float = dp(style.scrollbarInsetDp)

    protected open fun scrollbarWidth(): Float = dp(style.scrollbarWidthDp)

    @SuppressLint("UseKtx")
    private fun drawItems(
        canvas: Canvas,
        panel: RectF,
        scrollOffset: Float,
        pressedIndex: Int,
        stretch: Float,
        pressedAlpha: Int,
        selectedIndex: Int = -1,
    ) {
        val itemHeight = itemHeight(panel.height())
        if (itemHeight <= 0f) return
        canvas.withSave {
            // 根据拉伸方向应用不同的变换
            if (stretch != 0f) {
                if (stretch > 0) {
                    // 下拉：以顶部为锚点，垂直拉伸
                    val scaleY = 1f + stretch / panel.height()
                    translate(0f, panel.top)
                    scale(1f, scaleY)
                    translate(0f, -panel.top)
                } else {
                    // 上拉：以底部为锚点，垂直拉伸
                    val scaleY = 1f - stretch / panel.height()
                    translate(0f, panel.bottom)
                    scale(1f, scaleY)
                    translate(0f, -panel.bottom)
                }
            }
            val clipRadius = cornerRadius()
            if (clipRadius > 0f) {
                clipPath(Path().apply {
                    addRoundRect(panel, clipRadius, clipRadius, Path.Direction.CW)
                })
            } else {
                clipRect(panel)
            }
            // 保存原始的对齐方式，以便恢复
            val originalAlign = textPaint.textAlign
            items.forEachIndexed { index, item ->
                val top = panel.top + itemHeight * index - scrollOffset
                val bottom = top + itemHeight
                if (bottom < panel.top || top > panel.bottom) return@forEachIndexed

                //disabled selection background color
                val isSelected = index == selectedIndex
                val isPressed = index == pressedIndex
                if (isSelected || isPressed) {
                    val c = style.pressedItemColor
                    panelPaint.color = Color.rgb(Color.red(c), Color.green(c), Color.blue(c))
                    panelPaint.alpha = if (isSelected) 255 else pressedAlpha
                    drawRect(panel.left, top, panel.right, bottom, panelPaint)
                    panelPaint.alpha = 255
                }
                textPaint.color = item.textColor
                textPaint.textSize = dp(item.textSize)
                textPaint.typeface = Typeface.create(Typeface.DEFAULT, item.textStyle)
                textPaint.textAlign = Paint.Align.LEFT
                val bounds = Rect()
                val text = item.label
                textPaint.getTextBounds(text, 0, text.length, bounds)
                val centerX = panel.centerX()
                val centerY = (top + bottom) * 0.5f
                val x = centerX - bounds.exactCenterX()
                val y = centerY - bounds.exactCenterY() + 5
                drawText(text, x, y, textPaint)
            }
            // 恢复原始对齐方式
            textPaint.textAlign = originalAlign
        }
    }

    private fun drawScrollbar(canvas: Canvas, panel: RectF, scrollOffset: Float) {
        val maxScroll = maxScroll(panel.height())
        if (items.size <= visibleItemCount || maxScroll <= 0f) return
        val itemHeight = itemHeight(panel.height())
        if (itemHeight <= 0f) return
        val trackTop = panel.top + dp(style.scrollbarTrackInsetDp)
        val trackBottom = panel.bottom - dp(style.scrollbarTrackInsetDp)
        val trackHeight = trackBottom - trackTop
        if (trackHeight <= 0f) return
        val thumbHeight =
            (trackHeight * panel.height() / contentHeight(panel.height()) * 0.6f).coerceAtLeast(
                dp(
                    style.scrollbarMinThumbHeightDp
                )
            )
        val clampedScroll = scrollOffset.coerceIn(0f, maxScroll)
        val thumbTop = trackTop + (trackHeight - thumbHeight) * clampedScroll / maxScroll
        val width = scrollbarWidth()
        val left = panel.right - scrollbarInset()
        scrollbarRect.set(left, thumbTop, left + width, thumbTop + thumbHeight)
        scrollbarPaint.color = items.firstOrNull()?.textColor ?: style.scrollbarColor
        val radius = width / 2f
        canvas.drawRoundRect(scrollbarRect, radius, radius, scrollbarPaint)
    }

    protected fun dp(value: Float): Float = value * density

    abstract fun toItem(itemDef: KeyDef): Item?
}