/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2026 Fcitx5 for Android Contributors
 */
package com.jobeen.ime.input.keyboard.key.widget

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import androidx.annotation.ColorInt
import com.jobeen.ime.data.keyboard.theme.FontRole
import com.jobeen.ime.data.keyboard.theme.GlobalFonts
import com.jobeen.ime.data.keyboard.theme.KeyboardColors
import com.jobeen.ime.data.keyboard.theme.SkinFonts
import com.jobeen.ime.input.keyboard.key.KeyboardAction
import com.jobeen.ime.input.keyboard.key.KeyDef
import com.jobeen.ime.input.keyboard.key.KeyDef.Appearance.Variant
import kotlin.math.max
import androidx.core.graphics.withSave
import timber.log.Timber

abstract class SidePanelRender(
    protected val context: Context,
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
        val cornerRadiusDp: Float = 8f,
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
                }
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

    // 绘制期复用对象：draw 在手势/动画期以 60-120fps 调用，旧实现每帧新建
    // Path、每条目新建 Rect 与 Typeface（Typeface.create 走系统缓存查找也不便宜）
    private val clipPath = Path()
    private val textBounds = Rect()
    private val typefaceCache = mutableMapOf<Int, Typeface>()

    /**
     * 字族跟随（2026-10-27 新增分流）：侧栏绘制是 60–120fps 热
     * 路径，逐帧查快照不可取——只在族名变化时清一次缓存（每
     * 帧一次 volatile 读 + 字符串比较，无逐项分配），其余帧走
     * 原缓存。渲染实例跨皮肤复用时也不会沿用旧字体。
     */
    private var cachedSkinFamily: String? = null
    private var cachedFontVersion: Int = -1

    var items: List<Item> = emptyList()
        private set

    var visibleItemCount: Int = visibleItemCount.coerceAtLeast(1)
        private set

    fun updateItems(items: List<Item>) {
        this.items = items
    }

    fun itemHeight(panelHeight: Float): Float = panelHeight / visibleItemCount

    fun contentHeight(panelHeight: Float): Float = itemHeight(panelHeight) * items.size

    fun maxScroll(panelHeight: Float): Float = max(0f, contentHeight(panelHeight) - panelHeight)

    /** 触点所在条目下标；不在任何条目内时返回 -1。 */
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
        val skinFamily = SkinFonts.familyName()
        val fontVersion = GlobalFonts.version(context)
        if (skinFamily != cachedSkinFamily || fontVersion != cachedFontVersion) {
            cachedSkinFamily = skinFamily
            cachedFontVersion = fontVersion
            typefaceCache.clear()
        }
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
            // 条目只画在面板内。
            val scrollArea = RectF(panel.left, panel.top, panel.right, panel.bottom)
            val clipRadius = cornerRadius()
            if (clipRadius > 0f) {
                clipPath.reset()
                clipPath.addRoundRect(scrollArea, clipRadius, clipRadius, Path.Direction.CW)
                clipPath(clipPath)
            } else {
                clipRect(scrollArea)
            }
            // 保存原始的对齐方式，以便恢复
            val originalAlign = textPaint.textAlign
            items.forEachIndexed { index, item ->
                val top = panel.top + itemHeight * index - scrollOffset
                val bottom = top + itemHeight
                if (bottom < panel.top || top > panel.bottom) return@forEachIndexed
                // I-L5：每条目只画在自己格内——多行标签越格时不叠到
                // 相邻条目上（横屏“⊕／自定义”两行溢出即此；竖屏两行
                // 放得下，裁剪无影响）。裁剪在已有 stretch 变换内、
                // 与绘制同坐标空间，拉伸态下仍一致；与面板裁剪取交集，
                // 滑出面板的部分仍不画。选中／按下底色同在格内，不另扩。
                canvas.save()
                canvas.clipRect(panel.left, top, panel.right, bottom)

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
                textPaint.typeface = typefaceCache.getOrPut(item.textStyle) {
                    GlobalFonts.typefaceFor(context, FontRole.SIDEPANEL, item.textStyle)
                        ?: Typeface.create(Typeface.DEFAULT, item.textStyle)
                }
                textPaint.textAlign = Paint.Align.LEFT
                val bounds = textBounds
                val text = item.label
                val centerX = panel.centerX()
                val centerY = (top + bottom) * 0.5f
                // 多行标签（⊕\n自定义）按 '\n' 拆行逐行绘制、垂直居中
                // （I-⊕）：单行 drawText 不拆行，'\n' 会被当成行内
                // 空白画在一行里；单行标签走原口径，逐字不动。
                val lines = text.split('\n')
                if (lines.size == 1) {
                    textPaint.getTextBounds(text, 0, text.length, bounds)
                    val x = centerX - bounds.exactCenterX()
                    val y = centerY - bounds.exactCenterY() + 5
                    drawText(text, x, y, textPaint)
                } else {
                    val metrics = textPaint.fontMetrics
                    val baselines = multiLineBaselines(
                        lines.size, centerY, textPaint.fontSpacing,
                        metrics.ascent, metrics.descent,
                    )
                    lines.forEachIndexed { lineIndex, line ->
                        textPaint.getTextBounds(line, 0, line.length, bounds)
                        val x = centerX - bounds.exactCenterX()
                        drawText(line, x, baselines[lineIndex], textPaint)
                    }
                }
                canvas.restore()
            }
            // 恢复原始对齐方式
            textPaint.textAlign = originalAlign
        }
    }

    protected fun dp(value: Float): Float = value * density

    abstract fun toItem(itemDef: KeyDef): Item?
}

/**
 * 多行标签各行的基线 Y（纯函数，可单测；I-⊕）：行块整体以
 * centerY 为中心垂直居中，行间距取 fontSpacing；每行基线 =
 * 行中心 Y － (ascent＋descent)/2。单行时退化为标准垂直居中。
 */
internal fun multiLineBaselines(
    lineCount: Int,
    centerY: Float,
    lineHeightPx: Float,
    ascent: Float,
    descent: Float,
): List<Float> {
    if (lineCount <= 0) return emptyList()
    val mid = (lineCount - 1) / 2f
    return (0 until lineCount).map { i ->
        centerY + (i - mid) * lineHeightPx - (ascent + descent) / 2f
    }
}