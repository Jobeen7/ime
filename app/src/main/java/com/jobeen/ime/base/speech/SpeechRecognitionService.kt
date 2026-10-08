package com.jobeen.ime.base.speech

import android.Manifest
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.annotation.SuppressLint
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.SystemClock
import android.widget.Toast
import androidx.core.content.ContextCompat
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
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.delay

/**
 * Runs in the isolated `:speech` process. Owns audio capture + sherpa-onnx
 * recognition only; results are streamed back to the main process via Messenger.
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
    // 不依赖在采集协程里迟置位的 holding（STOP 先到时旧逻辑会直接吞掉停止请求）
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
    private var audioRecord: AudioRecord? = null
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

                SpeechIpc.MSG_START -> {
                    clientMessenger = msg.replyTo
                    if (!sessionActive.compareAndSet(false, true)) {
                        // 重复 START：已有会话在跑，忽略，避免两个采集协程抢同一个 stream
                        return@Callback true
                    }
                    val gen = ++sessionGen
                    val clientGen = runCatching {
                        msg.data?.getInt(SpeechIpc.KEY_GEN, 0) ?: 0
                    }.getOrDefault(0)
                    sessionClientGen = clientGen
                    // 引擎初始化（可能数百 ms）移到 IO 线程，不要阻塞服务主线程
                    scope.launch {
                      try {
                        val ready = initEngine(this@SpeechRecognitionService, silent = true)
                        val engine = if (ready) recognizerRef.get() else null
                        if (engine == null) {
                            // 只收尾自己这一代：初始化期间 STOP 已到达则静默；
                            // 若已有新一代会话开始，旧的失败收尾不能误杀新会话
                            if (gen == sessionGen && sessionActive.compareAndSet(true, false)) {
                                holding.set(false)
                                sendClient(SpeechIpc.MSG_ERROR)
                            }
                            return@launch
                        }
                        if (!sessionActive.get() || gen != sessionGen) {
                            // STOP 已在初始化期间到达：不要再启动采集（幽灵录音）
                            return@launch
                        }
                        synchronized(audioLock) {
                            // 新会话重置跨会话去重/节流状态：与采集协程对这三个
                            // 字段的读写同在 audioLock 内，避免跨线程可见性竞态
                            lastRawText = null
                            lastEmittedText = null
                            lastEmitUptimeMs = 0L
                            // 换流必须释放旧流：正常路径旧流由上个会话协程按
                            // 身份校验释放，但权限失败/STOP 抢先等中止路径会
                            // 留下孤儿 native stream，无人释放只能等进程死亡
                            val newStream = engine.createStream()
                            val oldStream = streamRef.getAndSet(newStream)
                            if (oldStream != null && oldStream !== newStream) {
                                oldStream.runCatching { release() }
                            }
                        }
                        startAudioStreaming()
                      } catch (t: Throwable) {
                        // 初始化段兜底：createStream 等 native 调用在模型
                        // 半损坏/OOM 时可能抛错，此前异常无人处理会直接杀掉
                        // :speech 进程且客户端收不到任何失败回信
                        Log.e("SpeechSvc", "Session init failed", t)
                        if (gen == sessionGen && sessionActive.compareAndSet(true, false)) {
                            holding.set(false)
                            sendClient(SpeechIpc.MSG_ERROR, gen = clientGen)
                        }
                      }
                    }
                }

                SpeechIpc.MSG_STOP -> {
                    val gen = sessionGen
                    // DONE 必须带被停会话的客户端代次：join 期间新会话可能已开始，
                    // 代次能让客户端识别这声 DONE 属于旧会话，不会误结算新会话
                    val stoppedClientGen = sessionClientGen
                    sessionActive.set(false)
                    holding.set(false)
                    audioRecord?.runCatching { stop() }
                    val job = audioJob
                    audioJob = null
                    if (job == null) {
                        // 还没开始采集（初始化中/STOP 先到）：也要回 DONE 让客户端收尾
                        sendClient(SpeechIpc.MSG_DONE, gen = stoppedClientGen)
                        clientMessenger = null
                    } else {
                        // 等最终解码在 IO 线程完成，不要在主线程 runBlocking 等待
                        scope.launch {
                            job.join()
                            // join 期间若已开始新会话，旧会话的 DONE 不得结算新会话；
                            // 服务已进入销毁（destroying）时同样不回——销毁守护
                            // 线程正在串行释放资源，这条协程不再碰客户端与引用
                            if (!destroying && gen == sessionGen) {
                                sendClient(SpeechIpc.MSG_DONE, gen = stoppedClientGen)
                                clientMessenger = null
                            }
                            reloadModelIfPending()
                        }
                    }
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
        audioRecord?.runCatching { stop() }
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

    /** 引擎指纹 = 模型目录指纹 + 热词文件戳 + 解码模式：任一变化都触发识别器重建。 */
    private fun engineFingerprint(context: android.content.Context, dir: java.io.File): String {
        val hw = SpeechHotwords.file(context)
        val hwStamp = if (hw.isFile) "${hw.length()}:${hw.lastModified()}" else "none"
        val mode = SpeechHotwords.readMode(context).name
        return modelFingerprint(dir) + "|hw=" + hwStamp + "|mode=" + mode
    }

    private fun initEngine(context: android.content.Context, silent: Boolean = false): Boolean {
        // 注意：本服务在 :speech 独立进程，不能在这里生成热词表——Rime 引擎与
        // 用户词典在主进程，本进程的会话导不出词。生成归主进程（RimeEngine
        // 输入结束时检查刷新、词典导入后刷新），本进程只读文件；新词表经
        // 指纹变化在下一次初始化时自动生效。
        if (recognizerRef.get() != null &&
            engineFingerprint(context, App.speechModelDir) == loadedModelFingerprint
        ) return true
        synchronized(audioLock) {
            val dir = App.speechModelDir
            val fingerprint = engineFingerprint(context, dir)
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
            val decodeMode = SpeechHotwords.readMode(context)
            // 热词档必须走 CPU：引擎 native 明示 "hotwords are not supported
            // with qnn transducers"——QNN 变体挂热词构造直接被拒（v1.1.2.3
            // 真机第三档无反应的根因，v1.1.2.4 三档隔离 + 库内字符串坐实）。
            // CPU 模型文件缺失时下方自然落到 QNN 分支，届时 useHotwords
            // 守卫会摘掉热词（等同 BEAM），语音不能被热词拖死。
            val qnnSupported = if (decodeMode == SpeechHotwords.DecodeMode.BEAM_HOTWORDS) {
                false
            } else {
                isQnnRuntimeSupported(context)
            }
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
                // 解码方式由设置中的三档开关决定（文件传递，默认 GREEDY
                // 即历史稳定行为）：BEAM 只切束搜索不挂词表，BEAM_HOTWORDS
                // 再加用户词库热词偏置，且构造前已强制 CPU 变体（见上）；
                // useQnn 守卫是第二道保险：万一仍落在 QNN 上，宁可不挂
                // 热词也不能让构造被 native 拒绝。
                val hotwordsFile = SpeechHotwords.file(context)
                val useBeam = decodeMode != SpeechHotwords.DecodeMode.GREEDY
                val useHotwords = decodeMode == SpeechHotwords.DecodeMode.BEAM_HOTWORDS &&
                    !useQnn && hotwordsFile.isFile && hotwordsFile.length() > 0
                val config = OnlineRecognizerConfig(
                    featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
                    modelConfig = modelConfig,
                    decodingMethod = if (useBeam) "modified_beam_search" else "greedy_search",
                    maxActivePaths = 4,
                    enableEndpoint = false,
                    hotwordsFile = if (useHotwords) hotwordsFile.absolutePath else "",
                    hotwordsScore = if (useHotwords) 2.5f else 0.0f,
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

    private fun startAudioStreaming() {
        if (!sessionActive.get()) return
        // 本会话的客户端代次在入口固定：协程收尾（最终解码）可能晚于新会话开始，
        // 回信必须带自己会话的代次，不能读到那时已被覆盖的 sessionClientGen
        val myGen = sessionClientGen
        val mySessionGen = sessionGen
        if (ContextCompat.checkSelfPermission(
                this, Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            sessionActive.set(false)
            sendClient(SpeechIpc.MSG_ERROR, gen = myGen)
            return
        }

        audioJob = scope.launch(Dispatchers.IO) {
            var recorder: AudioRecord? = null
            // 异常路径已发过 MSG_ERROR 时，finally 不再补发终结回信
            var pipelineFailed = false
            // 本会话的 stream 在协程内固定持有：结束时只释放自己这个，
            // 不能无条件清 streamRef（新会话可能已经换上了新 stream）
            val myStream = synchronized(audioLock) { streamRef.get() }
            try {
                val channel = AudioFormat.CHANNEL_IN_MONO
                val format = AudioFormat.ENCODING_PCM_16BIT
                val minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, channel, format)
                val chunkSamples = SAMPLE_RATE * CHUNK_MS / 1000
                val chunkBytes = chunkSamples * 2
                val bufferSize = minBuffer.coerceAtLeast(chunkBytes * 2)

                recorder = listOf(
                    MediaRecorder.AudioSource.MIC, MediaRecorder.AudioSource.VOICE_RECOGNITION
                ).firstNotNullOfOrNull { source ->
                    val candidate = runCatching {
                        AudioRecord(source, SAMPLE_RATE, channel, format, bufferSize)
                    }.getOrNull()
                    if (candidate != null && candidate.state == AudioRecord.STATE_INITIALIZED) {
                        candidate
                    } else {
                        // 未初始化成功的实例必须释放，否则每次探测都泄漏一个 AudioRecord
                        candidate?.runCatching { release() }
                        null
                    }
                } ?: error("Unable to initialize AudioRecord")

                audioRecord = recorder
                recorder.startRecording()
                if (!sessionActive.get()) {
                    // STOP 在采集启动期间已到达：立即收尾，不能把 holding 重新置 true
                    return@launch
                }
                holding.set(true)
                sendClient(SpeechIpc.MSG_RECORDING_STARTED, gen = myGen)

                val bytes = ByteArray(chunkBytes)
                val shortChunk = ShortArray(chunkSamples)
                val floatChunk = FloatArray(chunkSamples)
                // 音量消息节流：原先每 40ms 音频块无条件跨进程发一条（25Hz），
                // 波形 UI 10Hz 已足够，省掉长录音期间一半多的 Binder 唤醒
                var lastAmplitudeSentMs = 0L

                while (isActive && holding.get() && recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    val count = recorder.read(bytes, 0, bytes.size)
                    if (count < 0) {
                        Log.e("SpeechSvc", "recorder.read error count=$count")
                        break
                    }
                    if (count == 0) {
                        delay(10.milliseconds)
                        continue
                    }

                    val sampleCount = count / 2
                    ByteBuffer.wrap(bytes, 0, count).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                        .get(shortChunk, 0, sampleCount)

                    var maxAmp = 0
                    for (i in 0 until sampleCount) {
                        val v = abs(shortChunk[i].toInt())
                        if (v > maxAmp) maxAmp = v
                        floatChunk[i] = shortChunk[i] / 32768f
                    }
                    val amplitude = maxAmp / 32768f

                    synchronized(audioLock) {
                        val engine = recognizerRef.get()
                        val stream = myStream
                        if (engine != null && stream != null) {
                            stream.acceptWaveform(
                                if (sampleCount == floatChunk.size) floatChunk
                                else floatChunk.copyOf(sampleCount),
                                SAMPLE_RATE,
                            )
                            var loops = 0
                            while (engine.isReady(stream) && loops++ < 64) {
                                engine.decode(stream)
                            }

                            val rawText = engine.getResult(stream).text.trim()
                            if (rawText.isNotEmpty() && rawText != lastRawText) {
                                lastRawText = rawText
                                val now = SystemClock.uptimeMillis()
                                if (now - lastEmitUptimeMs >= PARTIAL_EMIT_MIN_INTERVAL_MS) {
                                    val partial = FinalTextNormalizer.normalizeSpacing(rawText)
                                    if (partial.isNotEmpty() && partial != lastEmittedText) {
                                        lastEmittedText = partial
                                        lastEmitUptimeMs = now
                                        sendClient(SpeechIpc.MSG_PARTIAL, partial, gen = myGen)
                                    }
                                }
                            }
                        }
                    }

                    val ampNow = SystemClock.uptimeMillis()
                    if (ampNow - lastAmplitudeSentMs >= 100L) {
                        lastAmplitudeSentMs = ampNow
                        sendClient(SpeechIpc.MSG_AMPLITUDE, amplitude = amplitude, gen = myGen)
                    }

                    delay(5.milliseconds)
                }

                synchronized(audioLock) {
                    val engine = recognizerRef.get()
                    val stream = myStream
                    if (engine != null && stream != null) {
                        try {
                            val tail = FloatArray(SAMPLE_RATE * FINAL_TAIL_PADDING_MS / 1000)
                            stream.acceptWaveform(tail, SAMPLE_RATE)
                            stream.inputFinished()

                            var loops = 0
                            while (engine.isReady(stream) && loops++ < 512) {
                                engine.decode(stream)
                            }
                            val finalText = FinalTextNormalizer.normalizeFinal(engine.getResult(stream).text)
                            finalText.takeIf { it.isNotEmpty() }?.let {
                                sendClient(SpeechIpc.MSG_FINAL, it, gen = myGen)
                            }
                        } catch (e: Throwable) {
                            Log.e("SpeechSvc", "Final decode failed", e)
                        }
                    }
                }
            } catch (t: Throwable) {
                if (t !is CancellationException) {
                    pipelineFailed = true
                    Log.e("SpeechSvc", "Audio recording or inference failed", t)
                    withContext(NonCancellable + Dispatchers.Main) {
                        toast("录音异常")
                    }
                    sendClient(SpeechIpc.MSG_ERROR, gen = myGen)
                }
            } finally {
                recorder?.runCatching { stop() }
                recorder?.release()
                if (audioRecord === recorder) audioRecord = null
                synchronized(audioLock) {
                    // 只释放本会话的 stream；新会话已换上新 stream 时不能误放
                    if (myStream != null && streamRef.get() === myStream) {
                        streamRef.set(null)
                        myStream.runCatching { release() }
                    }
                }
                // 管线自行终止（读错误 break、录音状态被系统收回、异常）时
                // MSG_STOP 不会来：会话状态无人复位，holding/sessionActive
                // 残留会让下一次 START 的 CAS 失败被静默吞掉（无反馈死会话，
                // 锁定模式下还会假录音）。仍是本代次且会话还标活跃即异常终止：
                // 复位并补发终结回信让客户端收尾。正常 STOP 路径 sessionActive
                // 已先置 false、DONE 由 STOP 处理方发出，不会进这里。
                if (mySessionGen == sessionGen && sessionActive.compareAndSet(true, false)) {
                    holding.set(false)
                    if (!pipelineFailed) {
                        sendClient(SpeechIpc.MSG_DONE, gen = myGen)
                    }
                    clientMessenger = null
                    reloadModelIfPending()
                }
            }
        }
    }

    private fun toast(text: String) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
    }

    companion object {
        private const val SAMPLE_RATE = 16000
        private const val CHUNK_MS = 40
        private const val FINAL_TAIL_PADDING_MS = 800
        private const val PARTIAL_EMIT_MIN_INTERVAL_MS = 80L

        /** onDestroy 等待在途解码收尾的上限：正常收尾远小于此值，仅防极端情况卡死销毁 */
        private const val DESTROY_JOIN_TIMEOUT_MS = 2000L
    }
}
