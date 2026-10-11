package com.jobeen.ime.base.speech

import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.annotation.SuppressLint
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.SystemClock
import android.widget.Toast
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import com.k2fsa.sherpa.onnx.QnnConfig
import com.jobeen.ime.data.App
import com.jobeen.ime.base.util.appContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Runs in the isolated `:speech` process. Owns sherpa-onnx recognition only:
 * audio capture lives in the main process (audio-first), PCM chunks are
 * pushed here via Messenger and decoded from a per-session queue; results
 * are streamed back to the main process via Messenger.
 */
class SpeechRecognitionService : Service() {
    private val scope = CoroutineScope(Dispatchers.IO + kotlinx.coroutines.SupervisorJob())

    private val recognizerRef = AtomicReference<OnlineRecognizer?>(null)
    private val streamRef = AtomicReference<OnlineStream?>(null)
    private val qnnRuntimeRef = AtomicReference<QnnRuntime?>(null)

    /**
     * 销毁短路标志：onDestroy 在主线程置位并封消息入口后，在飞的消息
     * 收尾协程与销毁守护线程不再并发碰同一批资源引用（见 onDestroy）
     */
    @Volatile
    private var destroying = false
    private val holding = AtomicBoolean(false)

    // 会话状态机：START 用 CAS 保证幂等；STOP 以 sessionActive/代次为准，
    // 不依赖在解码协程里迟置位的 holding（STOP 先到时旧逻辑会直接吞掉停止请求）
    private val sessionActive = AtomicBoolean(false)

    // 录音中检测到模型文件已更新：不能当场换引擎，先标记，会话结束后补重载
    @Volatile
    private var pendingModelReload = false

    @Volatile
    private var sessionGen = 0

    /** 已加载识别器对应的模型文件指纹（文件名+大小+修改时间）：在线更新替换文件后指纹变化，触发重载 */
    @Volatile
    private var loadedModelFingerprint: String? = null

    // 客户端随 START 发来的会话代次令牌：本会话全部回信原样带回，
    // 客户端据此丢弃旧会话迟到的结果（服务端内部代次 sessionGen 只管服务端自身防串扰）
    @Volatile
    private var sessionClientGen = 0
    private val audioLock = Any()
    private var audioJob: Job? = null

    /**
     * 推流会话状态（音频先行架构）：录音归客户端，本服务只解码。队列与
     * 结束/取消标记打包成一个对象随会话整体替换——旧会话的解码协程只
     * 持有自己那一份，字段被新会话覆盖不串扰它的收尾。
     */
    private class PushSession {
        val queue = ArrayBlockingQueue<ByteArray>(AUDIO_QUEUE_CAPACITY_BLOCKS)

        /** 客户端已发完最后一块（STOP）：解码协程排空队列后走收尾解码 */
        @Volatile
        var endOfStream: Boolean = false

        /** 会话被丢弃（CANCEL）：解码协程丢弃队列静默退出，不发 FINAL */
        @Volatile
        var cancelled: Boolean = false
    }

    /** 当前推流会话（无会话时为 null）；写入在 audioLock 内，读取可直接读 */
    @Volatile
    private var pushSession: PushSession? = null

    /**
     * STOP 先于会话建立（引擎初始化中）到达的标记：初始化完成后据此给
     * 新队列预置 endOfStream 走排空收尾，不让会话闩死在「等音频」上
     */
    @Volatile
    private var stopRequested = false

    /**
     * CANCEL 先于会话建立到达时记下的服务端代次：初始化协程据此静默
     * 退出（CANCEL 处理方已复位会话并发 DONE），不重复收尾
     */
    @Volatile
    private var cancelRequestedGen = 0

    private var clientMessenger: Messenger? = null

    private var lastRawText: String? = null
    private var lastEmittedText: String? = null
    private var lastEmitUptimeMs = 0L

    private data class ModelFiles(
        val tokens: File,
        val encoder: File,
        val decoder: File,
        val joiner: File,
        val isQnn: Boolean,
    )

    private data class QnnRuntime(
        val backend: File,
        val system: File,
        val target: File,
    )

