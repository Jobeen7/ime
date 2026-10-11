package com.jobeen.ime.engine

import android.content.Context
import android.content.SharedPreferences
import android.inputmethodservice.InputMethodService
import android.os.SystemClock
import android.text.InputType
import android.view.KeyEvent.*
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import androidx.core.content.edit
import com.jobeen.ime.ImeApplication
import com.jobeen.ime.base.util.InputConnectionUtil
import com.jobeen.ime.base.util.PinYinUtil
import com.jobeen.ime.base.util.TextUtil
import com.jobeen.ime.engine.behavior.IBehavior
import com.jobeen.ime.engine.rime.behavior.Segmentation
import com.jobeen.ime.data.database.AppDatabase
import com.jobeen.ime.data.manager.CandidateManager
import com.jobeen.ime.data.manager.CandidatePreferCache
import com.jobeen.ime.data.manager.CandidateSortingManager
import com.jobeen.ime.data.manager.applySavedCandidateOrder
import com.jobeen.ime.data.manager.candidateSortingKey
import com.jobeen.ime.data.manager.DeletedWordsStore
import com.jobeen.ime.data.manager.SchemaManager
import com.jobeen.ime.engine.event.KeyEvent
import com.jobeen.ime.engine.event.KeyModifiers
import com.jobeen.ime.engine.rime.host.BehaviorHost
import com.jobeen.ime.engine.data.CandidatePinYin
import com.jobeen.ime.engine.data.EngineMessage
import com.jobeen.ime.engine.data.EngineMessage.Candidate
import com.jobeen.ime.engine.rime.behavior.InputKey
import com.jobeen.ime.engine.rime.behavior.InputString
import com.jobeen.ime.engine.rime.behavior.Reset
import com.jobeen.ime.engine.rime.behavior.SelectPinYin
import com.jobeen.ime.engine.rime.behavior.Selection
import com.jobeen.ime.engine.rime.core.IRimeJob
import com.jobeen.ime.engine.rime.core.RimeApi
import com.jobeen.ime.engine.rime.daemon.RimeDaemon
import com.jobeen.ime.engine.rime.daemon.RimeSession
import com.jobeen.ime.engine.manager.CandidateRerankManager
import com.jobeen.ime.engine.manager.PredictionManager
import com.jobeen.ime.engine.rime.core.KeyMapping
import com.jobeen.ime.engine.rime.core.EngineMessageConverter
import com.jobeen.ime.engine.rime.core.Rime
import com.jobeen.ime.engine.rime.core.RimeConfig
import com.jobeen.ime.engine.rime.core.RimeMessage
import com.jobeen.ime.data.App.modelDir
import com.jobeen.ime.engine.rime.data.DataManager.sharedDataDir
import com.jobeen.ime.base.util.TraditionalConverter
import com.jobeen.ime.engine.rime.util.OptionsApplier
import com.jobeen.ime.input.ImeInputMethodService
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import java.lang.ref.WeakReference
import kotlin.lazy

class RimeEngine : IEngine, IBehaviorHost, IRimeJob {

    companion object {
        /**
         * librime native 层“禁止个性化学习”运行时开关
         *（见 cpp/patches/librime-no-personalized-learning.patch，
         * Memory::OnCommit 为 true 时跳过用户词典自学习）。
         * 非 schema switch，切换方案不会被重置；每次 onStartInputView 按输入框重设。
         */
        private const val NO_PERSONALIZED_LEARNING_OPTION = "__no_personalized_learning"

        /** 选词偏好表容量上限与裁剪检查节流间隔（按 upsert 次数计）。 */
        /** 分页补取的单页条数（与首屏 bulk 上限分开：续页可以大些，减少往返） */
        private const val CANDIDATE_PAGE_SIZE = 48
        /** 每批候选补取封顶：再深处是字体无字形的扩展区生僻字，不加载 */
        private const val CANDIDATE_BATCH_CAP = 200
        /** 动作/任务队列容量：远超正常深度，仅作异常堆积兜底 */
        private const val ACTION_QUEUE_CAPACITY = 512
        private const val JOB_QUEUE_CAPACITY = 256
        /** 清空输入框时的分段删除：每段字符数与最大段数 */
        private const val CLEAR_CHUNK_CHARS = 1000
        private const val MAX_CLEAR_CHUNKS = 64
        private const val PREFER_LIMIT = 5000
        private const val PRUNE_CHECK_INTERVAL = 200
    }

    /** 只在 jobs 串行通道内读写，无需同步。 */
    private var upsertsSincePruneCheck = 0
    private data class EngineState(
        var initialized: Boolean = false,
        var predictionVisible: Boolean = false,
        var suppressNextEmptyCandidates: Boolean = false,
        var initHookTriggered: Boolean = false,
        var candidateRequestId: Long = 0L,
        var latestCandidateRequestId: Long = 0L,
        var predictionRequestId: Long = 0L,
        var latestPredictionRequestId: Long = 0L,
    )

    private sealed interface Action {
        data class ProcessKey(val service: InputMethodService, val key: KeyEvent) : Action
        data class RimeMessage(val message: com.jobeen.ime.engine.rime.core.RimeMessage<*>) :
            Action

        data class Behavior(val behavior: IBehavior) : Action
        data class SelectCandidate(val candidate: Candidate) : Action
        data class Clear(val service: InputMethodService) : Action
        data class Undo(val service: InputMethodService) : Action
        data class Predict(val commit: String) : Action
        data class PredictionReady(val requestId: Long, val candidates: List<Candidate>) : Action
        data class EmitMessage(val message: EngineMessage) : Action
        data class CandidatesReady(val requestId: Long, val message: EngineMessage.Candidates) :
            Action

        data class PossibleCandidatePinYinSnapshot(
            val candidatePinYinType: String,
            val currentInput: String,
            val confirmedLen: Int,
        ) : Action

        data object Reset : Action
        data class SelectCandidatePinYin(val pinYin: CandidatePinYin) : Action
        data object Segment : Action
        data class SelectSchema(val schemaId: String) : Action
        data class Commit(val text: String) : Action
        data object InputCleared : Action
        data object Reload : Action
    }

    private val daemon by lazy { RimeDaemon }
    private val scope by lazy { CoroutineScope(SupervisorJob() + Dispatchers.Default) }
    // 两条队列都有界（旧实现 UNLIMITED）：正常深度个位数，容量只为异常
    // 生产者（引擎通知风暴、长任务堵住消费端）兜底防无限堆积。溢出策略
    // 分两边：jobs 全是控制任务，走 sendJob/awaitJob 的异步补投、永不丢；
    // actions 经 dispatchAction 分发，易变类满时自丢、控制类异步补投。
    private val actions = Channel<Action>(ACTION_QUEUE_CAPACITY)
    private val jobs by lazy { Channel<suspend RimeApi.() -> Unit>(JOB_QUEUE_CAPACITY) }
    private var session: RimeSession? = null
    private var behaviorHosted: BehaviorHost? = null
    private var context: Context? = null

