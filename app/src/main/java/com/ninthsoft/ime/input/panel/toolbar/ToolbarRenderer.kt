package com.ninthsoft.ime.input.panel.toolbar

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.Drawable
import androidx.core.graphics.withRotation
import com.ninthsoft.ime.input.panel.PanelAction
import com.ninthsoft.ime.input.panel.IRenderer
import com.ninthsoft.ime.input.panel.KawaiiPanel
import com.ninthsoft.ime.input.panel.Paints
import com.ninthsoft.ime.input.panel.component.ClipboardTab

class ToolbarRenderer(
    private val resources: ToolbarRendererResources,
    var horizontalPaddingDp: Float = 0f,
    var centerHorizontalPaddingDp: Float = 12f,
    var iconScale: Float = 0.94f,
) : IRenderer {

    var textEditingMode: Boolean = false
    var copyText: String? = null
    var clipMode: Boolean = false
    var clipTab: ClipboardTab = ClipboardTab.CLIPBOARD
    var clipLabelClipboard: String = ""
    var clipLabelPhrase: String = ""
    var clipSelectedTextColor: Int = 0
    var clipSelectedBgColor: Int = 0
    override var recording: Boolean = false

    private data class ClipGeom(
        val capsuleLeft: Float, val capsuleRight: Float, val capsuleTop: Float, val capsuleH: Float,
        val actionLeft: Float, val actionRight: Float, val closeLeft: Float, val closeRight: Float,
    )

    private fun clipGeom(width: Int, height: Int, density: Float): ClipGeom {
        val hPad = (horizontalPaddingDp + 4) * density
        val fixedW = 32f * density
        val menuRight = hPad + fixedW
        val capsuleH = 32f * density
        val capsuleTop = (height - capsuleH) / 2f
        val btnW = 30f * density
        val btnGap = 6f * density
        val closeRight = width - hPad
        val closeLeft = closeRight - btnW
        val actionRight = closeLeft - btnGap
        val actionLeft = actionRight - btnW * 2f - btnGap
        val rightZone = actionLeft
        val leftZone = menuRight + centerHorizontalPaddingDp * density
        val availW = (rightZone - 8f * density - leftZone).coerceAtLeast(0f)
        val capsuleW = (180f * density).coerceAtMost(availW)
        val capsuleLeft = leftZone
        val capsuleRight = capsuleLeft + capsuleW
        return ClipGeom(
            capsuleLeft,
            capsuleRight,
            capsuleTop,
            capsuleH,
            actionLeft,
            actionRight,
            closeLeft,
            closeRight
        )
    }

    private fun actionSlotCenter(g: ClipGeom, index: Int, density: Float): Float {
        val btnW = 30f * density
        val gap = 6f * density
        return g.actionRight - btnW / 2f - index * (btnW + gap)
    }

    private fun drawActionIcon(
        canvas: Canvas,
        drawable: Drawable,
        centerX: Float,
        centerY: Float,
        paints: Paints,
        density: Float,
    ) {
        drawable.setTint(dimColor(paints.toolbarIconColor))
        val size = 18f * density
        drawable.setBounds(
            (centerX - size / 2f).toInt(),
            (centerY - size / 2f).toInt(),
            (centerX + size / 2f).toInt(),
            (centerY + size / 2f).toInt(),
        )
        drawable.draw(canvas)
    }

    private fun dimColor(color: Int): Int {
        return if (recording) (color and 0x00FFFFFF) or 0x5A000000 else color
    }

    private val centerButtons = listOf(
        ImageButton(resources.undo, PanelAction.Undo, iconScale = iconScale),
        ImageButton(resources.redo, PanelAction.Redo, iconScale = iconScale),
        ImageButton(resources.cursorMove, PanelAction.CursorMove, iconScale = iconScale),
        ImageButton(resources.clipboard, PanelAction.Clipboard, iconScale = iconScale),
        ImageButton(resources.palette, PanelAction.Palette, iconScale = iconScale),
    )

    var pressAlpha: Int = 0
    var pressCx: Float = 0f
    var pressCy: Float = 0f
    var pressRadius: Float = 0f
    var pressRadiusMax: Float = 0f
    private val pressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    override fun draw(
        canvas: Canvas, width: Int, height: Int, paints: Paints,
        scrollX: Float, isExpanded: Boolean, density: Float,
    ) {
        if (width <= 0 || height <= 0) return
        val hPad = (horizontalPaddingDp + 4) * density
        val fixedW = 32f * density

        val menuLeft = hPad
        val menuCenter = menuLeft + fixedW / 2f
        val centerPad = centerHorizontalPaddingDp * density
        val centerAreaLeft = menuLeft + fixedW + centerPad
        val centerAreaW = width - centerAreaLeft - hPad - fixedW - centerPad

        if (pressRadius > 0f && pressRadiusMax > 0f) {
            val progress = (pressRadius / pressRadiusMax).coerceIn(0f, 1f)
            val currentAlpha = (pressAlpha * (1f - progress)).toInt().coerceIn(0, 255)
            if (currentAlpha > 0) {
                pressPaint.color = paints.toolbarPressedColor
                pressPaint.alpha = currentAlpha
                canvas.drawCircle(pressCx, pressCy, pressRadius, pressPaint)
            }
        }

        if (clipMode) {
            drawClipbar(canvas, width, height, paints, density)
            return
        }

        if (resources.menu != null) {
            val d =
                if (textEditingMode || copyText != null || showArrow) resources.arrow else resources.menu
            if (d != null) {
                d.setTint(dimColor(paints.toolbarIconColor))
                val iw = d.intrinsicWidth.toFloat() * iconScale
                val ih = d.intrinsicHeight.toFloat() * iconScale
                d.setBounds(
                    (menuCenter - iw / 2f).toInt(), (height / 2f - ih / 2f).toInt(),
                    (menuCenter + iw / 2f).toInt(), (height / 2f + ih / 2f).toInt(),
                )
                d.draw(canvas)
            }
        }

        if (copyText != null) {
            val t = copyText!!
            val textPaint = if (recording) Paint(paints.candidateTextPaint).apply {
                color = dimColor(paints.candidateTextPaint.color)
            } else paints.candidateTextPaint
            val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.FILL
                color = dimColor(paints.candidateBgPaint.color)
            }
            val pillR = 6f * density
            val pillH = 34f * density
            val pillPad = 8f * density
            val clipLeft = centerAreaLeft + 16f * density
            val clipRight = width - hPad - fixedW - centerPad - 16f * density
            val textCenterY = height / 2f
            val gap = 8f * density
            val iconW =
                (resources.clipboard?.intrinsicWidth?.toFloat()?.times(iconScale)?.toInt() ?: 0)
            val iconH =
                (resources.clipboard?.intrinsicHeight?.toFloat()?.times(iconScale)?.toInt() ?: 0)
            val iconAvail = if (resources.clipboard != null) iconW + gap else 0f
            val availW = clipRight - clipLeft
            val maxTextW = availW - iconAvail - pillPad * 2
            val src = if (t.length > 256) t.take(256) else t
            val ellipsized = if (textPaint.measureText(src) <= maxTextW) {
                src
            } else {
                var lo = 0
                var hi = src.length
                while (lo < hi) {
                    val mid = (lo + hi) / 2
                    if (textPaint.measureText(src.take(mid) + "…") <= maxTextW) lo = mid + 1
                    else hi = mid
                }
                src.take((lo - 1).coerceAtLeast(0)) + "…"
            }
            val textW = textPaint.measureText(ellipsized)
            val totalW = iconAvail + textW
            val contentLeft = clipLeft + (availW - totalW) / 2f
            val bgLeft = contentLeft - pillPad
            val bgRight = contentLeft + totalW + pillPad
            val bgTop = textCenterY - pillH / 2f
            val bgBottom = textCenterY + pillH / 2f
            canvas.drawRoundRect(bgLeft, bgTop, bgRight, bgBottom, pillR, pillR, bgPaint)
            var drawX = contentLeft
            if (resources.clipboard != null) {
                resources.clipboard.setTint(dimColor(paints.toolbarIconColor))
                val iconTop = (textCenterY - iconH / 2f).toInt()
                resources.clipboard.setBounds(
                    drawX.toInt(), iconTop, drawX.toInt() + iconW, iconTop + iconH
                )
                resources.clipboard.draw(canvas)
                drawX += iconW + gap
            }
            val textY = textCenterY - (textPaint.descent() + textPaint.ascent()) / 2f
            canvas.drawText(ellipsized, drawX, textY, textPaint)
        } else {
            val otherW = centerAreaW / centerButtons.size
            for ((i, btn) in centerButtons.withIndex()) {
                val savedColor = paints.toolbarIconColor
                val disabled = (textEditingMode && i >= 2) || recording
                if (disabled) {
                    paints.toolbarIconColor = (savedColor and 0x00FFFFFF) or 0x62000000.toInt()
                }
                btn.draw(
                    canvas, centerAreaLeft + otherW * i + otherW / 2f,
                    height / 2f, paints, density,
                )
                if (disabled) {
                    paints.toolbarIconColor = savedColor
                }
            }
        }

        val expandLeft = width - hPad - fixedW
        val expandCenter = expandLeft + fixedW / 2f
        val expandIcon = resources.expand
        if (expandIcon != null) {
            expandIcon.setTint(paints.toolbarIconColor)
            val iw = expandIcon.intrinsicWidth.toFloat() * iconScale
            val ih = expandIcon.intrinsicHeight.toFloat() * iconScale
            expandIcon.setBounds(
                (expandCenter - iw / 2f).toInt(), (height / 2f - ih / 2f).toInt(),
                (expandCenter + iw / 2f).toInt(), (height / 2f + ih / 2f).toInt(),
            )
            if (!textEditingMode && isExpanded) {
                canvas.withRotation(180f, expandCenter, height / 2f) { expandIcon.draw(this) }
            } else {
                expandIcon.draw(canvas)
            }
        }
    }

    private fun drawClipbar(
        canvas: Canvas, width: Int, height: Int, paints: Paints, density: Float,
    ) {
        val hPad = (horizontalPaddingDp + 4) * density
        val fixedW = 32f * density
        val menuCenter = hPad + fixedW / 2f
        val g = clipGeom(width, height, density)

        // 返回箭头
        resources.arrow?.let { d ->
            d.setTint(dimColor(paints.toolbarIconColor))
            val iw = d.intrinsicWidth.toFloat() * iconScale
            val ih = d.intrinsicHeight.toFloat() * iconScale
            d.setBounds(
                (menuCenter - iw / 2f).toInt(), (height / 2f - ih / 2f).toInt(),
                (menuCenter + iw / 2f).toInt(), (height / 2f + ih / 2f).toInt(),
            )
            d.draw(canvas)
        }

        // 胶囊（剪切板 / 快捷短语）
        val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = paints.toolbarIconColor and 0x1FFFFFFF
        }
        canvas.drawRoundRect(
            g.capsuleLeft,
            g.capsuleTop,
            g.capsuleRight,
            g.capsuleTop + g.capsuleH,
            g.capsuleH / 2f,
            g.capsuleH / 2f,
            trackPaint
        )
        val segW = (g.capsuleRight - g.capsuleLeft) / 2f
        val cy = g.capsuleTop + g.capsuleH / 2f
        val segPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
        val labels = listOf(
            clipLabelClipboard to (clipTab == ClipboardTab.CLIPBOARD),
            clipLabelPhrase to (clipTab == ClipboardTab.PHRASE),
        )
        for ((i, pair) in labels.withIndex()) {
            val segLeft = g.capsuleLeft + segW * i
            val segRight = segLeft + segW
            if (pair.second) {
                canvas.drawRoundRect(
                    segLeft + 3f * density, g.capsuleTop + 3f * density,
                    segRight - 3f * density, g.capsuleTop + g.capsuleH - 3f * density,
                    g.capsuleH / 2f - 3f * density, g.capsuleH / 2f - 3f * density,
                    pressPaint.apply { color = clipSelectedBgColor; alpha = 255 },
                )
            }
            segPaint.color =
                if (pair.second) clipSelectedTextColor else dimColor(paints.toolbarIconColor)
            segPaint.textSize = 14f * density
            val fm = segPaint.fontMetrics
            canvas.drawText(
                pair.first,
                (segLeft + segRight) / 2f,
                cy - fm.ascent / 2f - fm.descent / 2f,
                segPaint
            )
        }

        // 操作按钮：剪切板分页为「清空」，常用语分页为「新增」和「全部删除」
        val actionCy = height / 2f
        if (clipTab == ClipboardTab.CLIPBOARD) {
            resources.clear?.let { d ->
                drawActionIcon(canvas, d, actionSlotCenter(g, 0, density), actionCy, paints, density)
            }
        } else {
            val addPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                textAlign = Paint.Align.CENTER
                textSize = 22f * density
                color = dimColor(paints.toolbarIconColor)
            }
            canvas.drawText(
                "＋",
                actionSlotCenter(g, 1, density),
                actionCy - addPaint.ascent() / 2f - addPaint.descent() / 2f,
                addPaint,
            )
            resources.clear?.let { d ->
                drawActionIcon(canvas, d, actionSlotCenter(g, 0, density), actionCy, paints, density)
            }
        }

        // 关闭键盘按钮（始终显示）
        resources.expand?.let { d ->
            d.setTint(dimColor(paints.toolbarIconColor))
            val iw = d.intrinsicWidth.toFloat() * iconScale
            val ih = d.intrinsicHeight.toFloat() * iconScale
            d.setBounds(
                (g.closeLeft + (g.closeRight - g.closeLeft - iw) / 2f).toInt(),
                (height / 2f - ih / 2f).toInt(),
                (g.closeLeft + (g.closeRight - g.closeLeft + iw) / 2f).toInt(),
                (height / 2f + ih / 2f).toInt(),
            )
            d.draw(canvas)
        }
    }

    override fun hitTest(
        x: Float, y: Float, width: Int, height: Int,
        scrollX: Float, isExpanded: Boolean, density: Float,
    ): KawaiiPanel.TouchResult? {
        if (width <= 0) return null
        val hPad = (horizontalPaddingDp + 4) * density
        val fixedW = 32f * density
        val edgeTouchW = 48f * density
        val menuCenter = hPad + fixedW / 2f
        val closeCenter = width - hPad - fixedW / 2f
        val menuTouchLeft = (menuCenter - edgeTouchW / 2f).coerceAtLeast(0f)
        val menuTouchRight = (menuCenter + edgeTouchW / 2f).coerceAtMost(width.toFloat())
        val closeTouchLeft = (closeCenter - edgeTouchW / 2f).coerceAtLeast(0f)
        val closeTouchRight = (closeCenter + edgeTouchW / 2f).coerceAtMost(width.toFloat())

        if (clipMode) {
            val g = clipGeom(width, height, density)
            val setPress = { cx: Float ->
                pressCx = cx
                pressCy = height / 2f
                pressRadiusMax = height * 0.55f
                pressRadius = 0f
            }
            if (x in menuTouchLeft..menuTouchRight) {
                setPress(menuCenter)
                return KawaiiPanel.TouchResult.ToolbarAction(
                    PanelAction.SwitchKeyboard, tapX = x, tapY = y
                )
            }
            if (x in g.capsuleLeft..g.capsuleRight && y in g.capsuleTop..(g.capsuleTop + g.capsuleH)) {
                val segW = (g.capsuleRight - g.capsuleLeft) / 2f
                setPress(g.capsuleLeft + (if (x < g.capsuleLeft + segW) segW / 2f else segW + segW / 2f))
                val isClipboard = x < g.capsuleLeft + segW
                return KawaiiPanel.TouchResult.ToolbarAction(
                    PanelAction.ClipTab(isClipboard), tapX = x, tapY = y
                )
            }
            if (x in g.actionLeft..g.actionRight) {
                val action = if (clipTab == ClipboardTab.CLIPBOARD) {
                    val btnW = 30f * density
                    if (x < g.actionRight - btnW) return null
                    setPress(actionSlotCenter(g, 0, density))
                    PanelAction.ClearClipboard
                } else {
                    val btnW = 30f * density
                    val slot = if (x > g.actionRight - btnW) 0 else 1
                    setPress(actionSlotCenter(g, slot, density))
                    if (slot == 0) PanelAction.ClearPhrases else PanelAction.AddPhrase
                }
                return KawaiiPanel.TouchResult.ToolbarAction(action, tapX = x, tapY = y)
            }
            if (x in closeTouchLeft..closeTouchRight) {
                setPress((g.closeLeft + g.closeRight) / 2f)
                return KawaiiPanel.TouchResult.ToolbarAction(
                    PanelAction.CloseKeyboard, tapX = x, tapY = y
                )
            }
            return null
        }

        val centerPad = centerHorizontalPaddingDp * density
        val centerAreaLeft = hPad + fixedW + centerPad
        val centerAreaW = width - centerAreaLeft - hPad - fixedW - centerPad
        val otherW = centerAreaW / centerButtons.size

        when (x) {
            in closeTouchLeft..closeTouchRight -> {
                pressCx = closeCenter
                pressCy = height / 2f
                pressRadiusMax = height * 0.55f
                pressRadius = 0f
                return KawaiiPanel.TouchResult.ToolbarAction(
                    PanelAction.CloseKeyboard,
                    tapX = x,
                    tapY = y,
                )
            }

            in menuTouchLeft..menuTouchRight -> {
                pressCx = menuCenter
                pressCy = height / 2f
                pressRadiusMax = height * 0.55f
                pressRadius = 0f
                return KawaiiPanel.TouchResult.ToolbarAction(
                    PanelAction.SwitchKeyboard,
                    tapX = x,
                    tapY = y,
                )
            }

            else -> {
                if (copyText != null) return null
                val index = ((x - centerAreaLeft) / otherW).toInt()
                    .takeIf { it in centerButtons.indices } ?: return null
                if (textEditingMode && index >= 2) return null
                pressCx = centerAreaLeft + otherW * index + otherW / 2f
                pressCy = height / 2f
                pressRadiusMax = height * 0.55f
                pressRadius = 0f
                return KawaiiPanel.TouchResult.ToolbarAction(
                    centerButtons[index].action,
                    tapX = x,
                    tapY = y,
                )
            }
        }
    }

    var showArrow: Boolean = false
}
