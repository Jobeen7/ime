package com.ninthsoft.ime.input

import android.content.SharedPreferences
import android.content.res.Configuration
import android.inputmethodservice.InputMethodService
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import com.ninthsoft.ime.ImeApplication
import com.ninthsoft.ime.base.util.InputConnectionUtil
import com.ninthsoft.ime.data.manager.CandidateManager
import com.ninthsoft.ime.data.manager.ClipboardManager
import com.ninthsoft.ime.data.manager.KeyboardManager
import com.ninthsoft.ime.data.manager.SchemaManager
import com.ninthsoft.ime.engine.EngineFactory
import com.ninthsoft.ime.engine.IEngine
import com.ninthsoft.ime.input.keyboard.window.KeyboardWindow
import com.ninthsoft.ime.input.keyboard.window.KeyboardStateManager
import com.ninthsoft.ime.input.panel.component.TextEditView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class ImeInputMethodService : InputMethodService() {
    internal val engine: IEngine? get() = EngineFactory.current()
    internal var keyboardWindow: KeyboardWindow? = null
    internal lateinit var keyActionListener: KeyActionListener

    /** 桥接模式：添加常用语时为真，所有提交/删除/语音输出写入 [virtualInputConnection] 而非真实编辑器。 */
    var phraseAddBridgeActive = false
    val virtualInputConnection = ImeInputConnection(this)

    fun activeInputConnection(): android.view.inputmethod.InputConnection? =
        if (phraseAddBridgeActive) virtualInputConnection else currentInputConnection
    var scope: CoroutineScope? = null
    var messageObserveJob: Job? = null
    private var showingDialog: android.app.Dialog? = null
    private var lastSelectionStart = 0
    private var lastSelectionEnd = 0
    private val themePrefs: SharedPreferences by lazy {
        getSharedPreferences(KeyboardManager.PREFS_NAME, MODE_PRIVATE)
    }

    private val schemaPrefs: SharedPreferences by lazy {
        getSharedPreferences(SchemaManager.PREFS_NAME, MODE_PRIVATE)
    }

    private val candidatePrefs: SharedPreferences by lazy {
        getSharedPreferences(CandidateManager.PREFS_NAME, MODE_PRIVATE)
    }

    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        keyboardWindow?.onConfigChanged(key.orEmpty())
    }

    override fun onCreate() {
        super.onCreate()
        virtualInputConnection.addOnChangeListener {
            if (phraseAddBridgeActive) syncActiveInputState()
        }
        //service scope & message subscribe
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main).apply {
            launch {
                val app = applicationContext as? ImeApplication
                // 监听应用状态，当引擎启动的时候时开始绑定消息
                app?.state?.collectLatest { state ->
                    if (state == ImeApplication.AppState.EngineStarting) {
                        messageObserveJob = engine?.observeMessages(this) { message ->
                            keyboardWindow?.handleEngineMessage(message)
                        }
                    }
                }
            }
        }
        // KeyActionListener & prefrece change listenter
        keyActionListener = KeyActionListener(service = this)
        themePrefs.registerOnSharedPreferenceChangeListener(prefsListener)
        schemaPrefs.registerOnSharedPreferenceChangeListener(prefsListener)
        candidatePrefs.registerOnSharedPreferenceChangeListener(prefsListener)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        keyboardWindow?.view?.refreshColors()
    }

    override fun onCreateInputView(): View {
        keyboardWindow?.let {
            // InputMethodService adds the returned root to its own container.
            // Detach it first when the same window instance is requested again.
            (it.view.parent as? ViewGroup)?.removeView(it.view)
            return it.view
        }
        val window = KeyboardWindow(
            service = this,
            keyboardStateManager = KeyboardStateManager,
            panelActionListener = PanelActionListener(this),
        )
        keyboardWindow = window
        window.setKeyActionListener(keyActionListener)
        // KeyboardStateManager 是进程级单例，其键盤注册表可能残留上一实例（旧配色）的键盘；
        // 新建窗口（销毁重建路径）时重建一次，让键盘用本次实例解析出的新配色生成。
        KeyboardStateManager.rebuild()
        return window.view
    }

    override fun onStartInputView(info: EditorInfo, restarting: Boolean) {
        keyboardWindow?.onStartInputView(info, restarting)
        engine?.onStartInputView(currentInputConnection)
        notifyInputChanged()
        super.onStartInputView(info, restarting)
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        showingDialog?.dismiss()
        engine?.resetComposition()
        keyboardWindow?.onFinishInputView(finishingInput)
        engine?.onFinishInputView()
        super.onFinishInputView(finishingInput)
    }

    override fun onWindowHidden() {
        keyboardWindow?.onWindowHidden()
        ClipboardManager.stopMonitoring(this)
        super.onWindowHidden()
    }

    override fun onWindowShown() {
        super.onWindowShown()
        keyboardWindow?.onWindowShown()
        ClipboardManager.startMonitoring(this)
        if (messageObserveJob == null) {
            messageObserveJob = scope?.let { scope ->
                engine?.observeMessages(scope) {
                    keyboardWindow?.handleEngineMessage(it)
                }
            }
        }
    }

    override fun onDestroy() {
        messageObserveJob?.cancel()
        scope?.cancel()
        scope = null
        keyboardWindow = null

        ClipboardManager.stopMonitoring(this)
        themePrefs.unregisterOnSharedPreferenceChangeListener(prefsListener)
        schemaPrefs.unregisterOnSharedPreferenceChangeListener(prefsListener)
        candidatePrefs.unregisterOnSharedPreferenceChangeListener(prefsListener)
        super.onDestroy()
    }

    override fun onEvaluateFullscreenMode(): Boolean = false

    override fun onEvaluateInputViewShown(): Boolean {
        super.onEvaluateInputViewShown()
        return true
    }

    fun showDialog(dialog: android.app.Dialog) {
        showingDialog?.dismiss()
        val tokenView = keyboardWindow?.view ?: return
        dialog.window?.apply {
            attributes.token = tokenView.windowToken
            attributes.type = WindowManager.LayoutParams.TYPE_APPLICATION_ATTACHED_DIALOG
            addFlags(
                WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM or WindowManager.LayoutParams.FLAG_DIM_BEHIND
            )
            setDimAmount(0.5f)
        }
        dialog.setOnDismissListener { showingDialog = null }
        dialog.show()
        showingDialog = dialog
    }

    internal fun handleTextEditingAction(action: TextEditView.Action) {
        val ic = activeInputConnection()
        when (action) {
            is TextEditView.Action.MoveLeft -> sendCombinationKeyEvent(
                ic, KeyEvent.KEYCODE_DPAD_LEFT, action.shift
            )

            is TextEditView.Action.MoveRight -> sendCombinationKeyEvent(
                ic, KeyEvent.KEYCODE_DPAD_RIGHT, action.shift
            )

            is TextEditView.Action.MoveUp -> sendCombinationKeyEvent(
                ic, KeyEvent.KEYCODE_DPAD_UP, action.shift
            )

            is TextEditView.Action.MoveDown -> sendCombinationKeyEvent(
                ic, KeyEvent.KEYCODE_DPAD_DOWN, action.shift
            )

            is TextEditView.Action.MoveHome -> sendCombinationKeyEvent(
                ic, KeyEvent.KEYCODE_MOVE_HOME, action.shift
            )

            is TextEditView.Action.MoveEnd -> sendCombinationKeyEvent(
                ic, KeyEvent.KEYCODE_MOVE_END, action.shift
            )

            TextEditView.Action.SelectToggle -> { /* local state toggle handled in view */
            }

            TextEditView.Action.CancelSelection -> {
                if (ic != null) {
                    val selection = if (phraseAddBridgeActive) virtualInputConnection.selection
                    else lastSelectionStart to lastSelectionEnd
                    if (selection.first != selection.second) {
                        ic.setSelection(selection.second, selection.second)
                    }
                }
            }

            TextEditView.Action.SelectAll -> ic?.performContextMenuAction(android.R.id.selectAll)
            TextEditView.Action.Cut -> ic?.performContextMenuAction(android.R.id.cut)
            TextEditView.Action.Copy -> ic?.performContextMenuAction(android.R.id.copy)
            TextEditView.Action.Paste -> ic?.performContextMenuAction(android.R.id.paste)

            TextEditView.Action.Backspace -> engine?.processKey(
                this, com.ninthsoft.ime.engine.event.KeyEvent.CodeEvent(
                    KeyEvent.KEYCODE_DEL, com.ninthsoft.ime.engine.event.KeyModifiers.Empty
                )
            )
        }
    }

    private fun sendCombinationKeyEvent(
        ic: android.view.inputmethod.InputConnection?, keyCode: Int, shift: Boolean,
    ) {
        InputConnectionUtil.sendCombinationKeyEvent(ic, keyCode, shift = shift)
    }

    override fun onUpdateSelection(
        oldSelStart: Int, oldSelEnd: Int,
        newSelStart: Int, newSelEnd: Int,
        candidatesStart: Int, candidatesEnd: Int,
    ) {
        super.onUpdateSelection(
            oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd
        )
        lastSelectionStart = newSelStart
        lastSelectionEnd = newSelEnd
        keyboardWindow?.onSelectionUpdate(newSelStart, newSelEnd)
        notifyInputChanged()
    }

    internal fun syncActiveInputState() {
        val ic = activeInputConnection() ?: return
        val selection = if (phraseAddBridgeActive) virtualInputConnection.selection
        else lastSelectionStart to lastSelectionEnd
        keyboardWindow?.onSelectionUpdate(selection.first, selection.second)
        keyboardWindow?.onInputChanged(
            ic.getTextBeforeCursor(Int.MAX_VALUE, 0)?.toString().orEmpty() +
                ic.getTextAfterCursor(Int.MAX_VALUE, 0)?.toString().orEmpty(),
            virtualInputConnection = phraseAddBridgeActive,
        )
    }

    fun notifyInputChanged() {
        val ic = activeInputConnection() ?: return
        var text = ic.getTextBeforeCursor(1, 0)?.toString() ?: ""
        text += ic.getTextAfterCursor(1, 0)?.toString() ?: ""
        keyboardWindow?.onInputChanged(text, virtualInputConnection = phraseAddBridgeActive)
        if (text.isEmpty()) engine?.onInputCleared()
    }
}
