// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.engine.rime.core

import com.jobeen.ime.engine.event.KeyModifiers
import com.jobeen.ime.engine.rime.data.DataManager
import com.jobeen.ime.engine.rime.data.opencc.OpenCCDictManager
import com.jobeen.ime.engine.rime.data.userdict.UserDictManager
import com.jobeen.ime.base.util.appContext
import com.jobeen.ime.base.util.isStorageAvailable
import com.jobeen.ime.engine.data.CommandSymbol
import com.jobeen.ime.engine.data.EngineMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import timber.log.Timber

class Rime : RimeApi, RimeLifecycleOwner {
    private val lifecycleRegistry = RimeLifecycleRegistry()

    override val lifecycle get() = lifecycleRegistry

    override val messageFlow = messageFlow_.asSharedFlow()

    override val isReady: Boolean
        get() = lifecycle.currentState == RimeLifecycle.State.READY

    @Volatile
    override var schemaCached = RimeSchema(".default")
        private set

    @Volatile
    override var statusCached = StatusProto()
        private set

    @Volatile
    override var compositionCached = CompositionProto()
        private set

    @Volatile
    override var hasMenu: Boolean = false
        private set

    @Volatile
    override var paging: Boolean = false
        private set

    /** STARTING 阶段收到 finalize 时的延迟执行：独立作用域 + 防重复排队标记 */
    private val deferredFinalizeScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val deferredFinalizeScheduled = AtomicBoolean(false)

    /** 每次 startup() 递增：延迟 finalize 执行前比对，防止误停「排队之后又被重启好」的新一代引擎 */
    private val startupGeneration = java.util.concurrent.atomic.AtomicInteger(0)

    private val dispatcher = RimeDispatcher(
        object : RimeDispatcher.RimeController {
            override fun nativeStartup() {
                startRime(false)

                lifecycleRegistry.emitState(RimeLifecycle.State.READY)
            }

            override fun nativeFinalize() {
                shutdown()
            }
        },
        onStartupFailed = {
            // 启动失败：生命周期打回 STOPPED，让 whenReady 的等待方立即以
            // RimeStoppedException 失败（而不是在 STARTING 永久挂起），
            // 且后续 startup() 可以重试
            lifecycleRegistry.emitState(RimeLifecycle.State.STOPPED)
        },
    )

    init {
        if (lifecycle.currentState != RimeLifecycle.State.STOPPED) {
            throw IllegalStateException("Rime has already been created!")
        }
    }

    private suspend inline fun <T> withRimeContext(crossinline block: suspend () -> T): T =
        withContext(dispatcher) { block() }

    override suspend fun isEmpty(): Boolean = withRimeContext {
        getCurrentSchema() == ".default"
    }

    override suspend fun deploy() = withRimeContext {
        shutdown()
        try {
            startRime(true)
        } catch (t: Throwable) {
            // native 已 shutdown 但重启失败：状态不能停留在 READY，否则上层
            // 以为引擎可用、实际每次调用都打到已关闭的 native。打回 STOPPED
            // 让状态与实际一致，并允许后续 startup() 重试。
            lifecycleRegistry.emitState(RimeLifecycle.State.STOPPED)
            throw t
        }
    }

    override suspend fun updateConfig() = withRimeContext {
        shutdown()
        try {
            startRime(false)
        } catch (t: Throwable) {
            lifecycleRegistry.emitState(RimeLifecycle.State.STOPPED)
            throw t
        }
    }

    override suspend fun joinMaintenanceThread() {
        withContext(Dispatchers.IO) {
            Companion.joinMaintenanceThread()
        }
    }

    override suspend fun syncUserData(): Boolean = withRimeContext {
        Companion.syncUserData()
    }

    override suspend fun getUserDictList(): List<String> = withRimeContext {
        UserDictManager.getUserDictList().toList()
    }

    override suspend fun importUserDictLive(dictName: String, textFile: String): Int =
        withRimeContext { UserDictManager.importUserDictLive(dictName, textFile) }

    override suspend fun exportUserDictLive(dictName: String, textFile: String): Int =
        withRimeContext { UserDictManager.exportUserDictLive(dictName, textFile) }

    override suspend fun processKey(value: Int, modifiers: UInt, isVirtual: Boolean): Boolean =
        withRimeContext { processKeyInner(value, modifiers.toInt(), isVirtual) }

