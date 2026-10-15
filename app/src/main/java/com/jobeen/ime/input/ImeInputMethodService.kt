package com.jobeen.ime.input

import android.content.SharedPreferences
import android.content.res.Configuration
import android.inputmethodservice.InputMethodService
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import com.jobeen.ime.ImeApplication
import com.jobeen.ime.base.feedback.InputFeedbacks
import com.jobeen.ime.base.speech.VoiceUndoLedger
import com.jobeen.ime.base.util.InputConnectionUtil
import com.jobeen.ime.data.manager.CandidateManager
import com.jobeen.ime.data.manager.ClipboardManager
import com.jobeen.ime.data.manager.KeyboardManager
import com.jobeen.ime.data.manager.SchemaManager
import com.jobeen.ime.engine.EngineFactory
import com.jobeen.ime.engine.IEngine
import com.jobeen.ime.input.keyboard.window.KeyboardWindow
import com.jobeen.ime.input.keyboard.window.KeyboardStateManager
import com.jobeen.ime.input.panel.component.TextEditView
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
    internal var keyActionListener: KeyActionListener? = null

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

    /** onUpdateSelection 的探针合并：窗口内连发只保留最后一次 */
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val pendingNotifyInputChanged = Runnable { notifyInputChanged() }

    /**
     * 一帧探针缓存：同一输入会话、同一选区签名下，[syncActiveInputState]
     * 与 [notifyInputChanged] 共用一次探针结果——Commit 后两者常成对
     * 触发，旧实现对同一光标位置连做两轮跨进程探针。签名取选区，是
     * 因为编辑动作必伴选区变化；换输入框时整帧作废（见
     * [invalidateProbeFrame]），不会把旧框的探针带进新框。签名另含
     * [localWriteGen]：本端刚写入、宿主选区回调还没到时，服务侧记录
     * 的仍是旧选区，只按选区作键会命中写入前的旧帧（空框首上屏后被
     * 误判已清空的原故障）。
     */
    private var probeFrameText: String? = null
    private var probeFrameBridge = false
    private var probeFrameSelStart = -1
    private var probeFrameSelEnd = -1
    private var probeFrameWriteGen = -1

    /**
     * 本端写代次：主通道每写入一次连接就递增，进探针帧签名使帧失效。
     * 写入点覆盖审计（逐个 grep 核过）：
     * ①主通道：MessageHandler 的 Commit 上屏——写入处调 [noteLocalWrite]，
     * 文本同步落地，探针帧同步失效，无缺口；
     * ②靠选区签名跟随（与①不同，不构成无条件覆盖）：
     * [handleTextEditingAction] 的剪切/粘贴、直写方（语音组字/上屏/
     * 收尾、撤销账本的删与写、引擎清全文与收尾组字、面板粘贴）。
     * 剪切/粘贴调用处虽也调了 [noteLocalWrite]，但经
     * performContextMenuAction 由宿主异步落地：落地前探针回填的帧
     * 已带新代次，宿主变更随后到达且选区数值不变时仍可能命中该帧，
     * 与直写方同口径；其余直写方的文本写入都伴随光标移动，宿主选区
     * 回调改掉选区签名后合并探针必然真探；
     * ③纯选区/按键类（方向键组合、performEditorAction、选区设置）
     * 不改文本内容，不入此列。
     * 已知缺口：②类写入若在文本空/非空翻转的同时数值选区恰好不变、
     * 且宿主不发选区回调，帧仍可能命中旧结果——现行直写方无此形态
     * （写入必移光标、或不改变空/非空结论；剪切/粘贴的落地面由宿主
     * 决定、不在本端控制内），新增同类写入时须回看此审计。
     */
    private var localWriteGen = 0

    /** 主通道写入连接后调用：递增写代次，使当帧探针缓存失效。 */
    internal fun noteLocalWrite() {
        localWriteGen++
    }
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
            if (phraseAddBridgeActive) {
                // 桥接文本是本地 Editable：程序性改字（清空/替换）可以
                // 不伴随选区变化，只按选区作键的帧探针缓存会返回旧
                // 结果（K3），文本变更回调里先整帧作废再同步状态
                invalidateProbeFrame()
                syncActiveInputState()
            }
        }
        //service scope & message subscribe
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main).apply {
            launch {
                val app = applicationContext as? ImeApplication
                // 监听应用状态，当引擎启动的时候时开始绑定消息
                app?.state?.collectLatest { state ->
                    if (state == ImeApplication.AppState.EngineStarting) {
                        messageObserveJob?.cancel()
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
        InputFeedbacks.initFeedbackCache(this)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // 任何配置变化此前都无条件全量重建键盘（字体缩放/区域等与外观
        // 无关的变化也触发）；改走带变更检测的版本，配色未变不重建
        keyboardWindow?.view?.refreshColorsIfChanged()
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
        // onCreate 已初始化；防御性兜底：若为空（不应发生）则重建，避免 !! 崩溃
        val listener = keyActionListener
            ?: KeyActionListener(service = this).also { keyActionListener = it }
        window.setKeyActionListener(listener)
        // KeyboardStateManager 是进程级单例，其键盤注册表可能残留上一实例（旧配色）的键盘；
        // 新建窗口（销毁重建路径）时重建一次，让键盘用本次实例解析出的新配色生成。
        KeyboardStateManager.rebuild()
        return window.view
    }

    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        // 输入框获焦比输入视图弹出更早一步：语音预热/存活预检抢在这里
        // 发起，等用户点开键盘、长按语音时冷加载多半已经完成（函数内
        // 部按连接状态与节流自判，重复调用无副作用）
        com.jobeen.ime.base.speech.SherpaSpeechClient.preStartSync(this)
        super.onStartInput(attribute, restarting)
    }

    override fun onStartInputView(info: EditorInfo, restarting: Boolean) {
        // 换了输入框：上一框的语音纠错沉淀观察作废
        com.jobeen.ime.base.speech.SherpaSpeechClient.onInputViewFinished()
        // 新输入框（非同框重启）：语音上屏撤销账本是旧框的账，整表作废，
        // 不能把旧框的段拿来新框删
        if (!restarting) VoiceUndoLedger.clearAll()
        // 语音预热/存活预检（onStartInput 已先行一步，这里再兜一次）：
        // App 冷启动时不再 eager 拉起 :speech 进程
        com.jobeen.ime.base.speech.SherpaSpeechClient.preStartSync(this)
        keyboardWindow?.onStartInputView(info, restarting)
        engine?.onStartInputView(currentInputConnection, info)
        // 直调即时探针前先撤掉合并通道里排队的那次：它捕获的是旧
        // 输入框的待发任务，再跑一次只会在新框上多做一轮无用探针
        mainHandler.removeCallbacks(pendingNotifyInputChanged)
        invalidateProbeFrame()
        notifyInputChanged()
        super.onStartInputView(info, restarting)
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        showingDialog?.dismiss()
        invalidateProbeFrame()
        // 录音中切换输入框：立即丢弃本次录音。否则识别结果会经
        // activeInputConnection() 写进新输入框，且待定文字会残留在旧框。
        // 放在最前面，保证清待定文字时连接仍指向旧输入框。
        if (com.jobeen.ime.base.speech.SherpaSpeechClient.isHolding()) {
            com.jobeen.ime.base.speech.SherpaSpeechClient.stopHoldSession(discard = true)
        }
        com.jobeen.ime.base.speech.SherpaSpeechClient.onInputViewFinished()
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
        // 清掉还没跑的选区探针合并任务，避免销毁后它再碰输入连接
        mainHandler.removeCallbacks(pendingNotifyInputChanged)
        messageObserveJob?.cancel()
        scope?.cancel()
        scope = null
        keyboardWindow = null
        // KeyActionListener 持有 service，销毁后置空避免泄漏
        keyActionListener = null

        ClipboardManager.stopMonitoring(this)
        InputFeedbacks.releaseFeedbackCache(this)
        KeyboardStateManager.onDestroy()
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

    /** 当前弹窗的关闭回调（经 [showDialog] 注册，随弹窗一并清理） */
    private var showingDialogOnDismiss: (() -> Unit)? = null

    /**
     * 显示挂在本窗口 token 上的弹窗。关闭监听由服务侧独占：先清自身
     * 状态，再转调调用方经 [onDismiss] 注册的回调——调用方不得再对
     * 弹窗直接 setOnDismissListener，那会把这里的监听整份覆盖掉，
     * 服务侧状态与调用方自己的收尾便一起失效（编号 24 的原故障）。
     */
    fun showDialog(dialog: android.app.Dialog, onDismiss: (() -> Unit)? = null) {
        showingDialog?.dismiss()
        val tokenView = keyboardWindow?.view ?: return
        // view 已存在但尚未挂上窗口时 windowToken 为 null，拿它去
        // show() 会抛 BadTokenException，直接放弃本次显示
        val token = tokenView.windowToken ?: return
        dialog.window?.apply {
            attributes.token = token
            attributes.type = WindowManager.LayoutParams.TYPE_APPLICATION_ATTACHED_DIALOG
            addFlags(
                WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM or WindowManager.LayoutParams.FLAG_DIM_BEHIND
            )
            setDimAmount(0.5f)
        }
        showingDialog = dialog
        showingDialogOnDismiss = onDismiss
        dialog.setOnDismissListener {
            // dismiss 回调异步投递：到达时可能已有新弹窗顶替上来，
            // 只认仍指向本弹窗的那次，旧弹窗的迟到回调不得清新状态
            if (showingDialog === dialog) {
                showingDialog = null
                val callback = showingDialogOnDismiss
                showingDialogOnDismiss = null
                callback?.invoke()
            }
        }
        dialog.show()
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
            TextEditView.Action.Cut -> {
                ic?.performContextMenuAction(android.R.id.cut)
                // 剪切删文本：代次先递增，但文本由宿主异步落地，覆盖
                // 口径与直写方相同（见 localWriteGen 审计②）
                noteLocalWrite()
            }

            TextEditView.Action.Copy -> ic?.performContextMenuAction(android.R.id.copy)
            TextEditView.Action.Paste -> {
                ic?.performContextMenuAction(android.R.id.paste)
                // 粘贴写文本：同剪切口径
                noteLocalWrite()
            }

            TextEditView.Action.Backspace -> engine?.processKey(
                this, com.jobeen.ime.engine.event.KeyEvent.CodeEvent(
                    KeyEvent.KEYCODE_DEL, com.jobeen.ime.engine.event.KeyModifiers.Empty
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
        // 光标/选区变化：engine 侧光标前文本缓存失效
        engine?.onSelectionChanged()
        // 探针走 24ms 合并（见 scheduleNotifyInputChanged）；onStartInputView
        // 的即时调用不走此路、不受影响
        scheduleNotifyInputChanged()
    }

    /**
     * 输入变化探针的 24ms 合并通道：选区变化（拖动光标/程序调整选区）与
     * Commit 上屏都会高频连发，每次都跨进程探针是浪费；下游
     * （onInputChanged/onInputCleared）只用文本空/非空，窗口内取最后一次
     * 即可。需要即时结果的入口（onStartInputView）仍直调 notifyInputChanged。
     */
    fun scheduleNotifyInputChanged() {
        mainHandler.removeCallbacks(pendingNotifyInputChanged)
        mainHandler.postDelayed(pendingNotifyInputChanged, 24L)
    }

    /** 当帧选区签名：桥接模式读虚拟连接选区，其余读最近一次选区回调。 */
    private fun currentSelectionSignature(): Pair<Int, Int> =
        if (phraseAddBridgeActive) virtualInputConnection.selection
        else lastSelectionStart to lastSelectionEnd

    /** 换输入框/输入视图结束时作废整帧探针缓存。 */
    private fun invalidateProbeFrame() {
        probeFrameText = null
        probeFrameSelStart = -1
        probeFrameSelEnd = -1
        probeFrameWriteGen = -1
    }

    /**
     * 一帧共用探针：签名（桥接标志 + 选区 + 本端写代次）未变时直接复用
     * 本帧已探到的结果，不再重复跨进程取字；签名变化才真正探一次并
     * 回填缓存。
     */
    private fun probeAroundCursorShared(ic: android.view.inputmethod.InputConnection): String {
        val selection = currentSelectionSignature()
        val cached = probeFrameText
        if (cached != null && probeFrameBridge == phraseAddBridgeActive &&
            probeFrameSelStart == selection.first && probeFrameSelEnd == selection.second &&
            probeFrameWriteGen == localWriteGen
        ) {
            return cached
        }
        val text = probeAroundCursorText(ic)
        probeFrameText = text
        probeFrameBridge = phraseAddBridgeActive
        probeFrameSelStart = selection.first
        probeFrameSelEnd = selection.second
        probeFrameWriteGen = localWriteGen
        return text
    }

    internal fun syncActiveInputState() {
        val ic = activeInputConnection() ?: return
        val selection = currentSelectionSignature()
        keyboardWindow?.onSelectionUpdate(selection.first, selection.second)
        // 下游只用空/非空：旧实现用 Int.MAX_VALUE 全量抓前后文拼接（桥接模式
        // 下整篇文档跨进程过一遍），改为 1 字符探针（与 notifyInputChanged
        // 共用一帧缓存，见 probeAroundCursorShared）
        keyboardWindow?.onInputChanged(
            probeAroundCursorShared(ic),
            virtualInputConnection = phraseAddBridgeActive,
        )
    }

    /**
     * 光标前后各取 1 字符作「输入框是否有内容」的探针：下游全部消费方只用
     * 空/非空。前文非空即可短路，省掉第二次 Binder 调用（打字常态路径）。
     */
    private fun probeAroundCursorText(ic: android.view.inputmethod.InputConnection): String {
        val before = ic.getTextBeforeCursor(1, 0)?.toString().orEmpty()
        if (before.isNotEmpty()) return before
        return ic.getTextAfterCursor(1, 0)?.toString().orEmpty()
    }

    fun notifyInputChanged() {
        val ic = activeInputConnection() ?: return
        val text = probeAroundCursorShared(ic)
        keyboardWindow?.onInputChanged(text, virtualInputConnection = phraseAddBridgeActive)
        if (text.isEmpty()) engine?.onInputCleared()
        // 语音纠错沉淀借同一探针节奏观察上屏后编辑（未武装时为空转检查）；
        // 当帧探针结果（文本、光标位）随回调下传，沉淀侧能据此判定
        // 的帧不再自读整窗
        com.jobeen.ime.base.speech.SherpaSpeechClient.onEditorTextProbed(
            this, text, currentSelectionSignature().first
        )
    }
}
