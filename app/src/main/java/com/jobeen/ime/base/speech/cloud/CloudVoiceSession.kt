package com.jobeen.ime.base.speech.cloud

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.inputmethod.InputConnection
import com.jobeen.ime.base.speech.VoiceRecorder
import com.jobeen.ime.input.ImeInputMethodService
import timber.log.Timber

/** 云端会话失败原因（只作原因码，不携带识别文本与密钥）。 */
enum class CloudSessionFailure { GENERIC, NETWORK, BUDGET }

/** 录音采集抽象：生产用 VoiceRecorder 适配，单测用内存假实现。 */
interface CloudRecorder {
    fun start()
    fun stop()
}

/** 插件脚本要求 16kHz：录音器固定 16kHz（见 SpeechIpc），与契约一致。 */
class VoiceRecorderAdapter(
    context: Context,
    onChunk: (ByteArray) -> Unit,
    onAmplitude: (Float) -> Unit,
    onInitFailed: () -> Unit,
    onCaptureFailed: () -> Unit,
    onFocusLost: () -> Unit,
) : CloudRecorder {
    private val recorder = VoiceRecorder(
        context = context,
        onChunk = { _, chunk -> onChunk(chunk) },
        onAmplitude = { _, amp -> onAmplitude(amp) },
        onInitFailed = { onInitFailed() },
        onCaptureFailed = { onCaptureFailed() },
        onFocusLost = { onFocusLost() },
    )

    override fun start() = recorder.start()
    override fun stop() = recorder.stop()
}

/**
 * 输入连接写入口：启动时捕获的连接对象是唯一合法写入目标。
 * 结算时当前连接已换（切了输入框）则丢弃写入，绝不把云端文本
 * 写进新的输入框。写入只在主线程发生。
 */
class ServiceTextSink(
    private val service: ImeInputMethodService,
    private val captured: InputConnection?,
) : CloudTextSink {
    private fun live(): InputConnection? {
        val current = service.activeInputConnection()
        if (current !== captured) {
            Timber.w("cloudVoice sink dropped connection changed")
            return null
        }
        return current
    }

    override fun setPreview(text: String) {
        live()?.setComposingText(text, 1)
    }

    override fun commit(text: String) {
        live()?.commitText(text, 1)
    }

    override fun clearPreview() {
        live()?.setComposingText("", 1)
    }
}

/**
 * 云端语音会话：录音采集 → 插件宿主 → 文本收尾。
 *
 * 时序：宿主就绪后才开始采集（就绪前喂的音频宿主会丢弃）；
 * 插件自结束／失败／超时停用时先停采集、再结算、再通知视图；
 * 视图显式停止时视图自己负责界面收尾，会话只结算文本。
 * 停止后一切宿主与采集回调一律丢弃，不串入下一会话。
 */
