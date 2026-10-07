package com.jobeen.ime.input.keyboard.window

import android.content.Context
import android.text.InputType
import android.view.inputmethod.EditorInfo
import com.jobeen.ime.base.util.appContext
import com.jobeen.ime.data.PunctuationMode
import com.jobeen.ime.data.manager.SchemaManager
import com.jobeen.ime.engine.EngineFactory
import com.jobeen.ime.engine.data.EngineMessage
import com.jobeen.ime.input.keyboard.impl.IKeyboard
import com.jobeen.ime.input.keyboard.impl.NumberKeyboard
import com.jobeen.ime.input.keyboard.impl.QwertyKeyboard
import com.jobeen.ime.input.keyboard.key.KeyActionListener
import com.jobeen.ime.base.util.appScope
import kotlinx.coroutines.launch
import timber.log.Timber

object KeyboardStateManager {
    /**
     * UI 渲染回调，由上层 View/Window 层实现。
     * 回调直接把「键盘实例」交出去，由 View 负责把它挂到窗口上；
     * 键盘实例本身由 KeyboardStateManager 维护（注册表），但具体的创建（含主题色）
     * 通过 keyboardFactory 交给 View 层，从而不在这里持有颜色/创建 View 的逻辑。
     */
    interface Callback {
        fun onShowKeyboard(keyboard: IKeyboard)
        fun onHideKeyboard(keyboard: IKeyboard)
        fun onKeyboardChanged(keyboard: IKeyboard)
    }

    var callback: Callback? = null

    // 注册表持强引用：键盘收起时 View 会从布局移除，若只剩弱引用，
    // 两次打开之间发生 GC 会导致取回 null、按键区空白且无法恢复。
    // 服务 onDestroy 时统一清空，避免 service 销毁后泄漏。
    private val keyboards: MutableMap<String, IKeyboard> = hashMapOf()
    private var keyboardFactory: ((String) -> IKeyboard)? = null
    private var currentKeyboardName: String? = null
    private var keyboardAttached = false
    private var schemas: List<EngineMessage.Schema> = emptyList()
    private var currentSchema: EngineMessage.Schema? = null
    private var defaultKeyboardName = QwertyKeyboard.NAME

    // 打字状态：由 Status 消息驱动（RimeEngine 不参与）
    private var isComposing = false

    /** 当前引擎是否处于组字中（剪贴板搜索的退格分流要据此决定归属）。 */
    val isComposingNow: Boolean get() = isComposing
    private var lastEditorInfo: EditorInfo? = null
    private var lastInputEmpty = true
    // 上次实际应用到键盘的入参，用于避免无变化时的重复刷新
    private var lastImeAction = -1
    private var lastAppliedEmpty = true
    private var lastAppliedComposing = false

    var keyActionListener: KeyActionListener = KeyActionListener.Empty
        set(value) {
            field = value
            for (kb in keyboards.values) kb.keyActionListener = value
        }

    /** 服务销毁时调用：释放注册表与回调，避免持有已销毁的 View/Context */
    fun onDestroy() {
        keyboards.clear()
        callback = null
        currentKeyboardName = null
        keyboardAttached = false
        // factory 闭包持有 View、lastEditorInfo 持有输入框信息、keyActionListener 链条持有 View，
        // 服务销毁后不再需要，全部释放避免小泄漏
        keyboardFactory = null
        lastEditorInfo = null
        keyActionListener = KeyActionListener.Empty
    }

    fun setKeyboardFactory(factory: (String) -> IKeyboard) {
        keyboardFactory = factory
    }

    fun getSchemas(): List<EngineMessage.Schema> = schemas
    fun getCurrentSchema(): EngineMessage.Schema? = currentSchema
    fun get(name: String): IKeyboard? = keyboards[name]


    fun onAttach() {
        if (schemas.isEmpty()) refreshSchemas()
        if (currentKeyboardName == null) {
            switchTo(currentSchema?.layout ?: defaultKeyboardName)
            return
        }
        if (keyboardAttached) return
        currentKeyboardName?.let { name ->
            keyboards[name]?.let { kb ->
                kb.onAttach()
                callback?.onShowKeyboard(kb)
            }
        }
        keyboardAttached = true
    }