    private val serviceHandler = Handler(
        Looper.getMainLooper(),
        Handler.Callback { msg ->
            when (msg.what) {
                SpeechIpc.MSG_LOAD -> scope.launch(Dispatchers.IO) {
                    initEngine(this@SpeechRecognitionService, silent = true)
                }

                SpeechIpc.MSG_PING -> {
                    // 存活探针：立即回 PONG 并原样带回探针代次，不碰引擎
                    // 与会话状态。优先回给探针自带的 replyTo（预热预检时
                    // 还没有会话，clientMessenger 尚未由 START 设置），
                    // 没有才走会话通道 sendClient
                    val pingGen = runCatching {
                        msg.data?.getInt(SpeechIpc.KEY_GEN, 0) ?: 0
                    }.getOrDefault(0)
                    val replyTo = msg.replyTo
                    if (replyTo != null) {
                        runCatching { replyTo.send(SpeechIpc.message(SpeechIpc.MSG_PONG, gen = pingGen)) }
                            .onFailure { Log.e("SpeechSvc", "send PONG failed", it) }
                    } else {
                        sendClient(SpeechIpc.MSG_PONG, gen = pingGen)
                    }
                }

                SpeechIpc.MSG_START -> {
                    clientMessenger = msg.replyTo
                    if (!sessionActive.compareAndSet(false, true)) {
                        // 重复 START：已有会话在跑，忽略，避免两个解码协程抢同一个 stream
                        return@Callback true
                    }
                    val gen = ++sessionGen
                    val clientGen = runCatching {
                        msg.data?.getInt(SpeechIpc.KEY_GEN, 0) ?: 0
                    }.getOrDefault(0)
                    sessionClientGen = clientGen
                    // 新会话清掉上一会话残留的停止标记：迟到的旧 STOP/CANCEL
                    // 已在各自处理里按代次过滤，不会走到这里给新会话置位
                    stopRequested = false
                    cancelRequestedGen = 0
                    // 引擎初始化（可能数百 ms）移到 IO 线程，不要阻塞服务主线程
                    scope.launch {
                      try {
                        val ready = initEngine(this@SpeechRecognitionService, silent = true)
                        if (cancelRequestedGen == gen) {
                            // 初始化期间 CANCEL 已到达：处理方已复位会话并发
                            // DONE，这里静默退出，不建会话、不再回信
                            return@launch
                        }
                        val engine = if (ready) recognizerRef.get() else null
                        if (engine == null) {
                            // 只收尾自己这一代：STOP 已先行到达则回 DONE（客户
                            // 端在等会话终结而非失败口径）；若已有新一代会话
                            // 开始，旧的失败收尾不能误杀新会话
                            if (gen == sessionGen) {
                                sessionActive.set(false)
                                holding.set(false)
                                sendClient(
                                    if (stopRequested) SpeechIpc.MSG_DONE
                                    else SpeechIpc.MSG_ERROR,
                                    gen = clientGen,
                                )
                            }
                            return@launch
                        }
                        if (gen != sessionGen) {
                            // 初始化期间已有新一代会话开始：旧初始化静默退出
                            return@launch
                        }
                        val session = PushSession()
                        var newStream: OnlineStream? = null
                        synchronized(audioLock) {
                            newStream = engine.createStream()
                            if (gen != sessionGen) {
                                // 锁内代次复检：等锁期间（旧会话收尾解码
                                // 占用）已被新一代顶替——刚建的流就地
                                // 释放、不发布本会话，静默退出。去重/节流
                                // 状态也不得重置（那是新会话的）
                                newStream?.runCatching { release() }
                                newStream = null
                            } else {
                                // 新会话重置跨会话去重/节流状态：与解码协程对这三个
                                // 字段的读写同在 audioLock 内，避免跨线程可见性竞态
                                lastRawText = null
                                lastEmittedText = null
                                lastEmitUptimeMs = 0L
                                // 换流必须释放旧流：正常路径旧流由上个会话协程按
                                // 身份校验释放，但中止路径会留下孤儿 native stream，
                                // 无人释放只能等进程死亡
                                val oldStream = streamRef.getAndSet(newStream)
                                if (oldStream != null && oldStream !== newStream) {
                                    oldStream.runCatching { release() }
                                }
                                // 停止标记在发布前锁内现读：STOP 在初始
                                // 化期间（含等锁期间）已到达时，队列预置
                                // 流结束，解码协程建好后排空已有块（通常
                                // 为零）即走收尾，不闩死会话。此前在锁外
                                // 一次性快照，「快照后、发布前」到达的
                                // STOP 只作用于旧会话、新会话以未结束
                                // 发布，该代次永远等不到 DONE
                                session.endOfStream = stopRequested
                                pushSession = session
                            }
                        }
                        if (newStream == null) {
                            // 锁内复检未过：已在锁内释放刚建的流
                            return@launch
                        }
                        holding.set(true)
                        startDecodeSession(session, newStream!!, clientGen, gen)
                        // 推流就绪信号：队列与解码协程已就位，客户端可以开始
                        // 推音频（语义已变：不再等服务端开录，录音归客户端）
                        sendClient(SpeechIpc.MSG_RECORDING_STARTED, gen = clientGen)
                      } catch (t: Throwable) {
                        // 初始化段兜底：createStream 等 native 调用在模型
                        // 半损坏/OOM 时可能抛错，此前异常无人处理会直接杀掉
                        // :speech 进程且客户端收不到任何失败回信
                        Log.e("SpeechSvc", "Session init failed", t)
                        if (cancelRequestedGen != gen && gen == sessionGen) {
                            sessionActive.set(false)
                            holding.set(false)
                            sendClient(
                                if (stopRequested) SpeechIpc.MSG_DONE
                                else SpeechIpc.MSG_ERROR,
                                gen = clientGen,
                            )
                        }
                      }
                    }
                }

                SpeechIpc.MSG_AUDIO -> {
                    // 音频块入队（主线程只做校验与入队，重活在解码协程）：
                    // 代次不符是旧会话迟到块、会话不在进行中是 STOP 后的
                    // 残余块，都直接丢弃
                    val audioGen = runCatching {
                        msg.data?.getInt(SpeechIpc.KEY_GEN, 0) ?: 0
                    }.getOrDefault(0)
                    if (audioGen != 0 && audioGen != sessionClientGen) {
                        return@Callback true
                    }
                    val session = pushSession ?: return@Callback true
                    if (!sessionActive.get() || session.cancelled) return@Callback true
                    val block = runCatching {
                        msg.data?.getByteArray(SpeechIpc.KEY_AUDIO)
                    }.getOrNull() ?: return@Callback true
                    if (!session.queue.offer(block)) {
                        // 队列满（客户端领先解码约 60s）：丢新块保会话，
                        // 缺这一块只影响这一小段识别，不让会话闩死
                        Log.w("SpeechSvc", "Audio queue full; dropping chunk")
                    }
                }

                SpeechIpc.MSG_STOP -> {
                    val stopGen = runCatching {
                        msg.data?.getInt(SpeechIpc.KEY_GEN, 0) ?: 0
                    }.getOrDefault(0)
                    if (stopGen != 0 && stopGen != sessionClientGen) {
                        // 旧会话迟到的 STOP：不碰当前会话（代次纪律）
                        return@Callback true
                    }
                    // DONE 必须带被停会话的客户端代次：收尾期间新会话可能已
                    // 开始，代次能让客户端识别这声 DONE 属于旧会话
                    val stoppedClientGen = sessionClientGen
                    stopRequested = true
                    // 会话标志先复位：让下一次 START 不必等收尾完成就能接管
                    // （初始化在飞时同理——迟到的旧初始化靠代次守卫退出，
                    // 与旧架构 STOP 先行复位的口径一致）。本会话的排空收尾
                    // 由 stopRequested/endOfStream 与代次守卫保证，不依赖此标志
                    val wasActive = sessionActive.getAndSet(false)
                    holding.set(false)
                    val session = pushSession
                    if (session != null) {
                        // 会话已建立：只置流结束标记，FINAL/DONE 由解码协程
                        // 排空队列后统一发出。队列余量是松手收尾耗时的
                        // 主要变量，随松手链时序打点一并记录备查
                        Log.i(
                            "SpeechSvc",
                            "voice-stop: STOP gen=$stoppedClientGen queued=${session.queue.size}"
                        )
                        session.endOfStream = true
                    } else if (!wasActive) {
                        // 无会话（初始化也没在飞）：直接回 DONE 让客户端收尾
                        sendClient(SpeechIpc.MSG_DONE, gen = stoppedClientGen)
                        clientMessenger = null
                    }
                    // 余下情况是初始化在飞：stopRequested 已置位，初始化
                    // 完成后由建会话路径预置 endOfStream 走排空收尾
                }

                SpeechIpc.MSG_CANCEL -> {
                    val cancelGen = runCatching {
                        msg.data?.getInt(SpeechIpc.KEY_GEN, 0) ?: 0
                    }.getOrDefault(0)
                    if (cancelGen != 0 && cancelGen != sessionClientGen) {
                        // 旧会话迟到的 CANCEL：不碰当前会话（代次纪律）
                        return@Callback true
                    }
                    val cancelledClientGen = sessionClientGen
                    // 记下本代次已取消：初始化在飞时初始化协程据此静默退出
                    cancelRequestedGen = sessionGen
                    val session = pushSession
                    if (session != null) {
                        session.cancelled = true
                        synchronized(audioLock) {
                            if (pushSession === session) pushSession = null
                            // 释放当前流：解码协程的一切 stream 操作都在
                            // audioLock 内且先验身份，与此处串行化，不会踩
                            // 到已释放对象；协程收尾的身份释放是第二重兜底
                            streamRef.getAndSet(null)?.runCatching { release() }
                        }
                    }
                    sessionActive.set(false)
                    holding.set(false)
                    // 取消只回 DONE（无 FINAL）：客户端据此结算本代次
                    sendClient(SpeechIpc.MSG_DONE, gen = cancelledClientGen)
                }
            }
            true
        },
    )

