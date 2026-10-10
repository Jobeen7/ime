package com.jobeen.ime.input.panel

import android.annotation.SuppressLint
import android.content.Context
import com.jobeen.ime.R
import com.jobeen.ime.base.feedback.InputFeedbacks
import com.jobeen.ime.data.keyboard.theme.KeyboardColors
import com.jobeen.ime.data.manager.ClipboardManager
import com.jobeen.ime.data.manager.PhraseManager
import com.jobeen.ime.data.manager.KeyboardManager
import com.jobeen.ime.data.manager.CandidateManager
import com.jobeen.ime.engine.data.CandidatePinYin
import com.jobeen.ime.engine.data.EngineMessage
import com.jobeen.ime.engine.data.EngineMessage.Candidate
import com.jobeen.ime.input.panel.component.CandidateGridView
import com.jobeen.ime.input.panel.component.ClipboardView
import com.jobeen.ime.input.panel.component.ClipboardTab
import com.jobeen.ime.input.panel.component.ConfirmOverlay
import com.jobeen.ime.input.panel.component.TextEditView
import com.jobeen.ime.input.panel.component.MenuGridView
import com.jobeen.ime.input.panel.ComposingRenderer
import com.jobeen.ime.input.panel.state.ComposingStateRender
import com.jobeen.ime.input.panel.state.CopyStateRender
import com.jobeen.ime.input.panel.state.IdleStateRender
import com.jobeen.ime.input.panel.toolbar.ToolbarRenderer
import com.jobeen.ime.input.panel.toolbar.ToolbarRendererResources
import com.jobeen.ime.input.panel.state.IStateRender
import com.jobeen.ime.input.panel.state.MenuStateRender
import com.jobeen.ime.input.panel.state.ClipboardStateRender
import com.jobeen.ime.input.panel.state.PredictionStateRender
import com.jobeen.ime.input.panel.state.StateRenderContext
import com.jobeen.ime.input.panel.state.TextEditingStateRender
import com.jobeen.ime.base.util.appScope
import kotlinx.coroutines.launch
import timber.log.Timber

