package com.jobeen.ime.input.panel.state

import android.graphics.drawable.Drawable
import com.jobeen.ime.data.manager.CandidateManager
import com.jobeen.ime.data.manager.KeyboardManager
import com.jobeen.ime.data.keyboard.theme.KeyboardColors
import com.jobeen.ime.R
import com.jobeen.ime.engine.data.EngineMessage
import com.jobeen.ime.input.panel.ComposingRenderer
import com.jobeen.ime.input.panel.component.ClipboardTab
import com.jobeen.ime.input.panel.toolbar.ToolbarConfig
import com.jobeen.ime.input.panel.toolbar.ToolbarRenderer
import com.jobeen.ime.input.panel.toolbar.ToolbarRendererResources
import com.jobeen.ime.input.panel.toolbar.ToolbarToggleState
import com.jobeen.ime.input.panel.toolbar.voiceInputEnabled
import com.jobeen.ime.skin.icon.MaterialSymbols

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
    return ToolbarRenderer(
        idleResources,
        idleHorizontalPaddingDp(),
        centerActions = ToolbarConfig.DEFAULT_ACTIONS,
        centerIconOverrides = emptyMap(),
        // Symbol 名解析接线（参考项）：中央按钮 icon 不是
        // 包内资产 id 时查 Material Symbols 注册表。
        symbolIconProvider = { name ->
            MaterialSymbols.drawable(
                context, name,
                (24f * context.resources.displayMetrics.density).toInt().coerceAtLeast(1),
            )
        },
        // 开关启用判定（E6）：与菜单网格同口径。
        toggleActive = { action -> ToolbarToggleState.isActive(context, action) },
        // 语音入口可见判定（H2-13）：总开关关时工具栏语音键隐藏。
        voiceEnabled = { voiceInputEnabled(context) },
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
 * 皮肤 v3 死壳删除后恒用面板传入的内置图。
 */
private fun StateRenderContext.composingEdgeDrawable(): Drawable? {
    // 皮肤 v3 死壳删除后快照恒为 null，恒回落面板传入的内置图。
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
    var highlightedIndex: Int = 0,
    // 候选帧代次（WO38 E1-10/1）：与 candidates 同批原地更新；
    // showExpand 凭它调网格代次门，迟到的旧 render 不再覆盖新表
    var revision: Long = 0L,
) : IStateRender {

    override fun createToolbarRenderer(): ComposingRenderer {
        val settings = ctx.candidateDisplaySettings()
        return ComposingRenderer(
            candidates,
            ctx.composingEdgeDrawable(),
            ctx.idleHorizontalPaddingDp(),
            showIndex = settings.showIndex,
            showComment = settings.showComment,
            candidateBorder = settings.candidateBorder,
            expandBorder = settings.expandBorder,
            candidateRadiusDp = 6f,
            areaStyle = null,
            maxColumns = null,
            expandLeft = false,
            wordSpacingDp = settings.wordSpacingDp,
        ).also {
            it.recording = ctx.recording
            it.highlightedIndex = highlightedIndex
        }
    }

    override fun showExpand(isExpanded: Boolean) {
        if (isExpanded) ctx.candidateGrid.show(candidates, revision = revision)
    }

    override fun hideExpand() {
        ctx.candidateGrid.hide()
    }
}

/** 预测态：与组字态共用候选渲染器，但进入时强制收起展开栏。 */
class PredictionStateRender(
    private val ctx: StateRenderContext,
    var candidates: List<EngineMessage.Candidate>,
    var highlightedIndex: Int = 0,
    // 候选帧代次（WO38 E1-10/1）：与组字态同口径，随 candidates 同批原地更新
    var revision: Long = 0L,
) : IStateRender {
    override fun createToolbarRenderer(): ComposingRenderer {
        val settings = ctx.candidateDisplaySettings()
        return ComposingRenderer(
            candidates,
            ctx.composingEdgeDrawable(),
            ctx.idleHorizontalPaddingDp(),
            showIndex = settings.showIndex,
            showComment = settings.showComment,
            candidateBorder = settings.candidateBorder,
            expandBorder = settings.expandBorder,
            candidateRadiusDp = 6f,
            edgeBgColor = null,
            edgeRadiusDp = null,
            areaStyle = null,
            maxColumns = null,
            expandLeft = false,
            edgeTapClosesPredictions = true,
            // 预测首项不是 RIME 首选候选：首选色在此态关闭。
            preferredEnabled = false,
            // 取消钮画 ×（H2-2）：与组字态展开 chevron 区分开。
            cancelDrawable = ctx.cancelDrawable,
            wordSpacingDp = settings.wordSpacingDp,
        ).also {
            it.recording = ctx.recording
            it.highlightedIndex = highlightedIndex
        }
    }

    override fun showExpand(isExpanded: Boolean) {
        // 预测展开同样不是 RIME 候选：首选色关闭。
        if (isExpanded) ctx.candidateGrid.show(candidates, preferredEnabled = false, revision = revision)
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
        // H2-13：打开菜单前按语音总开关当前值刷新语音格显隐。
        ctx.menuGridView.refreshVoiceVisibility()
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

/**
 * 性能面板态（P2-10）：工具栏显示返回箭头（与剪贴板／编辑模式同
 * 口径），展开栏展示本进程累计性能（PerfMonitor.aggregates()；
 * 开发页 Compose 不能直接复用，只复用数据）。基础 5 项固定顺序
 * 在前，P4 分段探针仅诊断模式开时才有、面板只显示已有项。
 */
class PerformanceStateRender(
    private val ctx: StateRenderContext,
) : IStateRender {
    override fun createToolbarRenderer(): ToolbarRenderer =
        ctx.buildToolbarRenderer(showArrow = true)

    override fun showExpand(isExpanded: Boolean) {
        ctx.performanceView.show()
    }

    override fun hideExpand() {
        ctx.performanceView.hide()
    }
}

/** 命令面板态：工具栏显示返回箭头（与剪贴板／性能面板同口径），展开栏展示指令列表。 */
class CommandPanelStateRender(
    private val ctx: StateRenderContext,
) : IStateRender {
    override fun createToolbarRenderer(): ToolbarRenderer =
        ctx.buildToolbarRenderer(showArrow = true)

    override fun showExpand(isExpanded: Boolean) {
        ctx.commandPanelView.show()
    }

    override fun hideExpand() {
        ctx.commandPanelView.hide()
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
