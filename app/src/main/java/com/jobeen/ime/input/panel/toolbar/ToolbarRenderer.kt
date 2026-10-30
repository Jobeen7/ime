package com.jobeen.ime.input.panel.toolbar

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.text.TextPaint
import android.text.TextUtils
import androidx.core.graphics.withRotation
import com.jobeen.ime.input.panel.PanelAction
import com.jobeen.ime.input.panel.IRenderer
import com.jobeen.ime.input.panel.KawaiiPanel
import com.jobeen.ime.input.panel.Paints
import com.jobeen.ime.input.panel.component.ClipboardTab
import com.jobeen.ime.skin.icon.IconAlias
import com.jobeen.ime.skin.icon.MaterialSymbols
import com.jobeen.ime.skin.model.SkinToolbarSnapshot

/**
 * 自定义钮按压态：涟漪动画进行中（alpha>0 且半径未到峰值）且按压点落在该槽内。
 * 动画结束（半径到峰值）、取消（alpha 清零）后即不再是按压态，按下颜色随之收回；
 * 未设按下颜色时调用方回落背景色（旧行为不变）。
 */
internal fun customPressedFaceActive(
    pressAlpha: Int,
    pressRadius: Float,
    pressRadiusMax: Float,
    pressCx: Float,
    slotLeft: Float,
    slotRight: Float,
): Boolean = pressAlpha > 0 && pressRadiusMax > 0f && pressRadius < pressRadiusMax &&
    pressCx in slotLeft..slotRight