    override suspend fun processKey(
        value: KeyValue,
        modifiers: KeyModifiers,
        isVirtual: Boolean,
    ): Boolean = withRimeContext { processKeyInner(value.value, modifiers.toInt(), isVirtual) }

    override suspend fun simulateKeySequence(sequence: String): Boolean = withRimeContext {
        if (Companion.simulateKeySequence(sequence)) {
            val commit = getCommit()
            val input = Companion.getRawInput()
            if (!commit.text.isNullOrEmpty() || input.isNotEmpty()) {
                emitResponse { commit }
                true
            } else {
                emitResponse { CommitProto(sequence) }
                false
            }
        } else {
            false
        }
    }

    override suspend fun selectCandidate(idx: Int, global: Boolean): Boolean = withRimeContext {
        Companion.selectCandidate(idx, global).also { emitResponse() }
    }

    override suspend fun deleteCandidate(idx: Int, global: Boolean): Boolean = withRimeContext {
        Companion.deleteCandidate(idx, global).also { emitResponse() }
    }

    override suspend fun changeCandidatePage(backward: Boolean): Boolean = withRimeContext {
        Companion.changeCandidatePage(backward).also { emitResponse() }
    }

    override suspend fun moveCursorPos(position: Int) = withRimeContext {
        setCaretPos(position)
        emitResponse()
    }

    override suspend fun setInput(input: String, emit: Boolean) = withRimeContext {
        Companion.setInput(input).also {
            if (emit) {
                emitResponse()
            }
        }
    }

    override suspend fun appendInput(input: String, emit: Boolean) = withRimeContext {
        Companion.appendInput(input).also {
            if (emit) {
                emitResponse()
            }
        }
    }

    override suspend fun availableSchemata(): Array<SchemaItem> =
        withRimeContext { getAvailableSchemaList() }

    override suspend fun enabledSchemata(): Array<SchemaItem> =
        withRimeContext { getSelectedSchemaList() }

    override suspend fun setEnabledSchemata(schemaIds: Array<String>) =
        withRimeContext { selectSchemas(schemaIds) }

    override suspend fun selectedSchemata(): Array<SchemaItem> = withRimeContext { getSchemaList() }

    override suspend fun selectedSchemaId(): String = withRimeContext { getCurrentSchema() }

    override suspend fun selectSchema(schemaId: String) = withRimeContext {
        Companion.selectSchema(schemaId).also {
            RimeSchema(getCurrentSchema()).applyOptions(this@Rime)
        }
    }

    override suspend fun currentSchema(): RimeSchema = withRimeContext {
        RimeSchema(getCurrentSchema())
    }

    override suspend fun commitComposition(): Boolean =
        withRimeContext { Companion.commitComposition().also { if (it) emitResponse() } }

    override suspend fun commitCurrentSelection(append: String): Boolean =
        withRimeContext { Companion.commitCurrentSelection(append).also { if (it) emitResponse() } }

    override suspend fun clearComposition() = withRimeContext {
        Companion.clearComposition().also { emitResponse() }
    }

    override suspend fun freeContext() = withRimeContext {
        Companion.freeContext().also { emitResponse() }
    }

    override suspend fun getRawInput(): String = withRimeContext { Companion.getRawInput() }

    override suspend fun getInputConfirmedPosition(): Int =
        withRimeContext { Companion.getInputConfirmedPosition() }

    override suspend fun setRuntimeOption(option: String, value: Boolean) = withRimeContext {
        setOption(option, value)
    }

    override suspend fun getRuntimeOption(option: String): Boolean = withRimeContext {
        getOption(option)
    }

    override suspend fun getCandidates(startIndex: Int, limit: Int): Array<CandidateProto> =
        withRimeContext { Companion.getCandidates(startIndex, limit) }

    private fun startRime(fullCheck: Boolean) {
        DataManager.sync()
        val sharedDataDir = DataManager.sharedDataDir.absolutePath
        val userDataDir = DataManager.userDataDir.absolutePath
        Timber.d("Starting rime: shared=$sharedDataDir user=$userDataDir fullCheck=$fullCheck")

        val info = appContext.packageManager.getPackageInfo(appContext.packageName, 0)
        val versionName = info.versionName ?: "1.0"

        bootstrap(sharedDataDir, userDataDir, versionName, fullCheck)
    }

