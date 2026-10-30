package com.jobeen.ime.input.panel.state

import android.graphics.drawable.Drawable
import com.jobeen.ime.data.manager.CandidateManager
import com.jobeen.ime.data.manager.KeyboardManager
import com.jobeen.ime.data.keyboard.theme.KeyboardColors
import com.jobeen.ime.R
import com.jobeen.ime.engine.data.EngineMessage
import com.jobeen.ime.input.panel.ComposingRenderer
import com.jobeen.ime.input.panel.component.ClipboardTab
import com.jobeen.ime.input.panel.toolbar.SkinToolbarApplier
import com.jobeen.ime.input.panel.toolbar.ToolbarConfig
import com.jobeen.ime.input.panel.toolbar.ToolbarRenderer
import com.jobeen.ime.input.panel.toolbar.ToolbarRendererResources
import com.jobeen.ime.skin.diag.DiagLog
import com.jobeen.ime.skin.icon.MaterialSymbols
import com.jobeen.ime.skin.runtime.SkinSnapshotProvider

private fun StateRenderContext.idleHorizontalPaddingDp(): Float =
    KeyboardManager.Keyboard.Padding.getHorizontalDp(context).toFloat()

private fun StateRenderContext.buildToolbarRenderer(
    showArrow: Boolean = false,
    textEditingMode: Boolean = false,
    copyText: String? = null,
    clipMode: Boolean = false,
    clipTab: ClipboardTab = ClipboardTab.CLIPBOARD,
    clipLabelClipboard: String = "",
    clipLabelPhrase: String = "",
    clipSelectedTextColor: Int = 0,
    clipSelectedBgColor: Int = 0,
): ToolbarRenderer {
    // 工具栏构成：皮肤快照优先（SkinToolbarApplier 抉择，中央区
    // 未定义时用出厂默认）；态渲染器每次新建，在此读最新
    // 快照即生效。
    val skinApplied = SkinToolbarApplier.resolveFor(context)
    return ToolbarRenderer(
        idleResources,
        idleHorizontalPaddingDp(),
        centerActions = ToolbarConfig.DEFAULT_ACTIONS,
        centerIconOverrides = emptyMap(),
        skinToolbar = skinApplied?.toolbar,
        skinToolbarIcons = skinApplied?.icons ?: emptyMap(),
        // Symbol 名解析接线（参考项）：中央按钮 icon 不是
        // 包内资产 id 时查 Material Symbols 注册表。
        symbolIconProvider = { name ->
            MaterialSymbols.drawable(
                context, name,
                (24f * context.resources.displayMetrics.density).toInt().coerceAtLeast(1),
            )
        },
    ).apply {
        this.showArrow = showArrow
        this.textEditingMode = textEditingMode
        this.copyText = copyText
        this.clipMode = clipMode
        this.clipTab = clipTab
        this.clipLabelClipboard = clipLabelClipboard
        this.clipLabelPhrase = clipLabelPhrase
        this.clipSelectedTextColor = clipSelectedTextColor
        this.clipSelectedBgColor = clipSelectedBgColor
        this.recording = this@buildToolbarRenderer.recording
    }
}

/**
 * 组字/预测态右缘按钮图标（与闲置态最右槽口径一致）：
 * 皮肤收起资产 > 皮肤收起符号名 > 面板传入内置图；均缺失回落内置图。
 * 皮肤展开图标在组字/预测态无左缘槽，不适用。符号未知名记一条
 * WARN（只记动作与符号名），调用方再回落内置图，永不崩溃。
 * 态渲染器每次新建时读最新快照，皮肤切换后下次进组字/预测态即生效。
 */
private fun StateRenderContext.composingEdgeDrawable(): Drawable? {
    val applied = SkinToolbarApplier.resolveFor(context)
    if (applied != null) {
        applied.toolbar.collapseIcon?.assetId?.let { applied.icons[it] }?.let { return it }
        val symbol = applied.toolbar.collapseSymbol
        if (symbol != null) {
            val drawable = MaterialSymbols.drawable(
                context, symbol,
                (24f * context.resources.displayMetrics.density).toInt().coerceAtLeast(1),
            )
            if (drawable != null) return drawable
            DiagLog.w(
                "toolbar", "toolbar_icon_unresolved",
                mapOf("actionId" to "collapse", "icon" to symbol),
            )
        }
    }
    return expandDrawable
}

/** 空闲态：仅渲染菜单栏，无展开功能栏。 */
class IdleStateRender(
    private val ctx: StateRenderContext,
) : IStateRender {
    override fun createToolbarRenderer(): ToolbarRenderer = ctx.buildToolbarRenderer()

    override fun showExpand(isExpanded: Boolean) = Unit

    override fun hideExpand() = Unit
}

