package com.jobeen.ime.input.panel

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager as SystemClipboardManager
import android.content.Context
import com.jobeen.ime.R
import com.jobeen.ime.base.feedback.InputFeedbacks
import com.jobeen.ime.data.keyboard.theme.KeyboardColors
import com.jobeen.ime.data.database.ClipboardGroup
import com.jobeen.ime.data.manager.ClipboardManager
import com.jobeen.ime.data.manager.PhraseManager
import com.jobeen.ime.data.manager.takeCodePoints
import com.jobeen.ime.data.manager.KeyboardManager
import com.jobeen.ime.data.manager.CandidateManager
import com.jobeen.ime.data.manager.RareCharPolicy
import com.jobeen.ime.engine.data.CandidatePinYin
import com.jobeen.ime.engine.data.EngineMessage
import com.jobeen.ime.engine.data.EngineMessage.Candidate
import com.jobeen.ime.input.ImeInputMethodService
import com.jobeen.ime.input.panel.component.CandidateGridView
import com.jobeen.ime.input.panel.component.ClipboardView
import com.jobeen.ime.input.panel.component.ClipboardTab
import com.jobeen.ime.input.panel.component.ConfirmOverlay
import com.jobeen.ime.input.panel.component.PerformancePanelView
import com.jobeen.ime.input.panel.component.TextEditView
import com.jobeen.ime.input.keyboard.key.KeyboardAction
import com.jobeen.ime.input.panel.component.MenuGridView
import com.jobeen.ime.input.panel.ComposingRenderer
import com.jobeen.ime.input.panel.state.ComposingStateRender
import com.jobeen.ime.input.panel.state.CopyStateRender
import com.jobeen.ime.input.panel.state.IdleStateRender
import com.jobeen.ime.input.panel.toolbar.ToolbarRenderer
import com.jobeen.ime.input.panel.toolbar.ToolbarRendererResources
import com.jobeen.ime.input.panel.state.IStateRender
import com.jobeen.ime.input.panel.state.MenuStateRender
import com.jobeen.ime.input.panel.state.PerformanceStateRender
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

    /**
     * 候选表代次（I-M4）：每次 setCandidates 进入即递增；未布局时
     * 延迟重投的 runnable 只当捕获代次仍是最新时才执行——旧帧先被
     * post、随后新帧直接应用、再轮到旧 runnable 时，旧表不再覆盖
     * 新表。窗口层的 revision 门只拦直接调用，拦不住这条旁路。
     */
    private var candidateGeneration: Int = 0

    sealed class TouchResult {
        data class ToolbarAction(
            val action: PanelAction,
            val tapX: Float = Float.NaN,
            val tapY: Float = Float.NaN,
        ) : TouchResult()

        /**
         * 候选点选。index = 候选在当前批次中的下标（审计 I 项按压
         * 跟踪用；默认 -1 兼容旧构造点，渲染器命中时恒填实值）。
         */
        data class SelectCandidate(
            val candidate: EngineMessage.Candidate,
            val index: Int = -1,
        ) : TouchResult()
        data object ExpandCandidates : TouchResult()
        data object CollapseCandidates : TouchResult()
        data object LongPressExpand : TouchResult()
        data object LongPressClearPhrases : TouchResult()
        /**
         * 候选上下文菜单（审计 I 项）：皮肤 candidateContextMenu
         * 定义时，长按候选（横条／网格）发此结果而非遗忘确认；
         * items 为皮肤菜单项（已过滤非法动作）。
         */
        data class CandidateMenu(
            val candidate: EngineMessage.Candidate,
            val items: List<com.jobeen.ime.skin.model.CandidateMenuItem>,
        ) : TouchResult()
        /**
         * 联想关闭（K-predict）：预测态边缘钮点按。样式（关闭底色／
         * 圆角）此前已接线，点按动作此前仍是展开／收起；现按审计
         * 改为关闭联想（复用长按清空路径）。
         */
        data object ClosePredictions : TouchResult()

        /**
         * 自定义工具栏按钮的手势执行结果（工具栏编辑轮）：View 的
         * 自定义手势机按槽位抉择后发出，承载已转译的键盘动作；
         * 面板层只做触感反馈并经 [PanelListener.onSidePanelAction]
         * 交服务执行（与按键动作同一通道，不新开执行链）。
         */
        data class CustomKeyAction(val action: KeyboardAction) : TouchResult()
    }

    sealed class State {
        data object Idle : State()
        data class Composing(
            val candidates: List<EngineMessage.Candidate>,
            /** Rime 高亮候选在 candidates 中的下标（经字形过滤重映射后；-1 = 无可见高亮）。 */
            val highlightedIndex: Int = 0,
        ) : State()
        data class Prediction(
            val candidates: List<EngineMessage.Candidate>,
            val highlightedIndex: Int = 0,
        ) : State()
        data object Menu : State()
        data object Clipboard : State()
        data object TextEditing : State()
        data object Copy : State()
        /** 性能面板（P2-10）：读 PerfMonitor.aggregates() 显示本进程累计。 */
        data object Performance : State()
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
                (currentStateRender as? ComposingStateRender)?.highlightedIndex =
                    value.highlightedIndex
                (view.currentRenderer as? ComposingRenderer)?.candidates = value.candidates
                (view.currentRenderer as? ComposingRenderer)?.highlightedIndex =
                    value.highlightedIndex
                if (view.isExpanded) candidateGrid.updateCandidates(value.candidates)
                confirmOverlay.dismiss()
                field = value
                return
            }
            if (field is State.Prediction && value is State.Prediction) {
                (currentStateRender as? PredictionStateRender)?.candidates = value.candidates
                (currentStateRender as? PredictionStateRender)?.highlightedIndex =
                    value.highlightedIndex
                (view.currentRenderer as? ComposingRenderer)?.candidates = value.candidates
                (view.currentRenderer as? ComposingRenderer)?.highlightedIndex =
                    value.highlightedIndex
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
                if (clipReorderActive) {
                    clipReorderActive = false
                    clipboardView.setReorderMode(false)
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
            if (value is State.Clipboard) refreshClipGroups()
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
        is State.Composing -> ComposingStateRender(
            renderContext, state.candidates, state.highlightedIndex,
        )
        is State.Prediction -> PredictionStateRender(
            renderContext, state.candidates, state.highlightedIndex,
        )
            State.Menu -> MenuStateRender(renderContext)
            State.Clipboard -> ClipboardStateRender(renderContext, clipboardTab)
        State.TextEditing -> TextEditingStateRender(renderContext)
        is State.Copy -> CopyStateRender(renderContext, copyText)
        State.Performance -> PerformanceStateRender(renderContext)
    }

    private fun applyStateRender(state: State) {
        confirmOverlay.dismiss()
        currentStateRender?.hideExpand()
        val render = createStateRender(state)
        currentStateRender = render
        view.currentRenderer = render.createToolbarRenderer()
        // 新渲染器只含皮肤／默认：重压用户覆盖层（用户＞皮肤＞默认），
        // 否则用户工具栏在状态切换后丢失（连同用户栏背景半残）。
        view.reapplyUserToolbar()
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
                message = candidateForgetMessage(candidate),
                onConfirm = { handleCandidateForget(candidate) },
                cardX = x,
                cardY = y,
            )
        }
        // 候选菜单（审计 I 项）：皮肤定义菜单时网格长按走菜单
        // 而非遗忘确认；同一 onTap 通道分发，与横条长按一致。
        onCandidateMenu = { candidate, items ->
            view.onTap?.invoke(TouchResult.CandidateMenu(candidate, items))
        }
        onDragComplete = { candidates ->
            // 拖拽只换顺序不换项：高亮跟项走（按对象找新下标），
            // 找不到时沿用旧下标钳制到表内（拖拽不删项，理论上必找到）。
            val renderer = view.currentRenderer as? ComposingRenderer
            val oldHighlightedItem = renderer?.candidates?.getOrNull(
                renderer.highlightedIndex,
            )
            val newHighlighted = oldHighlightedItem?.let { candidates.indexOf(it) }
                ?.takeIf { it >= 0 }
                ?: (renderer?.highlightedIndex ?: 0)
                    .coerceIn(0, (candidates.size - 1).coerceAtLeast(0))
            renderer?.candidates = candidates
            renderer?.highlightedIndex = newHighlighted
            // 面板状态必须同步成新顺序：state 仍是拖前列表时，之后任何
            // 一次同态候选刷新走 setter 都会用拖前列表覆盖网格，拖拽
            // 结果当场或下个按键即被打回原位
            when (state) {
                is State.Composing -> state = State.Composing(candidates, newHighlighted)
                is State.Prediction -> state = State.Prediction(candidates, newHighlighted)
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

    val performanceView = PerformancePanelView(
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
            traditionalChinese = context.getDrawable(R.drawable.ic_keyboard_traditional_ch),
        ),
        expandDrawable = context.getDrawable(R.drawable.ic_keyboard_expand_more),
        cancelDrawable = context.getDrawable(R.drawable.ic_keyboard_close),
        candidateGrid = candidateGrid,
        textEditingView = textEditingView,
        clipboardView = clipboardView,
        performanceView = performanceView,
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
                PanelAction.Performance -> state = State.Performance
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
                    // 纠正 2：菜单中英 = ascii_mode 翻转（与键盘中英键
                    // 同款逻辑），不切键盘类型；类型切换走独立动作。
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
        // 性能面板发送：报告文本经既有 onCopyTextCommit 上屏到宿主
        // （PanelActionListener 走 CommitAction），再回键盘关闭面板
        // （对端 0.1.0(39)「发送上屏到宿主并关闭面板」同款口径）。
        performanceView.onSendReport = { text ->
            listener?.onCopyTextCommit(text)
            returnToKeyboard()
        }
        performanceView.onClose = { returnToKeyboard() }
        clipboardView.onSelectionChanged = { if (clipMultiActive) syncMultiRenderer() }
        clipboardView.onEditCursorMoved = { idx ->
            if (clipEditActive) {
                var cursor = idx.coerceIn(0, clipEditBuffer.length)
                // 落点停在代理对中间时向后吸附到码点边界：半个码点
                // 的光标会让后续插入/退格把字符劈成孤代理项
                if (cursor > 0 && cursor < clipEditBuffer.length &&
                    clipEditBuffer[cursor - 1].isHighSurrogate() &&
                    clipEditBuffer[cursor].isLowSurrogate()
                ) {
                    cursor += 1
                }
                clipEditCursor = cursor
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
                            val result = ClipboardManager.setPinned(
                                context, entry.text, !entry.pinned
                            )
                            // 三态分别反馈（契约见 ClipboardManager.setPinned）：
                            // 达上限与数据库失败文案不同，取消置顶失败
                            // （Error）旧实现完全没反馈。走键盘内置提示，
                            // 与 PanelActionListener 同口径（面板 context
                            // 即输入法服务本体）
                            val message = when (result) {
                                ClipboardManager.PinResult.Limit ->
                                    context.getString(
                                        R.string.clipboard_pin_limit,
                                        ClipboardManager.MAX_PINNED,
                                    )
                                ClipboardManager.PinResult.Error ->
                                    context.getString(R.string.clipboard_pin_failed)
                                ClipboardManager.PinResult.Ok -> null
                            }
                            if (message != null) {
                                (context as? ImeInputMethodService)
                                    ?.keyboardWindow?.showToast(message)
                            }
                            clipboardView.refresh()
                        }
                    },
                    context.getString(R.string.clipboard_move_to_group) to {
                        showMoveToGroupMenu(entry)
                    },
                    context.getString(R.string.clipboard_edit) to {
                        enterClipEdit(entry)
                    },
                    context.getString(R.string.clipboard_multi_select) to {
                        enterClipMulti(entry)
                    },
                    context.getString(R.string.clipboard_reorder) to {
                        enterClipReorder()
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
                                cardX = Float.NaN,
                                cardY = 0f,
                            )
                        }
                    },
                ),
                cardX = Float.NaN,
                cardY = 0f,
            )
        }

        ClipboardManager.onNewEntry = clipNewEntryCallback

        ClipboardManager.onContentChanged = clipContentChangedCallback

        clipboardView.onPhraseClick = { phrase ->
            listener?.onPhraseClick(phrase)
        }
        clipboardView.onPhraseDelete = { phrase ->
            // 与剪贴板同构的动作菜单：置顶 + 编辑 + 排序入口 + 删除（删除仍走确认）
            confirmOverlay.actions(
                message = context.getString(R.string.phrase_item_actions),
                items = listOf(
                    context.getString(
                        if (phrase.pinned) R.string.clipboard_unpin else R.string.clipboard_pin
                    ) to {
                        appScope.launch {
                            PhraseManager.setPinned(context, phrase.id, !phrase.pinned)
                            clipboardView.refresh()
                        }
                    },
                    context.getString(R.string.clipboard_edit) to {
                        enterPhraseEdit(phrase)
                    },
                    context.getString(R.string.clipboard_reorder) to {
                        enterClipReorder()
                    },
                    context.getString(R.string.clipboard_delete) to {
                        confirmOverlay.post {
                            confirmOverlay.confirm(
                                message = context.getString(
                                    R.string.phrase_delete_confirm, phrase.label
                                ),
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
                    },
                ),
                cardX = Float.NaN,
                cardY = 0f,
            )
        }
        clipboardView.onClipboardReordered = { texts ->
            appScope.launch { ClipboardManager.applyOrder(context, texts) }
        }
        clipboardView.onPhrasesReordered = { ids ->
            appScope.launch { PhraseManager.applyOrder(context, ids) }
        }
        clipboardView.onCreateGroupClick = { showCreateGroupDialog(assignText = null) }
        clipboardView.onGroupChipLongClick = { group, _, _ -> showGroupActions(group) }
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

    // ── 剪贴板自定义分组（H2-4） ────────────────────────────────
    // 分组表由面板持有（clipGroups，供长按菜单与分组条长按菜单用），
    // 视图只拿它画药丸与过滤。新建／重命名走服务 showDialog 的输入框
    // 弹窗（与部署确认／打开链接确认同口径）；移组与删组直接落库。

    /** 面板侧分组缓存：长按菜单列分组用；视图侧另有一份（画药丸）。 */
    private var clipGroups: List<ClipboardGroup> = emptyList()

    private fun refreshClipGroups() {
        appScope.launch {
            val gs = ClipboardManager.getGroups(context)
            clipGroups = gs
            clipboardView.setGroups(gs)
        }
    }

    private fun showGroupToast(message: String) {
        (context as? ImeInputMethodService)?.keyboardWindow?.showToast(message)
    }

    /** 长按菜单「移至分组…」：第二张动作卡列未分组／各分组／新建分组。 */
    private fun showMoveToGroupMenu(entry: ClipboardManager.Entry) {
        val items = mutableListOf<Pair<String, () -> Unit>>()
        items += context.getString(R.string.clipboard_group_ungrouped) to {
            moveEntryToGroup(entry, 0L)
        }
        for (g in clipGroups) {
            items += g.name to { moveEntryToGroup(entry, g.id) }
        }
        items += context.getString(R.string.clipboard_group_new) to {
            showCreateGroupDialog(assignText = entry.text)
        }
        // 延后一帧再弹：动作卡的点击处理在回调后会 dismiss，同步弹
        // 新卡会被随后的 dismiss 一起关掉（与删除确认同口径）
        confirmOverlay.post {
            confirmOverlay.actions(
                message = context.getString(R.string.clipboard_move_to_group),
                items = items,
                cardX = Float.NaN,
                cardY = 0f,
            )
        }
    }

    private fun moveEntryToGroup(entry: ClipboardManager.Entry, groupId: Long) {
        appScope.launch {
            ClipboardManager.setGroup(context, entry.text, groupId)
            clipboardView.refresh()
        }
    }

    /** 分组药丸长按：重命名／删除。 */
    private fun showGroupActions(group: ClipboardGroup) {
        confirmOverlay.actions(
            message = group.name,
            items = listOf(
                context.getString(R.string.clipboard_group_rename) to {
                    showRenameGroupDialog(group)
                },
                context.getString(R.string.clipboard_group_delete) to {
                    confirmOverlay.post {
                        confirmOverlay.confirm(
                            message = context.getString(
                                R.string.clipboard_group_delete_confirm, group.name
                            ),
                            onConfirm = {
                                appScope.launch {
                                    ClipboardManager.deleteGroup(context, group.id)
                                    refreshClipGroups()
                                    clipboardView.refresh()
                                }
                            },
                            cardX = Float.NaN,
                            cardY = 0f,
                        )
                    }
                },
            ),
            cardX = Float.NaN,
            cardY = 0f,
        )
    }

    private fun groupNameInput(initial: String): android.widget.EditText {
        val pad = (16 * context.resources.displayMetrics.density).toInt()
        return android.widget.EditText(context).apply {
            hint = context.getString(R.string.clipboard_group_name_hint)
            inputType = android.text.InputType.TYPE_CLASS_TEXT
            setSingleLine()
            setText(initial)
            setPadding(pad, pad, pad, pad)
        }
    }

    /**
     * 新建分组输入框：assignText 非空时建好后直接把该条移进新分组
     * （从「移至分组」里新建的语义）。服务未就绪（context 不是
     * ImeInputMethodService）时静默放弃——键盘窗口无 token 时
     * showDialog 本就会放弃显示。
     */
    private fun showCreateGroupDialog(assignText: String? = null) {
        val service = context as? ImeInputMethodService ?: return
        val input = groupNameInput("")
        val dialog = android.app.AlertDialog.Builder(service)
            .setTitle(R.string.clipboard_group_create_title)
            .setView(input)
            .setPositiveButton(R.string.clipboard_group_create) { _, _ ->
                val raw = input.text?.toString().orEmpty()
                appScope.launch {
                    when (val result = ClipboardManager.createGroup(context, raw)) {
                        is ClipboardManager.CreateGroupResult.Ok -> {
                            refreshClipGroups()
                            if (assignText != null) {
                                ClipboardManager.setGroup(context, assignText, result.id)
                            }
                            clipboardView.refresh()
                        }
                        ClipboardManager.CreateGroupResult.Duplicate ->
                            showGroupToast(context.getString(R.string.clipboard_group_exists))
                        ClipboardManager.CreateGroupResult.Invalid ->
                            showGroupToast(context.getString(R.string.clipboard_group_name_invalid))
                        ClipboardManager.CreateGroupResult.Limit ->
                            showGroupToast(
                                context.getString(
                                    R.string.clipboard_group_limit,
                                    ClipboardManager.MAX_GROUPS,
                                )
                            )
                        ClipboardManager.CreateGroupResult.Error ->
                            showGroupToast(context.getString(R.string.clipboard_group_failed))
                    }
                }
            }
            .setNegativeButton(R.string.clipboard_cancel, null)
            .create()
        service.showDialog(dialog)
    }

    /** 重命名分组输入框（预填原名）。 */
    private fun showRenameGroupDialog(group: ClipboardGroup) {
        val service = context as? ImeInputMethodService ?: return
        val input = groupNameInput(group.name)
        input.setSelection(input.text?.length ?: 0)
        val dialog = android.app.AlertDialog.Builder(service)
            .setTitle(R.string.clipboard_group_rename_title)
            .setView(input)
            .setPositiveButton(R.string.clipboard_group_rename) { _, _ ->
                val raw = input.text?.toString().orEmpty()
                appScope.launch {
                    when (ClipboardManager.renameGroup(context, group.id, raw)) {
                        ClipboardManager.RenameGroupResult.Ok -> {
                            refreshClipGroups()
                            clipboardView.refresh()
                        }
                        ClipboardManager.RenameGroupResult.Duplicate ->
                            showGroupToast(context.getString(R.string.clipboard_group_exists))
                        ClipboardManager.RenameGroupResult.Invalid ->
                            showGroupToast(context.getString(R.string.clipboard_group_name_invalid))
                        ClipboardManager.RenameGroupResult.Error ->
                            showGroupToast(context.getString(R.string.clipboard_group_failed))
                    }
                }
            }
            .setNegativeButton(R.string.clipboard_cancel, null)
            .create()
        service.showDialog(dialog)
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

    // ── 剪贴板条目/常用语编辑态 ────────────────────────────────
    // 与搜索态同构：标志钉住剪贴板态，键盘输入经 interceptCommit 改道
    // 进编辑缓冲（光标模型：插入光标处），退格同搜索分流。窗口形态与
    // 搜索共用（拉高+结果区），结果区改画条目全文供多行编辑（见
    // ClipboardView 编辑显示模式）。编辑与搜索可叠加：从搜索结果长按
    // 进编辑，保存/取消后回到搜索态（clipEditReturnToSearch 记账）。
    // 常用语编辑共用同一套状态（clipEditPhrase 记账目标），保存走
    // PhraseManager.update；常用语侧无搜索态，不存在回搜索的叠加。

    override var clipEditActive: Boolean = false
        private set
    private var clipEditOriginal: String? = null
    // 非空 = 本次编辑的是常用语（与 clipEditOriginal 二选一，两编辑态互斥）：
    // 缓冲/光标/显示/工具栏与剪贴板编辑共用同一套，只有保存目标不同
    private var clipEditPhrase: PhraseManager.Phrase? = null
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
        clipEditPhrase = null
        clipEditBuffer = entry.text
        clipEditCursor = entry.text.length
        clipEditActive = true
        view.currentRenderer = newClipToolbarRenderer().also { applyEditFields(it) }
        syncEditDisplay()
        view.invalidate()
        // 窗口按 clipEditActive 重排（与搜索同形态），结果区改画编辑全文
        clipboardView.requestLayout()
    }

    /** 常用语编辑：与剪贴板编辑共用缓冲/光标/显示/工具栏，入口只换记账目标。
     *  常用语侧没有搜索态（工具栏第二槽是新增、列表不按查询过滤），
     *  clipEditReturnToSearch 恒为 false，保存/取消后直接回常用语工具栏。 */
    private fun enterPhraseEdit(phrase: PhraseManager.Phrase) {
        clipEditReturnToSearch = clipSearchActive
        clipEditOriginal = null
        clipEditPhrase = phrase
        clipEditBuffer = phrase.text
        clipEditCursor = phrase.text.length
        clipEditActive = true
        view.currentRenderer = newClipToolbarRenderer().also { applyEditFields(it) }
        syncEditDisplay()
        view.invalidate()
        clipboardView.requestLayout()
    }

    private fun exitClipEdit(save: Boolean) {
        val original = clipEditOriginal
        val phrase = clipEditPhrase
        val newText = clipEditBuffer.trim()
        clipEditActive = false
        clipEditOriginal = null
        clipEditPhrase = null
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
        if (save && phrase != null) {
            // 常用语保存：空文本不保存（与剪贴板同口径）；label 镜像新增
            // 口径随新文本再生（insert 调用点传 text.takeCodePoints(12)），pinned/
            // sortOrder/createdAt 由 PhraseManager.update 事务内回读保留
            if (newText.isNotEmpty() && newText != phrase.text.trim()) {
                appScope.launch {
                    PhraseManager.update(
                        context,
                        phrase.copy(text = newText, label = newText.takeCodePoints(12)),
                    )
                    clipboardView.refresh()
                }
            }
        } else if (save && original != null && newText.isNotEmpty() && newText != original.trim()) {
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
        clipEditPhrase = null
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

    // ── 剪贴板/常用语排序态 ──────────────────────────────────────
    // 与多选同构的工具栏形态切换；拖动与落库在 ClipboardView 与
    // Manager 侧完成，这里只管进出与工具栏。搜索过滤中（显示子集）
    // 进排序前先退搜索恢复全量列表，否则子集顺序写回会打乱全局。

    private var clipReorderActive: Boolean = false

    private fun applyReorderFields(renderer: ToolbarRenderer) {
        renderer.clipReorderMode = true
        renderer.clipReorderLabel = context.getString(R.string.clipboard_reorder_hint)
        renderer.clipDoneLabel = context.getString(R.string.clipboard_reorder_done)
    }

    private fun enterClipReorder() {
        if (clipMultiActive) exitClipMulti()
        if (clipSearchActive) exitClipSearch()
        clipReorderActive = true
        clipboardView.setReorderMode(true)
        view.currentRenderer = newClipToolbarRenderer().also { applyReorderFields(it) }
        view.invalidate()
    }

    private fun exitClipReorder() {
        if (!clipReorderActive) return
        clipReorderActive = false
        clipboardView.setReorderMode(false)
        view.currentRenderer = newClipToolbarRenderer()
        view.invalidate()
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
                // 按码点删：光标前是代理对（emoji 等）时两个 UTF-16
                // 单元一起删，只删一个会留下孤代理项并被保存落库
                val del = if (c >= 2 &&
                    clipEditBuffer[c - 1].isLowSurrogate() &&
                    clipEditBuffer[c - 2].isHighSurrogate()
                ) 2 else 1
                clipEditBuffer = clipEditBuffer.substring(0, c - del) +
                    clipEditBuffer.substring(c)
                clipEditCursor = c - del
                syncEditDisplay()
            }
            return true
        }
        if (!clipSearchActive) return false
        // 组字中退格归引擎删拼音；其余情况一律截获，绝不让退格落到目标应用删字
        if (isComposing) return false
        val q = clipboardView.searchQuery
        if (q.isNotEmpty()) {
            // 按码点丢弃：末尾是代理对时丢两个 UTF-16 单元，
            // 只丢一个会留下孤代理项、搜索瞬间无结果
            val nq = if (q.length >= 2 &&
                q[q.length - 1].isLowSurrogate() &&
                q[q.length - 2].isHighSurrogate()
            ) q.dropLast(2) else q.dropLast(1)
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

    /** 遗忘确认文案（长按遗忘与菜单遗忘共用：超 5 字截断加省略号）。 */
    private fun candidateForgetMessage(candidate: EngineMessage.Candidate): String =
        context.getString(
            R.string.candidate_forget_confirm,
            if (candidate.text.length > 5) candidate.text.take(5) + "..." else candidate.text,
        )

    /**
     * 候选上下文菜单（审计 I 项）：ConfirmOverlay 动作列居中弹出
     * （不用系统对话框：IME 服务上下文无窗口令牌；不用锚点：
     * 横条与网格的触点坐标系与 Overlay 不同，居中可避错位）。
     * 动作复用既有通道：点选 → onCandidateSelected；复制 →
     * 系统剪贴板；遗忘 → 遗忘确认（菜单本身可含遗忘，功能不丢）。
     */
    private fun showCandidateMenu(
        candidate: EngineMessage.Candidate,
        items: List<com.jobeen.ime.skin.model.CandidateMenuItem>,
    ) {
        confirmOverlay.actions(
            message = "",
            items = items.map { item ->
                item.name to {
                    when (item.action) {
                        com.jobeen.ime.skin.model.CandidateMenuAction.SELECT ->
                            listener?.onCandidateSelected(candidate)
                        com.jobeen.ime.skin.model.CandidateMenuAction.COPY -> {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE)
                                as? SystemClipboardManager
                            clipboard?.setPrimaryClip(
                                ClipData.newPlainText("candidate", candidate.text),
                            )
                        }
                        com.jobeen.ime.skin.model.CandidateMenuAction.FORGET ->
                            confirmOverlay.confirm(
                                message = candidateForgetMessage(candidate),
                                onConfirm = { handleCandidateForget(candidate) },
                            )
                    }
                }
            },
            centerHorizontal = true,
            centerVertical = true,
        )
    }

    fun onSelectionUpdate(start: Int, end: Int) {
        textEditingView.setSelection(start, end)
    }

    fun onInputChanged(text: String) {
        textEditingView.onInputChanged(text)
    }

    private fun showCopyIfRecent(text: String) {
        if (suppressCopyBanner) return
        // 接线轮 R4：提示条显示验证码优先的提示态（跟踪本条时），
        // 去重键仍用原文（提示态与原文是同一条的不同显示）。
        copyText = ClipboardManager.promptState.displayTextFor(text)
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
                                if (clipReorderActive) {
                                    clipReorderActive = false
                                    clipboardView.setReorderMode(false)
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
                            PanelAction.ClipReorderExit -> exitClipReorder()
                            PanelAction.ClipMultiDelete -> handleClipMultiDelete()

                            PanelAction.SwitchKeyboard -> {
                                when (state) {
                                    State.Menu, State.Clipboard, State.TextEditing, State.Copy, State.Performance -> state = State.Idle

                                    else -> listener?.onToolbarAction(result.action)
                                }
                            }

                            // 快捷面板（W19-6）：与 SwitchKeyboard 同口径——
                            // 浮层态先收浮层（再点一次才开面板），其余直发监听开面板。
                            PanelAction.ShortcutPanel -> {
                                when (state) {
                                    State.Menu, State.Clipboard, State.TextEditing, State.Copy, State.Performance -> state = State.Idle

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
                        if (predictions) setCandidates(emptyList(), false, 0) else v.setExpanded(true)
                    }
                }

                TouchResult.LongPressClearPhrases -> showClearPhrasesConfirm()

                is TouchResult.CandidateMenu -> showCandidateMenu(result.candidate, result.items)

                // K-predict：复用长按清空路径（空表 → State.Idle）；
                // 混批（非全联想）时回落展开，与长按口径一致。
                TouchResult.ClosePredictions -> {
                    if (state is State.Prediction) {
                        val candidates = (state as State.Prediction).candidates
                        val allPredictions = candidates.all {
                            it.type == Candidate.TYPE_IME_PREDICTION
                        }
                        if (allPredictions) {
                            // 引擎侧一并取消在途联想：迟到联想不再
                            // 把已关的栏重新顶出来（H2-2）。
                            listener?.onDismissPredictions()
                            setCandidates(emptyList(), false, 0)
                        }
                        else v.setExpanded(true)
                    }
                }

                is TouchResult.CustomKeyAction -> {
                    InputFeedbacks.hapticFeedback(view)
                    listener?.onSidePanelAction(result.action)
                }

                null -> {
                    if (state == State.Copy && copyText != null) {
                        listener?.onCopyTextCommit(copyText ?: "")
                        // 接线轮 R4：已提交的提示清掉，同一提示不再复现；
                        // 原文去重键（lastShownCopyText）仍挡回放。
                        ClipboardManager.promptState.clear()
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

    /**
     * 打开剪贴板面板（P1-4：#showPasteboardView 键动作入口，
     * 与菜单剪贴板项同目的地、同初始页签）。
     */
    fun showClipboard() {
        clipboardTab = ClipboardTab.CLIPBOARD
        clipboardView.clipTab = ClipboardTab.CLIPBOARD
        state = State.Clipboard
    }

    /** 打开性能面板（P2-10：#keyboardPerformance 键动作与菜单入口）。 */
    fun showPerformance() {
        state = State.Performance
    }

    fun showTextEditing() {
        view.setExpanded(false)
        state = State.TextEditing
    }

    fun hideTextEditing() {
        if (state == State.TextEditing) state = State.Idle
    }

    /**
     * 粘贴后返回键盘（2026-10-29 批量第 4 项）：与工具栏切键盘键在
     * 剪贴板态的回落同口径（剪贴板态 → Idle），其余态不动。
     */
    override fun returnToKeyboard() {
        if (state == State.Clipboard || state == State.Performance) state = State.Idle
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
            // 接线轮 R4：同 showCopyIfRecent，显示取提示态，记录仍用原文。
            copyText = ClipboardManager.promptState.displayTextFor(text)
            lastShownCopyTimestamp = time
            lastShownCopyText = text
            state = State.Copy
        }
    }

    @SuppressLint("UseCompatLoadingForDrawables")
    override fun setCandidates(
        list: List<EngineMessage.Candidate>,
        hasMore: Boolean,
        highlightedIndex: Int,
    ) {
        // 代次重投（I-M4）：延迟重投只执行仍是最新代次的帧。
        val generation = ++candidateGeneration
        if (!view.isLaidOut) {
            view.post {
                if (shouldApplyDeferredCandidates(generation, candidateGeneration)) {
                    setCandidates(list, hasMore, highlightedIndex)
                }
            }
            return
        }
        candidateGrid.hasMoreCandidates = hasMore
        // 「已加载原始条数」按引擎口径累计（过滤前），供追加时与 total
        // 同口径判定是否到底；过滤后条数与 total 比会多打空往返
        rawCandidateLoaded = list.size
        // 本机字体无字形的单字候选（扩展区生僻字）不进列表：显示出来
        // 是方框、点选打出对方也看不到。生僻字开关关时扩展区单字一律
        // 不进列表（与无字形过滤同口径、共用原始计数）。条带与网格
        // 共用此列表，一致
        val showRare = CandidateManager.isRareCharactersEnabled(context)
        val displayList = list.filter {
            candidateGrid.isDisplayable(it) && (showRare || !RareCharPolicy.isRareChar(it.text))
        }
        // 高亮下标经字形过滤重映射：高亮项被滤掉时无可见高亮（-1），
        // 不把绿标错给另一候选（空格提交的仍是 Rime 侧原高亮项）。
        val visibleHighlighted = list.getOrNull(highlightedIndex)
            ?.let { displayList.indexOf(it) } ?: -1
        if (clipSearchActive || clipEditActive) {
            // 文本输入态（搜索/编辑）钉住：不切换面板状态，
            // 工具栏在输入框与候选行之间轮显
            when {
                displayList.isNotEmpty() -> {
                    val r = view.currentRenderer
                    if (r is ComposingRenderer) {
                        r.candidates = displayList
                        r.highlightedIndex = visibleHighlighted
                    } else {
                        view.currentRenderer =
                            ComposingStateRender(
                                renderContext, displayList, visibleHighlighted,
                            ).createToolbarRenderer()
                    }
                }
                list.isNotEmpty() && hasMore -> {
                    // 整屏无字形且还有更多：续取穿过去（不切组字态）
                    view.post { listener?.onRequestMoreCandidates() }
                }
                else -> {
                    // 组字结束（选词已上屏改道输入，或清空）：恢复输入框工具栏。
                    // 优先序 编辑 > 多选 > 搜索：多选可叠加在搜索之上进组字，
                    // 漏掉多选分支会让工具栏恢复成搜索框而多选状态与选中
                    // 还在、却没有删除/取消/计数（隐形多选）
                    if (view.currentRenderer !is ToolbarRenderer) {
                        view.currentRenderer = newClipToolbarRenderer().also { r ->
                            when {
                                clipEditActive -> applyEditFields(r)
                                clipMultiActive -> applyMultiFields(r)
                                else -> applySearchFields(r)
                            }
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
            state = if (predictions) {
                State.Prediction(list, visibleHighlighted)
            } else {
                State.Composing(list, visibleHighlighted)
            }
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
        // 同 setCandidates：先滤掉本机无字形的单字与（开关关时）扩展区
        // 生僻字。整页被滤光说明正处在一整带无字形生僻字里——不收尾，
        // 直接续取下一页穿过去（引擎侧有 200 封顶，续取链有界；真到底
        // 时引擎回空页走上面的收尾）
        val showRare = CandidateManager.isRareCharactersEnabled(context)
        val displayable = list.filter {
            candidateGrid.isDisplayable(it) && (showRare || !RareCharPolicy.isRareChar(it.text))
        }
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
                // 追加页接在后面，前页下标不变，高亮原样保留
                state = State.Composing(merged, s.highlightedIndex)
            }
            is State.Prediction -> {
                val merged = s.candidates + displayable
                candidateGrid.hasMoreCandidates = total < 0 || rawCandidateLoaded < total
                state = State.Prediction(merged, s.highlightedIndex)
            }
            else -> {}
        }
    }

    override fun onPossibleCandidatePinYin(pinyins: List<CandidatePinYin>) {
        candidateGrid.onPossibleCandidatePinYin(pinyins)
    }

    /**
     * 面板上次按其刷新的键盘类型（G1/G2 类型感知刷新）：面板共享、
     * 不持有状态管理器，类型由窗口层在键盘变化时传进来；未传时
     * 沿用上次类型（通知/方向刷新不把类型色板打回包级），从未传
     * 过时取包级（旧行为）。
     */
    private var lastPanelType: String? = null

    override fun refreshTheme() {
        refreshTheme(null)
    }

    fun refreshTheme(type: String?) {
        val effectiveType = type ?: lastPanelType
        lastPanelType = effectiveType
        // 同一配色只解析一次再分发：旧实现每个子视图各 resolve 一遍（共 5 次，
        // 每次都读主题配置并构建整套 ColorScheme）
        renderContext.invalidateDisplaySettings()
        val colors = panelColorScheme(context, effectiveType)
        view.refreshTheme(effectiveType)
        candidateGrid.refreshTheme(context, effectiveType)
        clipboardView.refreshTheme(colors)
        menuGridView.refreshTheme(colors)
        textEditingView.refreshTheme(colors)
        confirmOverlay.refreshTheme(colors)
    }
}

/**
 * 延迟重投的代次判定（纯函数，可单测；I-M4）：只有捕获代次仍
 * 是最新代次时才执行重投——期间已有更新的 setCandidates 进入，
 * 旧帧即过期丢弃，不覆盖新表。
 */
internal fun shouldApplyDeferredCandidates(capturedGeneration: Int, currentGeneration: Int): Boolean =
    capturedGeneration == currentGeneration