class KawaiiPanel(
    val context: Context,
    var listener: PanelListener? = null,
) : IPanel {

    /** 当前批次已从引擎加载的候选原始条数（字形过滤前），与 total 同口径 */
    private var rawCandidateLoaded: Int = 0

    sealed class TouchResult {
        data class ToolbarAction(
            val action: PanelAction,
            val tapX: Float = Float.NaN,
            val tapY: Float = Float.NaN,
        ) : TouchResult()

        data class SelectCandidate(val candidate: EngineMessage.Candidate) : TouchResult()
        data object ExpandCandidates : TouchResult()
        data object CollapseCandidates : TouchResult()
        data object LongPressExpand : TouchResult()
        data object LongPressClearPhrases : TouchResult()
    }

    sealed class State {
        data object Idle : State()
        data class Composing(val candidates: List<EngineMessage.Candidate>) : State()
        data class Prediction(val candidates: List<EngineMessage.Candidate>) : State()
        data object Menu : State()
        data object Clipboard : State()
        data object TextEditing : State()
        data object Copy : State()
    }

    private var state: State = State.Idle
        set(value) {
            // 同态新批次先行复用（且避开 data class 的全列表 equals 开销）：
            // 旧实现复用早退只在展开态成立，条带态每键都走 applyStateRender
            // 重建渲染器——新 ComposingRenderer 的布局缓存以实例为键，跨批
            // 永远命不中，每键白付全量测宽 + 5 次 SharedPreferences 读取。
            // 现在条带/展开一致复用：原地更新候选，网格仅展开时同步。
            if (field is State.Composing && value is State.Composing) {
                (currentStateRender as? ComposingStateRender)?.candidates = value.candidates
                (view.currentRenderer as? ComposingRenderer)?.candidates = value.candidates
                if (view.isExpanded) candidateGrid.updateCandidates(value.candidates)
                confirmOverlay.dismiss()
                field = value
                return
            }
            if (field is State.Prediction && value is State.Prediction) {
                (currentStateRender as? PredictionStateRender)?.candidates = value.candidates
                (view.currentRenderer as? ComposingRenderer)?.candidates = value.candidates
                if (view.isExpanded) candidateGrid.updateCandidates(value.candidates)
                confirmOverlay.dismiss()
                field = value
                return
            }
            if (field == value) return
            field = value
            // 搜索/编辑/多选只活在剪贴板态里：离开剪贴板态统一收口
            // （渲染器随状态重建自清；编辑静默收口=不保存）
            if (value != State.Clipboard) {
                if (clipSearchActive) resetClipSearchState()
                resetClipEditState()
                if (clipMultiActive) {
                    clipMultiActive = false
                    clipboardView.setMultiSelect(false)
                }
            }
            if (value is State.Menu) {
                clipboardTab = ClipboardTab.CLIPBOARD
                clipboardView.clipTab = ClipboardTab.CLIPBOARD
            }
            applyStateRender(value)
            if (value is State.Menu) {
                view.setExpanded(false)
            }
            if (field == State.Idle) checkPendingCopy()
        }

    private var copyText: String? = null

    /** 密码框内为 true：粘贴提示横幅禁用（复制过的密码不能明文弹在横幅上）。 */
    var suppressCopyBanner: Boolean = false
    private var lastShownCopyTimestamp: Long = 0L
    private var lastShownCopyText: String? = null

    // 全局静态回调：持有 KawaiiPanel 及 view 树，onFinishInputView 时必须清理，
    // 否则 service 销毁后旧 panel 泄漏；onStartInputView 时重新注册
    private val clipNewEntryCallback: (ClipboardManager.Entry) -> Unit =
        { entry -> showCopyIfRecent(entry.text) }
    private val clipContentChangedCallback: () -> Unit =
        {
            // 列表实际显示在 State.Clipboard 展开态，之前误写 State.Menu 导致内容变化不刷新
            if (state == State.Clipboard) clipboardView.refresh()
            // 系统剪贴板变化走 addEntry(notify=false)，onNewEntry 不触发：
            // 这里直接检查，Idle 状态下复制内容能立刻弹出粘贴提示
            checkPendingCopy()
        }
    private val phraseContentChangedCallback: () -> Unit =
        { if (state == State.Clipboard) clipboardView.refresh() }

    private var currentStateRender: IStateRender? = null

    private var clipboardTab: ClipboardTab = ClipboardTab.CLIPBOARD

    private fun createStateRender(state: State): IStateRender = when (state) {
        State.Idle -> IdleStateRender(renderContext)
        is State.Composing -> ComposingStateRender(renderContext, state.candidates)
        is State.Prediction -> PredictionStateRender(renderContext, state.candidates)
            State.Menu -> MenuStateRender(renderContext)
            State.Clipboard -> ClipboardStateRender(renderContext, clipboardTab)
        State.TextEditing -> TextEditingStateRender(renderContext)
        is State.Copy -> CopyStateRender(renderContext, copyText)
    }

    private fun applyStateRender(state: State) {
        confirmOverlay.dismiss()
        currentStateRender?.hideExpand()
        val render = createStateRender(state)
        currentStateRender = render
        view.currentRenderer = render.createToolbarRenderer()
        if (state is State.Prediction) view.setExpanded(false)
        render.showExpand(view.isExpanded)
        view.invalidate()
    }

    private val resolvedColors: KeyboardColors.ColorScheme
        get() = KeyboardColors.resolve(context)

    override var recording: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            renderContext.recording = value
            view.recording = value
        }

    var onRecordingStop: (() -> Unit)? = null

    val candidateGrid = CandidateGridView(
        context = context,
        colors = resolvedColors,
        onCandidateSelected = { candidate ->
            view.onTap?.invoke(TouchResult.SelectCandidate(candidate))
        },
        onSidePanelAction = { listener?.onSidePanelAction(it) },
    ).apply {
        onNeedMoreCandidates = { this@KawaiiPanel.listener?.onRequestMoreCandidates() }
        onWordForget = { candidate, x, y ->
            confirmOverlay.confirm(
                message = context.getString(
                    R.string.candidate_forget_confirm,
                    if (candidate.text.length > 5) candidate.text.take(5) + "..." else candidate.text
                ),
                onConfirm = { handleCandidateForget(candidate) },
                cardX = x,
                cardY = y,
            )
        }
        onDragComplete = { candidates ->
            (view.currentRenderer as? ComposingRenderer)?.candidates = candidates
            // 面板状态必须同步成新顺序：state 仍是拖前列表时，之后任何
            // 一次同态候选刷新走 setter 都会用拖前列表覆盖网格，拖拽
            // 结果当场或下个按键即被打回原位
            when (state) {
                is State.Composing -> state = State.Composing(candidates)
                is State.Prediction -> state = State.Prediction(candidates)
                else -> {}
            }
            this@KawaiiPanel.listener?.onCandidateGridDragComplete(candidates)
        }
    }

    val textEditingView = TextEditView(
        context = context,
        colors = resolvedColors,
    ).apply {
        onAction = { action -> listener?.onTextEditingAction(action) }
    }

    val confirmOverlay = ConfirmOverlay(
        context = context,
        colors = resolvedColors,
    )

    val clipboardView = ClipboardView(
        context = context,
        colors = resolvedColors,
    )

    val menuGridView = MenuGridView(context, resolvedColors)

    @SuppressLint("UseCompatLoadingForDrawables")
    private val renderContext = StateRenderContext(
        context = context,
        idleResources = ToolbarRendererResources(
            context.getDrawable(R.drawable.ic_keyboard_menu),
            context.getDrawable(R.drawable.ic_keyboard_arrow_back),
            context.getDrawable(R.drawable.ic_keyboard_clipboard),
            context.getDrawable(R.drawable.ic_keyboard_keyboard_close),
            context.getDrawable(R.drawable.ic_keyboard_trash),
            context.getDrawable(R.drawable.ic_toolbar_emoji),
            context.getDrawable(R.drawable.ic_toolbar_select_all),
            context.getDrawable(R.drawable.ic_toolbar_copy),
            context.getDrawable(R.drawable.ic_toolbar_paste),
            context.getDrawable(R.drawable.ic_keyboard_search),
        ),
        expandDrawable = context.getDrawable(R.drawable.ic_keyboard_expand_more),
        candidateGrid = candidateGrid,
        textEditingView = textEditingView,
        clipboardView = clipboardView,
        menuGridView = menuGridView,
        recording = recording,
    )

    init {
        menuGridView.onAction = { action ->
            if (state == State.Menu) state = State.Idle
            when (action) {
                PanelAction.Clipboard -> {
                    clipboardTab = ClipboardTab.CLIPBOARD
                    state = State.Clipboard
                }
                PanelAction.CommonPhrases -> {
                    clipboardTab = ClipboardTab.PHRASE
                    state = State.Clipboard
                }
                PanelAction.CursorMove -> state = State.TextEditing
                PanelAction.TogglePrediction -> {
                    CandidateManager.setPredictionEnabled(
                        context,
                        !CandidateManager.isPredictionEnabled(context),
                    )
                    menuGridView.refreshPredictionState()
                }
                PanelAction.ToggleShowComment -> {
                    CandidateManager.setShowComment(
                        context,
                        !CandidateManager.isShowComment(context),
                    )
                    menuGridView.refreshPredictionState()
                }
                PanelAction.ToggleTraditionalChinese -> {
                    CandidateManager.setTraditionalChineseEnabled(
                        context,
                        !CandidateManager.isTraditionalChineseEnabled(context),
                    )
                    menuGridView.refreshPredictionState()
                }
                PanelAction.ToggleEmojiInput -> {
                    CandidateManager.setEmojiEnabled(
                        context,
                        !CandidateManager.isEmojiEnabled(context),
                    )
                    menuGridView.refreshPredictionState()
                }
                PanelAction.ToggleAsciiMode -> {
                    CandidateManager.setAsciiModeEnabled(
                        context,
                        !CandidateManager.isAsciiModeEnabled(context),
                    )
                    menuGridView.refreshPredictionState()
                }
                else -> {
                    val dispatch: () -> Unit = { listener?.onToolbarAction(action) }
                    when (action) {
                        PanelAction.Settings,
                        PanelAction.SchemaSettings,
                        PanelAction.Palette,
                        PanelAction.About,
                        -> view.postDelayed(dispatch, 30L)
                        else -> dispatch()
                    }
                }
            }
        }
        clipboardView.onItemClick = { entry -> listener?.onClipboardItemClick(entry) }
        clipboardView.onSelectionChanged = { if (clipMultiActive) syncMultiRenderer() }
        clipboardView.onEditCursorMoved = { idx ->
            if (clipEditActive) {
                clipEditCursor = idx.coerceIn(0, clipEditBuffer.length)
                syncEditDisplay()
            }
        }
        clipboardView.onItemLongClick = { entry, x, y ->
            Timber.d("clipboard longClick: cardX=$x cardY=$y")
            val summary = if (entry.text.length > 5) entry.text.take(5) + "..." else entry.text
            confirmOverlay.actions(
                message = context.getString(R.string.clipboard_item_actions),
                items = listOf(
                    context.getString(
                        if (entry.pinned) R.string.clipboard_unpin else R.string.clipboard_pin
                    ) to {
                        appScope.launch {
                            ClipboardManager.setPinned(context, entry.text, !entry.pinned)
                            clipboardView.refresh()
                        }
                    },
                    context.getString(R.string.clipboard_edit) to {
                        enterClipEdit(entry)
                    },
                    context.getString(R.string.clipboard_multi_select) to {
                        enterClipMulti(entry)
                    },
                    context.getString(R.string.clipboard_delete) to {
                        // 延后一帧再弹删除确认：动作卡的点击处理在回调后会 dismiss，
                        // 同步弹新卡会被随后的 dismiss 一起关掉
                        confirmOverlay.post {
                            confirmOverlay.confirm(
                                message = context.getString(
                                    R.string.clipboard_delete_confirm, summary
                                ),
                                onConfirm = { handleClipboardDelete(entry) },
                                cardX = x + 100,
                                cardY = y + 100,
                            )
                        }
                    },
                ),
                cardX = x + 100,
                cardY = y + 100,
            )
        }

        ClipboardManager.onNewEntry = clipNewEntryCallback

        ClipboardManager.onContentChanged = clipContentChangedCallback

        clipboardView.onPhraseClick = { phrase ->
            listener?.onPhraseClick(phrase)
        }
        clipboardView.onPhraseDelete = { phrase ->
            confirmOverlay.confirm(
                message = context.getString(R.string.phrase_delete_confirm, phrase.label),
                onConfirm = {
                    appScope.launch {
                        PhraseManager.delete(context, phrase.id)
                        clipboardView.refresh()
                    }
                },
                cardX = Float.NaN,
                cardY = 0f,
            )
        }
        PhraseManager.onContentChanged = phraseContentChangedCallback

    }

        private fun showClearClipboardConfirm() {
        confirmOverlay.confirm(
            message = context.getString(R.string.clipboard_clear_confirm_title),
            onConfirm = { handleClipboardClear() },
            cardX = Float.NaN,
            cardY = 0f,
        )
    }

    private fun showClearPhrasesConfirm() {
        confirmOverlay.confirm(
            message = context.getString(R.string.phrase_clear_confirm),
            onConfirm = {
                appScope.launch {
                    PhraseManager.deleteAll(context)
                    clipboardView.refresh()
                }
            },
            cardX = Float.NaN,
            cardY = 0f,
        )
    }

    private fun handleClipboardClear() {
        // 与多选批量删除（handleClipMultiDelete）同一串行写法：同一协程内
        // 先等写库完成再 refresh。经 listener 转发时写库是即发即忘的独立
        // 协程，与 refresh 的 reload 读库无顺序关系，已清条目会被盖回列表
        appScope.launch {
            ClipboardManager.clearAll(context)
            clipboardView.refresh()
        }
    }

    private fun handleClipboardDelete(entry: ClipboardManager.Entry) {
        // 同 handleClipboardClear：先写库、后 refresh，串行保证读到新数据
        appScope.launch {
            ClipboardManager.removeEntry(context, entry.text)
            clipboardView.refresh()
        }
    }

    // ── 剪贴板搜索态 ──────────────────────────────────────────────
    // 搜索只存在于 State.Clipboard 之内：面板状态不新增，靠这个标志钉住——
    // 候选消息不再把状态抢去组字态，工具栏在「搜索框」与「候选行」间轮显。

    override var clipSearchActive: Boolean = false
        private set

    private fun newClipToolbarRenderer(): ToolbarRenderer =
        (createStateRender(State.Clipboard) as ClipboardStateRender).createToolbarRenderer()

    private fun applySearchFields(renderer: ToolbarRenderer) {
        renderer.clipSearchMode = true
        renderer.clipSearchQuery = clipboardView.searchQuery
        renderer.clipSearchHint = context.getString(R.string.clipboard_search_hint)
    }

    private fun enterClipSearch() {
        clipSearchActive = true
        clipboardView.setSearchQuery("")
        view.currentRenderer = newClipToolbarRenderer().also { applySearchFields(it) }
        view.invalidate()
        // 列表要缩高让出键盘区（窗口按 clipSearchActive 重排）
        clipboardView.requestLayout()
    }

    private fun exitClipSearch() {
        clipSearchActive = false
        clipboardView.setSearchQuery("")
        // 工具栏可能正被候选行轮显占用：一律重建回剪贴板工具栏
        view.currentRenderer = newClipToolbarRenderer()
        view.invalidate()
        clipboardView.requestLayout()
    }

    /** 离开剪贴板态/切换分页时的静默收口：状态机本身会换渲染器，只清标志与查询。 */
    private fun resetClipSearchState() {
        clipSearchActive = false
        clipboardView.setSearchQuery("")
        clipboardView.requestLayout()
    }

    // ── 剪贴板条目编辑态 ────────────────────────────────────────
    // 与搜索态同构：标志钉住剪贴板态，键盘输入经 interceptCommit 改道
    // 进编辑缓冲（光标模型：插入光标处），退格同搜索分流。窗口形态与
    // 搜索共用（拉高+结果区），结果区改画条目全文供多行编辑（见
    // ClipboardView 编辑显示模式）。编辑与搜索可叠加：从搜索结果长按
    // 进编辑，保存/取消后回到搜索态（clipEditReturnToSearch 记账）。

    override var clipEditActive: Boolean = false
        private set
    private var clipEditOriginal: String? = null
    private var clipEditBuffer: String = ""
    // 光标下标（0..buffer.length）：输入插入光标处，退格删光标前一字；
    // 点编辑区定位由 ClipboardView 回调写入
    private var clipEditCursor: Int = 0
    private var clipEditReturnToSearch: Boolean = false

    private fun applyEditFields(renderer: ToolbarRenderer) {
        renderer.clipEditMode = true
        renderer.clipEditHint = context.getString(R.string.clipboard_edit_hint)
        renderer.clipEditSaveLabel = context.getString(R.string.clipboard_edit_save)
        renderer.clipCancelLabel = context.getString(R.string.clipboard_cancel)
    }

    /** 编辑内容变化后同步到列表区的编辑显示（全文+光标）。 */
    private fun syncEditDisplay() {
        clipboardView.setEditContent(clipEditBuffer, clipEditCursor)
    }

    private fun enterClipEdit(entry: ClipboardManager.Entry) {
        clipEditReturnToSearch = clipSearchActive
        clipEditOriginal = entry.text
        clipEditBuffer = entry.text
        clipEditCursor = entry.text.length
        clipEditActive = true
        view.currentRenderer = newClipToolbarRenderer().also { applyEditFields(it) }
        syncEditDisplay()
        view.invalidate()
        // 窗口按 clipEditActive 重排（与搜索同形态），结果区改画编辑全文
        clipboardView.requestLayout()
    }

    private fun exitClipEdit(save: Boolean) {
        val original = clipEditOriginal
        val newText = clipEditBuffer.trim()
        clipEditActive = false
        clipEditOriginal = null
        clipEditBuffer = ""
        clipEditCursor = 0
        clipboardView.clearEditDisplay()
        val backToSearch = clipEditReturnToSearch
        clipEditReturnToSearch = false
        if (backToSearch) {
            view.currentRenderer = newClipToolbarRenderer().also { applySearchFields(it) }
        } else {
            view.currentRenderer = newClipToolbarRenderer()
        }
        view.invalidate()
        clipboardView.requestLayout()
        if (save && original != null && newText.isNotEmpty() && newText != original.trim()) {
            appScope.launch {
                ClipboardManager.updateEntry(context, original, newText)
                clipboardView.refresh()
            }
        }
    }

    /** 静默收口（离开剪贴板态等）：不保存，直接丢弃编辑缓冲。 */
    private fun resetClipEditState() {
        if (!clipEditActive) return
        clipEditActive = false
        clipEditOriginal = null
        clipEditBuffer = ""
        clipEditCursor = 0
        clipEditReturnToSearch = false
        clipboardView.clearEditDisplay()
        clipboardView.requestLayout()
    }

    // ── 剪贴板多选态 ────────────────────────────────────────────
    // 多选不涉及键盘输入改道，只换工具栏形态与列表点选语义；
    // 选中集合由 ClipboardView 持有，这里负责工具栏与批量删除。

    private var clipMultiActive: Boolean = false

    private fun applyMultiFields(renderer: ToolbarRenderer) {
        renderer.clipMultiMode = true
        renderer.clipMultiLabel = multiLabelText()
        renderer.clipDeleteLabel = context.getString(R.string.clipboard_delete)
        renderer.clipCancelLabel = context.getString(R.string.clipboard_cancel)
    }

    private fun multiLabelText(): String {
        val n = clipboardView.selectedCount
        return if (n > 0) context.getString(R.string.clipboard_selected_count, n)
        else context.getString(R.string.clipboard_select_none)
    }

    private fun syncMultiRenderer() {
        (view.currentRenderer as? ToolbarRenderer)?.let { r ->
            if (r.clipMultiMode) r.clipMultiLabel = multiLabelText()
        }
        view.invalidate()
    }

    private fun enterClipMulti(entry: ClipboardManager.Entry) {
        clipMultiActive = true
        clipboardView.beginMultiSelect(entry)
        view.currentRenderer = newClipToolbarRenderer().also { applyMultiFields(it) }
        view.invalidate()
    }

    private fun exitClipMulti() {
        if (!clipMultiActive) return
        clipMultiActive = false
        clipboardView.setMultiSelect(false)
        // 回到进入前的工具栏：搜索态下进多选则回搜索框，否则普通剪贴板栏
        view.currentRenderer = if (clipSearchActive) {
            newClipToolbarRenderer().also { applySearchFields(it) }
        } else {
            newClipToolbarRenderer()
        }
        view.invalidate()
    }

    private fun handleClipMultiDelete() {
        val texts = clipboardView.selectedSnapshot()
        exitClipMulti()
        if (texts.isEmpty()) return
        appScope.launch {
            ClipboardManager.removeEntries(context, texts)
            clipboardView.refresh()
        }
    }

    override fun interceptCommit(text: String): Boolean {
        if (clipEditActive) {
            // 编辑态优先于搜索态（可从搜索结果进编辑）：换行不进缓冲，
            // 其余文本插入光标处并推进光标
            val clean = text.replace("\n", "").replace("\r", "")
            if (clean.isNotEmpty()) {
                val c = clipEditCursor.coerceIn(0, clipEditBuffer.length)
                clipEditBuffer = clipEditBuffer.substring(0, c) + clean +
                    clipEditBuffer.substring(c)
                clipEditCursor = c + clean.length
                syncEditDisplay()
            }
            return true
        }
        if (!clipSearchActive) return false
        // 换行不进查询（回车在搜索态即无动作），其余文本（含空格）追加到查询尾
        val clean = text.replace("\n", "").replace("\r", "")
        if (clean.isNotEmpty()) {
            val q = clipboardView.searchQuery + clean
            clipboardView.setSearchQuery(q)
            (view.currentRenderer as? ToolbarRenderer)?.let { r ->
                if (r.clipSearchMode) r.clipSearchQuery = q
            }
            view.invalidate()
        }
        return true
    }

    override fun handleClipSearchBackspace(isComposing: Boolean): Boolean {
        if (clipEditActive) {
            // 组字中退格归引擎删拼音；其余一律截获删光标前一字，绝不落到目标应用
            if (isComposing) return false
            val c = clipEditCursor.coerceIn(0, clipEditBuffer.length)
            if (c > 0) {
                clipEditBuffer = clipEditBuffer.substring(0, c - 1) +
                    clipEditBuffer.substring(c)
                clipEditCursor = c - 1
                syncEditDisplay()
            }
            return true
        }
        if (!clipSearchActive) return false
        // 组字中退格归引擎删拼音；其余情况一律截获，绝不让退格落到目标应用删字
        if (isComposing) return false
        val q = clipboardView.searchQuery
        if (q.isNotEmpty()) {
            val nq = q.dropLast(1)
            clipboardView.setSearchQuery(nq)
            (view.currentRenderer as? ToolbarRenderer)?.let { r ->
                if (r.clipSearchMode) r.clipSearchQuery = nq
            }
            view.invalidate()
        }
        return true
    }

    private fun handleCandidateForget(candidate: EngineMessage.Candidate) {
        listener?.onCandidateForget(candidate)
    }

    fun onSelectionUpdate(start: Int, end: Int) {
        textEditingView.setSelection(start, end)
    }

    fun onInputChanged(text: String) {
        textEditingView.onInputChanged(text)
    }

    private fun showCopyIfRecent(text: String) {
        if (suppressCopyBanner) return
        copyText = text
        val recentTime = ClipboardManager.lastCopyTimestamp
        if (recentTime <= lastShownCopyTimestamp || System.currentTimeMillis() - recentTime >= 5 * 60 * 1000L) return
        if (text == lastShownCopyText) return
        lastShownCopyTimestamp = recentTime
        lastShownCopyText = text
        when (state) {
            State.Idle -> state = State.Copy
            is State.Copy -> {
                currentStateRender = createStateRender(State.Copy)
                view.currentRenderer = currentStateRender!!.createToolbarRenderer()
                view.invalidate()
            }

            State.Menu -> clipboardView.refresh()
            else -> {}
        }
    }

    override val view: KawaiiPanelView = KawaiiPanelView(context).also { v ->
        v.onTap = { result ->
            when (result) {
                is TouchResult.ToolbarAction -> {
                    InputFeedbacks.hapticFeedback(view)
                    if (recording && result.action is PanelAction.CloseKeyboard) {
                        onRecordingStop?.invoke()
                    } else {
                        when (result.action) {
                            PanelAction.CursorMove -> state = State.TextEditing
                            PanelAction.Clipboard -> {
                                clipboardTab = ClipboardTab.CLIPBOARD
                                state = State.Clipboard
                            }
                            is PanelAction.ClipTab -> {
                                confirmOverlay.dismiss()
                                // 切分页时编辑（不保存）与多选一并收口
                                resetClipEditState()
                                if (clipMultiActive) {
                                    clipMultiActive = false
                                    clipboardView.setMultiSelect(false)
                                }
                                if (clipSearchActive) {
                                    resetClipSearchState()
                                    (view.currentRenderer as? ToolbarRenderer)?.clipSearchMode = false
                                }
                                clipboardTab =
                                    if (result.action.isClipboard) ClipboardTab.CLIPBOARD else ClipboardTab.PHRASE
                                 (view.currentRenderer as? ToolbarRenderer)?.clipTab = clipboardTab
                                clipboardView.clipTab = clipboardTab
                                clipboardView.refresh()
                                view.invalidate()
                            }

                            PanelAction.AddPhrase -> enterAddPhraseMode()

                            PanelAction.ClearClipboard -> showClearClipboardConfirm()
                            PanelAction.ClearPhrases -> showClearPhrasesConfirm()

                            PanelAction.ClipSearch -> enterClipSearch()
                            PanelAction.ClipSearchExit -> exitClipSearch()
                            PanelAction.ClipSearchClear -> {
                                if (clipboardView.searchQuery.isEmpty()) {
                                    exitClipSearch()
                                } else {
                                    clipboardView.setSearchQuery("")
                                    (view.currentRenderer as? ToolbarRenderer)?.let { r ->
                                        if (r.clipSearchMode) r.clipSearchQuery = ""
                                    }
                                    view.invalidate()
                                }
                            }

                            PanelAction.ClipEditSave -> exitClipEdit(save = true)
                            PanelAction.ClipEditCancel -> exitClipEdit(save = false)
                            PanelAction.ClipMultiExit -> exitClipMulti()
                            PanelAction.ClipMultiDelete -> handleClipMultiDelete()

                            PanelAction.SwitchKeyboard -> {
                                when (state) {
                                    State.Menu, State.Clipboard, State.TextEditing, State.Copy -> state = State.Idle

                                    else -> listener?.onToolbarAction(result.action)
                                }
                            }

                            else -> listener?.onToolbarAction(result.action)
                        }
                    }
                }

                is TouchResult.SelectCandidate -> {
                    InputFeedbacks.hapticFeedback(view)
                    InputFeedbacks.soundEffect(context, InputFeedbacks.SoundEffect.Standard)
                    listener?.onCandidateSelected(result.candidate)
                }

                is TouchResult.ExpandCandidates -> v.setExpanded(true)
                is TouchResult.CollapseCandidates -> v.setExpanded(false)
                is TouchResult.LongPressExpand -> {
                    if (state is State.Prediction) {
                        val candidates = (state as State.Prediction).candidates
                        var predictions = true
                        candidates.forEach {
                            if (it.type != Candidate.TYPE_IME_PREDICTION) {
                                predictions = false
                                return@forEach
                            }
                        }
                        if (predictions) setCandidates(emptyList()) else v.setExpanded(true)
                    }
                }

                TouchResult.LongPressClearPhrases -> showClearPhrasesConfirm()

                null -> {
                    if (state == State.Copy && copyText != null) {
                        listener?.onCopyTextCommit(copyText ?: "")
                        state = State.Idle
                    }
                }
            }
        }

        v.onExpandChanged = { expanded, _ ->
            if (expanded) currentStateRender?.showExpand(true)
            else currentStateRender?.hideExpand()
        }

        currentStateRender = createStateRender(State.Idle)
        v.currentRenderer = currentStateRender!!.createToolbarRenderer()
    }

    fun toggleMenu() {
        if (state == State.Menu) {
            state = State.Idle
        } else {
            view.setExpanded(false)
            state = State.Menu
        }
    }

    fun showTextEditing() {
        view.setExpanded(false)
        state = State.TextEditing
    }

    fun hideTextEditing() {
        if (state == State.TextEditing) state = State.Idle
    }

    private var addPhraseActive = false

    private fun enterAddPhraseMode() {
        addPhraseActive = true
        state = State.Idle
        listener?.onEnterAddPhraseMode()
    }

    override fun exitAddPhraseMode() {
        if (!addPhraseActive) return
        addPhraseActive = false
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        confirmOverlay.dismiss()
        // 取消工具栏未触发的延迟点击（Palette/CloseKeyboard），避免输入结束后迟到执行
        view.cancelDelayedTaps()
        // 清理全局静态回调（仅当还是我们注册的才清，避免误清新 panel 的）
        if (ClipboardManager.onNewEntry === clipNewEntryCallback) ClipboardManager.onNewEntry = null
        if (ClipboardManager.onContentChanged === clipContentChangedCallback) ClipboardManager.onContentChanged = null
        if (PhraseManager.onContentChanged === phraseContentChangedCallback) PhraseManager.onContentChanged = null
        if (addPhraseActive) {
            addPhraseActive = false
            listener?.onAddPhraseCancel()
        }
        state = State.Idle
    }

    fun onStartInputView() {
        // 重新注册全局回调（onFinishInputView 已清理）
        ClipboardManager.onNewEntry = clipNewEntryCallback
        ClipboardManager.onContentChanged = clipContentChangedCallback
        PhraseManager.onContentChanged = phraseContentChangedCallback
        // 剪贴板已改为事件驱动（OnPrimaryClipChangedListener），这里只做一次性补偿检查：
        // 进程不存活期间的复制由 ClipboardManager.startMonitoring 补偿，
        // 键盘打开前 500ms 内的复制由下面两次检查覆盖，不再每 2 秒轮询
        appScope.launch {
            ClipboardManager.checkCurrentClipboard(context)
            checkPendingCopy()
        }
        view.postDelayed({
            appScope.launch {
                ClipboardManager.checkCurrentClipboard(context)
                checkPendingCopy()
            }
        }, 500L)
    }

    private fun checkPendingCopy() {
        if (suppressCopyBanner) return
        if (state != State.Idle) return
        val text = ClipboardManager.lastCopyText ?: return
        if (text == lastShownCopyText) return
        val time = ClipboardManager.lastCopyTimestamp
        if (time > lastShownCopyTimestamp && System.currentTimeMillis() - time < 5 * 60 * 1000L) {
            copyText = text
            lastShownCopyTimestamp = time
            lastShownCopyText = text
            state = State.Copy
        }
    }

    @SuppressLint("UseCompatLoadingForDrawables")
    override fun setCandidates(list: List<EngineMessage.Candidate>, hasMore: Boolean) {
        if (!view.isLaidOut) {
            view.post { setCandidates(list, hasMore) }
            return
        }
        candidateGrid.hasMoreCandidates = hasMore
        // 「已加载原始条数」按引擎口径累计（过滤前），供追加时与 total
        // 同口径判定是否到底；过滤后条数与 total 比会多打空往返
        rawCandidateLoaded = list.size
        // 本机字体无字形的单字候选（扩展区生僻字）不进列表：显示出来
        // 是方框、点选打出对方也看不到。条带与网格共用此列表，一致
        val displayList = list.filter { candidateGrid.isDisplayable(it) }
        if (clipSearchActive || clipEditActive) {
            // 文本输入态（搜索/编辑）钉住：不切换面板状态，
            // 工具栏在输入框与候选行之间轮显
            when {
                displayList.isNotEmpty() -> {
                    val r = view.currentRenderer
                    if (r is ComposingRenderer) {
                        r.candidates = displayList
                    } else {
                        view.currentRenderer =
                            ComposingStateRender(renderContext, displayList).createToolbarRenderer()
                    }
                }
                list.isNotEmpty() && hasMore -> {
                    // 整屏无字形且还有更多：续取穿过去（不切组字态）
                    view.post { listener?.onRequestMoreCandidates() }
                }
                else -> {
                    // 组字结束（选词已上屏改道输入，或清空）：恢复输入框工具栏；
                    // 编辑态优先（可叠加在搜索之上）
                    if (view.currentRenderer !is ToolbarRenderer) {
                        view.currentRenderer = newClipToolbarRenderer().also { r ->
                            if (clipEditActive) applyEditFields(r) else applySearchFields(r)
                        }
                    }
                }
            }
            view.invalidate()
            return
        }
        if (displayList.isEmpty() && list.isNotEmpty() && hasMore) {
            // 整屏被滤光且引擎还有更多：保持组字空态并续取下一页穿过
            // 无字形带，不要清成 Idle——那样追加页回来会因非组字态被
            // 丢弃、候选就此消失。续取链由引擎侧 200 封顶收口，真到底
            // 时追加空页关闭 hasMore。原始列表为空是真清空信号，不走此路
            state = State.Composing(emptyList())
            view.post { listener?.onRequestMoreCandidates() }
            view.invalidate()
            return
        }
        val list = displayList
        if (list.isEmpty()) {
            view.setExpanded(false)
            view.scrollX = 0f
            if (state !is State.Menu) state = State.Idle
        } else if (state is State.TextEditing) {
            // 编辑态下保持工具栏渲染器，不切换为组字渲染器（右侧入口才正确）。
            // 幂等守卫：旧实现每批候选都重走 applyStateRender——hide+show 展开
            // 视图、120ms 动画反复重启、新建 ToolbarRenderer，编辑态可见闪烁
            if (currentStateRender !is TextEditingStateRender) applyStateRender(state)
        } else {
            if (state is State.Copy) state = State.Idle
            view.scrollX = 0f
            var predictions = true
            list.forEach {
                if (it.type != Candidate.TYPE_IME_PREDICTION) {
                    predictions = false
                    return@forEach
                }
            }
            state = if (predictions) State.Prediction(list) else State.Composing(list)
            // 展开态的网格更新已由 state setter 的同态复用分支（或跨态时
            // applyStateRender 的 showExpand）完成，此处再调一次是每键全量
            // 网格布局 ×2，旧实现一直双跑
        }
        view.invalidate()
    }

    /**
     * 分页补取的追加页：并入当前组字/预测列表后走 state setter 的同态
     * 复用分支原地更新（渲染器与展开网格同步）。非候选态忽略——补取请求
     * 只由展开网格触发，回来时若用户已选词/清屏，追加页无处可放。
     */
    override fun appendCandidates(list: List<EngineMessage.Candidate>, total: Int) {
        if (list.isEmpty()) {
            candidateGrid.hasMoreCandidates = false
            return
        }
        // 原始口径累计（过滤前），与引擎侧 total 同口径判定是否到底
        rawCandidateLoaded += list.size
        // 同 setCandidates：先滤掉本机无字形的单字。整页被滤光说明正处
        // 在一整带无字形生僻字里——不收尾，直接续取下一页穿过去（引擎
        // 侧有 200 封顶，续取链有界；真到底时引擎回空页走上面的收尾）
        val displayable = list.filter { candidateGrid.isDisplayable(it) }
        if (displayable.isEmpty()) {
            // 先按原始口径收口「还有更多」：这一页若已是末页（total 已
            // 确定且原始计数已达），整页滤光也只是到底，不该多打一次
            // 空补取、更不该把 hasMore=true 粘住。仍有更多才续取
            candidateGrid.hasMoreCandidates = total < 0 || rawCandidateLoaded < total
            if (candidateGrid.hasMoreCandidates) {
                // 延迟一拍再续取：本页的引擎任务此刻可能还没复位防重入
                // 标志，同步调用会被它吞掉、续取链就此断掉
                view.post { listener?.onRequestMoreCandidates() }
            }
            return
        }
        when (val s = state) {
            is State.Composing -> {
                val merged = s.candidates + displayable
                candidateGrid.hasMoreCandidates = total < 0 || rawCandidateLoaded < total
                state = State.Composing(merged)
            }
            is State.Prediction -> {
                val merged = s.candidates + displayable
                candidateGrid.hasMoreCandidates = total < 0 || rawCandidateLoaded < total
                state = State.Prediction(merged)
            }
            else -> {}
        }
    }

    override fun onPossibleCandidatePinYin(pinyins: List<CandidatePinYin>) {
        candidateGrid.onPossibleCandidatePinYin(pinyins)
    }

    override fun refreshTheme() {
        // 同一配色只解析一次再分发：旧实现每个子视图各 resolve 一遍（共 5 次，
        // 每次都读主题配置并构建整套 ColorScheme）
        renderContext.invalidateDisplaySettings()
        val colors = KeyboardColors.resolve(context)
        view.refreshTheme()
        candidateGrid.refreshTheme(context)
        clipboardView.refreshTheme(colors)
        menuGridView.refreshTheme(colors)
        textEditingView.refreshTheme(colors)
        confirmOverlay.refreshTheme(colors)
    }
}
