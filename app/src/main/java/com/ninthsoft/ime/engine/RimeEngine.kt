package com.ninthsoft.ime.engine

import android.content.Context
import android.content.SharedPreferences
import android.inputmethodservice.InputMethodService
import android.view.KeyEvent.*
import android.view.inputmethod.InputConnection
import androidx.core.content.edit
import com.ninthsoft.ime.ImeApplication
import com.ninthsoft.ime.base.util.InputConnectionUtil
import com.ninthsoft.ime.base.util.TextUtil
import com.ninthsoft.ime.engine.behavior.IBehavior
import com.ninthsoft.ime.engine.rime.behavior.Segmentation
import com.ninthsoft.ime.data.database.AppDatabase
import com.ninthsoft.ime.data.database.CandidateSorting
import com.ninthsoft.ime.data.manager.CandidateManager
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
import com.ninthsoft.ime.engine.rime.core.EngineMessage
import com.ninthsoft.ime.engine.rime.core.IRimeJob
import com.ninthsoft.ime.engine.rime.core.RimeApi
import com.ninthsoft.ime.engine.rime.daemon.RimeDaemon
import com.ninthsoft.ime.engine.rime.daemon.RimeSession
import com.ninthsoft.ime.engine.manager.CandidateRerankManager
import com.ninthsoft.ime.engine.manager.PredictionManager
import com.ninthsoft.ime.engine.rime.core.KeyMapping
import com.ninthsoft.ime.engine.rime.core.Rime.Companion.getCurrentSchema
import com.ninthsoft.ime.engine.rime.core.RimeConfig
import com.ninthsoft.ime.engine.rime.core.RimeMessage
import com.ninthsoft.ime.engine.rime.core.RimeSchema
import com.ninthsoft.ime.engine.rime.data.DataManager.modelDir
import com.ninthsoft.ime.engine.rime.data.DataManager.sharedDataDir
import com.ninthsoft.ime.engine.rime.util.OptionsApplier
import com.github.houbb.opencc4j.util.ZhConverterUtil
import com.ninthsoft.ime.input.ImeInputMethodService
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import kotlin.lazy

class RimeEngine : IEngine, IBehaviorHost, IRimeJob {
    private val daemon by lazy { RimeDaemon }
    private val scope by lazy { CoroutineScope(SupervisorJob() + Dispatchers.Default) }
    private val jobs by lazy { Channel<suspend RimeApi.() -> Unit>(Channel.UNLIMITED) }
    private var session: RimeSession? = null
    private var behaviorHosted: BehaviorHost? = null
    private var context: Context? = null
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
    private var showPredictionCandidates = false
    private var notEmitNextEmptyCandidates = false
    private var inited: Boolean = false
    private var triggeredInitedHook = false
    private val messages = MutableSharedFlow<EngineMessage>(
        replay = 0, extraBufferCapacity = 64
    )

    override fun initialize(context: Context) {
        val appContext = context.applicationContext
        this@RimeEngine.context = appContext

        val app = this@RimeEngine.context as ImeApplication
        app.notifyState(ImeApplication.AppState.EngineStarting)
        behaviorHosted = BehaviorHost(this)

        //observe engine Messages at first.
        scope.launch {
            daemon.observeMessages { onMessage(it) }
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
            onMessage(RimeMessage.DeployMessage(RimeMessage.DeployMessage.State.Finish))
            RimeSchema(getCurrentSchema()).applyOptions(this)
        }
    }

    override fun finalize() {
        prefs?.unregisterOnSharedPreferenceChangeListener(prefsListener)
        prefs = null
        jobs.close()
        scope.cancel()
        predictionManager?.destroy()
        daemon.destroySession(javaClass.name)
    }

