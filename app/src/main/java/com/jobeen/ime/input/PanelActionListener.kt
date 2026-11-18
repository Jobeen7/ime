package com.jobeen.ime.input

import android.content.Intent
import com.jobeen.ime.data.manager.CandidateManager
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
import com.jobeen.ime.ui.SchemaSettingsActivity
import com.jobeen.ime.base.speech.SherpaSpeechClient
import com.jobeen.ime.base.speech.cloud.BuiltinPlugins
import com.jobeen.ime.base.speech.cloud.KeystoreSecretStore
import com.jobeen.ime.base.speech.cloud.PluginMeta
import com.jobeen.ime.base.speech.cloud.PluginStore
import com.jobeen.ime.base.speech.cloud.SharedPrefsStore
import com.jobeen.ime.base.util.appScope
import com.jobeen.ime.ui.screen.PreferredProviderDisplay
import com.jobeen.ime.ui.screen.VoiceRotateResult
import com.jobeen.ime.ui.screen.nextVoiceProvider
import com.jobeen.ime.ui.screen.pluginSecretsMissingIds
import com.jobeen.ime.ui.screen.preferredProviderDisplay
import com.jobeen.ime.ui.screen.voiceProviderRows
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
            PanelAction.ShortcutPanel -> service.keyboardWindow?.view?.toggleShortcutPanel()
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

            // 调色板（皮肤库已删）：改跳外观设置页，内置主题在该页切换。
            PanelAction.Palette -> service.startActivity(
                Intent(service, com.jobeen.ime.ui.AppearanceActivity::class.java).apply {
                    addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_CLEAR_TOP or
                            Intent.FLAG_ACTIVITY_SINGLE_TOP
                    )
                })

            PanelAction.ToggleVoice -> service.keyboardWindow?.toggleVoiceLocked()

            PanelAction.VoicePlugin -> rotateVoiceProvider()

            // 简繁（工具栏路径）：与菜单同款切换逻辑；工具栏按钮无
            // 开关态着色（着色逻辑只在菜单网格），故不刷新任何开关显示，
            // 切换对后续候选批次生效（与菜单路径一致）。
            PanelAction.ToggleTraditionalChinese -> CandidateManager.setTraditionalChineseEnabled(
                service,
                !CandidateManager.isTraditionalChineseEnabled(service),
            )

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
        service.keyActionListener?.onKeyAction(KeyboardAction.CommitAction(entry.text))
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

    /**
     * 工具栏“语音插件”：轮换首选语音服务商（本地／已装云插件），与设置页
     * 同款来源与可用性判断（voiceProviderRows），结果写同一份偏好
     * （PluginStore cloud_voice，preferred_plugin 键）。下次启动语音
     * 即走新首选（tryStartCloudVoice 每次现读）；云端未开启时不切云端
     * 插件（键盘上无隐私确认框，不静默改 consent）；切换结果 toast
     * 服务商名（与设置页首选副标题同口径）。
     */
    private fun rotateVoiceProvider() {
        val context = service
        val prefs = SharedPrefsStore(context, PluginStore.PREFS_NAME)
        val store = PluginStore(context.filesDir, prefs)
        val secrets = KeystoreSecretStore(prefs)
        // 内置插件路由前种子安装（与 tryStartCloudVoice 同口径）：
        // 否则没开过语音设置也没启动过语音时，已装列表里没有豆包／阿里云。
        runCatching {
            BuiltinPlugins.ensure(store, { name ->
                context.assets.open(name).use { it.readBytes() }
            }, secrets)
        }
        val plugins = store.list()
        val preferred = store.getPreferred()
        val rows = voiceProviderRows(
            modelReady = SherpaSpeechClient.isModelReady(context),
            plugins = plugins,
            preferredId = preferred,
            secretsMissingIds = pluginSecretsMissingIds(plugins) { id, field ->
                secrets.has(id, field)
            },
        )
        val window = service.keyboardWindow
        when (val result = nextVoiceProvider(rows, preferred, store.isOptIn())) {
            is VoiceRotateResult.Rotated -> {
                store.setPreferred(result.targetPluginId)
                val meta = result.targetPluginId?.let { id ->
                    plugins.singleOrNull { it.id == id }
                }
                window?.showToast(
                    context.getString(
                        R.string.voice_provider_rotated,
                        providerDisplayName(result.targetPluginId, meta),
                    ),
                )
            }
            VoiceRotateResult.Unchanged -> {
                val meta = preferred?.let { id -> plugins.singleOrNull { it.id == id } }
                window?.showToast(
                    context.getString(
                        R.string.voice_provider_rotated,
                        providerDisplayName(preferred, meta),
                    ),
                )
            }
            VoiceRotateResult.NoUsable -> {
                window?.showToast(context.getString(R.string.voice_provider_hint_no_engine))
            }
        }
    }

    /** 首选服务商显示名（与设置页首选副标题同源：本地／内置展示名／原始 id）。 */
    private fun providerDisplayName(preferredId: String?, preferredMeta: PluginMeta?): String =
        when (val display = preferredProviderDisplay(preferredId, preferredMeta)) {
            PreferredProviderDisplay.Local -> service.getString(R.string.voice_provider_local)
            is PreferredProviderDisplay.BuiltinName -> service.getString(display.resId)
            is PreferredProviderDisplay.RawId -> display.id
        }

    override fun onCommandResultCommit(text: String) {
        // AI 结果字面上屏：不经 Qwerty 标点全半角转换、不成对，
        // 选中文字时替换选区、无选区时在光标处插入。
        service.keyActionListener?.onKeyAction(KeyboardAction.LiteralCommitAction(text))
    }

    override fun onCandidateGridDragComplete(candidates: List<EngineMessage.Candidate>) {
        service.engine?.resortCandidates(candidates)
    }

    override fun onRequestMoreCandidates() {
        service.engine?.loadMoreCandidates()
    }

    override fun onPageCandidates(backward: Boolean) {
        service.engine?.pageCandidates(backward)
    }

    override fun onDismissPredictions() {
        service.engine?.dismissPredictions()
    }

    override fun onCandidateForget(candidate: EngineMessage.Candidate) {
        DeletedWordsStore.add(candidate.text)
        // 预测候选、生僻字补字候选、邮箱后缀候选与常用语输入码候选的 index 不是 Rime 组字序号，
        // 给它写墓碑会误伤同序号的真实候选；这类词只靠上面的 App 层过滤隐藏即可
        if (candidate.type != EngineMessage.Candidate.TYPE_IME_PREDICTION &&
            candidate.type != EngineMessage.Candidate.TYPE_RARE_CHAR &&
            candidate.type != EngineMessage.Candidate.TYPE_EMAIL_SUFFIX &&
            candidate.type != EngineMessage.Candidate.TYPE_PHRASE_SHORTCUT
        ) {
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
