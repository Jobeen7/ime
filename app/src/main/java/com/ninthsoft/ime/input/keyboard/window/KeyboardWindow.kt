package com.ninthsoft.ime.input.keyboard.window

import android.view.inputmethod.EditorInfo
import com.ninthsoft.ime.data.keyboard.theme.KeyboardColors
import com.ninthsoft.ime.engine.data.CandidatePinYin
import com.ninthsoft.ime.engine.data.EngineMessage
import com.ninthsoft.ime.input.ImeInputMethodService
import com.ninthsoft.ime.input.KeyActionListener
import com.ninthsoft.ime.input.keyboard.impl.IKeyboard
import com.ninthsoft.ime.input.keyboard.key.KeyActionListener as KeyboardKeyActionListener
import com.ninthsoft.ime.input.panel.IPanel
import com.ninthsoft.ime.input.panel.PanelListener

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

    fun setCandidates(list: List<EngineMessage.Candidate>) {
        view.setCandidates(list)
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