    private fun processKeyInner(value: Int, modifiers: Int, isVirtual: Boolean): Boolean {
        val handled = processKey(value, modifiers)
        emitResponse()
        if (!handled) {
            handleMessage(RimeMessage.MessageType.Key.ordinal, arrayOf(value, modifiers, isVirtual))
        }
        return handled
    }

    private fun emitResponse(commit: () -> CommitProto = { getCommit() }) {
        val c = commit()
        if (c.text?.isNotEmpty() == true) {
            handleMessage(RimeMessage.MessageType.Commit.ordinal, arrayOf(c))
        }
        getStatus()
        val context = getContext()
        handleMessage(
            RimeMessage.MessageType.Status.ordinal, arrayOf(
                StatusProto(
                    isComposing = !context.input.isEmpty()
                )
            )
        )
        //候选词列表
        if (context.menu.pageSize <= 0 && context.input.isNotEmpty() && !context.input.startsWith(
                CommandSymbol
            )
        ) {
            var commitText = ""
            var confirmedProto: SyllableProto? = null
            context.composition.syllables.forEachIndexed { index, proto ->
                if (confirmedProto != null) {
                    val cp = confirmedProto
                    if (cp.textSyllableStart >= 0 && cp.textSyllableEnd >= 0 && cp.textSyllableStart <= index && index <= cp.textSyllableEnd) {
                        return@forEachIndexed
                    }
                }
                if (proto.text.isNotEmpty()) {
                    confirmedProto = proto
                    commitText += proto.text
                    return@forEachIndexed
                }
                commitText += proto.rawInput
            }
            Companion.clearComposition()
            handleMessage(
                RimeMessage.MessageType.Commit.ordinal, arrayOf(CommitProto(commitText))
            )
            handleComposition(CompositionProto())
            handleMessage(RimeMessage.MessageType.Candidate.ordinal, getBulkCandidates())
            return
        }
        handleComposition(context.composition)
        handleMessage(RimeMessage.MessageType.Candidate.ordinal, getBulkCandidates())
    }

    private fun handleComposition(composition: CompositionProto) {
        handleMessage(
            RimeMessage.MessageType.InlinePreedit.ordinal, arrayOf(composition.preedit ?: "")
        )
        handleMessage(
            RimeMessage.MessageType.DynamicPreedit.ordinal, arrayOf(composition)
        )
        handleMessage(RimeMessage.MessageType.Composition.ordinal, arrayOf(composition))
    }

    /**
     * 通知侧 JNI 读的口径例外（架构项②的串行不变量之外）：本函数在通知分发
     * 线程（messageEmitScope，单线程 FIFO）上同步做 getStatus()/RimeSchema 构造
     * 等**只读快照**查询，以保证缓存与通知严格同序。这里绝不允许出现 native
     * 写调用——任何写操作必须走 withRimeContext 回到引擎 dispatcher 串行执行。
     */
    @Suppress("UNUSED_PARAMETER")
    private fun handleRimeMessage(it: RimeMessage<*>) {
        when (it) {
            is RimeMessage.SchemaMessage -> {
                statusCached = getStatus()
                schemaCached = RimeSchema(it.data.id)
                EngineMessageConverter.applySchemaKind(it.data.kind)
            }

            is RimeMessage.OptionMessage -> {
                statusCached = getStatus()
                updateSchemaCached(statusCached)
            }

            is RimeMessage.DeployMessage -> {
                if (it.data == RimeMessage.DeployMessage.State.Start) {
                    OpenCCDictManager.buildOpenCCDict()
                }
            }

            is RimeMessage.CompositionMessage -> {
                compositionCached = it.data
            }

            is RimeMessage.CandidateMenuMessage -> {
                paging = it.data.pageNumber != 0
                hasMenu = it.data.candidates.isNotEmpty()
            }

            is RimeMessage.CandidateListMessage -> {
                hasMenu = it.data.candidates.isNotEmpty()
            }

            is RimeMessage.StatusMessage -> {
                statusCached = it.data
                updateSchemaCached(it.data)
            }

            else -> {}
        }
    }

