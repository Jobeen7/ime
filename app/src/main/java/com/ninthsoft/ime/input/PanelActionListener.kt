package com.ninthsoft.ime.input

import android.content.Intent
import com.ninthsoft.ime.data.manager.ClipboardManager
import com.ninthsoft.ime.data.manager.PhraseManager
import com.ninthsoft.ime.R
import com.ninthsoft.ime.engine.data.EngineMessage
import com.ninthsoft.ime.input.keyboard.impl.EmojiKeyboard
import com.ninthsoft.ime.input.keyboard.impl.SymbolKeyboard
import com.ninthsoft.ime.input.keyboard.key.KeyboardAction
import com.ninthsoft.ime.input.panel.PanelAction
import com.ninthsoft.ime.input.panel.PanelListener
import com.ninthsoft.ime.input.panel.component.TextEditView
import com.ninthsoft.ime.ui.AboutActivity
import com.ninthsoft.ime.ui.KeyboardSettingsActivity
import com.ninthsoft.ime.ui.MainActivity
import com.ninthsoft.ime.ui.SchemaSettingsActivity

class PanelActionListener(
    private val service: ImeInputMethodService,
) : PanelListener {

    override fun onCandidateSelected(candidate: EngineMessage.Candidate) {
        service.engine?.selectCandidate(candidate)
    }

    override fun onToolbarAction(action: PanelAction) {
        when (action) {
            PanelAction.CloseKeyboard -> service.requestHideSelf(0)
            PanelAction.SwitchKeyboard -> service.keyboardWindow?.view?.toggleMenu()
            PanelAction.EmojiKeyboard -> service.keyboardWindow?.view?.switchKeyboard(
                EmojiKeyboard.NAME
            )

            PanelAction.SymbolKeyboard -> service.keyboardWindow?.view?.switchKeyboard(
                SymbolKeyboard.NAME
            )

            PanelAction.ReloadEngine -> service.engine?.reload()
            PanelAction.Undo -> service.engine?.undo(service)
            PanelAction.Redo -> service.engine?.redo(service)

            PanelAction.Palette -> service.startActivity(
                Intent(service, KeyboardSettingsActivity::class.java).apply {
                    addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_CLEAR_TOP or
                            Intent.FLAG_ACTIVITY_SINGLE_TOP
                    )
                })

            PanelAction.ToggleVoice -> service.keyboardWindow?.toggleVoiceLocked()

            PanelAction.Settings -> service.startActivity(
                Intent(service, MainActivity::class.java).apply {
                    addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_CLEAR_TOP or
                            Intent.FLAG_ACTIVITY_SINGLE_TOP
                    )
                })

            PanelAction.SchemaSettings -> service.startActivity(
                Intent(service, SchemaSettingsActivity::class.java).apply {
                    addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_CLEAR_TOP or
                            Intent.FLAG_ACTIVITY_SINGLE_TOP
                    )
                })

            PanelAction.About -> service.startActivity(
                Intent(service, AboutActivity::class.java).apply {
                    addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_CLEAR_TOP or
                            Intent.FLAG_ACTIVITY_SINGLE_TOP
                    )
                })

            else -> {}
        }
    }

    override fun onSidePanelAction(action: KeyboardAction) {
        service.keyActionListener.onKeyAction(action)
    }

    override fun onTextEditingAction(action: TextEditView.Action) {
        service.handleTextEditingAction(action)
    }

    override fun onClipboardItemClick(entry: ClipboardManager.Entry) {
        service.keyActionListener.onKeyAction(KeyboardAction.CommitAction(entry.text))
    }

    override fun onPhraseClick(phrase: PhraseManager.Phrase) {
        service.keyActionListener.onKeyAction(KeyboardAction.CommitAction(phrase.text))
    }

    override fun onClipboardClear() {
        ClipboardManager.clearAll(service)
    }

    override fun onClipboardItemDelete(entry: ClipboardManager.Entry) {
        ClipboardManager.removeEntry(service, entry.text)
    }

    override fun onCopyTextCommit(text: String) {
        service.keyActionListener.onKeyAction(KeyboardAction.CommitAction(text))
    }

    override fun onCandidateGridDragComplete(candidates: List<EngineMessage.Candidate>) {
        service.engine?.resortCandidates(candidates)
    }

    override fun onCandidateForget(candidate: EngineMessage.Candidate) {
        service.engine?.deleteCandidate(candidate.index)
    }

    override fun onEnterAddPhraseMode() {
        service.virtualInputConnection.clear()
        service.phraseAddBridgeActive = true
        service.keyboardWindow?.view?.enterAddPhraseMode(service.virtualInputConnection)
        service.syncActiveInputState()
    }

    override fun onAddPhraseSave(text: String) {
        val id = PhraseManager.insert(service, text, text.take(12))
        if (id > 0) {
            service.keyboardWindow?.showToast(service.getString(R.string.phrase_add_success))
        }
        service.phraseAddBridgeActive = false
        service.keyboardWindow?.view?.exitAddPhraseMode()
        service.keyboardWindow?.panel?.exitAddPhraseMode()
        service.engine?.onInputCleared()
    }

    override fun onAddPhraseCancel() {
        service.phraseAddBridgeActive = false
        service.keyboardWindow?.view?.exitAddPhraseMode()
        service.keyboardWindow?.panel?.exitAddPhraseMode()
        service.engine?.onInputCleared()
    }
}
