package com.jobeen.ime.input.keyboard.window

import android.view.inputmethod.EditorInfo
import com.jobeen.ime.data.keyboard.theme.KeyboardColors
import com.jobeen.ime.engine.data.CandidatePinYin
import com.jobeen.ime.engine.data.EngineMessage
import com.jobeen.ime.input.ImeInputMethodService
import com.jobeen.ime.input.KeyActionListener
import com.jobeen.ime.input.keyboard.impl.IKeyboard
import com.jobeen.ime.input.keyboard.key.KeyActionListener as KeyboardKeyActionListener
import com.jobeen.ime.input.panel.IPanel
import com.jobeen.ime.input.panel.PanelListener

class KeyboardWindow(
    service: ImeInputMethodService,
    keyboardStateManager: KeyboardStateManager,
    panelActionListener: PanelListener? = null,
) {

    var currentEditorInfo: EditorInfo? = null

    val view: KeyboardWindowView = KeyboardWindowView(
        context = service,
        keyboardStateManager = keyboardStateManager,
        panelListener = panelActionListener,
    )

    val colors: KeyboardColors.ColorScheme get() = KeyboardColors.resolve(view.context)

    val panel: IPanel get() = view.panel

    private val messageHandler = MessageHandler(service).also { it.attach(this) }

    init {
        keyboardStateManager.callback = object : KeyboardStateManager.Callback {
            override fun onShowKeyboard(keyboard: IKeyboard) {
                view.onShowKeyboard(keyboard)
            }

            override fun onHideKeyboard(keyboard: IKeyboard) {
                view.onHideKeyboard(keyboard)
            }

            override fun onKeyboardChanged(keyboard: IKeyboard) {
                view.onKeyboardChanged(keyboard)
            }
        }
    }

    fun setKeyActionListener(listener: KeyboardKeyActionListener) {
        view.keyActionListener = listener
    }

    fun setCandidates(list: List<EngineMessage.Candidate>, hasMore: Boolean = false) {
        view.setCandidates(list, hasMore)
    }

    fun appendCandidates(list: List<EngineMessage.Candidate>, total: Int) {
        view.appendCandidates(list, total)
    }

    fun onPossibleCandidatePinYin(pinyins: List<CandidatePinYin>) {
        view.onPossibleCandidatePinYin(pinyins)
    }

    fun updateDynamicPreedit(items: List<EngineMessage.DynamicPreedit.DynamicPreeditItem>) {
        view.updateDynamicPreedit(items)
    }

    fun onSelectionUpdate(start: Int, end: Int) {
        view.panel.onSelectionUpdate(start, end)
    }

    suspend fun handleEngineMessage(message: EngineMessage) {
        messageHandler.handle(message)
    }

    fun onStartInputView(info: EditorInfo, restarting: Boolean) {
        if (!restarting) {
            view.refreshColorsIfChanged()
        }
        currentEditorInfo = info
        view.onStartInput(info)
        view.refreshLayout()
    }

    fun onFinishInputView(finishingInput: Boolean) {
        panel.onFinishInputView(finishingInput)
    }

    fun onWindowShown() = view.onAttach()

    fun onWindowHidden() = view.onDetach()

    fun onConfigChanged(key: String) = view.onConfigChanged(key)

    fun toggleVoiceLocked() = view.toggleVoiceLocked()

    fun onInputChanged(text: String, virtualInputConnection: Boolean = false) =
        view.onInputChanged(currentEditorInfo, text, virtualInputConnection)

    fun showToast(message: CharSequence) = view.showImeToast(message)

    fun onDepolyFinished() = view.onDepolyFinished()
}
