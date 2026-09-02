package com.ninthsoft.ime.input.panel

import android.annotation.SuppressLint
import android.content.Context
import com.ninthsoft.ime.R
import com.ninthsoft.ime.base.feedback.InputFeedbacks
import com.ninthsoft.ime.data.keyboard.theme.KeyboardColors
import com.ninthsoft.ime.data.manager.ClipboardManager
import com.ninthsoft.ime.data.manager.PhraseManager
import com.ninthsoft.ime.data.manager.KeyboardManager
import com.ninthsoft.ime.data.manager.CandidateManager
import com.ninthsoft.ime.engine.data.CandidatePinYin
import com.ninthsoft.ime.engine.data.EngineMessage
import com.ninthsoft.ime.input.panel.component.CandidateGridView
import com.ninthsoft.ime.input.panel.component.ClipboardView
import com.ninthsoft.ime.input.panel.component.ClipboardTab
import com.ninthsoft.ime.input.panel.component.ConfirmOverlay
import com.ninthsoft.ime.input.panel.component.TextEditView
import com.ninthsoft.ime.input.panel.component.MenuGridView
import com.ninthsoft.ime.input.panel.ComposingRenderer
import com.ninthsoft.ime.input.panel.state.ComposingStateRender
import com.ninthsoft.ime.input.panel.state.CopyStateRender
import com.ninthsoft.ime.input.panel.state.IdleStateRender
import com.ninthsoft.ime.input.panel.toolbar.ToolbarRenderer
import com.ninthsoft.ime.input.panel.toolbar.ToolbarRendererResources
import com.ninthsoft.ime.input.panel.state.IStateRender
import com.ninthsoft.ime.input.panel.state.MenuStateRender
import com.ninthsoft.ime.input.panel.state.ClipboardStateRender
import com.ninthsoft.ime.input.panel.state.PredictionStateRender
import com.ninthsoft.ime.input.panel.state.StateRenderContext
import com.ninthsoft.ime.input.panel.state.TextEditingStateRender
import timber.log.Timber