    override fun processKey(service: InputMethodService, key: KeyEvent) {

        serviceRef = service as? ImeInputMethodService
        if (!this@RimeEngine.inited) {
            return
        }
        sendJob {
            when (key) {
                is KeyEvent.SequenceEvent -> {
                    this@RimeEngine.flowed(InputString(key.sequence))
                    return@sendJob
                }

                is KeyEvent.CodeEvent -> {
                    when (key.keyCode) {
                        KEYCODE_SPACE -> {
                            if (getRawInput().isEmpty()) {
                                messages.emit(EngineMessage.Commit(" "))
                                return@sendJob
                            }
                        }

                        KEYCODE_DEL -> {
                            if (showPredictionCandidates) {
                                showPredictionCandidates = false
                                messages.emit(EngineMessage.Candidates(emptyList(), 0, 0))
                                return@sendJob
                            }
                            val ic = inputConnection()
                            if (getRawInput().isEmpty()) {
                                if (!ic?.getSelectedText(0).isNullOrEmpty()) {
                                    messages.emit(EngineMessage.Commit(""))
                                    return@sendJob
                                }
                                ic?.let {
                                    if (!it.getTextBeforeCursor(1, 0).isNullOrEmpty()) {
                                        it.deleteSurroundingText(1, 0)
                                    }
                                }
                                return@sendJob
                            }
                            this@RimeEngine.flowed(Backspace())
                            return@sendJob
                        }

                        KEYCODE_APOSTROPHE -> {
                            this@RimeEngine.flowed(Segmentation())
                            return@sendJob
                        }

                        KEYCODE_ENTER -> {
                            if (getRawInput().isEmpty()) {
                                messages.emit(EngineMessage.Commit("\n"))
                                return@sendJob
                            }
                        }
                    }
                    this@RimeEngine.flowed(
                        InputKey(
                            key.keyCode, key.modifiers.toInt(), key.isVirtual
                        )
                    )
                    return@sendJob
                }
            }
        }
    }

    override fun selectCandidate(candidate: Candidate) {
        sendJob {
            if (candidate.type == Candidate.TYPE_IME_PREDICTION) {
                messages.emit(EngineMessage.Commit(candidate.text))
                this@RimeEngine.predict(candidate.text)
                return@sendJob
            }
            val ctx = context ?: return@sendJob
            val inputContext = (inputConnection()?.getTextBeforeCursor(20, 0)?.toString() ?: "")
            AppDatabase.getInstance(ctx).candidatePreferDao().upsert(candidate.text, inputContext)
        }
        notEmitNextEmptyCandidates = true
        this@RimeEngine.flowed(Selection(candidate.index))
    }

    override fun resetComposition() {
        this.flowed(Reset())
    }

    override fun selectCandidatePinYin(pinYin: CandidatePinYin) {
        this.flowed(SelectPinYin(pinYin))
    }

    override fun segement() {
        this.flowed(Segmentation())
    }

    override fun selectSchema(schemaId: String) {
        sendJob {
            this@RimeEngine.resetComposition()
            selectSchema(schemaId)
        }
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
        val db = AppDatabase.getInstance(ctx)
        sendJob {
            val preedit = getRawInput().replace("'", " ")
            if (preedit.isNotEmpty()) {
                db.candidateSortingDao()
                    .saveSorting(CandidateSorting(preedit, candidates.map { it.index }))
            }
        }
    }

    override fun deleteCandidate(index: Int) {
        sendJob { deleteCandidate(index, global = true) }
    }


    override fun flowed(behavior: IBehavior): Boolean = behaviorHosted?.flowed(behavior) == true
    override fun resetState() {
        behaviorHosted?.resetState()
    }

    private fun possibleCandidatePinYin() {
        sendJob {
            val currentInput = getRawInput()
            val confirmedLen = getInputConfirmedPosition()
            val pinYins = behaviorHosted?.possiblePinYin(currentInput, confirmedLen) ?: emptyList()
            messages.emit(EngineMessage.PossibleCandidatePinYin(pinYins))
        }
    }

