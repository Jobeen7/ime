package com.ninthsoft.ime.input.panel

import com.ninthsoft.ime.data.manager.ClipboardManager
import com.ninthsoft.ime.data.manager.PhraseManager
import com.ninthsoft.ime.engine.data.EngineMessage
import com.ninthsoft.ime.input.keyboard.key.KeyboardAction
import com.ninthsoft.ime.input.panel.component.TextEditView

interface PanelListener {
    fun onCandidateSelected(candidate: EngineMessage.Candidate) {}

    fun onToolbarAction(action: PanelAction) {}

    fun onSidePanelAction(action: KeyboardAction) {}

    fun onTextEditingAction(action: TextEditView.Action) {}

    fun onClipboardItemClick(entry: ClipboardManager.Entry) {}

    fun onPhraseClick(phrase: PhraseManager.Phrase) {}

    fun onClipboardClear() {}

    fun onClipboardItemDelete(entry: ClipboardManager.Entry) {}

    fun onCopyTextCommit(text: String) {}

    fun onCandidateGridDragComplete(candidates: List<EngineMessage.Candidate>) {}

    fun onCandidateForget(candidate: EngineMessage.Candidate) {}

    fun onEnterAddPhraseMode() {}

    fun onAddPhraseSave(text: String) {}

    fun onAddPhraseCancel() {}
}