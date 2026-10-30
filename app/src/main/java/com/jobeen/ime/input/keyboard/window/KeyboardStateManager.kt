package com.jobeen.ime.input.keyboard.window

import android.content.Context
import android.text.InputType
import android.view.inputmethod.EditorInfo
import com.jobeen.ime.R
import com.jobeen.ime.base.util.appContext
import com.jobeen.ime.base.util.ToastUtil
import com.jobeen.ime.data.PunctuationMode
import com.jobeen.ime.data.manager.SchemaManager
import com.jobeen.ime.data.manager.EngineDeployState
import com.jobeen.ime.data.manager.discoverSchemes
import com.jobeen.ime.engine.EngineFactory
import com.jobeen.ime.engine.data.EngineMessage
import com.jobeen.ime.engine.rime.data.DataManager
import com.jobeen.ime.input.keyboard.impl.IKeyboard
import com.jobeen.ime.input.keyboard.impl.NumberKeyboard
import com.jobeen.ime.input.keyboard.impl.QwertyKeyboard
import com.jobeen.ime.input.keyboard.key.KeyActionListener
import com.jobeen.ime.skin.runtime.CustomLayoutRuntime
import com.jobeen.ime.skin.runtime.SkinSnapshotProvider
import com.jobeen.ime.base.util.appScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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

    /**
     * 当前方案推进（2026-10-29 item-1 C）：赋值后同步给输入统计
     * 的方案分桶；全部 currentSchema 赋值点皆经此处，不直接赋值。
     */
    private fun updateCurrentSchema(schema: EngineMessage.Schema?) {
        currentSchema = schema
        com.jobeen.ime.input.stats.InputStats.setScheme(schema?.id.orEmpty())
    }
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

    /** 当前回车键类型文本（重点候选 8-8 动态文字供给）：取最近一次
     * 编辑框信息的 imeOptions 动作；无信息时为 ENTER。 */
    fun currentReturnKeyType(): String {
        val info = lastEditorInfo ?: return "ENTER"
        return ReturnKeyTypeText.fromImeAction(info.imeOptions and EditorInfo.IME_MASK_ACTION)
    }
    fun get(name: String): IKeyboard? = keyboards[name]


    fun onAttach() {
        if (schemas.isEmpty()) refreshSchemas()
        if (currentKeyboardName == null) {
            switchTo(currentSchema?.layout ?: defaultKeyboardName)
            return
        }
        if (keyboardAttached) return
        // 重挂时若当前方案布局已变（键盘收起期间切了方案），不直接
        // 复现旧键盘实例，改走 switchTo 同步布局与空格键文案。
        val targetLayout = currentSchema?.layout ?: defaultKeyboardName
        if (targetLayout != currentKeyboardName) {
            switchTo(targetLayout)
            return
        }
        currentKeyboardName?.let { name ->
            keyboards[name]?.let { kb ->
                kb.onAttach()
                // 同布局重挂此前直接重显缓存键盘、不走 switchTo，空格键文案
                // 仍是旧方案名：重显时也刷一次当前方案名（Emoji/Symbol
                // 的 updateSpaceKeyText 为空实现，此调用无影响）。
                kb.updateSpaceKeyText(currentSchema?.name.orEmpty())
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
        // 部署进行中：只读当前方案，不推进、不切布局、不碰引擎，并
        // 提示忙碌（此前静默返回，点选后键盘无任何变化、用户无法得
        // 知切换被挡下）；部署结束后由 Depoly.Finish 刷新接管。
        if (EngineDeployState.isRunning()) {
            ToastUtil.showToast(appContext.getString(R.string.fab_deploy_busy))
            return currentSchema?.id.orEmpty()
        }
        if (schemas.isEmpty()) return ""
        val index = schemas.indexOf(currentSchema)
        updateCurrentSchema(if (index >= 0) schemas[(index + 1) % schemas.size] else schemas.first())
        switchTo(currentSchema?.layout ?: QwertyKeyboard.NAME)
        return currentSchema?.id.orEmpty()
    }

    fun selectSchema(schemaId: String): String {
        // 部署进行中：不选、不切布局、不碰引擎，返回当前方案 id，并
        // 提示忙碌（与 rotateSchema 同口径，避免静默无反应）。
        if (EngineDeployState.isRunning()) {
            ToastUtil.showToast(appContext.getString(R.string.fab_deploy_busy))
            return currentSchema?.id.orEmpty()
        }
        val schema = schemas.find { it.id == schemaId } ?: return ""
        if (currentSchema?.id == schemaId) return schemaId
        updateCurrentSchema(schema)
        EngineFactory.current()?.selectSchema(schema.id)
        switchTo(schema.layout.ifEmpty { QwertyKeyboard.NAME })
        return schema.id
    }

    // 部署完成的方案刷新只剩 handleEngineMessage 一条触发通道（清点
    // Q9 已删 MessageHandler 的双通道透传），其余调用方（onAttach /
    // onConfigChanged）都是用户节奏、不突发：在飞时直接跳过即可，
    // 不再养单飞＋尾随重跑的合并装置。但“直接跳过”会吃掉两条部署期
    // 刷新：部署中早退的、部署中被单飞丢弃的（尤其 Finish 刷新撞上在
    // 飞的早退刷新时），且失败部署根本没有 Finish 可重触发。补救只记
    // 一个待补齐标志，不排队、不重试链：部署标志由真变假（成功与失败
    // 的收尾都会复位）或在飞收尾时（部署恰在早退检查后复位），补齐一次；
    // 补齐跑本身持有单飞权，不会被单飞再次吃掉；补齐撞上在飞刷新时不
    // 消费标志，留待在飞收尾重试。无标志时部署结束不刷，attach 不多跑。
    private val refreshPolicy = DeployRefreshPolicy()
    private val deployWatcherStarted = java.util.concurrent.atomic.AtomicBoolean(false)

    fun refreshSchemas() {
        ensureDeployWatcher()
        if (!refreshPolicy.tryStart(isDeploying = EngineDeployState.isRunning())) return
        appScope.launch {
            try {
                refreshSchemasOnce()
            } finally {
                refreshPolicy.release()
                tryAlignRefresh()
            }
        }
    }

    /** 有待补齐标志且部署已结束时补齐一次；无标志、部署中或撞单飞时不刷。 */
    private fun tryAlignRefresh() {
        if (!refreshPolicy.tryStartAlignment(isDeploying = EngineDeployState.isRunning())) return
        appScope.launch {
            try {
                refreshSchemasOnce()
            } finally {
                refreshPolicy.release()
                tryAlignRefresh()
            }
        }
    }

    /** 部署结束的唯一观察点：只在真变假时触发补齐判定，平时不刷。 */
    private fun ensureDeployWatcher() {
        if (!deployWatcherStarted.compareAndSet(false, true)) return
        appScope.launch {
            var wasRunning = EngineDeployState.isRunning()
            EngineDeployState.running.collect { running ->
                val deployEnded = wasRunning && !running
                wasRunning = running
                if (deployEnded) tryAlignRefresh()
            }
        }
    }

    private suspend fun refreshSchemasOnce() {
            val prefs = appContext.getSharedPreferences(SchemaManager.PREFS_NAME, Context.MODE_PRIVATE)
            val schemaIds = prefs.getString(SchemaManager.KEY_ENABLED_IDS, "")?.split(",")
                ?.filter { it.isNotBlank() } ?: emptyList()
            // 方案表一律读盘（当前目录＋共享目录，与方案页同口径），
            // 不读引擎实时表：部署进行中引擎表不可信。读盘走 IO（本
            // 函数跑在 MainScope 上，旧引擎表读取是内存操作才可直调）。
            val disk = withContext(Dispatchers.IO) {
                discoverSchemes(DataManager.userDataDir, DataManager.sharedDataDir)
            }
            val byId = disk.associateBy { it.id }
            schemas = schemaIds.mapNotNull { id ->
                byId[id]?.let {
                    EngineMessage.Schema(it.id, it.name, it.layout, it.punctuation, it.kind)
                }
            }
            // 部署进行中：只更新方案表，不选方案、不切布局、不碰引擎，
            // 但记待补齐标志——部署结束/失败后由观察点或在飞收尾补齐一次。
            if (EngineDeployState.isRunning()) {
                refreshPolicy.markPending()
                return
            }
            updateCurrentSchema(schemas.firstOrNull())
            currentSchema?.id?.let { EngineFactory.current()?.selectSchema(it) }

            // 只有在已经挂载到窗口时才切换键盘布局，避免在 factory 尚未注入、
            if (keyboardAttached) {
                switchTo(currentSchema?.layout ?: defaultKeyboardName)
            }
            // 完整刷新跑完：UI 已按最新磁盘对齐，待补齐标志不再欠账。
            refreshPolicy.markAligned()
    }

    private fun create(name: String): IKeyboard? {
        // 已创建过的实例直接复用，不依赖 factory：factory 在服务销毁时清掉，
        // 但注册表里的实例仍可切换；此前先查 factory 再查注册表，
        // 切已存在键盘也会在 factory 清掉后崩溃。
        keyboards[name]?.let { return it }
        // factory 尚未注入（View 未构造，如引擎先报 Schema 消息）时不崩溃：
        // 本次创建顺延——View 构造时设 factory，随后 onAttach 在
        // currentKeyboardName 为空时重新 switchTo，届时再建。
        // （用户 2026-10-28 诊断包 10:51:10 启动崩溃即此竞态。）
        val factory = keyboardFactory ?: return null
        val keyboard = factory(name)
        keyboards[name] = keyboard
        return keyboard
    }

    private var lastSwitchKey: Pair<String, String?>? = null

    /**
     * 切换到目标键盘。目标先经自创布局的主键盘位解析（皮肤 v3
     * B3 §3.3）：生效皮肤绑定了有效自创布局时，主键盘名改指
     * `custom:<layoutId>`；场景/工具键盘名原样。startInput 的
     * 场景强制目标走 [switchToResolved] 直达，不经此解析。
     * 横屏分支（2026-10-27 矩阵扩展）：竖屏绑定不存在但横屏
     * 绑定存在且当前为横屏时，主键盘名改指横屏绑定——旋转
     * 重建经 rebuild → switchTo(当前名) 走同一分支，横竖切换
     * 自动往返（竖屏回内置布局，横屏进横屏专属布局）。
     */
    fun switchTo(name: String) {
        val landscape = appContext.resources.configuration.orientation ==
            android.content.res.Configuration.ORIENTATION_LANDSCAPE
        val target = CustomLayoutRuntime.resolveSwitchTarget(
            SkinSnapshotProvider.current(appContext),
            name,
            currentSchema?.layout ?: defaultKeyboardName,
            landscape,
        )
        switchToResolved(target)
    }

    private fun switchToResolved(name: String) {
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
        // 切到的键盘实例（新切换或 rebuild 后重建的）还没被下发过 imeAction，
        // 而 updateReturnKeyIfNeeded 的三元组去重会把重发拦下，新实例的回车键
        // 在下一次输入变化前恒为默认换行行为：重置去重缓存并立即重发一次
        // （rebuild 也走 switchTo，一并覆盖）
        lastImeAction = -1
        updateReturnKeyIfNeeded()
    }

    private fun attachNew(name: String) {
        // factory 未注入时 create 返回 null：本次不建、不改状态（顺延至 View onAttach），
        // 不再像此前那样抛错崩进程。
        val keyboard = create(name) ?: return
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
        // 场景强制键盘（数字/电话/密码）不被自创布局替换（B3
        // §3.3 优先级表第一行）：走直达通道；主键盘位走 switchTo 解析
        when {
            inputClass == InputType.TYPE_CLASS_NUMBER ||
                inputClass == InputType.TYPE_CLASS_PHONE ->
                switchToResolved(NumberKeyboard.NAME)
            isPasswordText -> switchToResolved(QwertyKeyboard.NAME)
            else -> switchTo(currentSchema?.layout ?: defaultKeyboardName)
        }
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

            is EngineMessage.Schema -> {
                // 引擎内切方案（switcher/lua 等，不走 Action.SelectSchema）
                // 与设置页直接调引擎 selectSchema：此前落入 else 空分支，
                // currentSchema 不更新；后续 switchTo 去重键仍等于旧方案
                // 而早退，空格文案滞留。在本分支内同步推进 currentSchema
                // 再走 switchTo：引擎回声（本机 rotate/select 已先刷过）
                // 因去重键相等而早退、不重复刷；非回声必刷。
                // 与 rotate/select/onAttach 同线程（applicationScope 为
                // MainScope），本分支不另起协程、不搬移队列。
                val (resolved, target) = resolveEngineSchemaTarget(
                    schemas, message, currentKeyboardName, defaultKeyboardName
                )
                updateCurrentSchema(resolved)
                // 启动时序保护：键盘窗口尚未建立（factory 未注入，
                // 唯一设置点是窗口建视图时）时，switchTo 经 create
                // 取 factory 会抛错（切输入法回来进程重建、消息先到
                // 即闪退）。此时只推进 currentSchema、不切换；视图
                // 建立后 onAttach 会按 currentSchema 布局切到正确
                // 键盘，方案不丢失。未挂载时记一条元数据日志。
                if (keyboardAttached) {
                    switchTo(target)
                } else {
                    Timber.w("engine_schema_switch_deferred: keyboard not attached")
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

/**
 * 部署期刷新补齐的纯决策状态机（无 Android / 协程依赖，可单测）。
 * 语义：单飞权仍是唯一并发门；待补齐标志最多一个，不排队、不计数、
 * 不重试链。标志只在两种丢失时记下：部署中早退的刷新、部署中被单飞
 * 丢弃的刷新。补齐尝试拿权成功后才清标志——撞单飞时标志保留，留待
 * 在飞收尾重试，因此补齐本身不会被单飞吃掉；清标志与补齐读盘之间
 * 新记的标志要么被本次全量刷新覆盖（丢弃发生在读盘前），要么补齐
 * 跑遇部署中早退时重记，不丢账。
 */

/**
 * 引擎方案消息的纯决策（无 Android / 协程依赖，可单测）：由
 * [KeyboardStateManager.handleEngineMessage] 的 Schema 分支调用。
 * - 方案解析：已知表内有同 id 项时取表内项——与 rotateSchema 的
 *   indexOf 等值语义一致，且能补回补取失败时原始消息缺失的
 *   layout/punctuation/kind（原始串只有 id/name 两段）；表内无此
 *   id 时原样用消息。
 * - 切换目标：解析后 layout 非空时即该 layout；layout 为空且当前
 *   已有键盘时以当前键盘名为目标——只刷空格文案与标点模式，不虚
 *   构一个布局（空 layout 时硬 fallback 到 Qwerty 会把键盘错切走）；
 *   当前尚无键盘时退回默认布局，与 onAttach 首挂口径一致。
 * 返回 (解析后方案, switchTo 目标)。
 */
internal fun resolveEngineSchemaTarget(
    known: List<EngineMessage.Schema>,
    message: EngineMessage.Schema,
    currentKeyboardName: String?,
    defaultKeyboardName: String,
): Pair<EngineMessage.Schema, String> {
    val resolved = known.find { it.id == message.id } ?: message
    val target = resolved.layout.ifEmpty { currentKeyboardName ?: defaultKeyboardName }
    return resolved to target
}

internal class DeployRefreshPolicy {
    private val inFlight = java.util.concurrent.atomic.AtomicBoolean(false)
    private val pending = java.util.concurrent.atomic.AtomicBoolean(false)

    val isInFlight: Boolean get() = inFlight.get()
    val isPending: Boolean get() = pending.get()

    /** 正常刷新入口：拿到单飞权返回 true；拿不到时仅当部署中才记待补齐。 */
    fun tryStart(isDeploying: Boolean): Boolean {
        if (inFlight.compareAndSet(false, true)) return true
        if (isDeploying) pending.set(true)
        return false
    }

    /** 部署中早退的刷新：记待补齐。 */
    fun markPending() {
        pending.set(true)
    }

    /** 完整刷新跑完：UI 已按最新磁盘对齐，清待补齐。 */
    fun markAligned() {
        pending.set(false)
    }

    fun release() {
        inFlight.set(false)
    }

    /**
     * 补齐尝试：仅当有待补齐且部署已结束时拿单飞权并清标志；
     * 无标志、部署中或撞单飞时返回 false 且不消费标志。
     */
    fun tryStartAlignment(isDeploying: Boolean): Boolean {
        if (!pending.get() || isDeploying) return false
        if (!inFlight.compareAndSet(false, true)) return false
        pending.set(false)
        return true
    }
}