    override fun onBind(intent: Intent?): IBinder {
        return Messenger(serviceHandler).binder
    }

    override fun onCreate() {
        super.onCreate()
    }

    override fun onDestroy() {
        super.onDestroy()
        // 先封消息入口再 spawn 守护线程：消息回调与 onDestroy 同在主
        // 线程串行，置位并清掉待处理消息后，不会再有 START/STOP 进入；
        // 已在飞的 STOP 收尾协程回执前查 destroying 短路。由此资源
        // 释放只剩守护线程一条线，不与消息路径并发碰同一批引用
        destroying = true
        serviceHandler.removeCallbacksAndMessages(null)
        sessionActive.set(false)
        holding.set(false)
        // 等待+释放整段挪到后台线程：decode 是 audioLock 内的 native 阻塞
        // 调用，不响应协程取消，必须等在途解码收尾后才能 release stream/
        // recognizer（否则采集协程的收尾解码踩已释放对象）。旧实现在主
        // 线程 runBlocking 等最多 2 秒，堵 :speech 进程的主 Looper。
        // 收尾顺序不变（join 在 release 之前、同一线程串行），只是换线程；
        // 资源引用全是本实例字段，新服务实例有独立引用集，互不串扰；
        // 进程若在收尾完成前被系统回收，native 资源随进程死亡由系统处理。
        val job = audioJob
        audioJob = null
        Thread({
            if (job != null) {
                runCatching {
                    runBlocking {
                        withTimeoutOrNull(DESTROY_JOIN_TIMEOUT_MS) { job.join() }
                    }
                }
            }
            scope.cancel()
            synchronized(audioLock) {
                streamRef.getAndSet(null)?.runCatching { release() }
                recognizerRef.getAndSet(null)?.runCatching { release() }
            }
        }, "speech-destroy-cleanup").apply { isDaemon = true }.start()
    }

