package com.ninthsoft.ime.engine

import android.content.Context
import android.content.SharedPreferences
import android.inputmethodservice.InputMethodService
import android.view.KeyEvent.*
import android.view.inputmethod.InputConnection
import androidx.core.content.edit
import com.ninthsoft.ime.ImeApplication
import com.ninthsoft.ime.base.util.InputConnectionUtil
import com.ninthsoft.ime.base.util.PinYinUtil
import com.ninthsoft.ime.base.util.TextUtil
import com.ninthsoft.ime.engine.behavior.IBehavior
import com.ninthsoft.ime.engine.rime.behavior.Segmentation
import com.ninthsoft.ime.data.database.AppDatabase
import com.ninthsoft.ime.data.manager.CandidateManager
import com.ninthsoft.ime.data.manager.CandidateSortingManager
import com.ninthsoft.ime.data.manager.SchemaManager
import com.ninthsoft.ime.engine.event.KeyEvent
import com.ninthsoft.ime.engine.rime.host.BehaviorHost
import com.ninthsoft.ime.engine.data.CandidatePinYin
import com.ninthsoft.ime.engine.data.EngineMessage
import com.ninthsoft.ime.engine.data.EngineMessage.Candidate
import com.ninthsoft.ime.engine.rime.behavior.Backspace
import com.ninthsoft.ime.engine.rime.behavior.InputKey
import com.ninthsoft.ime.engine.rime.behavior.InputString
import com.ninthsoft.ime.engine.rime.behavior.Reset
import com.ninthsoft.ime.engine.rime.behavior.SelectPinYin
import com.ninthsoft.ime.engine.rime.behavior.Selection
import com.ninthsoft.ime.engine.rime.core.IRimeJob
import com.ninthsoft.ime.engine.rime.core.RimeApi
import com.ninthsoft.ime.engine.rime.daemon.RimeDaemon
import com.ninthsoft.ime.engine.rime.daemon.RimeSession
import com.ninthsoft.ime.engine.manager.CandidateRerankManager
import com.ninthsoft.ime.engine.manager.PredictionManager
import com.ninthsoft.ime.engine.rime.core.KeyMapping
import com.ninthsoft.ime.engine.rime.core.Rime.Companion.getCurrentSchema
import com.ninthsoft.ime.engine.rime.core.EngineMessageConverter
import com.ninthsoft.ime.engine.rime.core.RimeConfig
import com.ninthsoft.ime.engine.rime.core.RimeMessage
import com.ninthsoft.ime.engine.rime.core.RimeSchema
import com.ninthsoft.ime.data.App.modelDir
import com.ninthsoft.ime.engine.rime.data.DataManager.sharedDataDir
import com.ninthsoft.ime.base.util.TraditionalConverter
import com.ninthsoft.ime.engine.rime.util.OptionsApplier
import com.ninthsoft.ime.input.ImeInputMethodService
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
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import kotlin.lazy

class RimeEngine : IEngine, IBehaviorHost, IRimeJob {
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
        data class RimeMessage(val message: com.ninthsoft.ime.engine.rime.core.RimeMessage<*>) :
            Action

        data class Behavior(val behavior: IBehavior) : Action
        data class SelectCandidate(val candidate: Candidate) : Action
        data class Clear(val service: InputMethodService) : Action
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
    private var serviceRef: ImeInputMethodService? = null