    private fun updateSchemaCached(status: StatusProto) {
        val schemaId = status.schemaId
        if (schemaId.isNotBlank() && schemaId != schemaCached.schemaId) {
            // 状态消息中的 schemaId 即为权威当前方案，同步重建缓存即可，
            // 与 SchemaMessage 分支保持一致，避免异步 currentSchema() 的竞态/乱序。
            schemaCached = RimeSchema(schemaId)
        }
    }

    fun startup() {
        if (!appContext.isStorageAvailable()) {
            Timber.w("Skip starting rime: storage not available!")
            return
        }
        if (lifecycle.currentState != RimeLifecycle.State.STOPPED) {
            Timber.w("Skip starting rime: not at stopped state!")
            return
        }
        registerMessageHandler(::handleRimeMessage)
        startupGeneration.incrementAndGet()
        lifecycleRegistry.emitState(RimeLifecycle.State.STARTING)
        dispatcher.start()
    }

    fun finalize() {
        when (lifecycle.currentState) {
            RimeLifecycle.State.READY -> {
                lifecycleRegistry.emitState(RimeLifecycle.State.STOPPING)
                Timber.i("Rime finalize()")
                dispatcher.stop().let {
                    if (it.isNotEmpty()) {
                        Timber.w("${it.size} job(s) didn't get a chance to run!")
                    }
                }
                lifecycleRegistry.emitState(RimeLifecycle.State.STOPPED)
                unregisterMessageHandler(::handleRimeMessage)
            }
            RimeLifecycle.State.STARTING -> {
                // 启动尚未完成时收到的停止请求不能丢弃：否则会话已全部销毁、
                // 引擎却继续跑到 READY 无人持有（泄漏）。排队到就绪后立即执行。
                if (deferredFinalizeScheduled.compareAndSet(false, true)) {
                    Timber.i("Rime finalize() deferred until startup completes")
                    val scheduledGeneration = startupGeneration.get()
                    deferredFinalizeScope.launch {
                        try {
                            lifecycle.whenReady { }
                            // 排队期间引擎若已被「停掉又重启」成新一代，本次停止
                            // 意图只针对旧一代，不能误停刚重启好的引擎
                            if (startupGeneration.get() != scheduledGeneration) {
                                Timber.i("Deferred finalize superseded by a newer startup; skipped")
                                return@launch
                            }
                            finalize()
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            // 引擎在就绪前已被其它路径停掉，停止请求已达成，无需再做
                            Timber.w(e, "Deferred finalize aborted")
                        } finally {
                            deferredFinalizeScheduled.set(false)
                        }
                    }
                }
            }
            // STOPPING 时已有 finalize 在执行，本次请求由它覆盖；STOPPED 时无事可做
            else -> Timber.w("Skip stopping rime: not at ready state!")
        }
    }