    private fun sendClient(
        what: Int,
        text: String? = null,
        amplitude: Float = 0f,
        gen: Int = sessionClientGen,
    ) {
        val messenger = clientMessenger ?: return
        val ok = runCatching { messenger.send(SpeechIpc.message(what, text, amplitude, gen)) }
        if (ok.isFailure) Log.e("SpeechSvc", "sendClient failed what=$what", ok.exceptionOrNull())
    }

    private fun isQnnRuntimeSupported(context: android.content.Context): Boolean =
        android.os.Build.SUPPORTED_ABIS.contains("arm64-v8a")

    @SuppressLint("UnsafeDynamicallyLoadedCode")
    private fun prepareQnnRuntime(context: android.content.Context): Boolean {
        qnnRuntimeRef.get()?.let { return true }
        return runCatching {
            val cdsp = File(context.filesDir, "cdsp")
            prepareCdspFiles(context, cdsp)
            listOf("QnnSystem", "QnnHtp").forEach { System.loadLibrary(it) }
            OnlineRecognizer.prependAdspLibraryPath(cdsp.absolutePath)
            qnnRuntimeRef.set(
                QnnRuntime(
                    backend = File(context.applicationInfo.nativeLibraryDir, "libQnnHtp.so"),
                    system = File(context.applicationInfo.nativeLibraryDir, "libQnnSystem.so"),
                    target = cdsp,
                ),
            )
            Log.i("SpeechSvc", "QNN runtime prepared: DSP_PATH=$cdsp.absolutePath")
            true
        }.getOrElse {
            Log.e("SpeechSvc", "Failed to prepare QNN runtime", it)
            false
        }
    }