    fun onDetach() {
        if (!keyboardAttached) return
        currentKeyboardName?.let { name ->
            keyboards[name]?.let { kb ->
                if (keyboardAttached) kb.onDetach()
                callback?.onHideKeyboard(kb)
            }
        }
        keyboardAttached = false
    }

    fun onConfigChanged(key: String) {
        if (key == SchemaManager.KEY_ENABLED_IDS) refreshSchemas()
    }

    fun rotateSchema(): String {
        if (schemas.isEmpty()) return ""
        val index = schemas.indexOf(currentSchema)
        currentSchema = if (index >= 0) schemas[(index + 1) % schemas.size] else schemas.first()
        switchTo(currentSchema?.layout ?: QwertyKeyboard.NAME)
        return currentSchema?.id.orEmpty()
    }

    fun selectSchema(schemaId: String): String {
        val schema = schemas.find { it.id == schemaId } ?: return ""
        if (currentSchema?.id == schemaId) return schemaId
        currentSchema = schema
        EngineFactory.current()?.selectSchema(schema.id)
        switchTo(schema.layout.ifEmpty { QwertyKeyboard.NAME })
        return schema.id
    }

    // Deploy 完成有两条通道（MessageHandler 与 handleEngineMessage）都会调
    // refreshSchemas，旧实现每次部署全量刷新跑两遍。合并为单飞 + 尾随一次：
    // 已在刷新时只标记 pending，当前轮结束后再补一轮，不丢真实的第二次部署
    private val refreshInFlight = java.util.concurrent.atomic.AtomicBoolean(false)
    @Volatile
    private var refreshAgain = false

    fun refreshSchemas() {
        if (!refreshInFlight.compareAndSet(false, true)) {
            refreshAgain = true
            return
        }
        appScope.launch {
            try {
                do {
                    refreshAgain = false
                    refreshSchemasOnce()
                } while (refreshAgain)
            } finally {
                refreshInFlight.set(false)
            }
        }
    }

    private suspend fun refreshSchemasOnce() {
            val prefs = appContext.getSharedPreferences(SchemaManager.PREFS_NAME, Context.MODE_PRIVATE)
            val schemaIds = prefs.getString(SchemaManager.KEY_ENABLED_IDS, "")?.split(",")
                ?.filter { it.isNotBlank() } ?: emptyList()
            val schemaList = EngineFactory.current()?.schemasList() ?: emptyList()
            val byId = schemaList.associateBy { it.id }
            schemas = schemaIds.mapNotNull { byId[it] }
            currentSchema = schemas.firstOrNull()
            currentSchema?.id?.let { EngineFactory.current()?.selectSchema(it) }

            // 只有在已经挂载到窗口时才切换键盘布局，避免在 factory 尚未注入、
            if (keyboardAttached) {
                switchTo(currentSchema?.layout ?: defaultKeyboardName)
            }
    }

    private fun create(name: String): IKeyboard {
        val factory = keyboardFactory
            ?: error("KeyboardStateManager.keyboardFactory must be set before creating keyboards")
        keyboards[name]?.let { return it }
        val keyboard = factory(name)
        keyboards[name] = keyboard
        return keyboard
    }

    private var lastSwitchKey: Pair<String, String?>? = null

    fun switchTo(name: String) {
        // 同键盘 + 同方案的重复切换直接早退：旧实现每次都重设空格键文案/
        // 标点模式并回调 onKeyboardChanged（触发窗口侧重排），而调用方
        // （refreshSchemas、resume 等）经常同参数连调多次
        val key = name to currentSchema?.id
        if (name == currentKeyboardName && key == lastSwitchKey) return
        lastSwitchKey = key
        if (name != currentKeyboardName) {
            detachCurrent()
            attachNew(name)
        }
        val kb = keyboards[name]
        kb?.updateSpaceKeyText(currentSchema?.name.orEmpty())
        kb?.updatePunctuationMode(PunctuationMode.from(currentSchema?.punctuation.orEmpty()))
        kb?.let { callback?.onKeyboardChanged(it) }
    }