class KawaiiPanel(
    val context: Context,
    var listener: PanelListener? = null,
) : IPanel {

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
            if (field == value) return
            if (field is State.Composing && value is State.Composing && view.isExpanded) {
                candidateGrid.updateCandidates(value.candidates)
                (view.currentRenderer as? ComposingRenderer)?.candidates = value.candidates
                field = value
                return
            }
            if (field is State.Prediction && value is State.Prediction && view.isExpanded) {
                candidateGrid.updateCandidates(value.candidates)
                (view.currentRenderer as? ComposingRenderer)?.candidates = value.candidates
                field = value
                return
            }
            field = value
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
    private var clipboardCheckRunnable: Runnable? = null
    private var lastShownCopyTimestamp: Long = 0L
    private var lastShownCopyText: String? = null

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
        /*onWordForget = { candidate, x, y ->
            confirmOverlay.confirm(
                message = context.getString(
                    R.string.candidate_forget_confirm,
                    if (candidate.text.length > 5) candidate.text.take(5) + "..." else candidate.text
                ),
                onConfirm = { handleCandidateForget(candidate) },
                cardX = x,
                cardY = y,
            )
        }*/
        onDragComplete = { candidates ->
            (view.currentRenderer as? ComposingRenderer)?.candidates = candidates
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
            context.getDrawable(R.drawable.ic_keyboard_undo),
            context.getDrawable(R.drawable.ic_keyboard_redo),
            context.getDrawable(R.drawable.ic_keyboard_palette),
            context.getDrawable(R.drawable.ic_keyboard_cursor_move),
            context.getDrawable(R.drawable.ic_keyboard_keyboard_close),
            context.getDrawable(R.drawable.ic_keyboard_trash),
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
        clipboardView.onItemLongClick = { entry, x, y ->
            Timber.d("clipboard longClick: cardX=$x cardY=$y")
            confirmOverlay.confirm(
                message = context.getString(
                    R.string.clipboard_delete_confirm,
                    if (entry.text.length > 5) entry.text.take(5) + "..." else entry.text
                ),
                onConfirm = { handleClipboardDelete(entry) },
                cardX = x + 100,
                cardY = y + 100,
            )
        }

        ClipboardManager.onNewEntry = { entry ->
            showCopyIfRecent(entry.text)
        }

        ClipboardManager.onContentChanged = {
            if (state == State.Menu) {
                clipboardView.refresh()
            }
        }

        clipboardView.onPhraseClick = { phrase ->
            listener?.onPhraseClick(phrase)
        }
        clipboardView.onPhraseDelete = { phrase ->
            confirmOverlay.confirm(
                message = context.getString(R.string.phrase_delete_confirm, phrase.label),
                onConfirm = {
                    PhraseManager.delete(context, phrase.id)
                    clipboardView.refresh()
                },
                cardX = Float.NaN,
                cardY = 0f,
            )
        }
        PhraseManager.onContentChanged = {
            if (state == State.Menu) {
                clipboardView.refresh()
            }
        }

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
                PhraseManager.deleteAll(context)
                clipboardView.refresh()
            },
            cardX = Float.NaN,
            cardY = 0f,
        )
    }

    private fun handleClipboardClear() {
        listener?.onClipboardClear()
        clipboardView.refresh()
    }

    private fun handleClipboardDelete(entry: ClipboardManager.Entry) {
        listener?.onClipboardItemDelete(entry)
        clipboardView.refresh()
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
                            if (it.type != EngineMessage.Candidate.CandidateType.Prediction) {
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
        view.removeCallbacks(clipboardCheckRunnable)
        if (addPhraseActive) {
            addPhraseActive = false
            listener?.onAddPhraseCancel()
        }
        state = State.Idle
    }

    fun onStartInputView() {
        if (clipboardCheckRunnable == null) {
            clipboardCheckRunnable = object : Runnable {
                override fun run() {
                    ClipboardManager.checkCurrentClipboard(context)
                    checkPendingCopy()
                    view.postDelayed(this, 2000L)
                }
            }
        }
        ClipboardManager.checkCurrentClipboard(context)
        checkPendingCopy()
        view.postDelayed({
            ClipboardManager.checkCurrentClipboard(context)
            checkPendingCopy()
        }, 500L)
        view.removeCallbacks(clipboardCheckRunnable!!)
        view.postDelayed(clipboardCheckRunnable!!, 2000L)
    }

    private fun checkPendingCopy() {
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
    override fun setCandidates(list: List<EngineMessage.Candidate>) {
        if (!view.isLaidOut) {
            view.post { setCandidates(list) }
            return
        }
        if (list.isEmpty()) {
            view.setExpanded(false)
            view.scrollX = 0f
            if (state !is State.Menu) state = State.Idle
        } else if (state is State.TextEditing) {
            // 编辑态下保持工具栏渲染器，不切换为组字渲染器（右侧入口才正确）
            applyStateRender(state)
        } else {
            if (state is State.Copy) state = State.Idle
            view.scrollX = 0f
            var predictions = true
            list.forEach {
                if (it.type != EngineMessage.Candidate.CandidateType.Prediction) {
                    predictions = false
                    return@forEach
                }
            }
            state = if (predictions) State.Prediction(list) else State.Composing(list)

            if (view.isExpanded) {
                candidateGrid.updateCandidates(list)
            }
        }
        view.invalidate()
    }

    override fun onPossibleCandidatePinYin(pinyins: List<CandidatePinYin>) {
        candidateGrid.onPossibleCandidatePinYin(pinyins)
    }

    override fun refreshTheme() {
        view.refreshTheme()
        candidateGrid.refreshTheme(context)
        clipboardView.refreshTheme(KeyboardColors.resolve(context))
        menuGridView.refreshTheme(KeyboardColors.resolve(context))
        textEditingView.refreshTheme(KeyboardColors.resolve(context))
        confirmOverlay.refreshTheme(KeyboardColors.resolve(context))
    }
}
