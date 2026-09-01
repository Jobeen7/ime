package com.ninthsoft.ime.input.panel.state

import android.content.Context
import android.graphics.drawable.Drawable
import com.ninthsoft.ime.input.panel.component.CandidateGridView
import com.ninthsoft.ime.input.panel.component.ClipboardView
import com.ninthsoft.ime.input.panel.component.MenuGridView
import com.ninthsoft.ime.input.panel.component.TextEditView
import com.ninthsoft.ime.input.panel.toolbar.ToolbarRendererResources

/**
 * 提供给各 [IStateRender] 的共享上下文，避免 StateRender 反向依赖 [com.ninthsoft.ime.input.panel.KawaiiPanel]。
 */
class StateRenderContext(
    val context: Context,
    val idleResources: ToolbarRendererResources,
    val expandDrawable: Drawable?,
    val candidateGrid: CandidateGridView,
    val textEditingView: TextEditView,
    val clipboardView: ClipboardView,
    val menuGridView: MenuGridView,
    var recording: Boolean = false,
)
