package com.ninthsoft.ime.base.speech

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Messenger
import android.util.Log
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.ninthsoft.ime.base.util.TraditionalConverter
import com.ninthsoft.ime.base.util.appContext
import com.ninthsoft.ime.data.manager.CandidateManager
import com.ninthsoft.ime.engine.rime.data.DataManager
import com.ninthsoft.ime.input.ImeInputMethodService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.File
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Main-process proxy. Keeps the original public API untouched; the actual
 * audio capture + sherpa-onnx recognition runs in the isolated `:speech`
 * process. Results arrive through [clientMessenger] and are committed to the
 * editor or surfaced via [SpeechUiBridge] exactly as before.
 */
object SherpaSpeechClient {
    private val holding = AtomicBoolean(false)
    private val discarding = AtomicBoolean(false)
    private val composingText = AtomicReference<String?>(null)

    private var uiJob: Job? = null
    private var serviceRef: WeakReference<ImeInputMethodService>? = null

    private var speechMessenger: Messenger? = null
    private val clientMessenger = Messenger(
        Handler(
            Looper.getMainLooper(),
            { msg ->
                when (msg.what) {
                    SpeechIpc.MSG_RECORDING_STARTED -> onRecordingStarted()
                    SpeechIpc.MSG_PARTIAL -> onPartial(msg.data.getString(SpeechIpc.KEY_TEXT))
                    SpeechIpc.MSG_FINAL -> onFinal(msg.data.getString(SpeechIpc.KEY_TEXT))
                    SpeechIpc.MSG_AMPLITUDE -> onAmplitude(msg.data.getFloat(SpeechIpc.KEY_AMPLITUDE))
                    SpeechIpc.MSG_ERROR -> onError()
                    SpeechIpc.MSG_DONE -> onDone()
                }
                true
            },
        ),
    )
    private val connectLock = Any()
    private val pending = mutableListOf<Int>()

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val messenger = Messenger(binder)
            Timber.d("SpeechCli %s", "onServiceConnected")
            synchronized(connectLock) {
                speechMessenger = messenger
                val actions = pending.toList()
                pending.clear()
                actions.forEach { what ->
                    val msg = SpeechIpc.message(what)
                    if (what == SpeechIpc.MSG_START) msg.replyTo = clientMessenger
                    runCatching { messenger.send(msg) }
                }
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            Timber.d("SpeechCli %s", "onServiceDisconnected")
            synchronized(connectLock) { speechMessenger = null }
            if (holding.get()) cancelSession()

            //异常退出。清理资源
            runCatching {
                DataManager.speechModelDir.listFiles()?.forEach { it.deleteRecursively() }
            }.onFailure { Timber.d("SpeechCli clearSpeechModelDir failed %s", it.message) }
        }
    }

    private fun send(what: Int) {
        val messenger = synchronized(connectLock) { speechMessenger }
        if (messenger != null) {
            val msg = SpeechIpc.message(what)
            if (what == SpeechIpc.MSG_START) msg.replyTo = clientMessenger
            runCatching { messenger.send(msg) }
        } else {
            synchronized(connectLock) { pending.add(what) }
            Timber.d("SpeechCli %s", "bind requested what=$what")
            val app = appContext
            Handler(Looper.getMainLooper()).post {
                runCatching {
                    app.bindService(
                        Intent(app, SpeechRecognitionService::class.java),
                        connection,
                        Context.BIND_AUTO_CREATE,
                    )
                }.onFailure { Timber.e("SpeechCli bindService failed %s", it.message) }
            }
        }
    }

    private fun onRecordingStarted() {
        runCatching { SpeechUiBridge.onRecordingStarted?.invoke() }
    }

    private fun onPartial(text: String?) {
        if (discarding.get()) return
        text?.takeIf { it.isNotEmpty() }?.let { composingText.set(it) }
    }

    private fun onFinal(text: String?) {
        if (discarding.get()) return
        text?.takeIf { it.isNotEmpty() }?.let { composingText.set(it) }
    }

    private fun onAmplitude(value: Float) {
        runCatching { SpeechUiBridge.onAmplitude?.invoke(value) }
    }

    // 上屏前按繁体开关做 s2t 转换；默认简体不做处理。
    private fun toDisplayText(text: String): String =
        if (CandidateManager.isTraditionalChineseEnabled(appContext)) {
            TraditionalConverter.toTraditional(text)
        } else {
            text
        }

    private fun onError() {
        Timber.e("SpeechCli %s", "onError from speech service")
        cancelSession()
    }

    private fun onDone() {
        finishSession()
    }

    fun isQnnRuntimeSupported(context: Context): Boolean =
        android.os.Build.SUPPORTED_ABIS.contains("arm64-v8a")

    fun preStartSync(context: Context) {
        send(SpeechIpc.MSG_LOAD)
    }

    fun initialize(context: Context) {
        send(SpeechIpc.MSG_LOAD)
    }

    fun isModelReady(context: Context): Boolean {
        val dir = DataManager.speechModelDir
        return findModelFiles(dir, qnn = true) || findModelFiles(dir, qnn = false)
    }

    suspend fun downloadModel(
        context: Context,
        onProgress: (ModelDownloader.Progress) -> Unit = {},
        onExtract: (current: Long, total: Long) -> Unit = { _, _ -> },
    ): Boolean = ModelDownloader.download(context, onProgress, onExtract)

    fun startHoldSession(service: ImeInputMethodService) {
        if (!holding.compareAndSet(false, true)) return
        Timber.i("startHoldSession")
        serviceRef = WeakReference(service)
        composingText.set(null)
        discarding.set(false)

        uiJob = service.scope?.launch(Dispatchers.Main) {
            while (isActive && holding.get()) {
                composingText.getAndSet(null)?.let { text ->
                    service.activeInputConnection()?.setComposingText(toDisplayText(text), 1)
                }
                delay(50)
            }
        }

        if (ContextCompat.checkSelfPermission(
                service, Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            val intent = Intent(
                service, SpeechPermissionActivity::class.java
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { service.startActivity(intent) }
            cancelSession()
            return
        }

        if (!isModelReady(service)) {
            notifyModelMissing(
                service,
                if (isQnnRuntimeSupported(service)) ModelProvider.QNN else ModelProvider.CPU
            )
            cancelSession()
            return
        }

        send(SpeechIpc.MSG_LOAD)
        send(SpeechIpc.MSG_START)
    }

    fun stopHoldSession(discard: Boolean = false) {
        if (!holding.compareAndSet(true, false)) return
        if (discard) discarding.set(true)
        uiJob?.cancel()
        uiJob = null
        val messenger = synchronized(connectLock) { speechMessenger }
        if (messenger != null) {
            runCatching { messenger.send(SpeechIpc.message(SpeechIpc.MSG_STOP)) }
        } else {
            finishSession()
        }
    }

    private fun finishSession() {
        val service = serviceRef?.get()
        service?.scope?.launch(Dispatchers.Main) {
            val text = composingText.getAndSet(null)
            if (!discarding.get() && !text.isNullOrBlank()) {
                service.activeInputConnection()?.setComposingText(toDisplayText(text), 1)
            }
            service.activeInputConnection()?.finishComposingText()
            SpeechUiBridge.onDone?.invoke()
            resetState()
        } ?: resetState()
    }

    private fun cancelSession() {
        holding.set(false)
        uiJob?.cancel()
        uiJob = null
        val service = serviceRef?.get()
        val runUi = Runnable {
            runCatching {
                SpeechUiBridge.onFailed?.invoke() ?: SpeechUiBridge.onDone?.invoke()
            }
        }
        if (service != null) {
            ContextCompat.getMainExecutor(service).execute(runUi)
        } else {
            runUi.run()
        }
        resetState()
    }

    private fun resetState() {
        holding.set(false)
        discarding.set(false)
        uiJob?.cancel()
        uiJob = null
        serviceRef?.clear()
        serviceRef = null
    }


    private fun notifyModelMissing(context: Context, provider: ModelProvider) {
        val cb = SpeechUiBridge.onModelMissing ?: return
        ContextCompat.getMainExecutor(context).execute { cb(provider) }
    }

    private fun findModelFiles(dir: File, qnn: Boolean): Boolean {
        val tokens = File(dir, "tokens.txt")
        if (!tokens.isFile) return false
        val files = dir.listFiles().orEmpty().filter { it.isFile }
        val ext = if (qnn) "bin" else "onnx"
        fun pick(prefix: String) = files.firstOrNull {
            it.extension.equals(ext, ignoreCase = true) && it.nameWithoutExtension.contains(
                prefix, ignoreCase = true
            )
        }
        return pick("encoder") != null && pick("decoder") != null && pick("joiner") != null
    }
}