    @Volatile
    private var inputConnection: InputConnection? = null
    @Volatile
    private var editorInfo: EditorInfo? = null

    // 用户搭配学习链：上一个打字上屏的词段（简体形）。只由打字路径
    // （native 上屏、预测候选点选）推进；前端提交（剪贴板/常用语/语音
    // 等 CommitAction）不学且打断此链；输入结束/隐私闸门时清空。
    private var lastLearnSegment: String? = null
    // 弱引用：EngineFactory 单例持有 RimeEngine，强引用 service 会导致 service 销毁后泄漏
    private var serviceRef: WeakReference<ImeInputMethodService>? = null

    //引擎相关配置监控
    private var prefs: SharedPreferences? = null
    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (OptionsApplier.isOptionDependency(key)) {
            sendJob { currentSchema().applyOptions(this) }
        }
    }

    /** 桥接模式下返回虚拟连接，否则返回真实连接。 */
    private fun inputConnection(): InputConnection? =
        serviceRef?.get()?.activeInputConnection() ?: inputConnection

    private val rerankManager by lazy { context?.let { CandidateRerankManager(it) } }
    private val predictionManager by lazy { context?.let { PredictionManager(it) } }
    private val state = EngineState()

    /**
     * 光标前文本缓存：getTextBeforeCursor() 是跨进程 Binder 调用，
     * 之前每次产生候选都会调一次。这里缓存最近 64 个字符，TTL 800ms；
     * 新输入框 / 上屏 / 删除文本时主动失效。
     * 注意：调用方仍需各自做隐私门控（密码框等场景不要读前文）。
     */
    @Volatile private var beforeCursorCache = ""
    @Volatile private var beforeCursorCacheAt = 0L

    private fun peekTextBeforeCursor(n: Int): String {
        val now = SystemClock.uptimeMillis()
        if (beforeCursorCacheAt == 0L || now - beforeCursorCacheAt > 800L) {
            beforeCursorCache = runCatching {
                inputConnection()?.getTextBeforeCursor(64, 0)?.toString() ?: ""
            }.getOrDefault("")
            beforeCursorCacheAt = now
        }
        val want = n.coerceIn(0, 64)
        return if (beforeCursorCache.length <= want) beforeCursorCache
        else beforeCursorCache.takeLast(want)
    }

    private fun invalidateBeforeCursorCache() {
        beforeCursorCacheAt = 0L
    }
    @Volatile
    private var predictionJob: Job? = null
    @Volatile
    private var candidateRestoreJob: Job? = null
    /** 分页补取的批次状态：已从引擎取到的原始候选条数（分页 start 必须用原始计数，不能用 UI 过滤后的条数） */
    @Volatile
    private var candidateBatchLoaded: Int = 0
    /** 本批候选总数：-1 = 未知（首屏取满、后面可能还有） */
    @Volatile
    private var candidateBatchTotal: Int = -1
    @Volatile
    private var candidateLoadMoreInFlight: Boolean = false
    /** 候选拖拽排序表判空缓存：null 语义用 false+首次查询实现，保存时由 resortCandidates 失效 */
    @Volatile
    private var sortingTableEmpty: Boolean = false
    // 消息分流（在 emitMessage 里按类型决定）：缓冲本身用 SUSPEND 保序、
    // 永不自动丢弃；易变类消息（候选/编码/联想等每键都来、下一批即取代）
    // 走 tryEmit，缓冲满时丢自己，避免 UI 卡顿反压引擎管线；控制类消息
    // （上屏 Commit、切方案 Schema、部署 Deploy、状态 Status、分页追加页）
    // 走 emit 挂起等待，永不丢——旧实现是全流 DROP_OLDEST，极端积压时
    // 控制消息也可能被挤掉，分流后这个口子堵上。
    private val messages = MutableSharedFlow<EngineMessage>(
        replay = 0, extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.SUSPEND
    )

    /** 易变类：丢一条可由下一批同类消息自愈；其余皆为控制类 */
    private fun EngineMessage.isVolatile(): Boolean = when (this) {
        is EngineMessage.Candidates -> !append
        is EngineMessage.Composition, is EngineMessage.InlinePreedit,
        is EngineMessage.DynamicPreedit, is EngineMessage.PossibleCandidatePinYin,
        is EngineMessage.CandidateMenu -> true
        else -> false
    }

    /** 全部 UI 消息的统一出口：易变类 tryEmit 自丢，控制类 emit 不丢 */
    private suspend fun emitMessage(msg: EngineMessage) {
        if (msg.isVolatile()) messages.tryEmit(msg) else messages.emit(msg)
    }

    override fun initialize(context: Context) {
        val appContext = context.applicationContext
        this@RimeEngine.context = appContext

        val app = this@RimeEngine.context as ImeApplication
        app.notifyState(ImeApplication.AppState.EngineStarting)
        behaviorHosted = BehaviorHost(this)

        scope.launch {
            for (action in actions) reduce(action)
        }

        //observe engine Messages at first.
        scope.launch {
            daemon.observeMessages { actions.send(Action.RimeMessage(it)) }
        }
        session = daemon.createSession(javaClass.name)
        scope.launch {
            for (job in jobs) {
                try {
                    session?.runOnReady {
                        // 字段选项（禁个性化学习等隐私项）待补设时，在
                        // 本 job 之前先行应用：不依赖补设 job 在队列中
                        // 与按键 job 的相对位置——队列满时 sendJob 的
                        // 异步补投会让两者乱序，密码框最初几键可能在
                        // native 学习仍开启时被处理。消费端是唯一串行
                        // 执行点，在这里补设顺序即确定。
                        if (fieldOptionsPending) {
                            fieldOptionsPending = false
                            editorInfo?.let { applyFieldOptions(it) }
                        }
                        job()
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // 单个 job 失败（如引擎在就绪前被停掉，whenReady 抛
                    // RimeStoppedException）只丢这一个 job 并记日志，
                    // 不能让异常杀掉整个 job 消费循环、堵死后续所有引擎操作
                    Timber.w(e, "Rime job failed")
                }
            }
        }
        //监听引擎注册事件
        prefs = appContext.getSharedPreferences(
            CandidateManager.PREFS_NAME, Context.MODE_PRIVATE
        ).also {
            it.registerOnSharedPreferenceChangeListener(prefsListener)
        }
        //以结束为号
        sendJob {
            joinMaintenanceThread()
            actions.send(
                Action.RimeMessage(
                    RimeMessage.DeployMessage(RimeMessage.DeployMessage.State.Finish)
                )
            )
            currentSchema().applyOptions(this)
        }
    }

    override fun finalize() {
        prefs?.unregisterOnSharedPreferenceChangeListener(prefsListener)
        prefs = null
        actions.close()
        jobs.close()
        scope.cancel()
        predictionManager?.destroy()
        daemon.destroySession(javaClass.name)
    }

    override fun processKey(service: InputMethodService, key: KeyEvent) {
        dispatchAction(Action.ProcessKey(service, key))
    }

    private fun processKeyInternal(key: KeyEvent) {
        if (!state.initialized) {
            return
        }
        when (key) {
            is KeyEvent.SequenceEvent -> {
                // 序列键在分类 job 内原本即无条件转发、不读任何状态：
                // 直接分发 Behavior，省去纯转发的一跳。行为的 native
                // 工作仍由 Behavior 经 sendJob 入 jobs 队列执行。
                dispatchAction(Action.Behavior(InputString(key.sequence)))
            }

            is KeyEvent.CodeEvent -> {
                when (key.keyCode) {
                    // 空格/回车/退格的去向取决于执行时点的实时组字，
                    // 只能留在 job 内串行判定（见 sendStatefulKeyJob）
                    KEYCODE_SPACE, KEYCODE_ENTER, KEYCODE_DEL ->
                        sendStatefulKeyJob(key)

                    KEYCODE_APOSTROPHE -> {
                        // 分隔符键在分类 job 内同样是无条件转发
                        dispatchAction(Action.Behavior(Segmentation()))
                    }

                    else -> {
                        // 其余普通键在分类 job 内不读状态、只转发
                        // InputKey：直接分发，绕开纯转发 job
                        dispatchAction(
                            Action.Behavior(
                                InputKey(key.keyCode, key.modifiers.toInt(), key.isVirtual)
                            )
                        )
                    }
                }
            }
        }
    }

    /**
     * 空格/回车/退格的分类 job：三者的去向都取决于 jobs 串行执行
     * 时点的实时组字（getRawInput），不能在分发侧凭组字缓存预判——
     * compositionCached.preedit 是展示串而非原始输入，且分发时点上
     * 先行按键的 job 可能尚未执行、缓存滞后于在途按键，预判会重蹈
     * 退格注释里跨队列按旧状态误判的覆辙。空格/回车在空组字时直通
     * 文本、非空时与普通键同样转 InputKey；退格的判断与处理更必须
     * 同 job 完成（见 handleBackspaceInJob）。
     */
    private fun sendStatefulKeyJob(key: KeyEvent.CodeEvent) {
        sendJob {
            when (key.keyCode) {
                KEYCODE_SPACE -> {
                    if (getRawInput().isEmpty()) {
                        actions.send(Action.EmitMessage(EngineMessage.Commit(" ")))
                        return@sendJob
                    }
                }

                KEYCODE_DEL -> {
                    // 判断与处理收进本 job 内完成（见 handleBackspaceInJob）：
                    // 不再先在本 job 取组字状态、再经 actions 队列转发处理，
                    // 避免跨队列期间组字变化导致按旧状态误判
                    handleBackspaceInJob()
                    return@sendJob
                }

                KEYCODE_ENTER -> {
                    if (getRawInput().isEmpty()) {
                        actions.send(Action.EmitMessage(EngineMessage.Commit("\n")))
                        return@sendJob
                    }
                }
            }
            actions.send(
                Action.Behavior(
                    InputKey(key.keyCode, key.modifiers.toInt(), key.isVirtual)
                )
            )
            return@sendJob
        }
    }

    override fun selectCandidate(candidate: Candidate) {
        dispatchAction(Action.SelectCandidate(candidate))
    }

    private suspend fun selectCandidateInternal(candidate: Candidate) {
        // 选词后旧组字的候选还原结果作废，防止迟到结果把已清空的候选复活
        invalidatePendingCandidates()
        // 隐私：密码框 / 声明 IME_FLAG_NO_PERSONALIZED_LEARNING 的输入框不做任何学习
        //（不读前文、不写偏好表、不跑预测上下文）；正常上屏不受影响
        val noLearn = isNoPersonalizedLearning(editorInfo)
        if (candidate.type == Candidate.TYPE_IME_PREDICTION) {
            emitMessage(EngineMessage.Commit(candidate.text))
            invalidateBeforeCursorCache()
            // 预测候选点选属于打字流上屏：参与搭配学习
            requestPrediction(candidate.text, learnable = true)
            return
        }

        // Only Rime selection produces the empty candidate response that must be hidden.
        // Prediction candidates bypass Rime and must not arm this flag.
        state.suppressNextEmptyCandidates = true
        if (!noLearn) {
            sendJob {
                val ctx = context ?: return@sendJob
                val dao = AppDatabase.getInstance(ctx).candidatePreferDao()
                // 隐私：不再把光标前文存入 context 列——该列从未被排序/重排读取，
                // 却会随云备份与 .jbk 备份带出用户输入片段。
                dao.upsert(candidate.text, "")
                CandidatePreferCache.noteUpsert(candidate.text)
                // 偏好表容量上限：节流检查，超限时裁剪并让缓存重载（保持两者一致）
                if (++upsertsSincePruneCheck >= PRUNE_CHECK_INTERVAL) {
                    upsertsSincePruneCheck = 0
                    if (dao.count() > PREFER_LIMIT) {
                        dao.pruneToLimit(PREFER_LIMIT)
                        CandidatePreferCache.invalidate()
                    }
                }
            }
        }
        flowBehavior(Selection(candidate.index))
    }

    /**
     * 是否禁止个性化学习：统一判定在 [InputFieldPolicy]（密码类输入框，
     * 或输入框声明了 IME_FLAG_NO_PERSONALIZED_LEARNING）。
     * 生效范围：本 engine 的选词偏好表/缓存/预测/重排，以及经由
     * [NO_PERSONALIZED_LEARNING_OPTION] 控制的 librime native 用户词典自学习；
     * 语音纠错学习（VoiceCorrectionSession）也过同一判定。
     */
    private fun isNoPersonalizedLearning(info: EditorInfo?): Boolean =
        com.jobeen.ime.base.util.InputFieldPolicy.isNoPersonalizedLearning(info)

    /** 是否为文本类密码框（数字密码走数字键盘，不在此列）。 */
    private fun isPasswordTextField(info: EditorInfo?): Boolean {
        if (info == null) return false
        if ((info.inputType and InputType.TYPE_MASK_CLASS) != InputType.TYPE_CLASS_TEXT) return false
        return when (info.inputType and InputType.TYPE_MASK_VARIATION) {
            InputType.TYPE_TEXT_VARIATION_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD -> true
            else -> false
        }
    }

    override fun resetComposition() {
        dispatchAction(Action.Reset)
    }

    override fun moveCursor(service: InputMethodService, direction: Int) {
        sendJob {
            if (compositionCached.preedit?.isNotEmpty() == true) {
                // 正在输入拼音：交给 Rime 处理方向键，移动 preedit 内的 caret（Rime 原生行为）
                processKeyInternal(
                    KeyEvent.CodeEvent(
                        if (direction < 0) KEYCODE_DPAD_LEFT else KEYCODE_DPAD_RIGHT,
                        KeyModifiers.Empty,
                    )
                )
            } else {
                withContext(Dispatchers.Main.immediate) {
                    val ic = inputConnection() ?: return@withContext
                    // 结束编辑器侧 composing（如语音听写），避免与编辑器光标状态错位
                    ic.finishComposingText()
                    val keyCode = if (direction < 0) KEYCODE_DPAD_LEFT else KEYCODE_DPAD_RIGHT
                    ic.sendKeyEvent(android.view.KeyEvent(ACTION_DOWN, keyCode))
                    ic.sendKeyEvent(android.view.KeyEvent(ACTION_UP, keyCode))
                }
            }
        }
    }

    override fun selectCandidatePinYin(pinYin: CandidatePinYin) {
        dispatchAction(Action.SelectCandidatePinYin(pinYin))
    }

    override fun segement() {
        dispatchAction(Action.Segment)
    }

    override fun selectSchema(schemaId: String) {
        dispatchAction(Action.SelectSchema(schemaId))
    }

    override fun undo(service: InputMethodService) {
        dispatchAction(Action.Undo(service))
    }

    override fun redo(service: InputMethodService) {
        InputConnectionUtil.sendCombinationKeyEvent(
            inputConnection(), KEYCODE_Z, ctrl = true, shift = true
        )
    }

    override fun resortCandidates(candidates: List<Candidate>) {
        val ctx = context ?: return
        if (candidates.isEmpty()) return
        val db = AppDatabase.getInstance(ctx)
        // 有保存动作即排序表非空，失效 restoreCandidates 的判空缓存
        sortingTableEmpty = false
        sendJob {
            // 排序记录按当前组字串键保存（job 内即 RimeApi 作用域，
            // compositionCached 是与组字产生同步赋值的缓存）；
            // 无组字（预测态）时键为空，save 内部据此跳过
            CandidateSortingManager(db).save(
                candidates, candidateSortingKey(compositionCached.preedit.orEmpty())
            )
        }
    }

    override fun deleteCandidate(index: Int) {
        sendJob { deleteCandidate(index, global = true) }
    }


    override fun flowed(behavior: IBehavior): Boolean {
        dispatchAction(Action.Behavior(behavior))
        return true
    }

    private fun flowBehavior(behavior: IBehavior): Boolean =
        behaviorHosted?.flowed(behavior) == true

    override fun resetState() {
        dispatchAction(Action.Reset)
    }

    private fun possibleCandidatePinYin() {
        sendJob {
            if (!PinYinUtil.isValidType(schemaCached.candidateKind)) {
                return@sendJob
            }
            val currentInput = getRawInput()
            val confirmedLen = getInputConfirmedPosition()
            dispatchAction(
                Action.PossibleCandidatePinYinSnapshot(
                    schemaCached.candidateKind, currentInput, confirmedLen
                )
            )
        }
    }

    private suspend fun reduce(action: Action) {
        when (action) {
            is Action.ProcessKey -> {
                serviceRef = (action.service as? ImeInputMethodService)?.let(::WeakReference)
                processKeyInternal(action.key)
            }

            is Action.RimeMessage -> handleRimeMessage(action.message)
            is Action.Behavior -> flowBehavior(action.behavior)
            is Action.SelectCandidate -> selectCandidateInternal(action.candidate)
            is Action.Clear -> {
                serviceRef = (action.service as? ImeInputMethodService)?.let(::WeakReference)
                clearInternal()
            }

            is Action.Undo -> {
                serviceRef = (action.service as? ImeInputMethodService)?.let(::WeakReference)
                sendJob {
                    if (compositionCached.preedit?.isNotEmpty() == true) {
                        // 正在输入拼音：上滑撤销=清除当前正在打的拼音。
                        // 不能给编辑器发 Ctrl+Z，拼音在输入法内部，编辑器撤销够不着还会产生副作用。
                        actions.send(Action.Reset)
                    } else {
                        withContext(Dispatchers.Main.immediate) {
                            InputConnectionUtil.sendCombinationKeyEvent(
                                inputConnection(), KEYCODE_Z, ctrl = true
                            )
                        }
                    }
                }
            }

            is Action.Predict -> requestPrediction(action.commit)
            is Action.PredictionReady -> {
                if (action.requestId == state.latestPredictionRequestId) {
                    // 预测路径同样要过滤已删除词（删除过滤不能只挂在 Rime 候选还原上）
                    val visible = action.candidates.filterNot { DeletedWordsStore.isDeleted(it.text) }
                    state.predictionVisible = visible.isNotEmpty()
                    // 预测候选是本地联想的完整列表、没有分页：total 显式给
                    // 确切条数，避免下游按 -1（未知）误判"还有更多"而在
                    // 预测态触发引擎分页补取、把组字候选拼进联想列表
                    emitMessage(EngineMessage.Candidates(visible, 0, 0, total = visible.size))
                }
            }

            is Action.EmitMessage -> emitMessage(action.message)

            is Action.PossibleCandidatePinYinSnapshot -> {
                val pinYins = behaviorHosted?.possiblePinYin(
                    action.candidatePinYinType, action.currentInput, action.confirmedLen
                ) ?: emptyList()
                emitMessage(EngineMessage.PossibleCandidatePinYin(pinYins))
            }

            is Action.CandidatesReady -> {
                if (action.requestId == state.latestCandidateRequestId) {
                    emitMessage(action.message)
                }
            }

            Action.Reset -> {
                invalidatePendingCandidates()
                invalidatePendingPrediction()
                flowBehavior(Reset())
            }

            is Action.SelectCandidatePinYin -> flowBehavior(SelectPinYin(action.pinYin))
            Action.Segment -> flowBehavior(Segmentation())
            is Action.SelectSchema -> {
                flowBehavior(Reset())
                sendJob {
                    selectSchema(action.schemaId)
                    // ApplySchema 会清掉 librime 的 "_" 开头 transient options（含
                    // __no_personalized_learning），密码框 ascii 强制也可能被重置：
                    // 同一输入框内切方案后按当前输入框统一重设字段选项
                    editorInfo?.let { applyFieldOptions(it) }
                }
            }

            is Action.Commit -> requestCommit(action.text)
            Action.InputCleared -> {
                invalidatePendingCandidates()
                invalidatePendingPrediction()
                if (state.predictionVisible) {
                    state.predictionVisible = false
                    emitMessage(EngineMessage.Candidates(emptyList(), 0, 0))
                }
            }

            Action.Reload -> {
                behaviorHosted?.resetState()
                sendJob {
                    deploy()
                    joinMaintenanceThread()
                    actions.send(
                        Action.RimeMessage(
                            RimeMessage.DeployMessage(RimeMessage.DeployMessage.State.Finish)
                        )
                    )
                }
            }
        }
    }

    /**
     * 退格处理（在 processKeyInternal 的 rime job 内同步执行）：「组字是否
     * 为空」的判断与后续处理落在同一个 job 里，不再跨 actions/jobs 两条
     * 队列——旧实现先取状态再转发，期间组字被其它按键改掉就会按旧状态
     * 误判（组字已空仍转交 Rime、或组字未空却删了宿主文本）。
     * 组字非空时交给 [BehaviorHost.backspaceInJob] 在本 job 内同步消化
     * （其队列维护的第二套状态也在同一 job 内一并推进）；组字为空时才
     * 走原有出口：先收联想候选，再按选区/宿主 DEL 处理。
     */
    private suspend fun RimeApi.handleBackspaceInJob() {
        if (getRawInput().isNotEmpty()) {
            behaviorHosted?.backspaceInJob(this)
            return
        }
        if (state.predictionVisible) {
            state.predictionVisible = false
            emitMessage(EngineMessage.Candidates(emptyList(), 0, 0))
            return
        }
        withContext(Dispatchers.Main.immediate) {
            val ic = inputConnection()
            if (!ic?.getSelectedText(0).isNullOrEmpty()) {
                emitMessage(EngineMessage.Commit(""))
                invalidateBeforeCursorCache()
                return@withContext
            }
            InputConnectionUtil.sendCombinationKeyEvent(ic, KEYCODE_DEL)
            invalidateBeforeCursorCache()
        }
    }

    /**
     * 引擎未处理按键的透传：native 拒绝的按键原先经 converter 落到
     * EngineMessage.Unknown，在消费端被直接丢弃，目标应用收不到
     * （个别按键在应用里无反应）。这里按 Android 键事件补发给输入连接。
     * 修饰键状态由 [KeyModifiers.metaState] 还原；Release 修饰只补发抬起。
     * 映射不回 Android 键码的（[KeyMapping.Key_VoidSymbol] 等）原始键码在
     * 进入引擎的映射阶段已经丢失、无法还原，仍只能丢弃并记日志。
     */
    private suspend fun forwardUnhandledKey(message: RimeMessage.KeyMessage) {
        // 内部合成按键（isVirtual=false，如部署 hook 的 processKey
        // (Key_Delete,…,false)、退格在 job 内重走的 BackSpace）不是用户
        // 真实按键：native 未处理也不透传给宿主，避免凭空删字/触发宿主按键
        if (!message.data.isVirtual) return
        val keyCode = message.data.value.keyCode
        if (keyCode == KEYCODE_UNKNOWN) {
            Timber.d("Drop unhandled key without Android mapping: %s", message.data.value)
            return
        }
        val metaState = message.data.modifiers.metaState
        val now = SystemClock.uptimeMillis()
        withContext(Dispatchers.Main.immediate) {
            val ic = inputConnection() ?: return@withContext
            if (!message.data.modifiers.release) {
                ic.sendKeyEvent(
                    android.view.KeyEvent(now, now, ACTION_DOWN, keyCode, 0, metaState)
                )
            }
            ic.sendKeyEvent(
                android.view.KeyEvent(now, now, ACTION_UP, keyCode, 0, metaState)
            )
        }
    }

    private suspend fun handleRimeMessage(message: RimeMessage<*>) {
        if (message is RimeMessage.KeyMessage) {
            forwardUnhandledKey(message)
            return
        }
        val msg = EngineMessageConverter.convert(message)
        when (msg) {
            is EngineMessage.InlinePreedit -> {
                if (msg.preedit.isEmpty()) {
                    behaviorHosted?.resetState()
                }
                possibleCandidatePinYin()
                return
            }

            is EngineMessage.Commit -> {
                invalidatePendingCandidates()
                candidateBatchLoaded = 0
                candidateBatchTotal = -1
                state.suppressNextEmptyCandidates = true
                // native 刚上屏：前文缓存失效，预测读到新鲜前文
                invalidateBeforeCursorCache()
                // 打字上屏：参与搭配学习
                requestPrediction(msg.text, learnable = true)
            }

            is EngineMessage.Candidates -> {
                // 记录分页批次：首屏 bulk 的原始条数与总数信号（total=-1 为
                // 取满上限、后面可能还有），供 loadMoreCandidates 续取
                candidateBatchLoaded = msg.list.size
                candidateBatchTotal = msg.total
                if (msg.list.isNotEmpty()) {
                    // Rime candidates take over the panel from prediction candidates.
                    state.predictionVisible = false
                    invalidatePendingPrediction()
                }
                if (state.suppressNextEmptyCandidates) {
                    state.suppressNextEmptyCandidates = false
                    if (msg.list.isEmpty()) {
                        return
                    }
                }
                restoreCandidates(msg)
                return
            }

            is EngineMessage.Schema -> {
                // librime 内部切方案（switcher/lua 等）不走 Action.SelectSchema：
                // ApplySchema 会 ClearTransientOptions 清掉 "_" 开头的运行时
                // 选项（含 __no_personalized_learning），且没有上层路径补设，
                // 密码框等场景的禁学习会被内部切方案绕过。这里在方案变更
                // 消息路径上按 SelectSchema/Deploy 的同款方式补设一次。
                sendJob { editorInfo?.let { applyFieldOptions(it) } }
            }

            is EngineMessage.Depoly -> {
                when (msg.state) {
                    EngineMessage.Depoly.State.Start -> state.initialized = false
                    EngineMessage.Depoly.State.Finish -> {
                        state.initialized = true
                        val triggerHook = !state.initHookTriggered
                        state.initHookTriggered = true
                        sendJob {
                            val prefs = context?.getSharedPreferences(
                                SchemaManager.PREFS_NAME, Context.MODE_PRIVATE
                            )
                            val enabledIds =
                                prefs?.getString(SchemaManager.KEY_ENABLED_IDS, "")?.split(",")
                                    ?.filter { it.isNotBlank() }
                            val schemas = enabledSchemata()
                            if (enabledIds.isNullOrEmpty()) {
                                val ids = schemas.joinToString(",") { it.id }
                                prefs?.edit { putString(SchemaManager.KEY_ENABLED_IDS, ids) }
                            }
                            val currentSchema = currentSchema()
                            // openSchema 是 librime native 入口：本 job 运行在
                            // jobs 线程，直调会绕开 rime-main 串行线。派发到
                            // rime-main 执行并等待结果，读取失败按无语法模型降级
                            val grammarLanguage = runCatching {
                                Rime.runOnRimeMain {
                                    RimeConfig.openSchema(currentSchema.schemaId).use { config ->
                                        config.getString("grammar/language")
                                    }
                                }
                            }.getOrNull()
                            grammarLanguage?.let {
                                Timber.d("predictionManager load model %s.gram", it)
                                predictionManager?.loadModels(modelDir, sharedDataDir, it)
                            }

                            if (triggerHook) {
                                processKey(KeyMapping.Key_Delete, 0U, false)
                                context?.let { it as ImeApplication }
                                    ?.notifyState(ImeApplication.AppState.Finished)
                            }

                            // 部署重建了 native 会话：字段选项（禁个性化学习、
                            // 密码框强制英文）随会话丢失，按当前输入框重设
                            // （Reload 的完成路径也走这里）。初始化部署时
                            // editorInfo 为空，自动跳过。
                            editorInfo?.let { applyFieldOptions(it) }
                        }
                    }

                    else -> {}
                }
            }

            else -> {}
        }
        emitMessage(msg)
    }

    override fun observeMessages(
        scope: CoroutineScope, onMessage: suspend (EngineMessage) -> Unit
    ): Job {
        return scope.launch {
            messages.collect { message ->
                onMessage(message)
            }
        }
    }

    override suspend fun schemasList(): List<EngineMessage.Schema> =
        awaitJob(emptyList()) {
            enabledSchemata().map {
                EngineMessage.Schema(
                    it.id, it.name, it.layout, it.punctuation, it.kind
                )
            }
        }


    override fun clear(service: InputMethodService) {
        dispatchAction(Action.Clear(service))
    }

    private suspend fun clearInternal() {
        invalidatePendingCandidates()
        invalidatePendingPrediction()
        val clearPredictions = state.predictionVisible
        state.predictionVisible = false
        if (clearPredictions) {
            emitMessage(EngineMessage.Candidates(emptyList(), 0, 0))
        }
        sendJob {
            if (compositionCached.preedit?.isNotEmpty() == true) {
                actions.send(Action.Reset)
            } else {
                withContext(Dispatchers.Main.immediate) {
                    val ic = inputConnection()
                    if (ic != null && !ic.getTextBeforeCursor(1, 0).isNullOrEmpty()) {
                        // 分段删除：旧实现单次 deleteSurroundingText(MAX, MAX)
                        // 在部分 App 的自绘输入框上会只删一部分或触发全量文本
                        // 处理造成卡顿。按段删（前文→后文），每轮先探长度、
                        // 探到 0 或删除无进展即停，段数设上限防异常输入框
                        // 让循环失控；极端长文删不完时用户可再触发一次。
                        var chunks = 0
                        while (chunks++ < MAX_CLEAR_CHUNKS) {
                            val n = ic.getTextBeforeCursor(CLEAR_CHUNK_CHARS, 0)?.length ?: 0
                            if (n == 0 || !ic.deleteSurroundingText(n, 0)) break
                        }
                        chunks = 0
                        while (chunks++ < MAX_CLEAR_CHUNKS) {
                            val n = ic.getTextAfterCursor(CLEAR_CHUNK_CHARS, 0)?.length ?: 0
                            if (n == 0 || !ic.deleteSurroundingText(0, n)) break
                        }
                        invalidateBeforeCursorCache()
                    }
                }
            }
        }
    }

    /**
     * 动作队列的非挂起分发入口：先 trySend；队列满时易变类直接丢弃
     * （预测/候选回执有 requestId 守卫，候选/组字类引擎通知会被下一批
     * 取代），控制类（按键、选词、切方案、上屏、清空等）转异步挂起
     * 补投、永不丢。协程上下文内可直接 actions.send 挂起等待。
     */
    private fun dispatchAction(action: Action) {
        if (actions.trySend(action).isSuccess) return
        if (action.isDroppable()) return
        scope.launch { actions.send(action) }
    }

    private fun Action.isDroppable(): Boolean = when (this) {
        is Action.PredictionReady, is Action.CandidatesReady -> true
        is Action.RimeMessage -> when (message.messageType) {
            RimeMessage.MessageType.Candidate, RimeMessage.MessageType.Composition,
            RimeMessage.MessageType.InlinePreedit, RimeMessage.MessageType.DynamicPreedit,
            RimeMessage.MessageType.Menu -> true
            else -> false
        }
        else -> false
    }

    override fun sendJob(block: suspend RimeApi.() -> Unit) {
        if (jobs.trySend(block).isFailure) {
            // 队列满说明引擎正被长任务堵住：任务都是控制类（导词典、
            // 清屏、切方案等），不许丢，转异步挂起入队等空位
            scope.launch { jobs.send(block) }
        }
    }

    override suspend fun <T> awaitJob(defaultValue: T, block: suspend RimeApi.() -> T): T {
        val deferred = CompletableDeferred<T>()
        val task: suspend RimeApi.() -> Unit = {
            try {
                deferred.complete(block())
            } catch (_: Throwable) {
                deferred.complete(defaultValue)
            }
        }
        if (jobs.trySend(task).isFailure) {
            // 同 sendJob：满时异步补投，任务仍会执行；等待方有 2 秒
            // 超时兜底，超时先返回默认值，迟到的 complete 自动忽略
            scope.launch { jobs.send(task) }
        }
        return withTimeoutOrNull(2000L) { deferred.await() } ?: defaultValue
    }

    /**
     * 分页补取：首屏 bulk 只取少量候选（native 上限），UI 滚动到底时经此
     * 按已加载原始条数续取下一页。批次锚点用 latestCandidateRequestId：
     * 补取在途时用户又打了键，新批次会 ++ 该值，回来时对不上即丢弃，
     * 不会把上一编码的候选拼进新列表。追加页不走 restoreCandidates 的
     * 重排/排序还原（那套只管首屏），仅过滤已删词、保持引擎原序。
     */
    override fun loadMoreCandidates() {
        // 预测（联想）显示期间不补取：分页补的是引擎组字候选，拼进
        // 联想列表会串味。正常由消息侧 total 保证预测态不触发，这里
        // 再兜一道（批次计数是上一组字批次的残留值）
        if (state.predictionVisible) return
        val start = candidateBatchLoaded
        if (start <= 0) return
        val total = candidateBatchTotal
        if (total >= 0 && start >= total) return
        if (candidateLoadMoreInFlight) return
        candidateLoadMoreInFlight = true
        val batch = state.latestCandidateRequestId
        // 封顶：每批最多累计取 CANDIDATE_BATCH_CAP 个候选。词库深处是
        // 设备字体无字形的扩展区生僻字（渲染为方框、打出也无处可用），
        // 200 已覆盖全部真实输入需求，不再往深处加载
        val limit = minOf(CANDIDATE_PAGE_SIZE, CANDIDATE_BATCH_CAP - start)
        sendJob {
            try {
                if (limit <= 0) {
                    // 已达封顶：回空追加页把 UI 的「还有更多」关掉
                    candidateBatchTotal = start
                    emitMessage(
                        EngineMessage.Candidates(
                            list = emptyList(),
                            highlighted = 0,
                            page = 0,
                            total = start,
                            append = true,
                        )
                    )
                    return@sendJob
                }
                val page = getCandidates(start, limit)
                if (state.latestCandidateRequestId != batch) return@sendJob
                val raw = page.size
                if (raw == 0) {
                    candidateBatchTotal = start
                    return@sendJob
                }
                candidateBatchLoaded = start + raw
                if (raw < limit || candidateBatchLoaded >= CANDIDATE_BATCH_CAP) {
                    candidateBatchTotal = candidateBatchLoaded
                }
                val items = page.mapIndexed { i, c ->
                    EngineMessage.Candidate(
                        index = start + i,
                        text = c.text,
                        comment = c.comment,
                        type = c.type,
                    )
                }.filterNot { DeletedWordsStore.isDeleted(it.text) }
                if (items.isNotEmpty()) {
                    emitMessage(
                        EngineMessage.Candidates(
                            list = items,
                            highlighted = 0,
                            page = 0,
                            total = candidateBatchTotal,
                            append = true,
                        )
                    )
                }
            } finally {
                candidateLoadMoreInFlight = false
            }
        }
    }

    private fun restoreCandidates(msg: EngineMessage.Candidates) {
        val requestId = ++state.candidateRequestId
        state.latestCandidateRequestId = requestId
        candidateRestoreJob?.cancel()
        candidateRestoreJob = scope.launch {
            // 过滤用户长按删除的词（Rime 墓碑不隐藏系统词，App 层过滤）；
            // 兜底分支也要用过滤后的列表，故在 try 外先算好
            val filtered = msg.list.filterNot { DeletedWordsStore.isDeleted(it.text) }
            val filteredMsg = if (filtered.size == msg.list.size) msg
                else EngineMessage.Candidates(filtered, msg.highlighted, msg.page, total = msg.total)
            try {
                val ctx = context
                if (ctx == null) {
                    actions.send(Action.CandidatesReady(requestId, filteredMsg))
                    return@launch
                }

                val db = AppDatabase.getInstance(ctx)
                // 用户拖拽保存的顺序是对该候选集的显式意愿：无论是否开启
                // 智能重排都要应用（此前重排分支直接丢弃保存顺序，而重排
                // 默认开启，拖拽排序存进数据库也永不生效、拖完必回原位）。
                // 排序表为空时（绝大多数用户从未保存过）跳过查询：旧实现
                // 每键都算一次指纹 + 查一次 Room，结果注定为 null
                val savedIds = if (sortingTableEmpty) {
                    null
                } else {
                    val mgr = CandidateSortingManager(db)
                    if (mgr.isTableEmpty()) {
                        sortingTableEmpty = true
                        null
                    } else {
                        // 按本批候选所属的组字串查保存顺序（与保存端
                        // 同源同值）；保存的序号序列可套到本批子集上。
                        // 组字缓存只在 RimeApi 作用域内可读，经 awaitJob
                        // 取一次——仅排序表非空（用户真拖过）时才走这里，
                        // 从未拖过的用户被上面的判空缓存短路、热路径无感
                        val key = candidateSortingKey(
                            awaitJob("") { compositionCached.preedit.orEmpty() }
                        )
                        mgr.load(key)
                    }
                }
                val rerankEnabled = CandidateManager.isRerankEnabled(ctx)
                if (rerankEnabled) {
                    // 隐私模式下不读光标前文（gramDb 为空时前文本来也不参与打分）；
                    // 非隐私模式走前文缓存，避免每次候选刷新都跨进程读一次
                    val inputContext = if (isNoPersonalizedLearning(editorInfo)) "" else
                        peekTextBeforeCursor(20)
                    // gram 分数此前一直传 null 恒为 0：模型由 PredictionManager
                    // 加载并持有，这里取同一实例接进重排打分
                    val sortedList = rerankManager?.rerank(
                        filteredMsg.list, inputContext, predictionManager?.gramDb()
                    )
                    val baseList = sortedList ?: filteredMsg.list
                    actions.send(
                        Action.CandidatesReady(
                            requestId, EngineMessage.Candidates(
                                if (savedIds.isNullOrEmpty()) baseList
                                else applySavedCandidateOrder(baseList, savedIds),
                                0, 0, total = filteredMsg.total
                            )
                        )
                    )
                } else {
                    actions.send(
                        Action.CandidatesReady(
                            requestId, if (savedIds.isNullOrEmpty()) filteredMsg
                            else EngineMessage.Candidates(
                                applySavedCandidateOrder(filteredMsg.list, savedIds), 0, 0,
                                total = filteredMsg.total
                            )
                        )
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Timber.e(error, "Failed to restore candidates; using filtered list")
                actions.send(Action.CandidatesReady(requestId, filteredMsg))
            }
        }
    }

    /** 取消在途的候选还原并推进代次：迟到的旧还原结果会被 CandidatesReady 门控丢弃。 */
    private fun invalidatePendingCandidates() {
        candidateRestoreJob?.cancel()
        candidateRestoreJob = null
        state.latestCandidateRequestId = ++state.candidateRequestId
    }

    /** 取消在途的预测并推进代次，避免迟到预测覆盖新候选或在清空后重现。 */
    private fun invalidatePendingPrediction() {
        predictionJob?.cancel()
        predictionJob = null
        state.latestPredictionRequestId = ++state.predictionRequestId
    }

    override fun onFinishInputView() {
        inputConnection = null
        editorInfo = null
        lastLearnSegment = null
        invalidateBeforeCursorCache()
        invalidatePendingCandidates()
        invalidatePendingPrediction()
    }

    override fun onSelectionChanged() {
        // 光标移动后前文变了：缓存失效，避免 800ms 内读到旧前文
        invalidateBeforeCursorCache()
    }

    /** 有字段选项等待在下一个 job 之前补设（见 jobs 消费循环）。 */
    @Volatile
    private var fieldOptionsPending = false

    override fun onStartInputView(ic: InputConnection, info: EditorInfo) {
        inputConnection = ic
        editorInfo = info
        invalidateBeforeCursorCache()
        // 隐私：把当前输入框的学习开关同步给 librime native 层。
        // 密码框 / NO_PERSONALIZED_LEARNING 输入框内 native 用户词典不再自学习；
        // 文本密码框进出时保存/恢复中英状态（见 applyAsciiModeForField）。
        // 只置待补设标记：实际补设由 jobs 消费端在下一个 job（含本框
        // 的第一个按键）执行之前完成，顺序确定，不走 sendJob 的异步
        // 队列位置竞争。
        fieldOptionsPending = true
    }

    /**
     * 按输入框应用字段相关的运行时选项：禁个性化学习 + 密码框中英处理。
     * 输入开始时调一次；部署/切方案会重建或重置 native 侧状态，这些选项
     * 随之丢失，必须按当前输入框重设（Deploy Finish 与 SelectSchema 共用）。
     */
    private suspend fun RimeApi.applyFieldOptions(info: EditorInfo) {
        setRuntimeOption(NO_PERSONALIZED_LEARNING_OPTION, isNoPersonalizedLearning(info))
        applyAsciiModeForField(info)
    }

    /**
     * 密码框强制英文前保存的中英状态；null 表示当前不在"密码框强制英文"中。
     * 只在 jobs 通道消费者内读写（串行），无需同步。
     */
    private var asciiModeBeforePassword: Boolean? = null

    private suspend fun RimeApi.applyAsciiModeForField(info: EditorInfo) {
        if (isPasswordTextField(info)) {
            // 进入文本密码框：首次进入时保存当前中英状态（连续两个密码框不重复保存），
            // 然后强制英文直输。数字密码已走数字键盘，不在此处理。
            if (asciiModeBeforePassword == null) {
                asciiModeBeforePassword = getRuntimeOption("ascii_mode")
            }
            setRuntimeOption("ascii_mode", true)
        } else {
            // 离开密码框：恢复进入前保存的状态。
            // 非密码框之间切换不再按偏好重设：Shift 临时切换、方案锁定（如万象锁定中文）
            // 的状态都得以保留。
            val saved = asciiModeBeforePassword
            if (saved != null) {
                asciiModeBeforePassword = null
                setRuntimeOption("ascii_mode", saved)
            }
        }
    }

    override fun predict(commit: String) {
        dispatchAction(Action.Predict(commit))
    }

    private fun requestPrediction(commit: String, learnable: Boolean = false) {
        // 隐私：密码框 / 声明 IME_FLAG_NO_PERSONALIZED_LEARNING 的输入框不读前文、不跑预测、不学搭配
        if (isNoPersonalizedLearning(editorInfo)) {
            lastLearnSegment = null
            return
        }
        // 预测模型基于简体训练；先转成简体再推导，以支持繁体输入下的候选预测。
        // 上屏刚发生时缓存已失效，这里读到的是包含本次上屏内容的新鲜前文。
        val inputContext = TraditionalConverter.toSimplified(
            peekTextBeforeCursor(20) + commit
        )
        // 搭配学习（仅打字上屏路径 learnable=true 时）：与上一个打字
        // 词段组成词对计数；本段不合规（标点等）则断链。查询键取更新
        // 后的链尾，即刚上屏的词段。
        val userKey: String?
        if (learnable) {
            val segment = TraditionalConverter.toSimplified(commit)
            // 语音纠错沉淀：打字上屏词段是用户亲手选定的正形，与搭配
            // 学习共用此入口，交给语音侧与刚上屏的语音段配对（未武装
            // 时为空转检查）。密码/免学习输入框已在函数头统一拦下。
            com.jobeen.ime.base.speech.SherpaSpeechClient.notifyTypedSegment(segment)
            if (com.jobeen.ime.base.ngram.UserCollocationStore.isLearnableSegment(segment)) {
                lastLearnSegment?.let { prev ->
                    predictionManager?.learnCollocation(prev, segment)
                }
                lastLearnSegment = segment
            } else {
                lastLearnSegment = null
            }
            userKey = lastLearnSegment
        } else {
            userKey = null
        }
        val requestId = ++state.predictionRequestId
        state.latestPredictionRequestId = requestId
        predictionJob?.cancel()
        predictionJob = scope.launch {
            try {
                if (context?.let { !CandidateManager.isPredictionEnabled(it) } == true) {
                    actions.send(Action.PredictionReady(requestId, emptyList()))
                    return@launch
                }
                var candidates: List<Candidate> = emptyList()
                if (inputContext.isNotEmpty() && !TextUtil.isSymbol(inputContext.last()) && !TextUtil.isAlphabet(
                        inputContext.last()
                    )
                ) {
                    candidates = predictionManager?.makePredictions(inputContext, userKey) ?: emptyList()
                }
                if (context?.let { CandidateManager.isTraditionalChineseEnabled(it) } == true) {
                    candidates = candidates.map {
                        it.copy(
                            text = TraditionalConverter.toTraditional(it.text),
                            comment = it.comment.takeIf(String::isNotEmpty)
                                ?.let(TraditionalConverter::toTraditional) ?: it.comment,
                        )
                    }
                }
                actions.send(Action.PredictionReady(requestId, candidates))
            } catch (error: CancellationException) {
                throw error
            }
        }
    }

    override fun reload() {
        dispatchAction(Action.Reload)
    }

    //前端提交
    override fun commit(text: String) {
        dispatchAction(Action.Commit(text))
    }

    private fun requestCommit(text: String) {
        // 前端提交（剪贴板/常用语/语音/符号键等）不参与搭配学习，
        // 且打断打字学习链：粘贴的文本不作为下一个打字词的搭配前文
        lastLearnSegment = null
        sendJob {
            // 直接上屏：前文缓存失效，后续预测读新鲜前文
            invalidateBeforeCursorCache()
            if (compositionCached.preedit?.isNotEmpty() == true) {
                commitCurrentSelection(text)
                actions.send(Action.Predict(text))
                return@sendJob
            }
            actions.send(Action.EmitMessage(EngineMessage.Commit(text)))
            actions.send(Action.Predict(text))
        }
    }

    override fun onInputCleared() {
        dispatchAction(Action.InputCleared)
    }
}