class ToolbarRenderer(
    private val resources: ToolbarRendererResources,
    var horizontalPaddingDp: Float = 0f,
    var centerHorizontalPaddingDp: Float = 12f,
    var iconScale: Float = 0.94f,
    centerActions: List<PanelAction> = ToolbarConfig.DEFAULT_ACTIONS,
    centerIconOverrides: Map<String, String> = emptyMap(),
    /**
     * 皮肤工具栏构成（2026-10-27 工具栏进皮肤）：快照承载构成，
     * [skinToolbarIcons] 为按资产 id 解码好的图标（只含成功项，
     * 查无 = 该图标回退内置）。null = 无皮肤构成，用出厂默认。
     * 构造点统一经 SkinToolbarApplier 解析（StateRenders /
     * KawaiiPanelView），此处不读快照、不解码。
     */
    skinToolbar: SkinToolbarSnapshot? = null,
    skinToolbarIcons: Map<String, Drawable?> = emptyMap(),
    /**
     * Symbol 名解析接线（参考项）：工具栏按钮 icon 不是包内
     * 资产 id 时查 Material Symbols 注册表。由构造点注入
     * （MaterialSymbols.drawable）；null = 无符号源，按旧口径绘制空白。
     * 皮肤工具栏构成字段（按钮清单/展开收起图标）由工具栏分流负责，
     * 本类只负责“非资产 id → 注册表”这一段接线。
     */
    private val symbolIconProvider: ((String) -> Drawable?)? = null,
) : IRenderer {

    var textEditingMode: Boolean = false
    var copyText: String? = null
    var clipMode: Boolean = false
    // 剪贴板搜索态（仅 CLIPBOARD 分页）：true 时胶囊区改画搜索框
    var clipSearchMode: Boolean = false
    var clipSearchQuery: String = ""
    var clipSearchHint: String = ""
    // 剪贴板条目编辑态：true 时胶囊区改画提示行（全文编辑在列表区），
    // 动作区两槽改为「保存 / 取消」文字按钮
    var clipEditMode: Boolean = false
    var clipEditHint: String = ""
    var clipEditSaveLabel: String = ""
    var clipCancelLabel: String = ""
    // 剪贴板多选态：true 时胶囊区改画已选计数，动作区两槽为「删除 / 取消」
    var clipMultiMode: Boolean = false
    var clipMultiLabel: String = ""
    var clipDeleteLabel: String = ""
    // 排序态：胶囊区改画提示文字，动作区 slot1 为「完成」
    var clipReorderMode: Boolean = false
    var clipReorderLabel: String = ""
    var clipDoneLabel: String = ""
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

    // 中央区按钮来源（优先级见 ToolbarSkinResolution）：
    // 皮肤定义中央区 → 皮肤清单 + 皮肤图标（出厂默认全忽略）；
    // 否则出厂默认。长驻实例经
    // updateCenterActions（回落出厂默认）或 updateSkinToolbar
    // （应用皮肤）热更新；边缘图标在绘制期从皮肤字段直读。
    private var fallbackCenterActions: List<PanelAction> = centerActions
    private var fallbackCenterIconOverrides: Map<String, String> = centerIconOverrides
    private var skinToolbarSnapshot: SkinToolbarSnapshot? = skinToolbar
    private var skinIconDrawables: Map<String, Drawable?> = skinToolbarIcons

    private var centerButtons: List<ImageButton> = buildCenterButtons()

    /**
     * 用户覆盖中央区（工具栏编辑轮）：非空时整段替换 [centerButtons]
     * （池钮与自定义钮按用户列顺序混排）。池钮复用 [ImageButton]
     * 绘制；自定义钮按定义绘制文字／表情／图片＋可选背景面。
     * 槽位宽：自定义钮取 widthDp，池钮均分剩余（剩余 ≤0 时池钮
     * 槽宽为 0、绘制与命中皆跳过）。与 [userCenterSlots] 同一数学，
     * 绘制与命中不得各算一套。
     */
    sealed interface UserCenterItem {
        data class Pool(val action: PanelAction) : UserCenterItem
        data class Custom(val def: ToolbarCustomButton, val bitmap: Bitmap?) : UserCenterItem
    }

    private var userCenter: List<UserCenterItem>? = null

    /** 热更新用户覆盖中央区（null＝清除，回落皮肤／默认；旧自定义位图随之回收）。 */
    fun updateUserCenter(items: List<UserCenterItem>?) {
        userCenter?.forEach { item ->
            (item as? UserCenterItem.Custom)?.bitmap?.let { bitmap ->
                if (!bitmap.isRecycled) bitmap.recycle()
            }
        }
        userCenter = items
    }

    fun clearUserCenter() {
        userCenter = null
    }

    /** 中央区几何（与 draw／hitTest 同口径）。 */
    private fun centerArea(width: Int, density: Float): Pair<Float, Float> {
        val hPad = (horizontalPaddingDp + 4) * density
        val fixedW = 32f * density
        val centerPad = centerHorizontalPaddingDp * density
        val left = hPad + fixedW + centerPad
        val w = width - left - hPad - fixedW - centerPad
        return left to w
    }

    /** 用户中央区槽位：项＋左右界（池钮槽宽为 0 时命中跳过）。 */
    private data class UserCenterSlot(val item: UserCenterItem, val left: Float, val right: Float)

    private fun userCenterSlots(width: Int, density: Float): List<UserCenterSlot>? {
        val items = userCenter ?: return null
        if (items.isEmpty()) return emptyList()
        val (areaLeft, areaW) = centerArea(width, density)
        var customTotal = 0f
        for (item in items) {
            if (item is UserCenterItem.Custom) customTotal += item.def.widthDp * density
        }
        val poolCount = items.count { it is UserCenterItem.Pool }
        val poolW = if (poolCount > 0) {
            ((areaW - customTotal) / poolCount).coerceAtLeast(0f)
        } else 0f
        val slots = mutableListOf<UserCenterSlot>()
        var x = areaLeft
        for (item in items) {
            val w = if (item is UserCenterItem.Custom) item.def.widthDp * density else poolW
            slots += UserCenterSlot(item, x, x + w)
            x += w
        }
        return slots
    }

    /** 纯探针（View 手势机 DOWN 用，无副作用，不改按压态）。 */
    fun userCenterHit(x: Float, width: Int, density: Float): UserCenterItem? {
        if (clipMode || copyText != null) return null
        val slots = userCenterSlots(width, density) ?: return null
        return slots.firstOrNull { x in it.left..it.right && it.right > it.left }?.item
    }

    private val customTextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
    }

    private fun drawUserCenter(
        canvas: Canvas, height: Int, width: Int, paints: Paints, density: Float,
    ) {
        val slots = userCenterSlots(width, density) ?: return
        for (slot in slots) {
            if (slot.right <= slot.left) continue
            val cx = (slot.left + slot.right) / 2f
            val cy = height / 2f
            when (val item = slot.item) {
                is UserCenterItem.Pool -> {
                    val savedColor = paints.toolbarIconColor
                    val disabled = (textEditingMode && item.action in TEXT_EDIT_DISABLED) || recording
                    if (disabled) {
                        paints.toolbarIconColor = (savedColor and 0x00FFFFFF) or 0x62000000.toInt()
                    }
                    ImageButton(
                        iconFor(item.action, emptyMap()), item.action, iconScale = iconScale,
                    ).draw(canvas, cx, cy, paints, density)
                    if (disabled) {
                        paints.toolbarIconColor = savedColor
                    }
                }
                is UserCenterItem.Custom -> {
                    val def = item.def
                    val pressed = customPressedFaceActive(
                        pressAlpha, pressRadius, pressRadiusMax,
                        pressCx, slot.left, slot.right,
                    )
                    val faceColor = if (pressed) {
                        def.pressedArgb ?: def.backgroundArgb
                    } else {
                        def.backgroundArgb
                    }
                    val inset = 2f * density
                    val faceTop = cy - 16f * density
                    val faceBottom = cy + 16f * density
                    if (faceColor != null) {
                        val facePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                            color = dimColor(faceColor)
                        }
                        canvas.drawRoundRect(
                            slot.left + inset, faceTop,
                            slot.right - inset, faceBottom,
                            8f * density, 8f * density, facePaint,
                        )
                    }
                    if (def.borderWidthDp > 0f && def.borderArgb != null) {
                        val stroke = def.borderWidthDp * density
                        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                            color = dimColor(def.borderArgb)
                            style = Paint.Style.STROKE
                            strokeWidth = stroke
                        }
                        canvas.drawRoundRect(
                            slot.left + inset + stroke / 2f, faceTop + stroke / 2f,
                            slot.right - inset - stroke / 2f, faceBottom - stroke / 2f,
                            8f * density, 8f * density, borderPaint,
                        )
                    }
                    val bitmap = item.bitmap
                    if (def.kind == CustomDisplayKind.IMAGE && bitmap != null) {
                        val box = def.iconSizeDp * density
                        val scale = minOf(box / bitmap.width, box / bitmap.height)
                        val w = bitmap.width * scale
                        val h = bitmap.height * scale
                        val dst = RectF(cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f)
                        canvas.drawBitmap(bitmap, null, dst, null)
                    } else {
                        val text = when (def.kind) {
                            CustomDisplayKind.IMAGE -> def.display
                            else -> def.display
                        }
                        if (text.isNotEmpty()) {
                            customTextPaint.apply {
                                color = dimColor(paints.toolbarIconColor)
                                // 字号：自定义字号优先，否则跟随图标大小（旧行为）；
                                // 与图标大小同口径（乘 density，不另乘字体缩放）。
                                textSize = def.effectiveTextSizeDp() * density
                            }
                            val avail = slot.right - slot.left - 4f * density
                            val fitted = if (def.kind == CustomDisplayKind.EMOJI) {
                                // 表情不截断：超宽则等比缩小字号（下限 8dp），不断字形簇。
                                var size = customTextPaint.textSize
                                while (size > 8f * density &&
                                    customTextPaint.measureText(text) > avail
                                ) {
                                    size *= 0.9f
                                    customTextPaint.textSize = size
                                }
                                text
                            } else {
                                TextUtils.ellipsize(
                                    text, customTextPaint, avail,
                                    TextUtils.TruncateAt.END,
                                ).toString()
                            }
                            val textY = cy - (customTextPaint.descent() + customTextPaint.ascent()) / 2f
                            canvas.drawText(fitted, cx, textY, customTextPaint)
                        }
                    }
                }
            }
        }
    }

    private fun buildCenterButtons(): List<ImageButton> {
        val resolved = ToolbarSkinResolution.resolve(skinToolbarSnapshot, fallbackCenterActions)
        // 接线轮 R1：锁定通知按按钮 id 命中工具栏键时替换点按动作
        // （图标保持原钮身份，不跟新动作走；无映射的命令不替换）。
        val latched = com.jobeen.ime.skin.runtime.SkinRuntimeState.latchedNotifications()
        return resolved.centerActions.map { action ->
            val actionId = ToolbarConfig.POOL.find { it.action == action }?.id
            val effective = actionId?.let { latched[it]?.action }
                ?.let { ToolbarNotificationAction.panelActionFor(it) } ?: action
            ImageButton(iconForResolved(action, resolved), effective, iconScale = iconScale)
        }
    }

    /**
     * 皮肤中央区一钮的图标：皮肤资产解码成功用皮肤图；资产缺失/
     * 解码失败（图标表查无）回退该钮内置图标。
     * 符号名（resolved.centerIconSymbols）暂不解析：本分流无
     * 符号注册表，符号钮同样回退内置图标；图标主题分流在此
     * 接线（注册表命中用符号字形，未知名记日志并回退内置）。
     */
    private fun iconForResolved(
        action: PanelAction,
        resolved: ToolbarSkinResolution.Resolved,
    ): Drawable? {
        val actionId = ToolbarConfig.POOL.find { it.action == action }?.id ?: return null
        val assetId = resolved.centerIconAssets[actionId]
        if (assetId != null) {
            skinIconDrawables[assetId]?.let { return it }
            return drawableForIconId(actionId)
        }
        // 集成接线（工具栏符号闭合）：皮肤中央区符号名经注册表解析；
        // 命中用符号字形，未知名记日志并回落该钮内置图标，永不崩溃。
        val symbol = resolved.centerIconSymbols[actionId]
        if (symbol != null) {
            val drawable = symbolIconProvider?.invoke(symbol)
            if (drawable != null) return drawable
            com.jobeen.ime.skin.diag.DiagLog.w(
                "toolbar", "toolbar_icon_unresolved",
                mapOf("actionId" to actionId, "icon" to symbol),
            )
            return drawableForIconId(actionId)
        }
        return if (resolved.hasSkinCenter) {
            drawableForIconId(actionId)
        } else {
            iconFor(action, fallbackCenterIconOverrides)
        }
    }

    /**
     * 最左侧展开图标（皮肤资产解码成功时用皮肤图，否则内置菜单/
     * 返回；符号名 expandSymbol 暂不解析，注册表分流在此接线）。
     */
    private fun skinExpandDrawable(): Drawable? =
        skinToolbarSnapshot?.expandIcon?.assetId?.let { skinIconDrawables[it] }

    /**
     * 最右侧收起图标（皮肤资产解码成功时用皮肤图，否则内置收起图；
     * 符号名 collapseSymbol 暂不解析，注册表分流在此接线）。
     */
    private fun skinCollapseDrawable(): Drawable? =
        skinToolbarSnapshot?.collapseIcon?.assetId?.let { skinIconDrawables[it] }

    /**
     * 集成接线：皮肤边缘符号名（资产优先于符号，见
     * ToolbarSkinResolution）。命中用符号字形；未知名记日志并返回
     * null（调用方再回落结构覆盖/内置图），永不崩溃。
     */
    private fun skinSymbolDrawable(symbol: String?, actionId: String): Drawable? {
        if (symbol == null) return null
        val canonical = IconAlias.resolve(symbol, MaterialSymbols.NAMES.keys) ?: return null
        val drawable = symbolIconProvider?.invoke(canonical)
        if (drawable != null) return drawable
        com.jobeen.ime.skin.diag.DiagLog.w(
            "toolbar", "toolbar_icon_unresolved",
            mapOf("actionId" to actionId, "icon" to symbol),
        )
        return null
    }

    private fun skinExpandSymbolDrawable(): Drawable? =
        skinSymbolDrawable(skinToolbarSnapshot?.expandSymbol, "expand")

    private fun skinCollapseSymbolDrawable(): Drawable? =
        skinSymbolDrawable(skinToolbarSnapshot?.collapseSymbol, "collapse")

    /**
     * 热更新中央区按钮（长驻渲染器刷新用；面板态渲染器
     * 每次新建、本就读最新默认）。直接调用 = 明确回落出厂默认，
     * 会清除皮肤态；皮肤刷新走 [updateSkinToolbar]（经
     * SkinToolbarApplier 抉择，见 KawaiiPanelView.refreshTheme）。
     */
    fun updateCenterActions(
        actions: List<PanelAction>,
        iconOverrides: Map<String, String> = emptyMap(),
    ) {
        fallbackCenterActions = actions
        fallbackCenterIconOverrides = iconOverrides
        skinToolbarSnapshot = null
        skinIconDrawables = emptyMap()
        centerButtons = buildCenterButtons()
    }

    /** 热更新皮肤工具栏构成（快照 + 已解码图标；中央区按抉择重建）。 */
    fun updateSkinToolbar(
        snapshot: SkinToolbarSnapshot,
        icons: Map<String, Drawable?>,
    ) {
        skinToolbarSnapshot = snapshot
        skinIconDrawables = icons
        centerButtons = buildCenterButtons()
    }

    /**
     * 按钮图标：先查该动作的图标覆盖（换图标），无覆盖则用动作
     * 自身图标。图标 id 与动作 id 共用命名空间，结构图标
     * （菜单/返回/收起/删除/搜索）同理。
     */
    private fun iconFor(action: PanelAction, iconOverrides: Map<String, String>): Drawable? {
        val actionId = ToolbarConfig.POOL.find { it.action == action }?.id ?: return null
        val iconId = iconOverrides[actionId] ?: actionId
        val drawable = drawableForIconId(iconId)
        if (drawable == null && iconId != actionId) {
            // 覆盖值既非资产 id 又不在符号注册表内：空白按钮（与缺
            // 图标既有行为一致）+ 一条日志（只记动作与图标名元数据；
            // 构造/热更新期调用，非绘制热路径）。
            com.jobeen.ime.skin.diag.DiagLog.w(
                "toolbar", "toolbar_icon_unresolved",
                mapOf("actionId" to actionId, "icon" to iconId),
            )
        }
        return drawable
    }

    private fun drawableForIconId(iconId: String): Drawable? = when (iconId) {
        "emoji" -> resources.emoji
        "select_all" -> resources.selectAll
        "copy" -> resources.copy
        "paste" -> resources.paste
        "clipboard" -> resources.clipboard
        "symbol" -> resources.symbol
        "phrases" -> resources.phrases
        "voice" -> resources.voice
        "cursor" -> resources.cursor
        "undo" -> resources.undo
        "redo" -> resources.redo
        "palette" -> resources.palette
        "menu" -> resources.menu
        "arrow" -> resources.arrow
        "expand" -> resources.expand
        "clear" -> resources.clear
        "search" -> resources.search
        // 非资产 id → Material Symbols 注册表（先经别名层规范化）；
        // 未知名返回 null。
        else -> symbolIconProvider?.invoke(
            IconAlias.resolve(iconId, MaterialSymbols.NAMES.keys) ?: iconId,
        )
    }

    /** 展开钮当前图标：皮肤边缘图标走 skinCollapse 分流（见下），此处只回落内置图。 */
    private fun expandDrawable(): Drawable? = resources.expand

    companion object {
        /**
         * 文本编辑态下禁用的中央按钮：与顺序无关、按动作判定
         * （旧实现按下标 i>=2 写死，中央区可排序后必然错位）。
         * 集合与改版前一致：复制 / 粘贴 / 剪贴板。
         */
        val TEXT_EDIT_DISABLED: Set<PanelAction> = setOf(
            PanelAction.Copy, PanelAction.Paste, PanelAction.Clipboard,
        )
    }

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

        // 最左侧槽：皮肤展开图标优先（解码失败则回落内置菜单/返回）；
        // 剪贴板态（drawClipbar）不走此分支，仍用内置返回箭头。
        run {
            val d = skinExpandDrawable() ?: skinExpandSymbolDrawable()
                ?: if (textEditingMode || copyText != null || showArrow) resources.arrow else resources.menu
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
        } else if (userCenter != null) {
            drawUserCenter(canvas, height, width, paints, density)
        } else if (centerButtons.isNotEmpty()) {
            val otherW = centerAreaW / centerButtons.size
            for ((i, btn) in centerButtons.withIndex()) {
                val savedColor = paints.toolbarIconColor
                val disabled = (textEditingMode && btn.action in TEXT_EDIT_DISABLED) || recording
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
        // 最右侧槽：皮肤收起图标优先（资产 > 符号名 > 结构覆盖），
        // 均缺失回落内置收起图；展开态仍旋转 180°（与内置图行为一致）。
        val expandIcon = skinCollapseDrawable() ?: skinCollapseSymbolDrawable() ?: expandDrawable()
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

        if (clipReorderMode) {
            drawClipReorderbar(canvas, g, height, paints, density)
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
     * 编辑态：胶囊区只画一行提示（条目全文在上方列表区多行编辑），
     * 动作区两槽画「取消 / 保存」文字（slot1=取消、slot0=保存，
     * 与 hitTest 的槽位判定一致：靠右 btnW 内为 slot0）。
     */
    private fun drawClipEditbar(
        canvas: Canvas, g: ClipGeom, height: Int, paints: Paints, density: Float,
    ) {
        val cy = height / 2f
        val textPaint = searchTextPaint
        textPaint.textSize = 14f * density
        textPaint.color = dimColor(paints.toolbarIconColor)
        val fm = textPaint.fontMetrics
        canvas.drawText(
            clipEditHint, g.capsuleLeft + 4f * density,
            cy - fm.ascent / 2f - fm.descent / 2f, textPaint
        )
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

    private fun drawClipReorderbar(
        canvas: Canvas, g: ClipGeom, height: Int, paints: Paints, density: Float,
    ) {
        val cy = height / 2f
        val textPaint = searchTextPaint
        textPaint.textSize = 14f * density
        textPaint.color = paints.toolbarIconColor
        val fm = textPaint.fontMetrics
        canvas.drawText(
            clipReorderLabel, g.capsuleLeft + 4f * density,
            cy - fm.ascent / 2f - fm.descent / 2f, textPaint
        )
        drawClipTextAction(canvas, actionSlotCenter(g, 1, density), cy, clipDoneLabel, paints, density)
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
            if (clipReorderMode) {
                // 排序态：左箭头与动作区（完成）都退出排序，收起键照常
                if (x in menuTouchLeft..menuTouchRight) {
                    setPress(menuCenter)
                    return KawaiiPanel.TouchResult.ToolbarAction(
                        PanelAction.ClipReorderExit, tapX = x, tapY = y
                    )
                }
                if (x in g.actionLeft..g.actionRight) {
                    setPress(actionSlotCenter(g, 1, density))
                    return KawaiiPanel.TouchResult.ToolbarAction(
                        PanelAction.ClipReorderExit, tapX = x, tapY = y
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
                // 用户覆盖中央区：池钮走既有动作路；自定义钮返回 null
                // （手势归 View 的自定义手势机，命中探针走 userCenterHit）。
                if (userCenter != null) {
                    val slots = userCenterSlots(width, density) ?: return null
                    val slot = slots.firstOrNull {
                        x in it.left..it.right && it.right > it.left
                    } ?: return null
                    val pool = slot.item as? UserCenterItem.Pool ?: return null
                    if (textEditingMode && pool.action in TEXT_EDIT_DISABLED) {
                        return null
                    }
                    pressCx = (slot.left + slot.right) / 2f
                    pressCy = height / 2f
                    pressRadiusMax = height * 0.55f
                    pressRadius = 0f
                    return KawaiiPanel.TouchResult.ToolbarAction(
                        pool.action,
                        tapX = x,
                        tapY = y,
                    )
                }
                val index = ((x - centerAreaLeft) / otherW).toInt()
                    .takeIf { it in centerButtons.indices } ?: return null
                if (textEditingMode && centerButtons[index].action in TEXT_EDIT_DISABLED) return null
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