    private fun attachNew(name: String) {
        val keyboard = create(name)
        keyboard.keyActionListener = keyActionListener
        currentKeyboardName = name
        keyboard.onAttach()
        keyboardAttached = true
        callback?.onShowKeyboard(keyboard)
    }

    fun detachCurrent() {
        val name = currentKeyboardName
        if (name != null) {
            keyboards[name]?.let { kb ->
                kb.keyActionListener = null
                if (keyboardAttached) kb.onDetach()
                callback?.onHideKeyboard(kb)
            }
        }
        currentKeyboardName = null
        keyboardAttached = false
        lastSwitchKey = null
    }

    fun rebuild() {
        val currentName = currentKeyboardName
        detachCurrent()
        keyboards.clear()
        if (currentName != null) {
            switchTo(currentName)
        }
    }

    fun setRippleEnabled(enabled: Boolean) {
        for (kb in keyboards.values) {
            kb.setRippleEnabled(enabled)
        }
    }

    fun startInput(info: EditorInfo) {
        val inputClass = info.inputType and InputType.TYPE_MASK_CLASS
        val variation = info.inputType and InputType.TYPE_MASK_VARIATION
        // 文本类密码框自动切英文键盘（Qwerty + engine 侧强制 ascii_mode 直输）；
        // 数字密码走数字键盘，不在此处理
        val isPasswordText = inputClass == InputType.TYPE_CLASS_TEXT &&
            (variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD)
        val start = when {
            inputClass == InputType.TYPE_CLASS_NUMBER ||
                inputClass == InputType.TYPE_CLASS_PHONE -> NumberKeyboard.NAME
            isPasswordText -> QwertyKeyboard.NAME
            else -> currentSchema?.layout ?: defaultKeyboardName
        }
        switchTo(start)
    }

    fun resume() {
        switchTo(currentSchema?.layout ?: defaultKeyboardName)
    }

    fun onInputChanged(info: EditorInfo?, text: String, virtualInputConnection: Boolean = false) {
        if (info != null) {
            val effectiveInfo = if (virtualInputConnection) {
                EditorInfo().apply {
                    inputType = info.inputType
                    imeOptions = EditorInfo.IME_ACTION_UNSPECIFIED
                }
            } else {
                info
            }
            lastEditorInfo = effectiveInfo
            lastInputEmpty = text.isEmpty()
            updateReturnKeyIfNeeded()
        }
    }

    fun handleEngineMessage(message: EngineMessage) {
        when (message) {
            is EngineMessage.Status -> {
                isComposing = message.isComposing
                updateReturnKeyIfNeeded()
            }

            is EngineMessage.Commit -> {
                if (isComposing) {
                    isComposing = false
                    updateReturnKeyIfNeeded()
                }
            }

            is EngineMessage.Depoly -> {
                Timber.d("handleEngineMessage EngineMessage.Depoly ")
                if (message.state == EngineMessage.Depoly.State.Finish) {
                    refreshSchemas()
                }
            }

            else -> {}
        }
    }

    // 仅当 (imeAction, empty, isComposing) 三者有实际变化时才刷新回车键。
    private fun updateReturnKeyIfNeeded() {
        val info = lastEditorInfo ?: return
        val keyboard = currentKeyboardName?.let { keyboards[it] } ?: return
        val action = info.imeOptions and EditorInfo.IME_MASK_ACTION
        if (action == lastImeAction &&
            lastInputEmpty == lastAppliedEmpty &&
            isComposing == lastAppliedComposing
        ) {
            return
        }
        lastImeAction = action
        lastAppliedEmpty = lastInputEmpty
        lastAppliedComposing = isComposing
        keyboard.updateEditorInfo(info, lastInputEmpty, isComposing)
    }
}