/** 组字态：菜单栏用候选药丸渲染，展开态展示候选网格。 */
class ComposingStateRender(
    private val ctx: StateRenderContext,
    // var：同态新批次复用本对象时原地更新，展开时 showExpand 读到的才是最新批次
    var candidates: List<EngineMessage.Candidate>,
) : IStateRender {

    override fun createToolbarRenderer(): ComposingRenderer {
        val settings = ctx.candidateDisplaySettings()
        val snapshot = SkinSnapshotProvider.peek()
        return ComposingRenderer(
            candidates,
            ctx.composingEdgeDrawable(),
            ctx.idleHorizontalPaddingDp(),
            showIndex = settings.showIndex,
            showComment = settings.showComment,
            candidateBorder = settings.candidateBorder,
            expandBorder = settings.expandBorder,
            candidateRadiusDp = snapshot?.candidateRadiusDp ?: 6f,
        ).also { it.recording = ctx.recording }
    }

    override fun showExpand(isExpanded: Boolean) {
        if (isExpanded) ctx.candidateGrid.show(candidates)
    }

    override fun hideExpand() {
        ctx.candidateGrid.hide()
    }
}

/** 预测态：与组字态共用候选渲染器，但进入时强制收起展开栏。 */
class PredictionStateRender(
    private val ctx: StateRenderContext,
    var candidates: List<EngineMessage.Candidate>,
) : IStateRender {
    override fun createToolbarRenderer(): ComposingRenderer {
        val settings = ctx.candidateDisplaySettings()
        val snapshot = SkinSnapshotProvider.peek()
        return ComposingRenderer(
            candidates,
            ctx.composingEdgeDrawable(),
            ctx.idleHorizontalPaddingDp(),
            showIndex = settings.showIndex,
            showComment = settings.showComment,
            candidateBorder = settings.candidateBorder,
            expandBorder = settings.expandBorder,
            candidateRadiusDp = snapshot?.candidateRadiusDp ?: 6f,
            edgeBgColor = snapshot?.predictionCloseBg,
            edgeRadiusDp = snapshot?.predictionCloseRadiusDp,
        ).also { it.recording = ctx.recording }
    }

    override fun showExpand(isExpanded: Boolean) {
        if (isExpanded) ctx.candidateGrid.show(candidates)
    }

    override fun hideExpand() {
        ctx.candidateGrid.hide()
    }
}

/** 菜单态：菜单栏显示返回箭头，展开栏展示功能菜单网格。 */
class MenuStateRender(
    private val ctx: StateRenderContext,
) : IStateRender {
    override fun createToolbarRenderer(): ToolbarRenderer =
        ctx.buildToolbarRenderer(
            showArrow = true,
        )

    override fun showExpand(isExpanded: Boolean) {
        ctx.menuGridView.show()
    }

    override fun hideExpand() {
        ctx.menuGridView.hide()
    }
}

/** 剪切板态：工具栏显示分页胶囊，展开栏展示剪切板/常用语列表。 */
class ClipboardStateRender(
    private val ctx: StateRenderContext,
    private val clipTab: ClipboardTab = ClipboardTab.CLIPBOARD,
) : IStateRender {
    override fun createToolbarRenderer(): ToolbarRenderer =
        ctx.buildToolbarRenderer(
            showArrow = true,
            clipMode = true,
            clipTab = clipTab,
            clipLabelClipboard = ctx.context.getString(R.string.clipboard_tab),
            clipLabelPhrase = ctx.context.getString(R.string.phrase_tab),
            clipSelectedTextColor = KeyboardColors.resolve(ctx.context).accentKeyText,
            clipSelectedBgColor = KeyboardColors.resolve(ctx.context).accentKeyBackground,
        )

    override fun showExpand(isExpanded: Boolean) {
        ctx.clipboardView.clipTab = clipTab
        // show() 内部已触发一次加载，不要再叠加 refresh()（否则每次打开固定加载两遍）
        ctx.clipboardView.show()
    }

    override fun hideExpand() {
        ctx.clipboardView.hide()
    }
}

/** 文本编辑态：菜单栏进入编辑模式，展开栏展示文本编辑视图。 */
class TextEditingStateRender(
    private val ctx: StateRenderContext,
) : IStateRender {
    override fun createToolbarRenderer(): ToolbarRenderer =
        ctx.buildToolbarRenderer(textEditingMode = true)

    override fun showExpand(isExpanded: Boolean) {
        ctx.textEditingView.show()
    }

    override fun hideExpand() {
        ctx.textEditingView.hide()
    }
}

/** 复制提示态：菜单栏显示复制内容药丸，无展开功能栏。 */
class CopyStateRender(
    private val ctx: StateRenderContext,
    private val copyText: String?,
) : IStateRender {
    override fun createToolbarRenderer(): ToolbarRenderer =
        ctx.buildToolbarRenderer(copyText = copyText)

    override fun showExpand(isExpanded: Boolean) = Unit

    override fun hideExpand() = Unit
}
