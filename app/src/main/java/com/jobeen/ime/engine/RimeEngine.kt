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
import com.jobeen.ime.data.manager.DeletedWordsStore
import com.jobeen.ime.data.manager.SchemaManager
import com.jobeen.ime.engine.event.KeyEvent
import com.jobeen.ime.engine.event.KeyModifiers
import com.jobeen.ime.engine.rime.host.BehaviorHost
import com.jobeen.ime.engine.data.CandidatePinYin
import com.jobeen.ime.engine.data.EngineMessage
import com.jobeen.ime.engine.data.EngineMessage.Candidate
import com.jobeen.ime.engine.rime.behavior.Backspace
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
import com.jobeen.ime.engine.rime.core.Rime.Companion.getCurrentSchema
import com.jobeen.ime.engine.rime.core.EngineMessageConverter
import com.jobeen.ime.engine.rime.core.RimeConfig
import com.jobeen.ime.engine.rime.core.RimeMessage
import com.jobeen.ime.engine.rime.core.RimeSchema
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
    }
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
        data class Backspace(val rawInputEmpty: Boolean) : Action
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
    private val actions = Channel<Action>(Channel.UNLIMITED)
    private val jobs by lazy { Channel<suspend RimeApi.() -> Unit>(Channel.UNLIMITED) }
    private var session: RimeSession? = null
    private var behaviorHosted: BehaviorHost? = null
    private var context: Context? = null

    @Volatile
    private var inputConnection: InputConnection? = null
    private var editorInfo: EditorInfo? = null
    // 弱引用：EngineFactory 单例持有 RimeEngine，强引用 service 会导致 service 销毁后泄漏
    private var serviceRef: WeakReference<ImeInputMethodService>? = null

    //引擎相关配置监控
    private var prefs: SharedPreferences? = null
    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (OptionsApplier.isOptionDependency(key)) {
            sendJob { RimeSchema(getCurrentSchema()).applyOptions(this) }
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
    private var predictionJob: Job? = null
    private var candidateRestoreJob: Job? = null
    private val messages = MutableSharedFlow<EngineMessage>(
        replay = 0, extraBufferCapacity = 64
    )

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
                session?.runOnReady(job)
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
            RimeSchema(getCurrentSchema()).applyOptions(this)
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
        actions.trySend(Action.ProcessKey(service, key))
    }

    private fun processKeyInternal(key: KeyEvent) {
        if (!state.initialized) {
            return
        }
        sendJob {
            when (key) {
                is KeyEvent.SequenceEvent -> {
                    actions.send(Action.Behavior(InputString(key.sequence)))
                    return@sendJob
                }

                is KeyEvent.CodeEvent -> {
                    when (key.keyCode) {
                        KEYCODE_SPACE -> {
                            if (getRawInput().isEmpty()) {
                                actions.send(Action.EmitMessage(EngineMessage.Commit(" ")))
                                return@sendJob
                            }
                        }

                        KEYCODE_DEL -> {
                            actions.send(Action.Backspace(getRawInput().isEmpty()))
                            return@sendJob
                        }

                        KEYCODE_APOSTROPHE -> {
                            actions.send(Action.Behavior(Segmentation()))
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
        }
    }

    override fun selectCandidate(candidate: Candidate) {
        actions.trySend(Action.SelectCandidate(candidate))
    }

    private suspend fun selectCandidateInternal(candidate: Candidate) {
        // 选词后旧组字的候选还原结果作废，防止迟到结果把已清空的候选复活
        invalidatePendingCandidates()
        // 隐私：密码框 / 声明 IME_FLAG_NO_PERSONALIZED_LEARNING 的输入框不做任何学习
        //（不读前文、不写偏好表、不跑预测上下文）；正常上屏不受影响
        val noLearn = isNoPersonalizedLearning(editorInfo)
        if (candidate.type == Candidate.TYPE_IME_PREDICTION) {
            messages.emit(EngineMessage.Commit(candidate.text))
            invalidateBeforeCursorCache()
            requestPrediction(candidate.text)
            return
        }

        // Only Rime selection produces the empty candidate response that must be hidden.
        // Prediction candidates bypass Rime and must not arm this flag.
        state.suppressNextEmptyCandidates = true
        if (!noLearn) {
            sendJob {
                val ctx = context ?: return@sendJob
                val inputContext = peekTextBeforeCursor(20)
                AppDatabase.getInstance(ctx).candidatePreferDao().upsert(candidate.text, inputContext)
                CandidatePreferCache.noteUpsert(candidate.text)
            }
        }
        flowBehavior(Selection(candidate.index))
    }

    /**
     * 是否禁止个性化学习：密码类输入框，或输入框声明了
     * [EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING]。
     * 生效范围：本 engine 的选词偏好表/缓存/预测/重排，以及经由
     * [NO_PERSONALIZED_LEARNING_OPTION] 控制的 librime native 用户词典自学习。
     */
    private fun isNoPersonalizedLearning(info: EditorInfo?): Boolean {
        if (info == null) return false
        val inputClass = info.inputType and InputType.TYPE_MASK_CLASS
        val variation = info.inputType and InputType.TYPE_MASK_VARIATION
        val isPassword = when (inputClass) {
            InputType.TYPE_CLASS_TEXT -> variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD
            InputType.TYPE_CLASS_NUMBER -> variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD
            else -> false
        }
        if (isPassword) return true
        return (info.imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING) != 0
    }

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
        actions.trySend(Action.Reset)
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
        actions.trySend(Action.SelectCandidatePinYin(pinYin))
    }

    override fun segement() {
        actions.trySend(Action.Segment)
    }

    override fun selectSchema(schemaId: String) {
        actions.trySend(Action.SelectSchema(schemaId))
    }

    override fun undo(service: InputMethodService) {
        actions.trySend(Action.Undo(service))
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
        sendJob {
            CandidateSortingManager(db).save(candidates)
        }
    }

    override fun deleteCandidate(index: Int) {
        sendJob { deleteCandidate(index, global = true) }
    }


    override fun flowed(behavior: IBehavior): Boolean {
        actions.trySend(Action.Behavior(behavior))
        return true
    }

    private fun flowBehavior(behavior: IBehavior): Boolean =
        behaviorHosted?.flowed(behavior) == true

    override fun resetState() {
        actions.trySend(Action.Reset)
    }

    private fun possibleCandidatePinYin() {
        sendJob {
            if (!PinYinUtil.isValidType(schemaCached.candidateKind)) {
                return@sendJob
            }
            val currentInput = getRawInput()
            val confirmedLen = getInputConfirmedPosition()
            actions.trySend(
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

            is Action.Backspace -> handleBackspace(action.rawInputEmpty)

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
                    messages.emit(EngineMessage.Candidates(visible, 0, 0))
                }
            }

            is Action.EmitMessage -> messages.emit(action.message)

            is Action.PossibleCandidatePinYinSnapshot -> {
                val pinYins = behaviorHosted?.possiblePinYin(
                    action.candidatePinYinType, action.currentInput, action.confirmedLen
                ) ?: emptyList()
                messages.emit(EngineMessage.PossibleCandidatePinYin(pinYins))
            }

            is Action.CandidatesReady -> {
                if (action.requestId == state.latestCandidateRequestId) {
                    messages.emit(action.message)
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
                    // __no_personalized_learning），同一密码框内切方案后必须按当前输入框重设
                    editorInfo?.let {
                        setRuntimeOption(NO_PERSONALIZED_LEARNING_OPTION, isNoPersonalizedLearning(it))
                    }
                }
            }

            is Action.Commit -> requestCommit(action.text)
            Action.InputCleared -> {
                invalidatePendingCandidates()
                invalidatePendingPrediction()
                if (state.predictionVisible) {
                    state.predictionVisible = false
                    messages.emit(EngineMessage.Candidates(emptyList(), 0, 0))
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

    private suspend fun handleBackspace(rawInputEmpty: Boolean) {
        if (!rawInputEmpty) {
            flowBehavior(Backspace())
            return
        }
        if (state.predictionVisible) {
            state.predictionVisible = false
            messages.emit(EngineMessage.Candidates(emptyList(), 0, 0))
            return
        }
        withContext(Dispatchers.Main.immediate) {
            val ic = inputConnection()
            if (!ic?.getSelectedText(0).isNullOrEmpty()) {
                messages.emit(EngineMessage.Commit(""))
                invalidateBeforeCursorCache()
                return@withContext
            }
            InputConnectionUtil.sendCombinationKeyEvent(ic, KEYCODE_DEL)
            invalidateBeforeCursorCache()
        }
    }

    private suspend fun handleRimeMessage(message: RimeMessage<*>) {
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
                state.suppressNextEmptyCandidates = true
                // native 刚上屏：前文缓存失效，预测读到新鲜前文
                invalidateBeforeCursorCache()
                requestPrediction(msg.text)
            }

            is EngineMessage.Candidates -> {
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
                            RimeConfig.openSchema(currentSchema.schemaId).use { config ->
                                config.getString("grammar/language")?.let {
                                    Timber.d("predictionManager load model %s.gram", it)
                                    predictionManager?.loadModels(modelDir, sharedDataDir, it)
                                }
                            }

                            if (triggerHook) {
                                processKey(KeyMapping.Key_Delete, 0U, false)
                                context?.let { it as ImeApplication }
                                    ?.notifyState(ImeApplication.AppState.Finished)
                            }
                        }
                    }

                    else -> {}
                }
            }

            else -> {}
        }
        messages.emit(msg)
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
        actions.trySend(Action.Clear(service))
    }

    private suspend fun clearInternal() {
        invalidatePendingCandidates()
        invalidatePendingPrediction()
        val clearPredictions = state.predictionVisible
        state.predictionVisible = false
        if (clearPredictions) {
            messages.emit(EngineMessage.Candidates(emptyList(), 0, 0))
        }
        sendJob {
            if (compositionCached.preedit?.isNotEmpty() == true) {
                actions.send(Action.Reset)
            } else {
                withContext(Dispatchers.Main.immediate) {
                    val ic = inputConnection()
                    if (!ic?.getTextBeforeCursor(1, 0).isNullOrEmpty()) {
                        ic.deleteSurroundingText(Int.MAX_VALUE, Int.MAX_VALUE)
                        invalidateBeforeCursorCache()
                    }
                }
            }
        }
    }

    override fun sendJob(block: suspend RimeApi.() -> Unit) {
        jobs.trySend(block)
    }

    override suspend fun <T> awaitJob(defaultValue: T, block: suspend RimeApi.() -> T): T {
        val deferred = CompletableDeferred<T>()
        val result = jobs.trySend {
            try {
                deferred.complete(block())
            } catch (_: Throwable) {
                deferred.complete(defaultValue)
            }
        }
        if (!result.isSuccess) {
            deferred.complete(defaultValue)
        }
        return withTimeoutOrNull(2000L) { deferred.await() } ?: defaultValue
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
                else EngineMessage.Candidates(filtered, msg.highlighted, msg.page)
            try {
                val ctx = context
                if (ctx == null) {
                    actions.send(Action.CandidatesReady(requestId, filteredMsg))
                    return@launch
                }

                val db = AppDatabase.getInstance(ctx)
                val rerankEnabled = CandidateManager.isRerankEnabled(ctx)
                if (rerankEnabled) {
                    // 开启重排：使用重排结果，不还原用户排序
                    // 隐私模式下不读光标前文（gramDb 为空时前文本来也不参与打分）；
                    // 非隐私模式走前文缓存，避免每次候选刷新都跨进程读一次
                    val inputContext = if (isNoPersonalizedLearning(editorInfo)) "" else
                        peekTextBeforeCursor(20)
                    val sortedList = rerankManager?.rerank(filteredMsg.list, inputContext, null)
                    actions.send(
                        Action.CandidatesReady(
                            requestId, EngineMessage.Candidates(sortedList ?: filteredMsg.list, 0, 0)
                        )
                    )
                } else {
                    // 关闭重排：还原用户拖拽保存的排序；无记录则原样展示
                    val savedIds = CandidateSortingManager(db).load(filteredMsg.list)
                    actions.send(
                        Action.CandidatesReady(
                            requestId, if (savedIds.isNullOrEmpty()) filteredMsg
                            else EngineMessage.Candidates(
                                restoreCandidateOrder(filteredMsg.list, savedIds), 0, 0
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

    /** 按保存的原始序号顺序重排候选；不在保存列表中的候选保持原有相对顺序追加到末尾。 */
    private fun restoreCandidateOrder(
        list: List<Candidate>, savedIds: List<Int>
    ): List<Candidate> {
        val byId = list.associateBy { it.index }
        val savedSet = savedIds.toSet()
        val restored = ArrayList<Candidate>(list.size)
        for (id in savedIds) {
            byId[id]?.let { restored.add(it) }
        }
        for (c in list) {
            if (c.index !in savedSet) restored.add(c)
        }
        return restored
    }

    override fun onFinishInputView() {
        inputConnection = null
        editorInfo = null
        invalidateBeforeCursorCache()
        invalidatePendingCandidates()
        invalidatePendingPrediction()
    }

    override fun onSelectionChanged() {
        // 光标移动后前文变了：缓存失效，避免 800ms 内读到旧前文
        invalidateBeforeCursorCache()
    }

    override fun onStartInputView(ic: InputConnection, info: EditorInfo) {
        inputConnection = ic
        editorInfo = info
        invalidateBeforeCursorCache()
        // 隐私：把当前输入框的学习开关同步给 librime native 层。
        // 密码框 / NO_PERSONALIZED_LEARNING 输入框内 native 用户词典不再自学习；
        // 文本密码框进出时保存/恢复中英状态（见 applyAsciiModeForField）。
        sendJob {
            setRuntimeOption(NO_PERSONALIZED_LEARNING_OPTION, isNoPersonalizedLearning(info))
            applyAsciiModeForField(info)
        }
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
        actions.trySend(Action.Predict(commit))
    }

    private fun requestPrediction(commit: String) {
        // 隐私：密码框 / 声明 IME_FLAG_NO_PERSONALIZED_LEARNING 的输入框不读前文、不跑预测
        if (isNoPersonalizedLearning(editorInfo)) return
        // 预测模型基于简体训练；先转成简体再推导，以支持繁体输入下的候选预测。
        // 上屏刚发生时缓存已失效，这里读到的是包含本次上屏内容的新鲜前文。
        val inputContext = TraditionalConverter.toSimplified(
            peekTextBeforeCursor(20) + commit
        )
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
                    candidates = predictionManager?.makePredictions(inputContext) ?: emptyList()
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
        actions.trySend(Action.Reload)
    }

    //前端提交
    override fun commit(text: String) {
        actions.trySend(Action.Commit(text))
    }

    private fun requestCommit(text: String) {
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
        actions.trySend(Action.InputCleared)
    }
}