    private fun prepareCdspFiles(context: android.content.Context, cdspDir: File) {
        val marker = File(cdspDir, ".prepared")
        if (marker.isFile) return
        cdspDir.deleteRecursively()
        cdspDir.mkdirs()
        copyAssetDirectory(context, "cdsp", cdspDir)
        marker.createNewFile()
    }

    private fun copyAssetDirectory(context: android.content.Context, assetPath: String, targetDir: File) {
        val entries = context.assets.list(assetPath).orEmpty()
        if (entries.isEmpty()) {
            context.assets.open(assetPath).use { input ->
                val target = File(targetDir, assetPath.substringAfterLast('/'))
                target.parentFile?.mkdirs()
                target.outputStream().use { output -> input.copyTo(output) }
            }
            targetDir.listFiles()?.forEach { it.setExecutable(true) }
            return
        }
        for (entry in entries) {
            val childAssetPath = "$assetPath/$entry"
            val target = File(targetDir, entry)
            val children = context.assets.list(childAssetPath).orEmpty()
            if (children.isNotEmpty()) {
                target.mkdirs()
                copyAssetDirectory(context, childAssetPath, target)
            } else {
                context.assets.open(childAssetPath).use { input ->
                    target.parentFile?.mkdirs()
                    target.outputStream().use { output -> input.copyTo(output) }
                }
                target.setReadable(true)
                target.setExecutable(true)
            }
        }
    }

    private fun findModelFiles(dir: File, qnn: Boolean): ModelFiles? {
        val tokens = File(dir, "tokens.txt")
        if (!tokens.isFile) return null
        val files = dir.listFiles().orEmpty().filter { it.isFile }
        val ext = if (qnn) "bin" else "onnx"
        fun pick(prefix: String) = files.firstOrNull {
            it.extension.equals(ext, ignoreCase = true) && it.nameWithoutExtension.contains(
                prefix, ignoreCase = true
            )
        }

        val encoder = pick("encoder") ?: return null
        val decoder = pick("decoder") ?: return null
        val joiner = pick("joiner") ?: return null
        return ModelFiles(tokens, encoder, decoder, joiner, qnn)
    }

    @SuppressLint("UnsafeDynamicallyLoadedCode")
    private fun modelFingerprint(dir: java.io.File): String =
        dir.listFiles()?.filter { it.isFile }
            ?.sortedBy { it.name }
            ?.joinToString("|") { "${it.name}:${it.length()}:${it.lastModified()}" }
            ?: ""

