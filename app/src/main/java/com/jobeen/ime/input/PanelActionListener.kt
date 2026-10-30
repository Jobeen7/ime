package com.jobeen.ime.input

import android.content.Intent
import com.jobeen.ime.data.manager.ClipboardManager
import com.jobeen.ime.data.manager.DeletedWordsStore
import com.jobeen.ime.data.manager.PhraseManager
import com.jobeen.ime.data.manager.takeCodePoints
import com.jobeen.ime.R
import com.jobeen.ime.engine.data.EngineMessage
import com.jobeen.ime.input.keyboard.impl.EmojiKeyboard
import com.jobeen.ime.input.keyboard.impl.SymbolKeyboard
import com.jobeen.ime.input.keyboard.key.KeyboardAction
import com.jobeen.ime.input.panel.PanelAction
import com.jobeen.ime.input.panel.PanelListener
import com.jobeen.ime.input.panel.component.TextEditView
import com.jobeen.ime.ui.AboutActivity
import com.jobeen.ime.ui.MainActivity
import com.jobeen.ime.ui.SkinLibraryActivity
import com.jobeen.ime.ui.SchemaSettingsActivity
import com.jobeen.ime.base.util.appScope
import kotlinx.coroutines.launch

class PanelActionListener(
    private val service: ImeInputMethodService,
) : PanelListener {

    /**
     * 部署／同步菜单入口回调（重点候选 8-10）：由服务层挂接
     * KeyboardDeployController 的请求＋确认＋进度提示；未挂接时
     * 点按无动作（绝不静默部署）。
     */
    var onDeploySyncRequest: (() -> Unit)? = null

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
            PanelAction.DeploySync -> onDeploySyncRequest?.invoke()
            PanelAction.Undo -> service.engine?.undo(service)
            PanelAction.Redo -> service.engine?.redo(service)
            PanelAction.SelectAll ->
                service.activeInputConnection()?.performContextMenuAction(android.R.id.selectAll)
            PanelAction.Copy ->
                service.activeInputConnection()?.performContextMenuAction(android.R.id.copy)
            PanelAction.Paste ->
                service.activeInputConnection()?.performContextMenuAction(android.R.id.paste)

            // 调色板（主题设置页脱钩后改跳库页：内置主题与皮肤卡
            // 同页切换；原主题设置页不再有任何 UI 入口，仅保留类与
            // 注册，见报告——旧主题分享/刷新/内置跟随开关随之无 UI 入口）。
            PanelAction.Palette -> service.startActivity(
                Intent(service, SkinLibraryActivity::class.java).apply {
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
        service.keyActionListener?.onKeyAction(action)
    }

    override fun onTextEditingAction(action: TextEditView.Action) {
        service.handleTextEditingAction(action)
    }

    override fun onClipboardItemClick(entry: ClipboardManager.Entry) {
        // 粘贴输出规则（2026-10-29 批量第 4 项）：上屏前按规则变换文本
        val text = ClipboardManager.applyOutputRule(
            entry.text, ClipboardManager.getOutputRule(service)
        )
        service.keyActionListener?.onKeyAction(KeyboardAction.CommitAction(text))
        // 粘贴后返回：收起剪贴板面板回键盘（默认关，不动旧行为）
        if (ClipboardManager.isReturnAfterPasteEnabled(service)) {
            service.keyboardWindow?.panel?.returnToKeyboard()
        }
    }

    override fun onPhraseClick(phrase: PhraseManager.Phrase) {
        service.keyActionListener?.onKeyAction(KeyboardAction.CommitAction(phrase.text))
    }

    override fun onClipboardClear() {
        appScope.launch { ClipboardManager.clearAll(service) }
    }

    override fun onClipboardItemDelete(entry: ClipboardManager.Entry) {
        appScope.launch { ClipboardManager.removeEntry(service, entry.text) }
    }

    override fun onCopyTextCommit(text: String) {
        service.keyActionListener?.onKeyAction(KeyboardAction.CommitAction(text))
    }

    override fun onCandidateGridDragComplete(candidates: List<EngineMessage.Candidate>) {
        service.engine?.resortCandidates(candidates)
    }

    override fun onRequestMoreCandidates() {
        service.engine?.loadMoreCandidates()
    }

    override fun onCandidateForget(candidate: EngineMessage.Candidate) {
        DeletedWordsStore.add(candidate.text)
        // 预测候选的 index 不是 Rime 组字序号，给它写墓碑会误伤同序号的真实候选；
        // 预测词只靠上面的 App 层过滤隐藏即可
        if (candidate.type != EngineMessage.Candidate.TYPE_IME_PREDICTION) {
            service.engine?.deleteCandidate(candidate.index)
        }
    }

    override fun onEnterAddPhraseMode() {
        service.virtualInputConnection.clear()
        service.phraseAddBridgeActive = true
        service.keyboardWindow?.view?.enterAddPhraseMode(service.virtualInputConnection)
        service.syncActiveInputState()
    }

    override fun onAddPhraseSave(text: String) {
        appScope.launch {
            val id = PhraseManager.insert(service, text, text.takeCodePoints(12))
            if (id > 0) {
                service.keyboardWindow?.showToast(service.getString(R.string.phrase_add_success))
            }
            service.phraseAddBridgeActive = false
            service.keyboardWindow?.view?.exitAddPhraseMode()
            service.keyboardWindow?.panel?.exitAddPhraseMode()
            service.engine?.onInputCleared()
        }
    }

    override fun onAddPhraseCancel() {
        service.phraseAddBridgeActive = false
        service.keyboardWindow?.view?.exitAddPhraseMode()
        service.keyboardWindow?.panel?.exitAddPhraseMode()
        service.engine?.onInputCleared()
    }
}
