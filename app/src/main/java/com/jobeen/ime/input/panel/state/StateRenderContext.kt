package com.jobeen.ime.input.panel.state

import android.content.Context
import android.graphics.drawable.Drawable
import com.jobeen.ime.input.panel.component.CandidateGridView
import com.jobeen.ime.input.panel.component.ClipboardView
import com.jobeen.ime.input.panel.component.MenuGridView
import com.jobeen.ime.input.panel.component.PerformancePanelView
import com.jobeen.ime.input.panel.component.TextEditView
import com.jobeen.ime.input.panel.toolbar.ToolbarRendererResources

/**
 * 提供给各 [IStateRender] 的共享上下文，避免 StateRender 反向依赖 [com.jobeen.ime.input.panel.KawaiiPanel]。
 */
class StateRenderContext(
    val context: Context,
    val idleResources: ToolbarRendererResources,
    val expandDrawable: Drawable?,
    val candidateGrid: CandidateGridView,
    val textEditingView: TextEditView,
    val clipboardView: ClipboardView,
    val performanceView: PerformancePanelView,
    val menuGridView: MenuGridView,
    var recording: Boolean = false,
    /** 预测态边缘取消钮图标（×，H2-2）；null = 回落展开图。 */
    val cancelDrawable: Drawable? = null,
) {
    /** 候选条显示设置五项（构造渲染器时此前每次都同步读一遍 prefs 背书值） */
    data class CandidateDisplaySettings(
        val showIndex: Boolean,
        val showComment: Boolean,
        val candidateBorder: Boolean,
        val expandBorder: Boolean,
        /** 候选间距（dp）：null = 未设定，渲染器回落皮肤值／横条默认 6dp。 */
        val wordSpacingDp: Float?,
    )

    private var displaySettingsCache: CandidateDisplaySettings? = null

    fun candidateDisplaySettings(): CandidateDisplaySettings =
        displaySettingsCache ?: CandidateDisplaySettings(
            showIndex = com.jobeen.ime.data.manager.CandidateManager.isShowIndex(context),
            showComment = com.jobeen.ime.data.manager.CandidateManager.isShowComment(context),
            candidateBorder = com.jobeen.ime.data.manager.CandidateManager.isBorderEnabled(context),
            expandBorder = com.jobeen.ime.data.manager.KeyboardManager.Keyboard.ExpandBorder
                .isEnabled(context),
            wordSpacingDp = com.jobeen.ime.data.manager.CandidateManager
                .getCandidateSpacingDp(context),
        ).also { displaySettingsCache = it }

    /** 面板刷新主题/重新显示时失效，用户在设置页改完回来即生效 */
    fun invalidateDisplaySettings() {
        displaySettingsCache = null
    }
}