    private fun initEngine(context: android.content.Context, silent: Boolean = false): Boolean {
        if (recognizerRef.get() != null &&
            modelFingerprint(App.speechModelDir) == loadedModelFingerprint
        ) return true
        synchronized(audioLock) {
            val dir = App.speechModelDir
            val fingerprint = modelFingerprint(dir)
            val existing = recognizerRef.get()
            if (existing != null) {
                if (fingerprint == loadedModelFingerprint) return true
                if (sessionActive.get()) {
                    // 正在录音时绝不能换引擎：采集协程还持有旧 recognizer/
                    // stream，此时 release 会让它在已释放的 native 对象上
                    // decode。标记待重载，沿用旧模型完成本次会话，会话结束
                    // 后由 reloadModelIfPending 补执行
                    pendingModelReload = true
                    Log.i("SpeechSvc", "Model changed during active session; reload deferred")
                    return true
                }
                // 模型文件已被在线更新替换：释放旧识别器、下面按新文件重建，
                // 否则下载成功的新模型在本进程重启前永不生效且无任何提示
                Log.i("SpeechSvc", "Speech model files changed; reloading recognizer")
                recognizerRef.set(null)
                loadedModelFingerprint = null
                streamRef.getAndSet(null)?.runCatching { release() }
                existing.runCatching { release() }
            }
            val qnnSupported = isQnnRuntimeSupported(context)
            val qnnFiles = if (qnnSupported) findModelFiles(dir, qnn = true) else null

            val (files, useQnn) = if (qnnFiles != null && prepareQnnRuntime(context)) {
                qnnFiles to true
            } else {
                val cpuFiles = findModelFiles(dir, qnn = false)
                if (cpuFiles == null) {
                    Log.w("SpeechSvc", "No available speech model in $dir")
                    return false
                }
                cpuFiles to false
            }

            return try {
                val transducer = if (useQnn) {
                    val rt = qnnRuntimeRef.get() ?: error("QNN runtime missing")
                    OnlineTransducerModelConfig(
                        encoder = "",
                        decoder = "",
                        joiner = "",
                        qnnConfig = QnnConfig(
                            backendLib = rt.backend.absolutePath,
                            systemLib = rt.system.absolutePath,
                            contextBinary = "${files.encoder.absolutePath},${files.decoder.absolutePath},${files.joiner.absolutePath}",
                        ),
                    )
                } else {
                    OnlineTransducerModelConfig(
                        encoder = files.encoder.absolutePath,
                        decoder = files.decoder.absolutePath,
                        joiner = files.joiner.absolutePath,
                    )
                }

                val modelConfig = OnlineModelConfig(
                    transducer = transducer,
                    tokens = files.tokens.absolutePath,
                    numThreads = if (useQnn) 1 else Runtime.getRuntime().availableProcessors()
                        .coerceIn(1, 4),
                    debug = false,
                    provider = if (useQnn) "qnn" else "cpu",
                    modelType = if (useQnn) "zipformer" else "",
                )
                val config = OnlineRecognizerConfig(
                    featConfig = FeatureConfig(sampleRate = SpeechIpc.SAMPLE_RATE, featureDim = 80),
                    modelConfig = modelConfig,
                    decodingMethod = "greedy_search",
                    enableEndpoint = false,
                )
                recognizerRef.set(OnlineRecognizer(null, config))
                loadedModelFingerprint = fingerprint
                val encoderPath = files.encoder.absolutePath
                val engineVariant = when {
                    useQnn -> "qnn"
                    files.isQnn -> "qnn-fallback-cpu"
                    files.encoder.nameWithoutExtension.contains("int8", ignoreCase = true) -> "int8"
                    else -> "standard"
                }
                Log.i("SpeechSvc", "Creating OnlineRecognizer: encoder=$encoderPath, variant=$engineVariant")
                true
            } catch (t: Throwable) {
                Log.e("SpeechSvc", "Sherpa recognizer initialization failed", t)
                Log.e("SpeechSvc", "initEngine failed", t)
                false
            }
        }
    }

    /** 录音中被推迟的模型重载在会话结束后补执行（销毁中不再触发） */
    private fun reloadModelIfPending() {
        if (!pendingModelReload || sessionActive.get() || destroying) return
        pendingModelReload = false
        scope.launch(Dispatchers.IO) { initEngine(this@SpeechRecognitionService, silent = true) }
    }

