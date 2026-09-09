package com.ninthsoft.ime.input.panel.state

import com.ninthsoft.ime.data.manager.CandidateManager
import com.ninthsoft.ime.data.manager.KeyboardManager
import com.ninthsoft.ime.data.keyboard.theme.KeyboardColors
import com.ninthsoft.ime.R
import com.ninthsoft.ime.engine.data.EngineMessage
import com.ninthsoft.ime.input.panel.ComposingRenderer
import com.ninthsoft.ime.input.panel.component.ClipboardTab
import com.ninthsoft.ime.input.panel.toolbar.ToolbarRenderer
import com.ninthsoft.ime.input.panel.toolbar.ToolbarRendererResources

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
    private val candidates: List<EngineMessage.Candidate>,
) : IStateRender {

    override fun createToolbarRenderer(): ComposingRenderer {
        return ComposingRenderer(
            candidates,
            ctx.expandDrawable,
            ctx.idleHorizontalPaddingDp(),
            showIndex = CandidateManager.isShowIndex(ctx.context),
            showComment = CandidateManager.isShowComment(ctx.context),
            candidateBorder = CandidateManager.isBorderEnabled(ctx.context),
            expandBorder = KeyboardManager.Keyboard.ExpandBorder.isEnabled(ctx.context),
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
    private val candidates: List<EngineMessage.Candidate>,
) : IStateRender {
    override fun createToolbarRenderer(): ComposingRenderer {
        return ComposingRenderer(
            candidates,
            ctx.expandDrawable,
            ctx.idleHorizontalPaddingDp(),
            showIndex = CandidateManager.isShowIndex(ctx.context),
            showComment = CandidateManager.isShowComment(ctx.context),
            candidateBorder = CandidateManager.isBorderEnabled(ctx.context),
            expandBorder = KeyboardManager.Keyboard.ExpandBorder.isEnabled(ctx.context),
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
        ctx.clipboardView.show()
        ctx.clipboardView.refresh()
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
