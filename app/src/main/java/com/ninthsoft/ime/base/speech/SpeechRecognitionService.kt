package com.ninthsoft.ime.base.speech

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
import com.ninthsoft.ime.engine.rime.data.DataManager
import com.ninthsoft.ime.base.util.appContext
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
                    val ready = initEngine(this@SpeechRecognitionService, silent = true)
                    if (!ready) {
                        sendClient(SpeechIpc.MSG_ERROR)
                        return@Callback true
                    }
                    val engine = recognizerRef.get()
                    if (engine == null) {
                        sendClient(SpeechIpc.MSG_ERROR)
                        return@Callback true
                    }
                    synchronized(audioLock) { streamRef.set(engine.createStream()) }
                    startAudioStreaming()
                }

                SpeechIpc.MSG_STOP -> {
                    if (!holding.compareAndSet(true, false)) return@Callback true
                    audioRecord?.runCatching { stop() }
                    audioJob?.let { runBlocking { it.join() } }
                    sendClient(SpeechIpc.MSG_DONE)
                    clientMessenger = null
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
        scope.cancel()
        synchronized(audioLock) {
            streamRef.getAndSet(null)?.runCatching { release() }
        }
        recognizerRef.getAndSet(null)
    }

    private fun sendClient(what: Int, text: String? = null, amplitude: Float = 0f) {
        val messenger = clientMessenger ?: return
        val ok = runCatching { messenger.send(SpeechIpc.message(what, text, amplitude)) }
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

            val dir = DataManager.speechModelDir
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
        if (ContextCompat.checkSelfPermission(
                this, Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            sendClient(SpeechIpc.MSG_ERROR)
            return
        }

        audioJob = scope.launch(Dispatchers.IO) {
            var recorder: AudioRecord? = null
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
                    runCatching {
                        AudioRecord(source, SAMPLE_RATE, channel, format, bufferSize)
                    }.getOrNull()?.takeIf { it.state == AudioRecord.STATE_INITIALIZED }
                } ?: error("Unable to initialize AudioRecord")

                audioRecord = recorder
                recorder.startRecording()
                holding.set(true)
                sendClient(SpeechIpc.MSG_RECORDING_STARTED)

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
                        val stream = streamRef.get()
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
                                        sendClient(SpeechIpc.MSG_PARTIAL, partial)
                                    }
                                }
                            }
                        }
                    }

                    sendClient(SpeechIpc.MSG_AMPLITUDE, amplitude = amplitude)

                    delay(5.milliseconds)
                }

                synchronized(audioLock) {
                    val engine = recognizerRef.get()
                    val stream = streamRef.get()
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
                                sendClient(SpeechIpc.MSG_FINAL, it)
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
                    sendClient(SpeechIpc.MSG_ERROR)
                }
            } finally {
                recorder?.runCatching { stop() }
                recorder?.release()
                if (audioRecord === recorder) audioRecord = null
                synchronized(audioLock) {
                    streamRef.getAndSet(null)?.runCatching { release() }
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