    /**
     * 推流解码协程：从本会话队列取音频块喂识别流、节流发 partial；队列
     * 排空且 endOfStream 已置位后做收尾解码（静音尾巴＋inputFinished＋
     * FINAL，与旧采集协程尾部逐项一致）并按本会话代次发 DONE。
     * 会话被取消（[PushSession.cancelled]）时丢弃队列静默退出（DONE 已
     * 由 CANCEL 处理方发出）；被新会话取代时身份校验让 FINAL 自然落空。
     * 本会话的 stream 在入口固定持有，结束时只按身份释放自己这个，
     * 不能无条件清 streamRef（新会话可能已经换上了新 stream）。
     */
    private fun startDecodeSession(
        session: PushSession,
        myStream: OnlineStream,
        myClientGen: Int,
        mySessionGen: Int,
    ) {
        audioJob = scope.launch(Dispatchers.IO) {
            val floatChunk = FloatArray(SpeechIpc.CHUNK_SAMPLES)
            try {
                while (isActive) {
                    // destroying 时立即退出：销毁守护线程在 join 本协程，
                    // 不短路会把它拖满超时；回信短路在下面的收尾段
                    if (session.cancelled || destroying) break
                    val block = try {
                        session.queue.poll(QUEUE_POLL_MS, TimeUnit.MILLISECONDS)
                    } catch (ie: InterruptedException) {
                        Thread.currentThread().interrupt()
                        null
                    }
                    if (block != null) {
                        feedAudioBlock(block, myStream, myClientGen, floatChunk)
                        continue
                    }
                    if (session.endOfStream) break
                    if (mySessionGen != sessionGen) {
                        // 已被新会话取代且队列已空：旧流早已被换走，继续
                        // 等只会闩住收尾；退出走统一收尾，身份校验会让
                        // FINAL 落空、DONE 仍按本会话代次发出（客户端按
                        // 代次过滤，不会误结算新会话）
                        break
                    }
                }

                if (!session.cancelled && !destroying && isActive) {
                    // 松手链时序打点：解码队列已排空、进入收尾解码
                    Log.i("SpeechSvc", "voice-stop: drained gen=$myClientGen, finalizing")
                    // 收尾解码前必须复检身份：STOP 后新会话 START 会在锁内
                    // getAndSet 换流并 release 旧 stream，本协程若在那之后
                    // 才拿到锁，myStream 已是被释放的 native 对象，再调
                    // acceptWaveform/decode 会踩已释放内存（SIGSEGV）。
                    // 已被换走就放弃收尾解码：旧会话结果丢弃是正确语义
                    synchronized(audioLock) {
                        val engine = recognizerRef.get()
                        if (engine != null && streamRef.get() === myStream) {
                            try {
                                val tail = FloatArray(
                                    SpeechIpc.SAMPLE_RATE * FINAL_TAIL_PADDING_MS / 1000
                                )
                                myStream.acceptWaveform(tail, SpeechIpc.SAMPLE_RATE)
                                myStream.inputFinished()

                                var loops = 0
                                while (engine.isReady(myStream) && loops++ < 512) {
                                    engine.decode(myStream)
                                }
                                val finalText = FinalTextNormalizer.normalizeFinal(
                                    engine.getResult(myStream).text
                                )
                                finalText.takeIf { it.isNotEmpty() }?.let {
                                    sendClient(SpeechIpc.MSG_FINAL, it, gen = myClientGen)
                                }
                            } catch (e: Throwable) {
                                Log.e("SpeechSvc", "Final decode failed", e)
                            }
                        }
                    }
                    sendClient(SpeechIpc.MSG_DONE, gen = myClientGen)
                }
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                // 解码管线自行失败（native 抛错等）：MSG_STOP 不会再带来
                // 收尾，ERROR 是本会话唯一的终结回信（与旧采集管线异常
                // 路径同口径：发 ERROR、不再补 DONE）
                Log.e("SpeechSvc", "Stream decode failed", t)
                withContext(NonCancellable + Dispatchers.Main) {
                    toast("录音异常")
                }
                sendClient(SpeechIpc.MSG_ERROR, gen = myClientGen)
            } finally {
                synchronized(audioLock) {
                    if (pushSession === session) pushSession = null
                    // 只释放本会话的 stream；新会话已换上新 stream 时不能误放
                    if (streamRef.get() === myStream) {
                        streamRef.set(null)
                        myStream.runCatching { release() }
                    }
                }
                // 全局复位只许当前代次做：被新会话取代的旧协程不得碰
                // 新会话的标志与通道（与旧采集协程收尾的代次守卫同口径）
                if (mySessionGen == sessionGen) {
                    sessionActive.set(false)
                    holding.set(false)
                    clientMessenger = null
                    reloadModelIfPending()
                }
            }
        }
    }

