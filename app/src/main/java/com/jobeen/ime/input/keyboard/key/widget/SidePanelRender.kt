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

    /**
     * 底部固定入口（2026-11-12 九宫格左竖栏“自定义”）：不参与滚动，
     * 恒贴面板底部；为 null 时面板与旧行为完全一致。footerHeightFraction
     * 为入口占面板高度的比例（默认 1/6，两行 12sp 字放得下）。
     */
    var footer: Item? = null
    var footerHeightFraction: Float = 1f / 6f

    fun updateItems(items: List<Item>) {
        this.items = items
    }

    fun updateVisibleItemCount(count: Int) {
        visibleItemCount = count.coerceAtLeast(1)
    }

    /** 入口高度（无入口时为 0）。 */
    fun footerHeight(panelHeight: Float): Float =
        if (footer != null) panelHeight * footerHeightFraction else 0f

    /** 可滚区高度（面板高减入口高）。 */
    fun scrollAreaHeight(panelHeight: Float): Float = panelHeight - footerHeight(panelHeight)

    fun itemHeight(panelHeight: Float): Float = scrollAreaHeight(panelHeight) / visibleItemCount

    fun contentHeight(panelHeight: Float): Float = itemHeight(panelHeight) * items.size

    fun maxScroll(panelHeight: Float): Float = max(0f, contentHeight(panelHeight) - scrollAreaHeight(panelHeight))

    /**
     * 触点所在条目下标；落在底部固定入口内时返回 [items].size（入口哨兵，
     * 调用方据此取 [footer] 的动作，禁止直接拿它下标 [items]）。
     */
    fun itemIndexAt(panel: RectF, y: Float, scrollOffset: Float): Int {
        val fh = footerHeight(panel.height())
        if (footer != null && fh > 0f && y >= panel.bottom - fh) return items.size
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
        drawFooter(canvas, panel, pressedIndex, pressedAlpha)
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
            // 条目只画在可滚区（入口区不参与滚动，条目不得画进入口区）。
            val scrollArea = RectF(panel.left, panel.top, panel.right, panel.bottom - footerHeight(panel.height()))
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
                if (bottom < panel.top || top > panel.bottom - footerHeight(panel.height())) return@forEachIndexed

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
        val scrollBottom = panel.bottom - footerHeight(panel.height())
        val trackTop = panel.top + dp(style.scrollbarTrackInsetDp)
        val trackBottom = scrollBottom - dp(style.scrollbarTrackInsetDp)
        val trackHeight = trackBottom - trackTop
        if (trackHeight <= 0f) return
        val thumbHeight =
            (trackHeight * scrollAreaHeight(panel.height()) / contentHeight(panel.height()) * 0.6f).coerceAtLeast(
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

    /**
     * 底部固定入口：分隔线＋两行字（“⊕”／“自定义”，label 按 \n 分行，
     * 每行居中）。无入口时不画。按压态（pressedIndex 为入口哨兵）时
     * 入口区铺按压底色。
     */
    private fun drawFooter(canvas: Canvas, panel: RectF, pressedIndex: Int, pressedAlpha: Int) {
        val foot = footer ?: return
        val fh = footerHeight(panel.height())
        if (fh <= 0f) return
        val top = panel.bottom - fh
        val bottom = panel.bottom
        if (pressedIndex == items.size) {
            val c = style.pressedItemColor
            panelPaint.color = Color.rgb(Color.red(c), Color.green(c), Color.blue(c))
            panelPaint.alpha = pressedAlpha
            canvas.drawRect(panel.left, top, panel.right, bottom, panelPaint)
            panelPaint.alpha = 255
        }
        // 分隔线（入口区顶部）。
        panelPaint.color = style.scrollbarColor
        panelPaint.alpha = 60
        canvas.drawRect(panel.left + dp(6f), top, panel.right - dp(6f), top + dp(1f), panelPaint)
        panelPaint.alpha = 255
        // 两行字：label 按 \n 分行，行高均分入口高，各行居中。
        val lines = foot.label.split("\n")
        textPaint.color = foot.textColor
        textPaint.textSize = dp(foot.textSize)
        textPaint.typeface = typefaceCache.getOrPut(foot.textStyle) {
            GlobalFonts.typefaceFor(context, FontRole.SIDEPANEL, foot.textStyle)
                ?: Typeface.create(Typeface.DEFAULT, foot.textStyle)
        }
        val lineH = fh / lines.size
        val originalAlign = textPaint.textAlign
        textPaint.textAlign = Paint.Align.LEFT
        lines.forEachIndexed { i, line ->
            val bounds = textBounds
            textPaint.getTextBounds(line, 0, line.length, bounds)
            val centerX = panel.centerX()
            val centerY = top + lineH * i + lineH * 0.5f
            val x = centerX - bounds.exactCenterX()
            val y = centerY - bounds.exactCenterY() + 5
            canvas.drawText(line, x, y, textPaint)
        }
        textPaint.textAlign = originalAlign
    }

    protected fun dp(value: Float): Float = value * density

    abstract fun toItem(itemDef: KeyDef): Item?
}