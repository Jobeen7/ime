package com.jobeen.ime.base.speech

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.core.content.ContextCompat
import timber.log.Timber
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

/**
 * 客户端（主进程）本地录音：长按命中的第一时间就起录音，不等 :speech
 * 进程绑定与引擎加载（音频先行）。采集到的 PCM 块经 [onChunk] 交给
 * SherpaSpeechClient 保留/推送，引擎就绪前说话不丢失。
 *
 * 构建与焦点逻辑平移自旧服务端采集管线，口径保持一致：
 * - MIC → VOICE_RECOGNITION 双源试建，未初始化成功的实例立即释放；
 * - 音频焦点在采集线程内申请、本线程 finally 释放（覆盖正常停止/
 *   异常/焦点被抢全部出口）；焦点被抢只停采集，会话收尾走
 *   [onFocusLost] 回到客户端统一处理，不在此动会话状态。
 *
 * 回调都带启动时快照的 [sessionTag]（由客户端在 start 前写入本会话
 * 代次）：stop 后迟到的块/事件可由客户端按代次丢弃，不串入下一会话。
 * onChunk/onAmplitude 在采集线程回调；onInitFailed/onFocusLost 的
 * 线程随触发源（采集线程/焦点回调线程），客户端须自行回主线程。
 */
class VoiceRecorder(
    context: Context,
    private val onChunk: (tag: Int, chunk: ByteArray) -> Unit,
    private val onAmplitude: (tag: Int, amplitude: Float) -> Unit,
    private val onInitFailed: (tag: Int) -> Unit,
    private val onFocusLost: (tag: Int) -> Unit,
) {
    private val appContext = context.applicationContext

    /** 本会话代次标签：客户端在 start() 前写入，回调原样带回供验代次 */
    @Volatile
    var sessionTag: Int = 0

    @Volatile
    private var stopRequested = false

    @Volatile
    private var captureThread: Thread? = null

    @Volatile
    private var audioRecord: AudioRecord? = null

    private var audioFocusRequest: AudioFocusRequest? = null
    private var audioFocusHeld = false

    @Volatile
    private var focusLostNotified = false

    private val audioFocusListener = AudioManager.OnAudioFocusChangeListener { change ->
        if (change == AudioManager.AUDIOFOCUS_LOSS ||
            change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT ||
            change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK
        ) {
            // 与旧服务端同纪律：焦点被抢只停采集（置停止标志＋停
            // AudioRecord 让阻塞 read 返回），会话如何收尾由客户端
            // 经 onFocusLost 统一决定，这里不碰会话状态
            stopRequested = true
            audioRecord?.runCatching { stop() }
            if (!focusLostNotified) {
                focusLostNotified = true
                onFocusLost(sessionTag)
            }
        }
    }

    /**
     * 幂等启动：真正在跑的采集线程在场时直接返回；只剩一个已被
     * 要求停止、正在退出的旧线程时照常另起新线程——旧线程的
     * AudioRecord 已被 stop，其循环靠 recordingState 变化必然退出，
     * 退出清理全部按实例身份复检，不会碰新线程的状态；它迟到的块
     * 带旧代次标签，客户端按代次丢弃。
     */
    @Synchronized
    fun start() {
        if (captureThread != null && !stopRequested) return
        stopRequested = false
        focusLostNotified = false
        val tag = sessionTag
        captureThread = Thread({ runCapture(tag) }, "VoiceRecorder").also { it.start() }
    }

    /**
     * 幂等停止（不阻塞调用方）：只置停止标志并停 AudioRecord 使阻塞
     * read 返回，采集线程随即自行退出并在 finally 里释放资源。
     * 此前这里还要 join 等线程退出才返回——调用方在输入法主线程上
     * （松手收尾），真机上 AudioRecord 停止慢时能把浮层关闭拖后
     * 约一秒。停止后队列定格改由客户端的发送锁保证：本方法返回后
     * 迟到块至多一块、且只能进入待发队列、不会越过随后的 STOP。
     */
    @Synchronized
    fun stop() {
        stopRequested = true
        audioRecord?.runCatching { stop() }
    }

    private fun runCapture(tag: Int) {
        var recorder: AudioRecord? = null
        var myFocusRequest: AudioFocusRequest? = null
        try {
            recorder = buildRecorder()
            if (recorder == null) {
                Timber.e("VoiceRecorder: AudioRecord init failed (both sources)")
                onInitFailed(tag)
                return
            }
            audioRecord = recorder
            // 焦点在采集线程内申请、同一线程 finally 释放（平移旧口径）
            requestAudioFocus()
            myFocusRequest = audioFocusRequest
            recorder.startRecording()
            if (stopRequested) {
                // 启动期间停止已到达：立即收尾（finally 统一释放）
                return
            }

            val bytes = ByteArray(SpeechIpc.CHUNK_BYTES)
            val shortChunk = ShortArray(SpeechIpc.CHUNK_SAMPLES)
            while (!stopRequested &&
                recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING
            ) {
                val count = recorder.read(bytes, 0, bytes.size)
                if (count < 0) {
                    Timber.e("VoiceRecorder: recorder.read error count=%d", count)
                    break
                }
                if (count == 0) {
                    Thread.sleep(10)
                    continue
                }
                val sampleCount = count / 2
                ByteBuffer.wrap(bytes, 0, count).order(ByteOrder.LITTLE_ENDIAN)
                    .asShortBuffer().get(shortChunk, 0, sampleCount)
                var maxAmp = 0
                for (i in 0 until sampleCount) {
                    val v = abs(shortChunk[i].toInt())
                    if (v > maxAmp) maxAmp = v
                }
                // 已 stop 的迟到块不再外送：块归属以代次＋停止标志双判
                if (stopRequested) break
                onAmplitude(tag, maxAmp / 32768f)
                onChunk(tag, bytes.copyOf(count))
            }
        } catch (t: Throwable) {
            Timber.e(t, "VoiceRecorder: capture failed")
            // 采集线程异常与构建失败同口径：通知客户端按启动失败收尾
            if (!stopRequested) onInitFailed(tag)
        } finally {
            recorder?.runCatching { stop() }
            recorder?.runCatching { release() }
            if (audioRecord === recorder) audioRecord = null
            // 只释放本会话申请的焦点（平移旧纪律，防误放新会话的焦点）
            if (audioFocusRequest === myFocusRequest) abandonAudioFocus()
            synchronized(this) {
                if (captureThread === Thread.currentThread()) captureThread = null
            }
        }
    }

    /** 双源试建（平移旧服务端）：先 MIC 后 VOICE_RECOGNITION，未初始化成功的实例必须释放防泄漏 */
    private fun buildRecorder(): AudioRecord? {
        // 使用点复查（平移旧服务端开录前的同款纪律）：入口层虽已查过，
        // 权限可能在会话开始前一瞬被撤销，缺权限时按构建失败收尾，
        // 不把 SecurityException 留给 AudioRecord 构造去抛
        if (ContextCompat.checkSelfPermission(
                appContext, Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            Timber.e("VoiceRecorder: RECORD_AUDIO not granted at build time")
            return null
        }
        val channel = AudioFormat.CHANNEL_IN_MONO
        val format = AudioFormat.ENCODING_PCM_16BIT
        val minBuffer = AudioRecord.getMinBufferSize(SpeechIpc.SAMPLE_RATE, channel, format)
        val bufferSize = minBuffer.coerceAtLeast(SpeechIpc.CHUNK_BYTES * 2)
        return listOf(
            MediaRecorder.AudioSource.MIC, MediaRecorder.AudioSource.VOICE_RECOGNITION
        ).firstNotNullOfOrNull { source ->
            val candidate = runCatching {
                AudioRecord(source, SpeechIpc.SAMPLE_RATE, channel, format, bufferSize)
            }.getOrNull()
            if (candidate != null && candidate.state == AudioRecord.STATE_INITIALIZED) {
                candidate
            } else {
                candidate?.runCatching { release() }
                null
            }
        }
    }

    private fun requestAudioFocus() {
        runCatching {
            val am = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                val request = AudioFocusRequest.Builder(
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE
                ).setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                ).setOnAudioFocusChangeListener(audioFocusListener).build()
                audioFocusRequest = request
                audioFocusHeld =
                    am.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            } else {
                @Suppress("DEPRECATION")
                audioFocusHeld = am.requestAudioFocus(
                    audioFocusListener,
                    AudioManager.STREAM_VOICE_CALL,
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE,
                ) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            }
        }.onFailure { Timber.w("VoiceRecorder requestAudioFocus failed: %s", it.message) }
    }

    private fun abandonAudioFocus() {
        if (!audioFocusHeld && audioFocusRequest == null) return
        audioFocusHeld = false
        runCatching {
            val am = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val request = audioFocusRequest
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O &&
                request != null
            ) {
                am.abandonAudioFocusRequest(request)
            } else {
                @Suppress("DEPRECATION")
                am.abandonAudioFocus(audioFocusListener)
            }
        }.onFailure { Timber.w("VoiceRecorder abandonAudioFocus failed: %s", it.message) }
        audioFocusRequest = null
    }
}