    /**
     * 把一块 PCM16LE 音频喂进识别流：按共享块长切片转换、解码、partial
     * 节流发射——转换与发射口径与旧采集循环逐项一致（partial 仍走 80ms
     * 节流、normalizeSpacing 规整与 lastRaw/lastEmitted 双重去重）。
     */
    private fun feedAudioBlock(
        block: ByteArray,
        myStream: OnlineStream,
        myClientGen: Int,
        floatChunk: FloatArray,
    ) {
        val sampleCount = block.size / 2
        if (sampleCount <= 0) return
        val shorts = ByteBuffer.wrap(block, 0, sampleCount * 2)
            .order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        var offset = 0
        while (offset < sampleCount) {
            val n = minOf(SpeechIpc.CHUNK_SAMPLES, sampleCount - offset)
            for (i in 0 until n) {
                floatChunk[i] = shorts.get(offset + i) / 32768f
            }
            synchronized(audioLock) {
                val engine = recognizerRef.get()
                // 与收尾段同款身份复检：stream 已被新会话换走并释放时
                // 本片直接丢弃，不碰已释放的 native 对象
                if (engine != null && streamRef.get() === myStream) {
                    myStream.acceptWaveform(
                        if (n == floatChunk.size) floatChunk
                        else floatChunk.copyOf(n),
                        SpeechIpc.SAMPLE_RATE,
                    )
                    var loops = 0
                    while (engine.isReady(myStream) && loops++ < 64) {
                        engine.decode(myStream)
                    }

                    val rawText = engine.getResult(myStream).text.trim()
                    if (rawText.isNotEmpty() && rawText != lastRawText) {
                        lastRawText = rawText
                    }
                    // 发射判定与变化检测解耦：此前被节流压住的版本因
                    // lastRawText 已先行更新、后续同文本不再触发判定
                    // 而永不补发，停顿期间字幕缺尾字。现在只要规整后
                    // 的文本与上次已发不同且节流窗已过就发射——说话
                    // 间隙持续喂入的静音块会在节流窗过后的第一个块
                    // 把待发文本补出去；同文本仍只发一次（已发去重）
                    if (rawText.isNotEmpty()) {
                        val partial = FinalTextNormalizer.normalizeSpacing(rawText)
                        if (partial.isNotEmpty() && partial != lastEmittedText) {
                            val now = SystemClock.uptimeMillis()
                            if (now - lastEmitUptimeMs >= PARTIAL_EMIT_MIN_INTERVAL_MS) {
                                lastEmittedText = partial
                                lastEmitUptimeMs = now
                                sendClient(SpeechIpc.MSG_PARTIAL, partial, gen = myClientGen)
                            }
                        }
                    }
                }
            }
            offset += n
        }
    }

    private fun toast(text: String) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
    }

    companion object {
        private const val FINAL_TAIL_PADDING_MS = 800
        private const val PARTIAL_EMIT_MIN_INTERVAL_MS = 80L

        /** 推流队列上限：40ms/块 × 1500 ≈ 60s，足够覆盖引擎冷加载期间客户端的领先量 */
        private const val AUDIO_QUEUE_CAPACITY_BLOCKS = 1500

        /** 解码协程等块的轮询片：够短以及时响应结束/取消/取代，够长不空转 */
        private const val QUEUE_POLL_MS = 50L

        /** onDestroy 等待在途解码收尾的上限：正常收尾远小于此值，仅防极端情况卡死销毁 */
        private const val DESTROY_JOIN_TIMEOUT_MS = 2000L
    }
}
