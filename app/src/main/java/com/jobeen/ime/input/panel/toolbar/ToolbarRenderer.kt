package com.jobeen.ime.input.panel.toolbar

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.Drawable
import androidx.core.graphics.withRotation
import com.jobeen.ime.input.panel.PanelAction
import com.jobeen.ime.input.panel.IRenderer
import com.jobeen.ime.input.panel.KawaiiPanel
import com.jobeen.ime.input.panel.Paints
import com.jobeen.ime.input.panel.component.ClipboardTab

class ToolbarRenderer(
    private val resources: ToolbarRendererResources,
    var horizontalPaddingDp: Float = 0f,
    var centerHorizontalPaddingDp: Float = 12f,
    var iconScale: Float = 0.94f,
) : IRenderer {

    var textEditingMode: Boolean = false
    var copyText: String? = null
    var clipMode: Boolean = false
    // 剪贴板搜索态（仅 CLIPBOARD 分页）：true 时胶囊区改画搜索框
    var clipSearchMode: Boolean = false
    var clipSearchQuery: String = ""
    var clipSearchHint: String = ""
    // 剪贴板条目编辑态：true 时胶囊区改画编辑框（与搜索框同几何），
    // 动作区两槽改为「保存 / 取消」文字按钮
    var clipEditMode: Boolean = false
    var clipEditText: String = ""
    var clipEditHint: String = ""
    var clipEditSaveLabel: String = ""
    var clipCancelLabel: String = ""
    // 剪贴板多选态：true 时胶囊区改画已选计数，动作区两槽为「删除 / 取消」
    var clipMultiMode: Boolean = false
    var clipMultiLabel: String = ""
    var clipDeleteLabel: String = ""
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
        ImageButton(resources.emoji, PanelAction.EmojiKeyboard, iconScale = iconScale),
        ImageButton(resources.selectAll, PanelAction.SelectAll, iconScale = iconScale),
        ImageButton(resources.copy, PanelAction.Copy, iconScale = iconScale),
        ImageButton(resources.paste, PanelAction.Paste, iconScale = iconScale),
        ImageButton(resources.clipboard, PanelAction.Clipboard, iconScale = iconScale),
    )

    var pressAlpha: Int = 0
    var pressCx: Float = 0f
    var pressCy: Float = 0f
    var pressRadius: Float = 0f
    var pressRadiusMax: Float = 0f
    private val pressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    // draw 期复用：旧实现每次绘制新建 2-3 个 Paint（工具栏每帧重绘时持续分配）
    private val copyBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val dimTextPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val segPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val searchTextPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val searchLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    // 搜索文本尾部截断缓存：(查询, 可用宽, 字号) -> 显示文本
    private var searchEllipsizeKey: Triple<String, Float, Float>? = null
    private var searchEllipsizeValue: String = ""
    private val addPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    // 复制药丸截断缓存：（源文本, 可用宽, 字号）→ 截断结果
    private var ellipsizeCacheKey: Triple<String, Float, Float>? = null
    private var ellipsizeCacheValue: String? = null

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
            val textPaint = if (recording) dimTextPaint.apply {
                set(paints.candidateTextPaint)
                color = dimColor(paints.candidateTextPaint.color)
            } else paints.candidateTextPaint
            val bgPaint = copyBgPaint.apply {
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
            // 截断结果缓存：按压动画约 18 帧重绘时 copyText 不变，此前每帧
            // 都重跑一遍二分（每轮迭代还新建子串+拼接串）
            val cacheKey = Triple(src, maxTextW, textPaint.textSize)
            val ellipsized = if (cacheKey == ellipsizeCacheKey) {
                ellipsizeCacheValue!!
            } else {
                val computed = if (textPaint.measureText(src) <= maxTextW) {
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
                ellipsizeCacheKey = cacheKey
                ellipsizeCacheValue = computed
                computed
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

        if (clipSearchMode) {
            drawClipSearchbar(canvas, g, height, paints, density)
            drawClipClose(canvas, g, height, paints, density)
            return
        }

        if (clipEditMode) {
            drawClipEditbar(canvas, g, height, paints, density)
            drawClipClose(canvas, g, height, paints, density)
            return
        }

        if (clipMultiMode) {
            drawClipMultibar(canvas, g, height, paints, density)
            drawClipClose(canvas, g, height, paints, density)
            return
        }

        // 胶囊（剪切板 / 快捷短语）
        trackPaint.color = paints.toolbarIconColor and 0x1FFFFFFF
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
        val segPaint = this.segPaint
        // 两段标签直接展开绘制（此前每帧现建 List+Pair，与本文件绘制期
        // 复用的约定不一致）
        for (i in 0..1) {
            val label = if (i == 0) clipLabelClipboard else clipLabelPhrase
            val selected = (i == 0) == (clipTab == ClipboardTab.CLIPBOARD)
            val segLeft = g.capsuleLeft + segW * i
            val segRight = segLeft + segW
            if (selected) {
                canvas.drawRoundRect(
                    segLeft + 3f * density, g.capsuleTop + 3f * density,
                    segRight - 3f * density, g.capsuleTop + g.capsuleH - 3f * density,
                    g.capsuleH / 2f - 3f * density, g.capsuleH / 2f - 3f * density,
                    pressPaint.apply { color = clipSelectedBgColor; alpha = 255 },
                )
            }
            segPaint.color =
                if (selected) clipSelectedTextColor else dimColor(paints.toolbarIconColor)
            segPaint.textSize = 14f * density
            val fm = segPaint.fontMetrics
            canvas.drawText(
                label,
                (segLeft + segRight) / 2f,
                cy - fm.ascent / 2f - fm.descent / 2f,
                segPaint
            )
        }

        // 操作按钮：剪切板分页为「搜索（左槽）+清空」，常用语分页为「新增」和「全部删除」
        val actionCy = height / 2f
        if (clipTab == ClipboardTab.CLIPBOARD) {
            resources.search?.let { d ->
                drawActionIcon(canvas, d, actionSlotCenter(g, 1, density), actionCy, paints, density)
            }
            resources.clear?.let { d ->
                drawActionIcon(canvas, d, actionSlotCenter(g, 0, density), actionCy, paints, density)
            }
        } else {
            addPaint.textSize = 22f * density
            addPaint.color = dimColor(paints.toolbarIconColor)
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
        drawClipClose(canvas, g, height, paints, density)
    }

    private fun drawClipClose(
        canvas: Canvas, g: ClipGeom, height: Int, paints: Paints, density: Float,
    ) {
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

    /** 搜索态：胶囊区改画搜索框（图标+查询文本+尾部光标），动作区 slot0 画清除叉。 */
    private fun drawClipSearchbar(
        canvas: Canvas, g: ClipGeom, height: Int, paints: Paints, density: Float,
    ) {
        val fieldLeft = g.capsuleLeft
        val fieldRight = g.actionLeft - 6f * density
        val fieldTop = g.capsuleTop
        val fieldH = g.capsuleH
        trackPaint.color = paints.toolbarIconColor and 0x1FFFFFFF
        canvas.drawRoundRect(
            fieldLeft, fieldTop, fieldRight, fieldTop + fieldH,
            fieldH / 2f, fieldH / 2f, trackPaint
        )
        val cy = fieldTop + fieldH / 2f
        // 左侧放大镜
        resources.search?.let { d ->
            d.setTint(dimColor(paints.toolbarIconColor))
            val size = 16f * density
            val cx = fieldLeft + 16f * density
            d.setBounds(
                (cx - size / 2f).toInt(), (cy - size / 2f).toInt(),
                (cx + size / 2f).toInt(), (cy + size / 2f).toInt(),
            )
            d.draw(canvas)
        }
        // 查询文本（或 hint）：超宽时保留尾部（最新输入在尾端）
        val textPaint = searchTextPaint
        textPaint.textSize = 14f * density
        val textLeft = fieldLeft + 28f * density
        val textRight = fieldRight - 10f * density
        val availW = (textRight - textLeft).coerceAtLeast(0f)
        val q = clipSearchQuery
        val showing: String
        if (q.isEmpty()) {
            textPaint.color = dimColor(paints.toolbarIconColor)
            showing = clipSearchHint
            val fm = textPaint.fontMetrics
            canvas.drawText(showing, textLeft, cy - fm.ascent / 2f - fm.descent / 2f, textPaint)
        } else {
            textPaint.color = paints.toolbarIconColor
            val key = Triple(q, availW, textPaint.textSize)
            showing = if (key == searchEllipsizeKey) {
                searchEllipsizeValue
            } else {
                var s = q
                while (s.isNotEmpty() && textPaint.measureText(s) > availW) s = s.drop(1)
                searchEllipsizeKey = key
                searchEllipsizeValue = s
                s
            }
            val fm = textPaint.fontMetrics
            val baseline = cy - fm.ascent / 2f - fm.descent / 2f
            canvas.drawText(showing, textLeft, baseline, textPaint)
            // 尾部光标（静态竖线，表达可继续输入）
            val cursorX = textLeft + textPaint.measureText(showing) + 2f * density
            searchLinePaint.color = paints.toolbarIconColor
            searchLinePaint.strokeWidth = 1.6f * density
            val halfTextH = (fm.descent - fm.ascent) / 2f * 0.8f
            canvas.drawLine(cursorX, cy - halfTextH, cursorX, cy + halfTextH, searchLinePaint)
        }
        // 动作区 slot0：清除叉（两条对角线，不依赖字体字形）
        val xCenter = actionSlotCenter(g, 0, density)
        val half = 6f * density
        searchLinePaint.color = dimColor(paints.toolbarIconColor)
        searchLinePaint.strokeWidth = 1.6f * density
        canvas.drawLine(xCenter - half, cy - half, xCenter + half, cy + half, searchLinePaint)
        canvas.drawLine(xCenter - half, cy + half, xCenter + half, cy - half, searchLinePaint)
    }

    /**
     * 编辑态：胶囊区改画编辑框——与搜索框同几何同尾截断逻辑，但不画放大镜，
     * 文本从左内边距起；动作区两槽画「取消 / 保存」文字（slot1=取消、slot0=保存，
     * 与 hitTest 的槽位判定一致：靠右 btnW 内为 slot0）。
     */
    private fun drawClipEditbar(
        canvas: Canvas, g: ClipGeom, height: Int, paints: Paints, density: Float,
    ) {
        val fieldLeft = g.capsuleLeft
        val fieldRight = g.actionLeft - 6f * density
        val fieldTop = g.capsuleTop
        val fieldH = g.capsuleH
        trackPaint.color = paints.toolbarIconColor and 0x1FFFFFFF
        canvas.drawRoundRect(
            fieldLeft, fieldTop, fieldRight, fieldTop + fieldH,
            fieldH / 2f, fieldH / 2f, trackPaint
        )
        val cy = fieldTop + fieldH / 2f
        val textPaint = searchTextPaint
        textPaint.textSize = 14f * density
        val textLeft = fieldLeft + 12f * density
        val textRight = fieldRight - 10f * density
        val availW = (textRight - textLeft).coerceAtLeast(0f)
        val t = clipEditText
        val fm = textPaint.fontMetrics
        val baseline = cy - fm.ascent / 2f - fm.descent / 2f
        if (t.isEmpty()) {
            textPaint.color = dimColor(paints.toolbarIconColor)
            canvas.drawText(clipEditHint, textLeft, baseline, textPaint)
        } else {
            textPaint.color = paints.toolbarIconColor
            var s = t
            while (s.isNotEmpty() && textPaint.measureText(s) > availW) s = s.drop(1)
            canvas.drawText(s, textLeft, baseline, textPaint)
            val cursorX = textLeft + textPaint.measureText(s) + 2f * density
            searchLinePaint.color = paints.toolbarIconColor
            searchLinePaint.strokeWidth = 1.6f * density
            val halfTextH = (fm.descent - fm.ascent) / 2f * 0.8f
            canvas.drawLine(cursorX, cy - halfTextH, cursorX, cy + halfTextH, searchLinePaint)
        }
        drawClipTextAction(canvas, actionSlotCenter(g, 1, density), cy, clipCancelLabel, paints, density)
        drawClipTextAction(canvas, actionSlotCenter(g, 0, density), cy, clipEditSaveLabel, paints, density)
    }

    /** 多选态：胶囊区画已选计数文本，动作区两槽画「取消 / 删除」文字。 */
    private fun drawClipMultibar(
        canvas: Canvas, g: ClipGeom, height: Int, paints: Paints, density: Float,
    ) {
        val cy = height / 2f
        val textPaint = searchTextPaint
        textPaint.textSize = 14f * density
        textPaint.color = paints.toolbarIconColor
        val fm = textPaint.fontMetrics
        canvas.drawText(
            clipMultiLabel, g.capsuleLeft + 4f * density,
            cy - fm.ascent / 2f - fm.descent / 2f, textPaint
        )
        drawClipTextAction(canvas, actionSlotCenter(g, 1, density), cy, clipCancelLabel, paints, density)
        drawClipTextAction(canvas, actionSlotCenter(g, 0, density), cy, clipDeleteLabel, paints, density)
    }

    /** 动作槽文字按钮（编辑/多选栏共用）：居中 14sp 文字。 */
    private fun drawClipTextAction(
        canvas: Canvas, cx: Float, cy: Float, label: String, paints: Paints, density: Float,
    ) {
        if (label.isEmpty()) return
        val p = searchTextPaint
        p.textSize = 14f * density
        p.color = paints.toolbarIconColor
        val w = p.measureText(label)
        val fm = p.fontMetrics
        canvas.drawText(label, cx - w / 2f, cy - fm.ascent / 2f - fm.descent / 2f, p)
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
            if (clipSearchMode) {
                // 搜索态：左箭头=退出搜索，动作区 slot0=清除查询/退出，
                // slot1 与搜索框本身点击无动作，收起键照常
                if (x in menuTouchLeft..menuTouchRight) {
                    setPress(menuCenter)
                    return KawaiiPanel.TouchResult.ToolbarAction(
                        PanelAction.ClipSearchExit, tapX = x, tapY = y
                    )
                }
                if (x in g.actionLeft..g.actionRight) {
                    val btnW = 30f * density
                    if (x < g.actionRight - btnW) return null
                    setPress(actionSlotCenter(g, 0, density))
                    return KawaiiPanel.TouchResult.ToolbarAction(
                        PanelAction.ClipSearchClear, tapX = x, tapY = y
                    )
                }
                if (x in closeTouchLeft..closeTouchRight) {
                    setPress((g.closeLeft + g.closeRight) / 2f)
                    return KawaiiPanel.TouchResult.ToolbarAction(
                        PanelAction.CloseKeyboard, tapX = x, tapY = y
                    )
                }
                return null
            }
            if (clipEditMode) {
                // 编辑态：左箭头=取消编辑，动作区 slot1=取消、slot0=保存，
                // 编辑框本身点击无动作，收起键照常
                if (x in menuTouchLeft..menuTouchRight) {
                    setPress(menuCenter)
                    return KawaiiPanel.TouchResult.ToolbarAction(
                        PanelAction.ClipEditCancel, tapX = x, tapY = y
                    )
                }
                if (x in g.actionLeft..g.actionRight) {
                    val btnW = 30f * density
                    val slot0 = x >= g.actionRight - btnW
                    setPress(actionSlotCenter(g, if (slot0) 0 else 1, density))
                    return KawaiiPanel.TouchResult.ToolbarAction(
                        if (slot0) PanelAction.ClipEditSave else PanelAction.ClipEditCancel,
                        tapX = x, tapY = y
                    )
                }
                if (x in closeTouchLeft..closeTouchRight) {
                    setPress((g.closeLeft + g.closeRight) / 2f)
                    return KawaiiPanel.TouchResult.ToolbarAction(
                        PanelAction.CloseKeyboard, tapX = x, tapY = y
                    )
                }
                return null
            }
            if (clipMultiMode) {
                // 多选态：左箭头=退出多选，动作区 slot1=退出、slot0=删除所选，
                // 胶囊区点击无动作，收起键照常
                if (x in menuTouchLeft..menuTouchRight) {
                    setPress(menuCenter)
                    return KawaiiPanel.TouchResult.ToolbarAction(
                        PanelAction.ClipMultiExit, tapX = x, tapY = y
                    )
                }
                if (x in g.actionLeft..g.actionRight) {
                    val btnW = 30f * density
                    val slot0 = x >= g.actionRight - btnW
                    setPress(actionSlotCenter(g, if (slot0) 0 else 1, density))
                    return KawaiiPanel.TouchResult.ToolbarAction(
                        if (slot0) PanelAction.ClipMultiDelete else PanelAction.ClipMultiExit,
                        tapX = x, tapY = y
                    )
                }
                if (x in closeTouchLeft..closeTouchRight) {
                    setPress((g.closeLeft + g.closeRight) / 2f)
                    return KawaiiPanel.TouchResult.ToolbarAction(
                        PanelAction.CloseKeyboard, tapX = x, tapY = y
                    )
                }
                return null
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
                    val slot = if (x > g.actionRight - btnW) 0 else 1
                    setPress(actionSlotCenter(g, slot, density))
                    if (slot == 0) PanelAction.ClearClipboard else PanelAction.ClipSearch
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