class CloudVoiceSession(
    private val plugin: PluginStore.InstalledPlugin,
    private val settings: Map<String, String>,
    private val secrets: SecretStore,
    private val sink: CloudTextSink,
    private val listener: Listener,
    private val markBudgetDisabled: () -> Unit,
    private val recorderFactory: (
        onChunk: (ByteArray) -> Unit,
        onAmplitude: (Float) -> Unit,
        onInitFailed: () -> Unit,
        onCaptureFailed: () -> Unit,
        onFocusLost: () -> Unit,
    ) -> CloudRecorder,
    private val networkAvailable: () -> Boolean,
    private val transport: NetworkTransport = OkHttpNetworkTransport(),
    private val codec: Base64Codec = AndroidBase64Codec,
    private val displayTransform: (String) -> String = { it },
    private val graceTimeoutMs: Long = GRACE_TIMEOUT_MS,
    private val graceScheduler: ((Long, () -> Unit) -> (() -> Unit))? = null,
) {
    companion object {
        /**
         * 显式停止后的宽限：服务端收到 stop 指令后回 final／ended
         * 通常数百毫秒；宽限内到的尾巴照常结算，超时则按已到
         * partial 结算，不无限等（2026-10-31 一句话末尾丢字：
         * 旧语义停录即杀 socket，服务端在途尾巴必丢）。
         */
        const val GRACE_TIMEOUT_MS = 1500L
    }
    interface Listener {
        fun onStarted()
        fun onAmplitude(amp: Float)
        fun onDone()
        fun onFailed(reason: CloudSessionFailure)
    }

    private val settle = CloudVoiceSettle(sink, displayTransform)

    @Volatile
    private var stopped = false

    /** 宽限收尾中：已停采集、已发 stop 指令，未结算、未杀会话。 */
    @Volatile
    private var stopping = false

    /** 宽限超时取消句柄（注入调度器时由调用方提供，默认走主线程 Handler）。 */
    private var graceCancel: (() -> Unit)? = null

    private var host: PluginHost? = null
    private var recorder: CloudRecorder? = null

    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    /** 是否已出过 partial（窗口层空探针 discard 的判断依据）。 */
    val hasPartial: Boolean get() = settle.hasPartial

    fun start() {
        if (stopped) return
        if (!networkAvailable()) {
            Timber.w("cloudVoice session no network plugin=%s", plugin.meta.id)
            listener.onFailed(CloudSessionFailure.NETWORK)
            return
        }
        val created = PluginHost(
            plugin = plugin,
            settings = settings,
            secrets = secrets,
            transport = transport,
            listener = object : PluginHost.Listener {
                override fun onReady() {
                    if (stopped) return
                    recorder = recorderFactory(
                        { chunk -> onRecorderChunk(chunk) },
                        { amp -> postMain { listener.onAmplitude(amp) } },
                        { onRecorderInitFailed() },
                        { onRecorderCaptureFailed() },
                        { onRecorderFocusLost() },
                    )
                    recorder?.start()
                    postMain { listener.onStarted() }
                }

                override fun onPartial(text: String) {
                    if (stopped) return
                    postMain {
                        Timber.i(
                            "cloudVoice partial plugin=%s len=%d",
                            plugin.meta.id, text.length,
                        )
                        settle.onPartial(text)
                    }
                }

                override fun onFinal(text: String) {
                    if (stopped) return
                    postMain {
                        Timber.i(
                            "cloudVoice final plugin=%s len=%d",
                            plugin.meta.id, text.length,
                        )
                        settle.onFinal(text)
                    }
                }

                override fun onError() {
                    if (stopped) return
                    stopRecorder()
                    postMain {
                        if (stopping) {
                            // 宽限中服务端报错：不再清预览（用户已松手，
                            // 预览即已说出的话），按已到 partial 结算收尾。
                            settle.stop(discard = false)
                            finalizeGrace()
                        } else {
                            settle.onError()
                            listener.onFailed(CloudSessionFailure.GENERIC)
                        }
                    }
                }

                override fun onEnded() {
                    if (stopped) return
                    stopRecorder()
                    postMain {
                        if (stopping) {
                            settle.onEnded()
                            finalizeGrace()
                        } else {
                            settle.onEnded()
                            listener.onDone()
                        }
                    }
                }

                override fun onBudgetDisabled() {
                    if (stopped) return
                    // 宽限中 stop 指令本身超预算：旧语义下 stop 后回调
                    // 本就被 stopped 门丢弃，故此处同样不标记禁用、
                    // 不通知失败，只按已到 partial 结算收尾。
                    if (stopping) {
                        postMain {
                            settle.stop(discard = false)
                            finalizeGrace()
                        }
                        return
                    }
                    markBudgetDisabled()
                    stopRecorder()
                    postMain {
                        settle.onError()
                        listener.onFailed(CloudSessionFailure.BUDGET)
                    }
                }
            },
            base64 = codec,
        )
        host = created
        created.start()
    }

    /**
     * 显式停止：界面收尾归视图，会话只结算文本。
     * discard=true（探针／主动丢弃）：旧语义不变，立即清预览并杀会话。
     * discard=false（正常松手）：优雅停止——停采集、发 stop 指令但
     * 不立即结算、不杀会话；宽限内到的 final／ended 照常结算，
     * 宽限超时或服务端终结时再按已到 partial 结算并杀会话。
     * 宽限中再调 stop(discard=true)，丢弃胜出：取消宽限、清预览、杀会话。
     */
    fun stop(discard: Boolean) {
        if (stopped) return
        if (stopping) {
            if (!discard) return
            runCatching { graceCancel?.invoke() }
            graceCancel = null
            stopping = false
            stopped = true
            stopRecorder()
            settle.stop(discard = true)
            host?.kill()
            host = null
            return
        }
        if (discard) {
            stopped = true
            stopRecorder()
            settle.stop(discard = true)
            host?.stop()
            host = null
            return
        }
        stopping = true
        stopRecorder()
        val currentHost = host
        if (currentHost == null) {
            stopping = false
            stopped = true
            settle.stop(discard = false)
            return
        }
        currentHost.requestStop()
        val scheduler = graceScheduler ?: { delay, task -> defaultGraceScheduler(delay, task) }
        graceCancel = runCatching { scheduler(graceTimeoutMs) { finalizeGraceFromTimeout() } }.getOrNull()
    }

    /** 默认宽限调度：主线程 Handler 延时＋可取消（与注入调度器同口径）。 */
    private fun defaultGraceScheduler(delay: Long, task: () -> Unit): () -> Unit {
        val runnable = Runnable { task() }
        runCatching { mainHandler.postDelayed(runnable, delay) }
        return { runCatching { mainHandler.removeCallbacks(runnable) } }
    }

    /** 宽限收尾：结算已由各终结分支完成，此处只杀会话、关宽限门。 */
    private fun finalizeGrace() {
        if (!stopping) return
        stopping = false
        stopped = true
        runCatching { graceCancel?.invoke() }
        graceCancel = null
        host?.kill()
        host = null
    }

    /** 宽限超时：按已到 partial 结算，再走宽限收尾。 */
    private fun finalizeGraceFromTimeout() {
        if (!stopping) return
        settle.stop(discard = false)
        finalizeGrace()
    }

    private fun stopRecorder() {
        runCatching { recorder?.stop() }
        recorder = null
    }

    private fun onRecorderChunk(chunk: ByteArray) {
        if (stopped) return
        val encoded = runCatching { codec.encodeToString(chunk) }.getOrNull() ?: return
        host?.audio(encoded)
    }

    private fun onRecorderInitFailed() {
        if (stopped) return
        stopped = true
        stopRecorder()
        postMain {
            settle.onError()
            listener.onFailed(CloudSessionFailure.GENERIC)
        }
    }

    /** 采集中途失败：已录部分按正常松手结算（与离线口径一致）。 */
    private fun onRecorderCaptureFailed() {
        if (stopped) return
        stopped = true
        stopRecorder()
        postMain {
            settle.stop(discard = false)
            listener.onDone()
        }
    }

    /** 焦点被抢：停采集、按正常松手结算。 */
    private fun onRecorderFocusLost() {
        if (stopped) return
        stopped = true
        stopRecorder()
        postMain {
            settle.stop(discard = false)
            listener.onDone()
        }
    }

    /**
     * 回主线程投递：已在主线程直接跑，否则经 Handler。
     * JVM 单测里 Looper 是未 mock 的空桩（调用即抛），此时
     * 直接跑——测试替身本就与调用线程同处，不存在线程问题。
     */
    private fun postMain(task: () -> Unit) {
        val onMain = runCatching {
            Looper.myLooper() == Looper.getMainLooper()
        }.getOrDefault(true)
        if (onMain) {
            task()
        } else {
            mainHandler.post(task)
        }
    }
}
