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
import com.jobeen.ime.data.manager.RimeSyncPrefs
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
     * 输入框字段身份跟踪（R3）：onStartInput 与 onStartInputView
     * 两入口共用一份快照（见 [trackInputField]）。
     */
    private val inputFieldTracker = InputFieldTracker()

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
                // 不伴随选区变化，文本变更回调里直接同步状态（探针
                // 每次直探、结果永远新鲜，见 probeAroundCursorText）
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

    /**
     * 字段身份跟踪（R3）：把本次输入开始的字段身份喂给
     * [inputFieldTracker]（与引擎联想共用 InputFieldKey 口径），
     * 关键字段变化即实为逻辑换框——无论宿主以 restarting 真假
     * 哪种形式投递。restart 型换框没有 FINISH 回调，S1 收窄后
     * 的两个真结束信号都不会来，旧框的语音结算尾会按旧纪元通
     * 过复检、写进新框；故判变即经与 FINISH 侧共用的投递点
     * （invalidateSpeechAfterInputEnd）同步投递同一作废，作废
     * 内容单点定义、日后增项两路不会漏跟。投递在 IMS 回调内
     * 同步完成，早于本回调之后一切语音结算路径对新连接的使
     * 用；同框 hide/show 与同框 restarting 身份不变、恒不投
     * 递，不误杀同框待结算整句（S1 的收窄口径不变）。
     *
     * 引擎联想的代次仍由引擎在自己 onStartInputView 内自算推
     * 进（其快照只在该入口滚动，与改前逐字一致），本跟踪不碰
     * 预测代次，不存在双重推进。
     */
    private fun trackInputField(info: EditorInfo?) {
        if (inputFieldTracker.track(InputFieldKey.of(info))) {
            invalidateSpeechAfterInputEnd()
        }
    }

    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        // 输入框获焦比输入视图弹出更早一步：语音预热/存活预检抢在这里
        // 发起，等用户点开键盘、长按语音时冷加载多半已经完成（函数内
        // 部按连接状态与节流自判，重复调用无副作用）
        com.jobeen.ime.base.speech.SherpaSpeechClient.preStartSync(this)
        // 字段身份跟踪（R3）：视图尚未弹出时（如键盘隐藏中换框）
        // 只有本入口能先于结算路径更新身份，故两入口都喂
        trackInputField(attribute)
        super.onStartInput(attribute, restarting)
    }

    override fun onStartInputView(info: EditorInfo, restarting: Boolean) {
        // 语音的输入结束作废（纠错沉淀观察/待就绪整句/撤销账本/
        // 输入框纪元）的 FINISH 口径不在此投递（S1，判定见
        // advancesEditorEpoch：START_INPUT_VIEW 恒不推进）：同框
        // 收起再打开走的正是本回调且 restarting=false，在此作
        // 废会把同框待结算的整句误杀；真换框的结束作废由先行的
        // onFinishInput（doStartInput 在 !restarting 时必先
        // doFinishInput）与 onFinishInputView(true) 承担，不漏。
        // 例外是字段身份变化（R3，见 trackInputField）：restart
        // 型逻辑换框没有 FINISH 回调、上面的承担链断裂，故以字
        // 段比对在本回调内单独投递同一作废——身份不变恒不触发
        // 语音预热/存活预检（onStartInput 已先行一步，这里再兜一次）：
        // App 冷启动时不再 eager 拉起 :speech 进程
        com.jobeen.ime.base.speech.SherpaSpeechClient.preStartSync(this)
        // 字段身份跟踪（R3）：同步投递，先于下方 engine 与窗口
        // 对新连接的使用
        trackInputField(info)
        keyboardWindow?.onStartInputView(info, restarting)
        engine?.onStartInputView(currentInputConnection, info, restarting)
        // 直调即时探针前先撤掉合并通道里排队的那次：它捕获的是旧
        // 输入框的待发任务，再跑一次只会在新框上多做一轮无用探针
        mainHandler.removeCallbacks(pendingNotifyInputChanged)
        notifyInputChanged()
        super.onStartInputView(info, restarting)
    }

    override fun onFinishInput() {
        // 输入会话结束（W2）：待就绪整句的作废只认真正的输入结
        // 束信号——本回调与 onFinishInputView(true)（投递判定
        // 见 advancesEditorEpoch）。onFinishInput 按 AOSP 语义
        // 只在输入会话结束时投递（仅隐藏键盘不投递、整句仍可
        // 落回同一框的 N13 场景不受影响）；「先收起键盘再换
        // 框」时框架对新框可能只投递 onStartInput（视图未弹出）
        // 或以 restarting=true 投递 onStartInputView：!restarting
        // 的换框必先经 doFinishInput、作废在本处承担；
        // restarting=true 的逻辑换框不经任何 FINISH 回调
        // （doStartInput 在 restarting 时不调 doFinishInput），
        // 其作废不在本处、由字段身份跟踪在判变时经共用投递点
        // 单独投递（R3，见 trackInputField）。个别厂商若在隐
        // 藏键盘时也投递本回调，该场景会被误作废——此点代码
        // 层无法证伪，列入真机验收项待核。与
        // onFinishInputView(true) 先后重复调用无碍：
        // onInputViewFinished 对重复调用幂等（快照清零是重复赋
        // 空、作废支内保留音频已清空后二次进入只重撤待发 START
        // 并按「确有音频被清」判提示，不重复置位）
        if (com.jobeen.ime.base.speech.advancesEditorEpoch(
                com.jobeen.ime.base.speech.InputLifecycleCallback.FINISH_INPUT
            )
        ) {
            invalidateSpeechAfterInputEnd()
        }
        super.onFinishInput()
    }

    /**
     * 输入真正结束时的语音作废一并执行：客户端侧作废（纠错沉
     * 淀观察、显示快照、对账窗与暂存、待就绪保留音频、输入框
     * 纪元推进）与语音上屏撤销账本整表作废（账本是旧框的账，
     * 不能把旧框的段拿来新框删）。本函数是语音作废的单点投
     * 递定义，投递点有三：onFinishInput 与
     * onFinishInputView(true) 两个真结束信号（S1），以及
     * trackInputField 判得字段身份变化时（R3：restart 型逻辑
     * 换框没有 FINISH 回调）；账本作废此前挂在
     * onStartInputView(!restarting)，同框收起再打开会被误清，
     * 已随投递收窄一并归位到此处。
     */
    private fun invalidateSpeechAfterInputEnd() {
        com.jobeen.ime.base.speech.SherpaSpeechClient.onInputViewFinished()
        VoiceUndoLedger.clearAll()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        showingDialog?.dismiss()
        // 录音中切换输入框：立即丢弃本次录音。否则识别结果会经
        // activeInputConnection() 写进新输入框，且待定文字会残留在旧框。
        // 放在最前面，保证清待定文字时连接仍指向旧输入框。
        if (com.jobeen.ime.base.speech.SherpaSpeechClient.isHolding()) {
            com.jobeen.ime.base.speech.SherpaSpeechClient.stopHoldSession(discard = true)
        }
        // 待就绪整句的作废只认真正结束输入（finishingInput=true，
        // 框架换框/收尾走 doFinishInput 的情形）：仅收起键盘
        // （false）时输入会话并未结束、连接仍指向同一框，作废会
        // 把已说完待落屏的整句误删并误报「已切换输入框」。换框
        // 的作废与 onFinishInput 同口径承担（S1：投递判定见
        // advancesEditorEpoch），两处重复调用幂等、不重叠误伤
        if (com.jobeen.ime.base.speech.advancesEditorEpoch(
                com.jobeen.ime.base.speech.InputLifecycleCallback.FINISH_INPUT_VIEW,
                finishingInput,
            )
        ) {
            invalidateSpeechAfterInputEnd()
        }
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
        // 键盘 RIME 通知：设置侧部署/同步完成时投递的待显示通知，在键盘
        // 下次出现时经键盘区 Toast 显示一次；开关关时直接丢弃。
        // （现有通道：KeyboardWindowView.showImeToast / ImeToastView，
        // 语音失败提示也在用；本开关只管部署/同步结果通知。）
        runCatching {
            RimeSyncPrefs.drainNotice(this)?.let { keyboardWindow?.showToast(it) }
        }
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
     * [dim] 控制非弹层区域压暗：默认压暗 0.5；不需要压暗的弹窗传
     * false（方案选择弹窗——其构建侧曾自清压暗标志，但这里后写覆盖，
     * 两处设置互相矛盾，最终以这里为准，统一收口到这个参数）。
     */
    fun showDialog(
        dialog: android.app.Dialog,
        dim: Boolean = true,
        onDismiss: (() -> Unit)? = null,
    ) {
        showingDialog?.dismiss()
        val tokenView = keyboardWindow?.view ?: return
        // view 已存在但尚未挂上窗口时 windowToken 为 null，拿它去
        // show() 会抛 BadTokenException，直接放弃本次显示
        val token = tokenView.windowToken ?: return
        dialog.window?.apply {
            attributes.token = token
            attributes.type = WindowManager.LayoutParams.TYPE_APPLICATION_ATTACHED_DIALOG
            addFlags(WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM)
            if (dim) {
                addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                setDimAmount(0.5f)
            } else {
                clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                setDimAmount(0f)
            }
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
            }

            TextEditView.Action.Copy -> ic?.performContextMenuAction(android.R.id.copy)
            TextEditView.Action.Paste -> {
                ic?.performContextMenuAction(android.R.id.paste)
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

    /** 当帧选区：桥接模式读虚拟连接选区，其余读最近一次选区回调。 */
    private fun currentSelectionSignature(): Pair<Int, Int> =
        if (phraseAddBridgeActive) virtualInputConnection.selection
        else lastSelectionStart to lastSelectionEnd

    internal fun syncActiveInputState() {
        val ic = activeInputConnection() ?: return
        val selection = currentSelectionSignature()
        keyboardWindow?.onSelectionUpdate(selection.first, selection.second)
        // 下游只用空/非空：旧实现用 Int.MAX_VALUE 全量抓前后文拼接（桥接模式
        // 下整篇文档跨进程过一遍），改为每次直探的 1 字符探针（帧缓存已于
        // 清点 S14 拆除：直探永远新鲜，原「空框首上屏后被误判已清空」故障
        // 由此天然不成立；高频连发的降本由 24ms 合并通道承担）
        keyboardWindow?.onInputChanged(
            probeAroundCursorText(ic),
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
        val text = probeAroundCursorText(ic)
        keyboardWindow?.onInputChanged(text, virtualInputConnection = phraseAddBridgeActive)
        if (text.isEmpty()) engine?.onInputCleared()
        // 语音纠错沉淀借同一探针节奏观察上屏后编辑（未武装时为空转检查）；
        // 本次探针结果（文本、光标位）随回调下传，沉淀侧能据此判定
        // 的帧不再自读整窗
        com.jobeen.ime.base.speech.SherpaSpeechClient.onEditorTextProbed(
            this, text, currentSelectionSignature().first
        )
    }
}
