package com.jobeen.ime.input.keyboard.window

import android.content.Context
import android.text.InputType
import android.view.inputmethod.EditorInfo
import com.jobeen.ime.R
import com.jobeen.ime.base.util.appContext
import com.jobeen.ime.base.util.ToastUtil
import com.jobeen.ime.data.PunctuationMode
import com.jobeen.ime.data.manager.CandidateManager
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
import com.jobeen.ime.skin.model.KeyboardTypes
import com.jobeen.ime.skin.runtime.CustomLayoutRuntime
import com.jobeen.ime.skin.symbol.BoardLockState
import com.jobeen.ime.skin.runtime.SkinColorMode
import com.jobeen.ime.skin.runtime.SkinSnapshotProvider
import com.jobeen.ime.skin.store.SkinTypePrefs
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

    // ---- 多主槽一期：键盘类型状态（状态支持，不含动作接线） ----
    // currentKeyboardType 是“当前键盘所属类型”，与 currentKeyboardName
    // （运行时名，如 custom:<布局 id>）并行存在：同一运行时名可被
    // 多类型共享（如两类型绑定同一布局），类型才是切换与去重的
    // 语义单位。类型切换只改本状态与键盘实例，不碰方案与引擎。
    private val typeState = KeyboardTypeState()

    /** 当前键盘类型；null = 尚未确立（首挂前）。 */
    val currentKeyboardType: String? get() = typeState.current

    /** 主键盘类型（最近一次主类型 pinyin/alphabetic）；工具类型不改它。 */
    val primaryKeyboardType: String? get() = typeState.primary

    /** 上一键盘类型（最近一次类型切换前的类型）；供 returnLastKeyboard 用。 */
    val lastKeyboardType: String? get() = typeState.last

    /** 当前类型所归属的皮肤 id（换肤复位判定用；null = 无皮肤/未确立）。 */
    private var lastTypeSkinId: String? = null

    // ---- 符号/表情板锁定（审计 G，进程级会话态） ----
    // 两把锁互不干扰；返回/恢复动作不清除锁（sticky），只由
    // 切换动作翻转或服务销毁清空。条件现值由窗口层经
    // BoardLockState.conditionCurrent() 并入条件总表。
    private val boardLocks = BoardLockState()

    /** 符号板是否锁定（锁定 = 提交后不自动回主键盘）。 */
    val symbolicBoardLocked: Boolean get() = boardLocks.symbolicLocked

    /** 表情板是否锁定。 */
    val emojiBoardLocked: Boolean get() = boardLocks.emojiLocked

    /** 某类型板是否锁定；非板类型恒为不锁定。 */
    fun isBoardLocked(boardType: String): Boolean = boardLocks.isLocked(boardType)

    /** 翻转符号板锁，返回翻转后的锁定态。 */
    fun toggleSymbolicLock(): Boolean = boardLocks.toggleSymbolic()

    /** 翻转表情板锁，返回翻转后的锁定态。 */
    fun toggleEmojiLock(): Boolean = boardLocks.toggleEmoji()

    /**
     * 板提交后自动返回（审计 G，对端 returnLastKeyboard 口径）：
     * 优先回上一类型（进板前的主键盘通常即上一类型），上一
     * 类型缺失或与当前相同时退主类型，再缺失时退旧 resume
     * 路径（按方案布局切主键盘）。显式返回键不走本函数，
     * 仍走 resume()（旧行为：按方案布局回主键盘）。
     */
    fun returnFromBoardAfterCommit() {
        val last = typeState.last
        if (last != null && last != typeState.current) {
            switchKeyboardType(last)
            return
        }
        val primary = typeState.primary
        if (primary != null && primary != typeState.current) {
            switchKeyboardType(primary)
            return
        }
        resume()
    }

    /** 输入模式 → 初始类型（纯决策，供首挂与换肤复位用）。 */
    fun initialKeyboardType(asciiMode: Boolean): String = typeState.initialType(asciiMode)

    /**
     * 按类型切换键盘（多主槽一期唯一的类型切换入口；中英键、
     * returnPrimary/returnLast 与后续接线轮皆经此函数，不直接
     * 调 switchToResolved）。目标经类型解析（含内置回退，永不
     * 空白）；factory 未注入致建键盘失败时类型状态不动，View
     * 建好后 onAttach 首挂分支会重走本函数。
     */
    fun switchKeyboardType(type: String) {
        val landscape = appContext.resources.configuration.orientation ==
            android.content.res.Configuration.ORIENTATION_LANDSCAPE
        val target = CustomLayoutRuntime.resolveTypeTarget(
            SkinSnapshotProvider.current(appContext),
            type,
            currentSchema?.layout ?: defaultKeyboardName,
            landscape,
            SkinColorMode.isDark(appContext),
        )
        switchToResolvedWithType(target, type)
    }

    /**
     * 回到主键盘（状态支持；无新动作类型，接线轮由按键调用
     * 本函数）。主类型未确立时为空操作。
     */
    fun returnPrimaryKeyboard() {
        val primary = typeState.primary ?: return
        if (primary != typeState.current) switchKeyboardType(primary)
    }

    /**
     * 回到上一键盘（状态支持；无新动作类型）。切换后 last 与
     * current 对调，故连续调用即在两者间往返。上一类型未确
     * 立时为空操作。
     */
    fun returnLastKeyboard() {
        val last = typeState.last ?: return
        if (last != typeState.current) switchKeyboardType(last)
    }

    /** 中英切换目标（状态支持；接线轮由中英键调用本函数再切类型）。 */
    fun toggleMainTypeTarget(): String = typeState.toggleMainTarget()

    /**
     * 主类型往返（纠正 2：keyboardType 动作的引擎，与中英切换
     * 无关）：在 pinyin↔alphabetic 间切类型。皮肤绑定
     * keyboardType 动作且目标为主类型往返时经此函数；中英键
     * （ToggleAsciiModeAction）只翻 ascii 偏好，不走这里。
     */
    fun toggleMainType() {
        switchKeyboardType(toggleMainTypeTarget())
    }

    /** 服务销毁时调用：释放注册表与回调，避免持有已销毁的 View/Context */
    fun onDestroy() {
        keyboards.clear()
        callback = null
        currentKeyboardName = null
        keyboardAttached = false
        boardLocks.reset()
        typeState.reset()
        lastTypeSkinId = null
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
        val snapshot = SkinSnapshotProvider.current(appContext)
        val landscape = appContext.resources.configuration.orientation ==
            android.content.res.Configuration.ORIENTATION_LANDSCAPE
        if (currentKeyboardName == null) {
            // 首挂：先确立类型再按类型切（旧路径首挂按方案布局名
            // 切；类型路径在无表皮肤上经 pinyin 旧绑定回落到同一
            // 目标，内置行为不变）。类型来源：已确立的沿用 → 本
            // 皮肤存过的恢复 → 按输入模式初始（中文 pinyin、英文
            // alphabetic）。factory 未注入时 switchKeyboardType
            // 不提交类型，View 建好后再次 onAttach 会重走本分支。
            val skinId = snapshot?.ref?.skinId
            val stored = skinId?.let {
                runCatching { SkinTypePrefs.getType(appContext, it) }.getOrNull()
            }
            val ascii = runCatching {
                CandidateManager.isAsciiModeEnabled(appContext)
            }.getOrDefault(false)
            // 首挂恢复只认主类型：进程重建时存过的工具类型
            // （如收起前停在符号/表情板上留下的 symbolic/emojis）
            // 不得成为首挂键盘，须回落按输入模式的初始主类型；
            // 同进程内 currentName 被清空后重走首挂时，存活的
            // 非主类型（如之前点 123 留下的 numeric）同口径过滤；
            // 换肤分支的存过类型恢复不受此限（见下方的换肤复位）。
            val rawType = resolveFirstAttachRaw(typeState.current, stored, ascii)
            // 首挂同样对账（进程重建时存过的类型也可能是上个方案留下的）；
            // 方案布局未知时 typeForLayoutName 为空，对账不动原值。
            val type = reconcileOpenType(
                currentSchema?.layout?.let {
                    CustomLayoutRuntime.typeForLayoutName(snapshot, it, landscape)
                },
                rawType,
                ascii,
            ) ?: rawType
            typeState.establish(type)
            lastTypeSkinId = skinId
            // 首挂确立亦写持久化：switchKeyboardType 内仅在类型
            // 实际变化时写盘，而 establish 后的切换是同值切换、
            // 不写盘；此处补写，使进程重启后仍恢复本皮肤类型。
            persistType(type)
            switchKeyboardType(type)
            return
        }
        if (keyboardAttached) return
        // 换肤复位（类型状态按皮肤隔离）：快照皮肤已变时，本皮肤
        // 存过的类型恢复、无存过类型时回初始类型；皮肤未变时
        // 不动类型，走下方的重挂决策。皮肤 id 为空（无皮肤生
        // 效）时亦不动类型（类型解析本身已按无表回退）。
        val skinId = snapshot?.ref?.skinId
        if (skinId != null && skinId != lastTypeSkinId) {
            val stored = runCatching {
                SkinTypePrefs.getType(appContext, skinId)
            }.getOrNull()
            if (stored != null && stored != typeState.current) {
                switchKeyboardType(stored)
                return
            }
            if (stored == null) {
                val ascii = runCatching {
                    CandidateManager.isAsciiModeEnabled(appContext)
                }.getOrDefault(false)
                val initial = typeState.initialType(ascii)
                if (initial != typeState.current) {
                    switchKeyboardType(initial)
                    return
                }
            }
            lastTypeSkinId = skinId
        }
        // 重挂时若当前方案布局已变（键盘收起期间切了方案），不直接
        // 复现旧键盘实例，改走切换同步布局与空格键文案。
        // round-1 空白修复的类型化推广（先解析再比较原则不变）：
        // 有类型时按类型解析目标名比较——当前键盘名可能是
        // custom:<布局 id>，而方案布局名是 T9，未解析直接比较
        // 恒不等，必走切换并撞上去重早返，视图不挂回；无类型
        // （旧路径）时沿用旧按名解析比较，内置行为不变。
        val targetLayout = currentSchema?.layout ?: defaultKeyboardName
        // 打开对账：切方案只切布局不动类型，留存主类型可能是上个
        // 方案留下的陈旧类型（打开即 26 键＋万象九键之类）；ascii
        // 开着是主动英文，保留；否则以方案为准回主类型。工具板、
        // 自定义类型与未知方案布局不动；切换后写盘，下次打开即正。
        val reconciledType = reconcileOpenType(
            CustomLayoutRuntime.typeForLayoutName(snapshot, targetLayout, landscape),
            typeState.current,
            runCatching { CandidateManager.isAsciiModeEnabled(appContext) }.getOrDefault(false),
        )
        if (reconciledType != null && reconciledType != typeState.current) {
            switchKeyboardType(reconciledType)
            return
        }
        when (
            val action = decideReattach(
                snapshot, currentKeyboardName!!, typeState.current, targetLayout, landscape,
                SkinColorMode.isDark(appContext),
            )
        ) {
            is ReattachAction.SwitchType -> switchKeyboardType(action.type)
            is ReattachAction.SwitchName -> switchTo(action.name)
            ReattachAction.Reshow -> {
                currentKeyboardName?.let { name ->
                    keyboards[name]?.let { kb ->
                        kb.onAttach()
                        // 同布局重挂此前直接重显缓存键盘、不走 switchTo：重显时
                        // 也刷一次空格文案（皮肤文案优先，无文案时回落方案名；
                        // Emoji/Symbol 的 updateSpaceKeyText 为空实现，此调用无影响）。
                        kb.updateSpaceKeyText(spaceFallbackText())
                        callback?.onShowKeyboard(kb)
                    }
                }
                keyboardAttached = true
            }
        }
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
        // 临时类型收起复位：temp_pinyin 不跨收起存活，回到主类型
        // （无主类型时按输入模式初始类型）；下次附着不再回到临时板。
        val ascii = runCatching {
            CandidateManager.isAsciiModeEnabled(appContext)
        }.getOrDefault(false)
        typeState.clearTemp(typeState.primary ?: typeState.initialType(ascii))
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

    // 去重键（多主槽一期）：（运行时名，类型，方案）——名必须
    // 留在键内，否则换肤后同类型同方案会把新目标名吞掉（名由
    // 类型解析得出，皮肤一换名即变）；类型必须在键内，否则两
    // 类型共享同一运行时名时切类型会被旧（名，方案）键早退。
    private var lastSwitchKey: Triple<String, String?, String?>? = null

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
        // 按名直达（场景/工具键盘）：类型不动——名到类型的反查
        // 属后续接线轮，本轮名路径只保证不破坏既有类型状态。
        switchToResolvedWithType(name, typeState.current)
    }

    /**
     * 空格回落文案：当前方案名（用户定，2026-11-02 改回）；
     * 皮肤无空格文案时用它。皮肤文案优先口径在
     * BaseKeyboard.resolveSpaceText，不变。
     * 注意：切类型不改方案（类型与方案正交），故切类型后
     * 文案仍是当前方案名——与键盘布局不一致时是类型／方案
     * 未同步，不是文案刷新滞留。
     */
    fun spaceFallbackText(): String = currentSchema?.name.orEmpty()

    private fun switchToResolvedWithType(name: String, type: String?) {
        // 同键盘 + 同类型 + 同方案的重复切换直接早退：旧实现每次都重设空格键文案/
        // 标点模式并回调 onKeyboardChanged（触发窗口侧重排），而调用方
        // （refreshSchemas、resume 等）经常同参数连调多次
        val key = Triple(name, type, currentSchema?.id)
        if (name == currentKeyboardName && key == lastSwitchKey) return
        lastSwitchKey = key
        if (name != currentKeyboardName) {
            detachCurrent()
            attachNew(name)
        }
        // 类型提交点（唯一）：只有确实挂上目标键盘（新挂或已是
        // 当前）才提交；factory 未注入致 attachNew 空转时名未
        // 变，类型亦不动。类型实际变化时记 last、主类型与持久化。
        if (currentKeyboardName == name && type != null && typeState.switchTo(type)) {
            persistType(type)
        }
        val kb = keyboards[name]
        kb?.updateSpaceKeyText(spaceFallbackText())
        kb?.updatePunctuationMode(PunctuationMode.from(currentSchema?.punctuation.orEmpty()))
        kb?.let { callback?.onKeyboardChanged(it) }
        // 切到的键盘实例（新切换或 rebuild 后重建的）还没被下发过 imeAction，
        // 而 updateReturnKeyIfNeeded 的三元组去重会把重发拦下，新实例的回车键
        // 在下一次输入变化前恒为默认换行行为：重置去重缓存并立即重发一次
        // （rebuild 也走 switchTo，一并覆盖）
        lastImeAction = -1
        updateReturnKeyIfNeeded()
    }

    /** 类型持久化（按皮肤隔离，见 SkinTypePrefs）：无快照时只留内存态，不写盘。 */
    private fun persistType(type: String) {
        if (!shouldPersistType(type)) return
        val skinId = SkinSnapshotProvider.peek()?.ref?.skinId ?: return
        lastTypeSkinId = skinId
        runCatching { SkinTypePrefs.setType(appContext, skinId, type) }
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

    /**
     * 显式返回键（Wave-4）：回主键盘（returnPrimary 口径）——
     * 主类型已确立时按类型切（与当前主键盘一致），未确立时
     * 退旧按方案布局返回。板提交后自动返回不走本函数（走
     * returnFromBoardAfterCommit 的上一类型口径）。
     */
    fun resumeToPrimary() {
        val primary = typeState.primary
        if (primary != null) {
            switchKeyboardType(primary)
        } else {
            resume()
        }
    }

    /**
     * 主类型英文态查询（纠正 2：仅供 keyboardType 动作侧读取
     * 类型态；菜单／工具栏中英开关显示态已恢复为 ascii 偏好，
     * 不再经此函数）：主类型已确立时以类型为准（alphabetic =
     * 英文态）；未确立时（首附着前）退偏好派生初始类型（与
     * onAttach 同口径）。
     */
    fun isMainTypeAlphabetic(context: Context): Boolean {
        val primary = typeState.primary
        if (primary != null) return primary == KeyboardTypes.ALPHABETIC
        return typeState.initialType(CandidateManager.isAsciiModeEnabled(context)) ==
            KeyboardTypes.ALPHABETIC
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

/**
 * 键盘类型纯状态机（无 Android / 协程依赖，可单测），由
 * [KeyboardStateManager] 持有唯一实例。
 * - current：当前类型；primary：最近一次主类型（中英切换的两
 *   端 pinyin/alphabetic 之一）；last：上一次类型。
 * - 主类型切换更新 primary，工具类型（emojis/symbolic/数字/
 *   自定义）不改 primary——returnPrimary 恒回主键盘，不回工具键盘。
 * - 切换到同一类型返回 false（调用方不记 last、不写持久化）。
 */
internal class KeyboardTypeState {
    var current: String? = null
        private set
    var primary: String? = null
        private set
    var last: String? = null
        private set

    /** 输入模式 → 初始类型：中文 → pinyin，英文（ascii）→ alphabetic。 */
    fun initialType(asciiMode: Boolean): String =
        if (asciiMode) KeyboardTypes.ALPHABETIC else KeyboardTypes.PINYIN

    fun switchTo(type: String): Boolean {
        if (type == current) return false
        // 临时类型不进 last 往返链：从临时切走时 last 保持临时前
        // 的类型（返回上一键盘不回到已失效的临时板）。
        if (!KeyboardTypes.isTemp(current)) last = current
        current = type
        if (type in KeyboardTypes.MAIN) primary = type
        return true
    }

    /**
     * 临时类型复位（收起时调用）：当前为临时类型时回到 fallback
     * （主类型），last 不动（已指向临时前类型）；非临时时不动。
     * 返回是否发生了复位。
     */
    fun clearTemp(fallback: String): Boolean {
        if (!KeyboardTypes.isTemp(current)) return false
        current = fallback
        return true
    }

    /** 首挂确立：仅当尚未确立时置 current 与 primary，last 不动。 */
    fun establish(type: String) {
        if (current == null) {
            current = type
            primary = type
        }
    }

    /** 中英切换目标：pinyin↔alphabetic；其余类型 → primary 或 pinyin。 */
    fun toggleMainTarget(): String = when (current) {
        KeyboardTypes.PINYIN -> KeyboardTypes.ALPHABETIC
        KeyboardTypes.ALPHABETIC -> KeyboardTypes.PINYIN
        else -> primary ?: KeyboardTypes.PINYIN
    }

    fun reset() {
        current = null
        primary = null
        last = null
    }
}

/** 类型是否写持久化（纯决策，可单测）：临时类型永不写盘。 */
internal fun shouldPersistType(type: String): Boolean = !KeyboardTypes.isTemp(type)

/**
 * 首挂类型恢复（纯决策，可单测）：存过的类型只有主类型
 * （pinyin/alphabetic）才恢复；工具类型、自定义类型、临时类型、空值
 * 一律回落按输入模式的初始主类型（中文 → pinyin，英文 →
 * alphabetic）。理由：工具板是 transient 的——进程重建的首挂
 * 键盘必须是主键盘，收起前停在符号/表情板上留下的存过类型
 * 不能把首挂定在板上；已确立的内存类型（typeState.current）
 * 不走本函数（调用方优先沿用，见 onAttach 首挂分支）。
 */
internal fun resolveFirstAttachType(stored: String?, asciiMode: Boolean): String {
    if (stored != null && stored in KeyboardTypes.MAIN) return stored
    return if (asciiMode) KeyboardTypes.ALPHABETIC else KeyboardTypes.PINYIN
}

/**
 * 首挂类型来源（纯决策，可单测）：存活的当前类型亦只认主类型——
 * 同进程内 currentName 被清空（如挂接失败）后重走首挂时，之前
 * 留在数字／符号板上的非主类型不得直接成为首挂键盘，与进程
 * 重建的存过类型同口径；存活主类型沿用，存活非主／空时走存过
 * 类型／初始主类型。
 */
internal fun resolveFirstAttachRaw(current: String?, stored: String?, asciiMode: Boolean): String {
    if (current != null && current in KeyboardTypes.MAIN) return current
    return resolveFirstAttachType(stored, asciiMode)
}

/**
 * 打开时方案与留存类型对账（纯决策，可单测）：切方案只切布局不动类型，
 * 留存主类型可能是上个方案留下的陈旧类型（打开即布局与方案名对不上）；
 * ascii 开着是主动英文，保留；否则以方案的主类型为准。工具板、自定义
 * 类型、未知方案布局与空类型一律不动，返回原值。
 */
internal fun reconcileOpenType(schemeType: String?, current: String?, asciiMode: Boolean): String? {
    if (asciiMode) return current
    if (schemeType in KeyboardTypes.MAIN && current in KeyboardTypes.MAIN && schemeType != current) {
        return schemeType
    }
    return current
}

/** 重挂决策（[KeyboardStateManager.onAttach] 的纯决策部分，可单测）。 */
internal sealed interface ReattachAction {
    /** 目标名与当前名相等：重显缓存键盘实例，不切换。 */
    data object Reshow : ReattachAction

    /** 目标名不等：按类型重新切换（类型解析路径）。 */
    data class SwitchType(val type: String) : ReattachAction

    /** 目标名不等：按名重新切换（旧路径，类型未确立时）。 */
    data class SwitchName(val name: String) : ReattachAction
}

/**
 * 重挂决策（round-1 空白修复 b3458e5a 的类型化推广）：先经与
 * 切换路径相同的解析算出目标名再比较，绝不拿未解析的方案
 * 布局名直接比当前键盘名。
 * - 有类型：类型解析目标 == 当前名 → 重显；否则按类型切。
 * - 无类型：旧按名解析目标 == 当前名 → 重显；否则按名切。
 * - 非主类型（数字／符号／表情／自定义板）：一律重显当前名，
 *   绝不按类型切（首挂只认主类型同原则；陈旧板类型不得在重挂
 *   时覆盖字段键盘）。
 * 两种路径的目标计算与 switchKeyboardType/switchTo 的目标计算
 * 同源（同一解析函数、同一 schemaDefault），决策与切换不可能
 * 因解析口径不同而相撞（旧 bug 正是口径不同所致）。
 */
internal fun decideReattach(
    snapshot: com.jobeen.ime.skin.model.SkinSnapshot?,
    currentName: String,
    currentType: String?,
    schemaLayout: String,
    landscape: Boolean,
    isDark: Boolean = false,
): ReattachAction {
    return if (currentType != null) {
        // 非主类型不得在重挂时翻回（与首挂只认主类型同原则）：
        // 按名切换不改类型，文本字段的 startInput 先把名切回主键盘、
        // 类型却留在板上；重挂时当前名已是字段键盘（startInput 先行），
        // 若仍信这个陈旧板类型，就会把字段键盘翻回板上（重启／换框
        // 后首键盘变数字／符号／表情板即此路径）。主类型（中英语言
        // 选择）不受此限，照常按类型切、跨收起保留。
        if (currentType !in KeyboardTypes.MAIN) {
            return ReattachAction.Reshow
        }
        val target = CustomLayoutRuntime.resolveTypeTarget(
            snapshot, currentType, schemaLayout, landscape, isDark,
        )
        if (target == currentName) ReattachAction.Reshow else ReattachAction.SwitchType(currentType)
    } else {
        val target = CustomLayoutRuntime.resolveSwitchTarget(
            snapshot, schemaLayout, schemaLayout, landscape,
        )
        if (target == currentName) ReattachAction.Reshow else ReattachAction.SwitchName(schemaLayout)
    }
}