    //引擎相关配置监控
    private var prefs: SharedPreferences? = null
    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (OptionsApplier.isOptionDependency(key)) {
            sendJob { RimeSchema(getCurrentSchema()).applyOptions(this) }
        }
    }

    /** 桥接模式下返回虚拟连接，否则返回真实连接。 */
    private fun inputConnection(): InputConnection? =
        serviceRef?.activeInputConnection() ?: inputConnection

    private val rerankManager by lazy { context?.let { CandidateRerankManager(it) } }
    private val predictionManager by lazy { context?.let { PredictionManager(it) } }
    private val state = EngineState()
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
        if (candidate.type == Candidate.TYPE_IME_PREDICTION) {
            messages.emit(EngineMessage.Commit(candidate.text))
            requestPrediction(candidate.text)
            return
        }

        // Only Rime selection produces the empty candidate response that must be hidden.
        // Prediction candidates bypass Rime and must not arm this flag.
        state.suppressNextEmptyCandidates = true
        sendJob {
            val ctx = context ?: return@sendJob
            val inputContext = (inputConnection()?.getTextBeforeCursor(20, 0)?.toString() ?: "")
            AppDatabase.getInstance(ctx).candidatePreferDao().upsert(candidate.text, inputContext)
        }
        flowBehavior(Selection(candidate.index))
    }

    override fun resetComposition() {
        actions.trySend(Action.Reset)
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
        InputConnectionUtil.sendCombinationKeyEvent(inputConnection(), KEYCODE_Z, ctrl = true)
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
                serviceRef = action.service as? ImeInputMethodService
                processKeyInternal(action.key)
            }

            is Action.Backspace -> handleBackspace(action.rawInputEmpty)

            is Action.RimeMessage -> handleRimeMessage(action.message)
            is Action.Behavior -> flowBehavior(action.behavior)
            is Action.SelectCandidate -> selectCandidateInternal(action.candidate)
            is Action.Clear -> {
                serviceRef = action.service as? ImeInputMethodService
                clearInternal()
            }

            is Action.Predict -> requestPrediction(action.commit)
            is Action.PredictionReady -> {
                if (action.requestId == state.latestPredictionRequestId) {
                    state.predictionVisible = action.candidates.isNotEmpty()
                    messages.emit(EngineMessage.Candidates(action.candidates, 0, 0))
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
                flowBehavior(Reset())
            }

            is Action.SelectCandidatePinYin -> flowBehavior(SelectPinYin(action.pinYin))
            Action.Segment -> flowBehavior(Segmentation())
            is Action.SelectSchema -> {
                flowBehavior(Reset())
                sendJob { selectSchema(action.schemaId) }
            }

            is Action.Commit -> requestCommit(action.text)
            Action.InputCleared -> {
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
                return@withContext
            }
            if (!ic?.getTextBeforeCursor(1, 0).isNullOrEmpty()) {
                ic.deleteSurroundingText(1, 0)
            }
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
                state.suppressNextEmptyCandidates = true
                requestPrediction(msg.text)
            }

            is EngineMessage.Candidates -> {
                if (msg.list.isNotEmpty()) {
                    // Rime candidates take over the panel from prediction candidates.
                    state.predictionVisible = false
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

    override fun schemasList(): List<EngineMessage.Schema> = runBlocking {
        awaitJob(emptyList()) {
            enabledSchemata().map {
                EngineMessage.Schema(
                    it.id, it.name, it.layout, it.punctuation, it.kind
                )
            }
        }
    }


    override fun clear(service: InputMethodService) {
        actions.trySend(Action.Clear(service))
    }

    private suspend fun clearInternal() {
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
                    inputConnection()?.deleteSurroundingText(Int.MAX_VALUE, Int.MAX_VALUE)
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
            try {
                val ctx = context
                if (ctx == null) {
                    actions.send(Action.CandidatesReady(requestId, msg))
                    return@launch
                }

                val db = AppDatabase.getInstance(ctx)
                val rerankEnabled = CandidateManager.isRerankEnabled(ctx)
                if (rerankEnabled) {
                    // 开启重排：使用重排结果，不还原用户排序
                    val inputContext =
                        (inputConnection()?.getTextBeforeCursor(20, 0)?.toString() ?: "")
                    val sortedList = rerankManager?.rerank(msg.list, inputContext, null)
                    actions.send(
                        Action.CandidatesReady(
                            requestId, EngineMessage.Candidates(sortedList ?: msg.list, 0, 0)
                        )
                    )
                } else {
                    // 关闭重排：还原用户拖拽保存的排序；无记录则原样展示
                    val savedIds = CandidateSortingManager(db).load(msg.list)
                    actions.send(
                        Action.CandidatesReady(
                            requestId, if (savedIds.isNullOrEmpty()) msg
                            else EngineMessage.Candidates(
                                restoreCandidateOrder(msg.list, savedIds), 0, 0
                            )
                        )
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Timber.e(error, "Failed to restore candidates; using original list")
                actions.send(Action.CandidatesReady(requestId, msg))
            }
        }
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
    }

    override fun onStartInputView(ic: InputConnection) {
        inputConnection = ic
    }

    override fun predict(commit: String) {
        actions.trySend(Action.Predict(commit))
    }

    private fun requestPrediction(commit: String) {
        // 预测模型基于简体训练；先转成简体再推导，以支持繁体输入下的候选预测。
        val inputContext = TraditionalConverter.toSimplified(
            (inputConnection()?.getTextBeforeCursor(20, 0)?.toString() ?: "") + commit
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