    private suspend fun onMessage(message: RimeMessage<*>) {
        val msg = message.EngineMessage()
        when (msg) {
            is EngineMessage.InlinePreedit -> {
                if (msg.preedit.isEmpty()) {
                    behaviorHosted?.resetState()
                }
                possibleCandidatePinYin()
                return
            }

            is EngineMessage.Commit -> {
                notEmitNextEmptyCandidates = true
                this@RimeEngine.predict(msg.text)
            }

            is EngineMessage.Candidates -> {
                if (notEmitNextEmptyCandidates && msg.list.isEmpty()) {
                    notEmitNextEmptyCandidates = false
                    return
                }
                restoreCandidates(msg)
                return
            }

            is EngineMessage.Depoly -> {
                when (msg.state) {
                    EngineMessage.Depoly.State.Start -> inited = false
                    EngineMessage.Depoly.State.Finish -> {
                        inited = true
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

                            if (!triggeredInitedHook) {
                                processKey(KeyMapping.Key_Delete, 0U, false)
                                context?.let { it as ImeApplication }
                                    ?.notifyState(ImeApplication.AppState.Finished)
                                triggeredInitedHook = true
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
                    it.id, it.name, it.layout, it.punctuation
                )
            }
        }
    }


    override fun clear(service: InputMethodService) {
        serviceRef = service as? ImeInputMethodService
        sendJob {
            if (compositionCached.preedit?.isNotEmpty() == true) {
                this@RimeEngine.resetComposition()
            } else {
                if (showPredictionCandidates) {
                    showPredictionCandidates = false
                    messages.emit(EngineMessage.Candidates(emptyList(), 0, 0))
                }
                inputConnection()?.let {
                    val p0 = it.getTextBeforeCursor(Int.MAX_VALUE, 0)?.length ?: 0
                    val p1 = it.getTextAfterCursor(Int.MAX_VALUE, 0)?.length ?: 0
                    it.deleteSurroundingTextInCodePoints(p0, p1)
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
        sendJob {
            context?.let {
                val rerankEnabled = CandidateManager.isRerankEnabled(it)
                if (!rerankEnabled) {
                    messages.emit(msg)
                    return@sendJob
                }
                val inputContext = (inputConnection()?.getTextBeforeCursor(20, 0)?.toString() ?: "")
                val sortedList = rerankManager?.rerank(msg.list, inputContext, null)
                messages.emit(EngineMessage.Candidates(sortedList ?: msg.list, 0, 0))
            }
        }
    }

    override fun onFinishInputView() {
        inputConnection = null
    }

    override fun onStartInputView(ic: InputConnection) {
        inputConnection = ic
    }

    override fun predict(commit: String) {
        val inputContext =
            (inputConnection()?.getTextBeforeCursor(20, 0)?.toString() ?: "") + commit
        sendJob {
            if (context?.let { !CandidateManager.isPredictionEnabled(it) } == true) {
                messages.emit(EngineMessage.Candidates(emptyList(), 0, 0))
                return@sendJob
            }
            var candidates: List<Candidate> = emptyList()
            if (!inputContext.isEmpty() && !TextUtil.isSymbol(inputContext.last()) && !TextUtil.isAlphabet(
                    inputContext.last()
                )
            ) {
                candidates = predictionManager?.makePredictions(inputContext) ?: emptyList()
            }
            if (context?.let { CandidateManager.isTraditionalChineseEnabled(it) } == true) {
                candidates = candidates.map {
                    it.copy(
                        text = ZhConverterUtil.toTraditional(it.text),
                        comment = it.comment.takeIf(String::isNotEmpty)
                            ?.let(ZhConverterUtil::toTraditional) ?: it.comment,
                    )
                }
            }
            showPredictionCandidates = candidates.isNotEmpty()
            messages.emit(EngineMessage.Candidates(candidates, 0, 0))
        }
    }

    override fun reload() {
        resetState()
        sendJob {
            deploy()
            joinMaintenanceThread()
            onMessage(RimeMessage.DeployMessage(RimeMessage.DeployMessage.State.Finish))
        }
    }

    //前端提交
    override fun commit(text: String) {
        sendJob {
            messages.emit(EngineMessage.Commit(text))
            this@RimeEngine.predict(text)
        }
    }

    override fun onInputCleared() {
        if (showPredictionCandidates) {
            sendJob {
                showPredictionCandidates = false
                messages.emit(EngineMessage.Candidates(emptyList(), 0, 0))
            }
        }
    }
}