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
 * 采集中途失败（read 报错/被外部停录）走 [onCaptureFailed]（采集
 * 线程收尾时回调），与构建失败的 [onInitFailed] 分开：前者已有
 * 音频在外，收尾口径是结算已录部分，不是整场作废。
 */
class VoiceRecorder(
    context: Context,
    private val onChunk: (tag: Int, chunk: ByteArray) -> Unit,
    private val onAmplitude: (tag: Int, amplitude: Float) -> Unit,
    private val onInitFailed: (tag: Int) -> Unit,
    private val onCaptureFailed: (tag: Int) -> Unit,
    private val onFocusLost: (tag: Int) -> Unit,
) {
    private val appContext = context.applicationContext

    /** 本会话代次标签：客户端在 start() 前写入，回调原样带回供验代次 */
    @Volatile
    var sessionTag: Int = 0

    /**
     * 单次采集的停止控制：每次 [start] 新建一份、由采集线程捕获。
     * 停止只作用于「当前」这份，旧线程永远读自己捕获的那份——新
     * start 不会把已停的旧线程复活（共享单一 stopRequested 时，
     * 旧线程卡在 buildRecorder 期间 stop 停不到它、start 又把标志
     * 复位，旧线程启动后检查失效、与新线程双录）。
     */
    private class CaptureControl {
        @Volatile
        var stop = false
    }

    /** 当前采集的控制对象（[start]/[stop]/焦点监听据此作用于当前代） */
    @Volatile
    private var currentControl: CaptureControl? = null

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
            // 与旧服务端同纪律：焦点被抢只停采集（置当前控制对象＋停
            // AudioRecord 让阻塞 read 返回），会话如何收尾由客户端
            // 经 onFocusLost 统一决定，这里不碰会话状态
            currentControl?.stop = true
            audioRecord?.runCatching { stop() }
            if (!focusLostNotified) {
                focusLostNotified = true
                onFocusLost(sessionTag)
            }
        }
    }

    /**
     * 幂等启动：当前代采集线程仍在跑（其控制对象未被停）时直接
     * 返回；只剩一个已被要求停止、正在退出的旧线程时照常另起新
     * 线程。每次启动新建一份 [CaptureControl] 并由新线程捕获：旧
     * 线程读的是自己那份（早已被停），哪怕它此前卡在
     * buildRecorder 里、stop 没停到它，启动后检查也会命中、立即
     * 收尾，永不与新线程双录。共享字段（[audioRecord]/焦点）由各
     * 线程按身份认领，迟到的旧线程不覆盖新线程已写入的字段，其
     * 资源只走局部释放；它迟到的块带旧代次标签，客户端按代次丢弃。
     */
    @Synchronized
    fun start() {
        val current = currentControl
        if (captureThread != null && current != null && !current.stop) return
        val control = CaptureControl()
        currentControl = control
        focusLostNotified = false
        val tag = sessionTag
        captureThread = Thread({ runCapture(tag, control) }, "VoiceRecorder").also { it.start() }
    }

    /**
     * 幂等停止（不阻塞调用方）：只置当前控制对象的停止标志并停
     * AudioRecord 使阻塞 read 返回，采集线程随即自行退出并在
     * finally 里释放资源。此前这里还要 join 等线程退出才返回——
     * 调用方在输入法主线程上（松手收尾），真机上 AudioRecord
     * 停止慢时能把浮层关闭拖后约一秒。停止后队列定格改由客户端
     * 的发送锁保证：本方法返回后迟到块至多一块、且只能进入待发
     * 队列、不会越过随后的 STOP。
     */
    @Synchronized
    fun stop() {
        currentControl?.stop = true
        audioRecord?.runCatching { stop() }
    }

    private fun runCapture(tag: Int, control: CaptureControl) {
        var recorder: AudioRecord? = null
        var myFocusRequest: AudioFocusRequest? = null
        // 采集中途失败（read 报错/被外部停录）：finally 收尾时经
        // onCaptureFailed 通知客户端结算已录部分。此前这两条退出
        // 路径静默无声，界面假装仍在录音、后续语音全丢
        var captureFailed = false
        try {
            recorder = buildRecorder()
            if (recorder == null) {
                Timber.e("VoiceRecorder: AudioRecord init failed (both sources)")
                onInitFailed(tag)
                return
            }
            // 共享字段按身份认领：只在字段空闲时写入——迟到的旧
            // 线程（卡在 buildRecorder 后才回来）不得覆盖新线程已
            // 写入的字段，它的 recorder 只保局部引用、走局部释放
            synchronized(this) {
                if (audioRecord == null) audioRecord = recorder
            }
            // 已被停的旧线程不再申请焦点：申请动作本身会把新线程的
            // 焦点登记顶掉（同一监听收到 LOSS 会停掉新会话），而它
            // 申请完也只是立即收尾，纯有害无收益
            if (control.stop) return
            // 焦点在采集线程内申请、同一线程 finally 释放（平移旧口径）；
            // 申请到的句柄本地持有，共享字段只在空闲时认领发布（见
            // requestAudioFocus），迟到线程不得回读字段冒认别人的句柄
            myFocusRequest = requestAudioFocus()
            recorder.startRecording()
            if (control.stop) {
                // 启动期间停止已到达：读的是本线程捕获的控制对象，
                // 新会话的 start 不会把它复活（每代一份，见类注释）
                return
            }

            val bytes = ByteArray(SpeechIpc.CHUNK_BYTES)
            val shortChunk = ShortArray(SpeechIpc.CHUNK_SAMPLES)
            while (!control.stop &&
                recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING
            ) {
                val count = recorder.read(bytes, 0, bytes.size)
                if (count < 0) {
                    Timber.e("VoiceRecorder: recorder.read error count=%d", count)
                    captureFailed = true
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
                if (control.stop) break
                onAmplitude(tag, maxAmp / 32768f)
                onChunk(tag, bytes.copyOf(count))
            }
            // 循环因 recordingState 被外部翻转而退出（系统/其他应用
            // 停了录音）：非本方停止、非正常结束，同属采集中途失败
            if (!control.stop &&
                recorder.recordingState != AudioRecord.RECORDSTATE_RECORDING
            ) {
                captureFailed = true
            }
        } catch (t: Throwable) {
            Timber.e(t, "VoiceRecorder: capture failed")
            // 采集线程异常与构建失败同口径：通知客户端按启动失败收尾
            if (!control.stop) onInitFailed(tag)
        } finally {
            recorder?.runCatching { stop() }
            recorder?.runCatching { release() }
            if (audioRecord === recorder) audioRecord = null
            // 只释放本会话申请的焦点（平移旧纪律，防误放新会话的焦点）：
            // 赢得共享字段认领的走字段释放；没赢得的（迟到旧线程）按
            // 本地句柄直接释放自己申请的那份，不碰共享字段
            val focusRequest = myFocusRequest
            if (focusRequest != null) {
                if (audioFocusRequest === focusRequest) {
                    abandonAudioFocus()
                } else {
                    runCatching {
                        val am = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                            am.abandonAudioFocusRequest(focusRequest)
                        }
                    }.onFailure {
                        Timber.w("VoiceRecorder abandonAudioFocus(local) failed: %s", it.message)
                    }
                }
            } else if (audioFocusRequest == null) {
                // 无请求对象的旧系统路径：字段里没有别人的请求才释放
                abandonAudioFocus()
            }
            synchronized(this) {
                if (captureThread === Thread.currentThread()) captureThread = null
            }
            // 中途失败通知放收尾最后：资源已释放、状态已定格，客户
            // 端据此结算已录部分（stop 已先行到达时不重复打扰——
            // 松手结算自会处理截断音频）
            if (captureFailed && !control.stop) onCaptureFailed(tag)
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

    /**
     * 申请音频焦点，返回本次申请到的请求句柄（旧系统无句柄返回
     * null）。共享字段（[audioFocusRequest]/[audioFocusHeld]）只在
     * 空闲时认领发布：迟到的旧线程申请到的句柄只保本地，由其
     * finally 按本地句柄释放，不覆盖新线程已发布的字段。
     */
    private fun requestAudioFocus(): AudioFocusRequest? {
        var created: AudioFocusRequest? = null
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
                created = request
                val granted =
                    am.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
                synchronized(this) {
                    if (audioFocusRequest == null) {
                        audioFocusRequest = request
                        audioFocusHeld = granted
                    }
                }
            } else {
                @Suppress("DEPRECATION")
                val granted = am.requestAudioFocus(
                    audioFocusListener,
                    AudioManager.STREAM_VOICE_CALL,
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE,
                ) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
                synchronized(this) {
                    if (!audioFocusHeld) audioFocusHeld = granted
                }
            }
        }.onFailure { Timber.w("VoiceRecorder requestAudioFocus failed: %s", it.message) }
        return created
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