    companion object {
        // 消息流不丢消息：这里走的是 Schema/Option/Deploy 等状态消息，
        // 丢一条 Deploy 成功或 Schema 变更，awaitMessage 的等待方会永久挂起、
        // 守护进程的部署通知也会缺失。旧配置 DROP_OLDEST 在收集方卡顿时
        // 直接把最旧的未消费消息扔掉。用 SUSPEND 语义 + 溢出时异步补发，
        // 保证至少送达一次（补发走单线程调度，保持溢出消息之间的先后顺序）。
        private val messageFlow_ = MutableSharedFlow<RimeMessage<*>>(
            extraBufferCapacity = 64,
            onBufferOverflow = BufferOverflow.SUSPEND,
        )

        private val messageEmitScope = kotlinx.coroutines.CoroutineScope(
            kotlinx.coroutines.SupervisorJob() +
                Dispatchers.IO.limitedParallelism(1),
        )

        private val rimeMessageHandlers = CopyOnWriteArrayList<(RimeMessage<*>) -> Unit>()

        /**
         * librime 通知（schema/option/deploy）的待处理队列。通知回调发生在
         * librime 的调用栈里（维护线程或 API 调用途中），旧实现同步在回调里
         * 做消息转换与分发，而转换（getSchemaList）和处理器（getStatus）
         * 又会回调 JNI 取状态——在 librime 操作中途重入其 API。改为回调
         * 只入队，由 [messageEmitScope] 的单线程消费者按 FIFO 顺序在
         * native 调用栈之外完成转换与分发。
         */
        private val notificationQueue =
            kotlinx.coroutines.channels.Channel<Pair<Int, Array<Any>>>(
                kotlinx.coroutines.channels.Channel.UNLIMITED,
            )

        init {
            System.loadLibrary("rime_jni")
        }

        init {
            messageEmitScope.launch {
                for ((type, params) in notificationQueue) {
                    runCatching { dispatchMessage(type, params) }
                        .onFailure { Timber.w(it, "Failed to dispatch rime notification") }
                }
            }
        }

        @JvmStatic
        external fun bootstrap(
            sharedDir: String, userDir: String, versionName: String, fullCheck: Boolean
        )

        @JvmStatic
        external fun shutdown()

        @JvmStatic
        external fun joinMaintenanceThread()

        @JvmStatic
        external fun deploySchemaFile(schemaFile: String): Boolean

        @JvmStatic
        external fun deployConfigFile(fileName: String, versionKey: String): Boolean

        @JvmStatic
        external fun syncUserData(): Boolean

        @JvmStatic
        external fun processKey(keycode: Int, mask: Int): Boolean

        @JvmStatic
        external fun commitComposition(): Boolean

        @JvmStatic
        external fun commitCurrentSelection(append: String): Boolean

        @JvmStatic
        external fun clearComposition()

        @JvmStatic
        external fun freeContext()

        @JvmStatic
        external fun getCommit(): CommitProto

        @JvmStatic
        external fun getContext(): ContextProto

        @JvmStatic
        external fun getStatus(): StatusProto

        @JvmStatic
        external fun setOption(option: String, value: Boolean)

        @JvmStatic
        external fun getOption(option: String): Boolean

        @JvmStatic
        external fun getSchemaList(): Array<SchemaItem>

        @JvmStatic
        external fun getCurrentSchema(): String

        @JvmStatic
        external fun selectSchema(schemaId: String): Boolean

        @JvmStatic
        external fun simulateKeySequence(keySequence: String): Boolean

        @JvmStatic
        external fun getRawInput(): String

        @JvmStatic
        external fun setInput(keySequence: String): Boolean

        @JvmStatic
        external fun appendInput(keySequence: String): Boolean

        @JvmStatic
        external fun getCaretPos(): Int

        @JvmStatic
        external fun setCaretPos(caretPos: Int)

        @JvmStatic
        external fun selectCandidate(index: Int, global: Boolean): Boolean

        @JvmStatic
        external fun deleteCandidate(index: Int, global: Boolean): Boolean

        @JvmStatic
        external fun changeCandidatePage(backward: Boolean): Boolean

        @JvmStatic
        external fun getAvailableSchemaList(): Array<SchemaItem>

        @JvmStatic
        external fun getSelectedSchemaList(): Array<SchemaItem>

        @JvmStatic
        external fun selectSchemas(schemaIds: Array<String>): Boolean

        @JvmStatic
        external fun getCandidates(startIndex: Int, limit: Int): Array<CandidateProto>

        @JvmStatic
        external fun getBulkCandidates(): Array<Any>

        @JvmStatic
        external fun getInputConfirmedPosition(): Int

        /**
         * librime 通知回调的 JNI 入口：只把原始消息入队后立即返回，
         * 不在 native 调用栈里做任何转换/分发（见 [notificationQueue]）。
         */
        @JvmStatic
        fun handleNativeNotification(type: Int, params: Array<Any>) {
            notificationQueue.trySend(type to params)
        }

        @JvmStatic
        fun handleMessage(type: Int, params: Array<Any>) = dispatchMessage(type, params)

        private fun dispatchMessage(type: Int, params: Array<Any>) {
            val message = RimeMessage.nativeCreate(type, params)
            rimeMessageHandlers.forEach { it.invoke(message) }
            if (!messageFlow_.tryEmit(message)) {
                // 缓冲已满（收集方一时卡住）：异步补发到送达为止，不丢弃
                messageEmitScope.launch { messageFlow_.emit(message) }
            }
        }

        private fun registerMessageHandler(handler: (RimeMessage<*>) -> Unit) {
            if (handler !in rimeMessageHandlers) {
                rimeMessageHandlers.add(handler)
            }
        }

        private fun unregisterMessageHandler(handler: (RimeMessage<*>) -> Unit) {
            rimeMessageHandlers.remove(handler)
        }
    }
}
