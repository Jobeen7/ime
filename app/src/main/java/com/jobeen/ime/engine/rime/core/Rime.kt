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
        // 登记活跃实例，供 companion 的 runOnRimeMain 派发定位（本类在进程内
        // 由 RimeDaemon 单例式持有一个实例）
        activeInstance = this
    }

    private suspend inline fun <T> withRimeContext(crossinline block: suspend () -> T): T =
        withContext(dispatcher) { block() }

    /**
     * 消息消费者的补取专用通道：带 2 秒上限（与 dispatcher stop 的排空
     * 上限对齐）。引擎正在停机时补取任务可能被排空丢弃，等待方若无限
     * 挂起会把消息消费者——进而整个消息通道——永久堵死；超时即放弃
     * 补取、按原消息继续（各调用点本就容忍补取失败）。
     */
    private suspend fun <T> snapshotOnRimeMain(block: suspend () -> T): T? =
        runCatching {
            kotlinx.coroutines.withTimeoutOrNull(SNAPSHOT_TIMEOUT_MS) {
                withRimeContext { block() }
            }
        }.onFailure { e ->
            // 取消不能吞：调用方协程被取消时必须立即中止（超时返回
            // null 是 withTimeoutOrNull 的内部语义、不经这里）；吞掉
            // 取消会让消息消费者在作用域已取消后继续跑完整条处理
            if (e is kotlinx.coroutines.CancellationException) throw e
            Timber.w(e, "Failed to refresh snapshot on rime-main")
        }.getOrNull()

    /**
     * 把 [block] 派发到 rime-main 执行并等待结果：[runOnRimeMain] 的实例侧
     * 入口。RimeApi 各 suspend 方法本身就走这条串行线，但接口之外的 native
     * 入口（如 RimeConfig.openSchema）没有现成通道，补此一处统一派发。
     */
    suspend fun <T> dispatchToRimeMain(block: suspend RimeApi.() -> T): T =
        withRimeContext { block(this@Rime) }

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
            val schema = RimeSchema(getCurrentSchema())
            // 切方案的同步点回写缓存：此前 schemaCached 只靠 Schema 通知在
            // 通知分发线程异步回写，其间的 sendJob 读 candidateKind 会读到
            // 旧方案的值（通知路径本身随后仍会以同一 id 再刷新一次）
            schemaCached = schema
            schema.applyOptions(this@Rime)
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
        // OpenCC 文本词典先同步转成 .ocd2 再 bootstrap：异步转换与
        // 部署并发时 librime 会先加载旧 .ocd2，新词典要到下次部署
        // 才生效（方案更新后繁简转换沿用旧词典一整代）
        OpenCCDictManager.buildOpenCCDict()
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
        // 不再单独调 getStatus()：其结果无人消费，isComposing 由 context.input 推导
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
            handleMessage(
                RimeMessage.MessageType.Candidate.ordinal,
                getBulkCandidates(context.menu.highlightedCandidateIndex)
            )
            return
        }
        handleComposition(context.composition)
        handleMessage(
            RimeMessage.MessageType.Candidate.ordinal,
            getBulkCandidates(context.menu.highlightedCandidateIndex)
        )
    }

    private fun handleComposition(composition: CompositionProto) {
        // 组字缓存首行同步赋值：emitResponse 的正常分支与「候选页为空→
        // 合成空组字」特殊分支都经此处，赋值对象即本轮真正分发的组字，
        // 与组字产生同步完成。此前靠 CompositionMessage 在通知分发线程
        // 异步回写，其间读 compositionCached 的 sendJob 会读到上一轮组字。
        compositionCached = composition
        handleMessage(
            RimeMessage.MessageType.InlinePreedit.ordinal, arrayOf(composition.preedit ?: "")
        )
        handleMessage(
            RimeMessage.MessageType.DynamicPreedit.ordinal, arrayOf(composition)
        )
        handleMessage(RimeMessage.MessageType.Composition.ordinal, arrayOf(composition))
    }

    /**
     * 消息处理点：由 [dispatchMessage] 在通知分发队列上串行调用，各缓存都在
     * 本处理点同步赋值（补取 await 完成后才继续分发给 UI），不另起异步任务
     * 回写——否则处理点之后触发的 sendJob 可能读到上一轮缓存（组字缓存同
     * 口径，见 [handleComposition]）。其中一切 librime native 读取
     * （getStatus / RimeSchema 构造 / 方案列表查询）都不在分发线程直调，
     * 一律经 [withRimeContext] 回到 rime-main 补取，使全部 native 调用串行于
     * rime-main 一条线，关闭其与 finalize 的 UAF 窗口。补取失败（引擎停止
     * 中等）保留旧缓存并原样分发，不丢消息。
     *
     * @return 实际分发的消息：Schema 消息在补到完整方案条目时以其替换原始
     * 消息（原始串只有 id/name），其余消息原样返回。
     */
    private suspend fun handleRimeMessage(it: RimeMessage<*>): RimeMessage<*> {
        when (it) {
            is RimeMessage.SchemaMessage -> {
                val snapshot = snapshotOnRimeMain {
                    val status = getStatus()
                    val schema = RimeSchema(it.data.id)
                    val item = cachedSchemaList().firstOrNull { s -> s.id == it.data.id }
                    Triple(status, schema, item)
                }
                if (snapshot != null) {
                    statusCached = snapshot.first
                    schemaCached = snapshot.second
                    val item = snapshot.third
                    EngineMessageConverter.applySchemaKind(item?.kind ?: it.data.kind)
                    if (item != null) return RimeMessage.SchemaMessage(item)
                }
                return it
            }

            is RimeMessage.OptionMessage -> {
                snapshotOnRimeMain {
                    val status = getStatus()
                    statusCached = status
                    updateSchemaCached(status)
                }
                return it
            }

            is RimeMessage.DeployMessage -> {
                // OpenCC 词典构建已前移到 startRime 的 bootstrap 之前
                // 同步执行（见彼处），这里不再异步补触发——异步构建赶
                // 不上本次部署，纯属重复转换
                if (it.data == RimeMessage.DeployMessage.State.Success) {
                    invalidateSchemaListCache()
                }
                return it
            }

            // 组字缓存不在这里回写：已改为在 handleComposition 产生处同步赋值
            is RimeMessage.CompositionMessage -> return it

            is RimeMessage.CandidateMenuMessage -> {
                paging = it.data.pageNumber != 0
                hasMenu = it.data.candidates.isNotEmpty()
                return it
            }

            is RimeMessage.CandidateListMessage -> {
                hasMenu = it.data.candidates.isNotEmpty()
                return it
            }

            is RimeMessage.StatusMessage -> {
                // emitResponse 发来的 StatusMessage 是稀疏增量：只携带
                // isComposing，其余字段都是数据类默认值（schemaId=""、
                // isAsciiMode=true 等）。整表覆盖会把全量缓存冲成默认值，
                // 故以当前缓存为基底合并，只采纳增量真正携带的字段；
                // schemaId 等未出现在增量里的字段保持原值。
                statusCached = statusCached.copy(isComposing = it.data.isComposing)
                val status = statusCached
                // 稀疏增量的 schemaId 恒为空、不触发重建（每键都走此路，
                // 不能每键都往 rime-main 跑一趟）；真携带了新 id 时，
                // RimeSchema 构造走 native，同样派发到 rime-main 补取。
                if (status.schemaId.isNotBlank() && status.schemaId != schemaCached.schemaId) {
                    snapshotOnRimeMain { updateSchemaCached(status) }
                }
                return it
            }

            else -> return it
        }
    }

    /**
     * 按状态中的 schemaId 重建方案缓存（RimeSchema 构造走 native）：
     * 调用方必须已在 rime-main 上（见 [handleRimeMessage] 的补取路径）。
     */
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
        /** 消息补取的等待上限：与 RimeDispatcher.stop() 的排空上限对齐 */
        private const val SNAPSHOT_TIMEOUT_MS = 2_000L

        // 消息流不丢消息：这里走的是 Schema/Option/Deploy 等状态消息，
        // 丢一条 Deploy 成功或 Schema 变更，awaitMessage 的等待方会永久挂起、
        // 守护进程的部署通知也会缺失。旧配置 DROP_OLDEST 在收集方卡顿时
        // 直接把最旧的未消费消息扔掉。现为 SUSPEND 语义，且全部分发都经
        // notificationQueue 的单线程消费者串行完成（见 dispatchMessage），
        // 缓冲满时消费者原地挂起等位，保证送达且全局有序。
        private val messageFlow_ = MutableSharedFlow<RimeMessage<*>>(
            extraBufferCapacity = 64,
            onBufferOverflow = BufferOverflow.SUSPEND,
        )

        private val messageEmitScope = kotlinx.coroutines.CoroutineScope(
            kotlinx.coroutines.SupervisorJob() +
                Dispatchers.IO.limitedParallelism(1),
        )

        // 处理器为 suspend 且返回实际分发消息：消息处理点要在 rime-main
        // 补取快照后才继续分发（见 handleRimeMessage），分发必须 await 它
        private val rimeMessageHandlers =
            CopyOnWriteArrayList<suspend (RimeMessage<*>) -> RimeMessage<*>>()

        /** 当前活跃实例（进程内单例式持有一台 Rime），供 [runOnRimeMain] 定位派发目标 */
        @Volatile
        private var activeInstance: Rime? = null

        /**
         * 把 [block] 派发到 rime-main 执行并等待结果，供 RimeApi 接口之外
         * 的 native 入口调用方使用（典型：RimeConfig.openSchema）。调用方
         * 线程（如引擎 jobs 线程、设置页协程）直调这类入口会绕开 rime-main
         * 串行线，与 finalize 形成 UAF 窗口。没有活跃实例（引擎未创建）或
         * 引擎已停止时抛 IllegalStateException，由调用方按既有口径降级。
         */
        suspend fun <T> runOnRimeMain(block: suspend RimeApi.() -> T): T {
            val instance = activeInstance
                ?: throw IllegalStateException("Rime has not been created!")
            return instance.dispatchToRimeMain(block)
        }

        /**
         * librime 通知（schema/option/deploy）的待处理队列。通知回调发生在
         * librime 的调用栈里（维护线程或 API 调用途中），旧实现同步在回调里
         * 做消息转换与分发，而转换（getSchemaList）和处理器（getStatus）
         * 又会回调 JNI 取状态——在 librime 操作中途重入其 API。改为回调
         * 只入队，由 [messageEmitScope] 的单线程消费者按 FIFO 顺序在
         * native 调用栈之外完成转换与分发。引擎自产消息（按键响应等，
         * 见 [handleMessage]）也入同一队列，使两类消息全局有序。
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
                    try {
                        dispatchMessage(type, params)
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        Timber.w(e, "Failed to dispatch rime notification")
                    }
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

        // 方案列表缓存：Schema 通知在 rime-main 补取时只需按 id 查一条，
        // 此前每次都走 JNI 全量拉取。本函数走 native，只许在 rime-main 上
        // 调用。部署成功后方案集可能变化，届时由通知处理失效缓存
        @Volatile
        private var schemaListCache: Array<SchemaItem>? = null

        fun cachedSchemaList(): Array<SchemaItem> =
            schemaListCache ?: getSchemaList().also { schemaListCache = it }

        fun invalidateSchemaListCache() {
            schemaListCache = null
        }

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
        external fun getBulkCandidates(highlighted: Int): Array<Any>

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
        fun handleMessage(type: Int, params: Array<Any>) {
            // 引擎自产消息（按键响应等）与原生通知共用同一队列、同一单线程
            // 消费者分发，保证两类消息全局有序。旧实现由调用方线程（rime-main）
            // 同步直分发，与通知分发线程并发，顺序无法保证。
            notificationQueue.trySend(type to params)
        }

        /**
         * 只由 [notificationQueue] 的单线程消费者调用：转换、处理器回调与
         * 写入 [messageFlow_] 在此串行完成。缓冲满时在本线程内挂起补发，
         * 后续消息仍在队列中排队——不会像旧实现另起协程补发那样被后续
         * 消息超车，也不会丢弃。
         */
        private suspend fun dispatchMessage(type: Int, params: Array<Any>) {
            // nativeCreate 只做原始数据记录（不在本线程调 native）；处理器
            // 在其内部把快照派发到 rime-main 补取并 await，返回实际分发
            // 的消息后才写入消息流，保证缓存与 UI 分发严格同序
            var message = RimeMessage.nativeCreate(type, params)
            rimeMessageHandlers.forEach { handler -> message = handler.invoke(message) }
            if (!messageFlow_.tryEmit(message)) {
                messageFlow_.emit(message)
            }
        }

        private fun registerMessageHandler(
            handler: suspend (RimeMessage<*>) -> RimeMessage<*>,
        ) {
            if (handler !in rimeMessageHandlers) {
                rimeMessageHandlers.add(handler)
            }
        }

        private fun unregisterMessageHandler(
            handler: suspend (RimeMessage<*>) -> RimeMessage<*>,
        ) {
            rimeMessageHandlers.remove(handler)
        }
    }
}
