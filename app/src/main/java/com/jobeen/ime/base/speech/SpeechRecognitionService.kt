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
import kotlinx.coroutines.withContext
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

    private val holding = AtomicBoolean(false)

    // 会话状态机：START 用 CAS 保证幂等；STOP 以 sessionActive/代次为准，
    // 不依赖在采集协程里迟置位的 holding（STOP 先到时旧逻辑会直接吞掉停止请求）
    private val sessionActive = AtomicBoolean(false)

    @Volatile
    private var sessionGen = 0

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
                        val ready = initEngine(this@SpeechRecognitionService, silent = true)
                        val engine = if (ready) recognizerRef.get() else null
                        if (engine == null) {
                            // 初始化期间 STOP 已到达（会话已撤销）则静默收尾，不再回错误
                            if (sessionActive.get()) {
                                sessionActive.set(false)
                                sendClient(SpeechIpc.MSG_ERROR)
                            }
                            return@launch
                        }
                        if (!sessionActive.get() || gen != sessionGen) {
                            // STOP 已在初始化期间到达：不要再启动采集（幽灵录音）
                            return@launch
                        }
                        // 新会话重置跨会话去重/节流状态，避免首段文字被上一会话吞掉
                        lastRawText = null
                        lastEmittedText = null
                        lastEmitUptimeMs = 0L
                        synchronized(audioLock) { streamRef.set(engine.createStream()) }
                        startAudioStreaming()
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
                            // join 期间若已开始新会话，旧会话的 DONE 不得结算新会话
                            if (gen == sessionGen) {
                                sendClient(SpeechIpc.MSG_DONE, gen = stoppedClientGen)
                                clientMessenger = null
                            }
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
        sessionActive.set(false)
        holding.set(false)
        scope.cancel()
        synchronized(audioLock) {
            streamRef.getAndSet(null)?.runCatching { release() }
        }
        recognizerRef.getAndSet(null)
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
    private fun initEngine(context: android.content.Context, silent: Boolean = false): Boolean {
        if (recognizerRef.get() != null) return true
        synchronized(audioLock) {
            if (recognizerRef.get() != null) return true

            val dir = App.speechModelDir
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
                    featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
                    modelConfig = modelConfig,
                    decodingMethod = "greedy_search",
                    enableEndpoint = false,
                )
                recognizerRef.set(OnlineRecognizer(null, config))
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

    private fun startAudioStreaming() {
        if (!sessionActive.get()) return
        // 本会话的客户端代次在入口固定：协程收尾（最终解码）可能晚于新会话开始，
        // 回信必须带自己会话的代次，不能读到那时已被覆盖的 sessionClientGen
        val myGen = sessionClientGen
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
                                    val partial = normalizeCjkSpacing(rawText)
                                    if (partial.isNotEmpty() && partial != lastEmittedText) {
                                        lastEmittedText = partial
                                        lastEmitUptimeMs = now
                                        sendClient(SpeechIpc.MSG_PARTIAL, partial, gen = myGen)
                                    }
                                }
                            }
                        }
                    }

                    sendClient(SpeechIpc.MSG_AMPLITUDE, amplitude = amplitude, gen = myGen)

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
                            val finalText = normalizeCjkSpacing(engine.getResult(stream).text)
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
            }
        }
    }

    private fun normalizeCjkSpacing(text: String): String {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return trimmed
        val chars = trimmed.toCharArray()
        val output = StringBuilder(trimmed.length)
        var i = 0
        while (i < chars.size) {
            if (chars[i].isWhitespace()) {
                var nextIndex = i + 1
                while (nextIndex < chars.size && chars[nextIndex].isWhitespace()) nextIndex++
                val previous = output.lastOrNull()
                val next = chars.getOrNull(nextIndex)
                val betweenCjk =
                    previous != null && next != null && isCjkOrPunctuation(previous) && isCjkOrPunctuation(
                        next
                    )
                val beforeAsciiPunctuation = next != null && next in ".,!?;:%)]}"
                if (!betweenCjk && !beforeAsciiPunctuation) {
                    repeat(nextIndex - i) { output.append(' ') }
                }
                i = nextIndex
            } else {
                output.append(chars[i++])
            }
        }
        return output.toString()
    }

    private fun isCjkOrPunctuation(ch: Char): Boolean =
        ch in '\u3400'..'\u4DBF' || ch in '\u4E00'..'\u9FFF' || ch in '\uF900'..'\uFAFF' || ch in '！'..'～' || ch in '\u3000'..'\u303F' || ch in '\uFF00'..'\uFFEF' || ch in '\uFE30'..'\uFE4F'

    private fun toast(text: String) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
    }

    companion object {
        private const val SAMPLE_RATE = 16000
        private const val CHUNK_MS = 40
        private const val FINAL_TAIL_PADDING_MS = 800
        private const val PARTIAL_EMIT_MIN_INTERVAL_MS = 80L
    }
}
