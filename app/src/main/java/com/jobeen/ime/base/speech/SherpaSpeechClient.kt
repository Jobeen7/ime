package com.jobeen.ime.base.speech

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
import com.jobeen.ime.base.util.TraditionalConverter
import com.jobeen.ime.base.util.appContext
import com.jobeen.ime.data.App
import com.jobeen.ime.data.manager.CandidateManager
import com.jobeen.ime.input.ImeInputMethodService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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

    // 等 DONE 窗口：stopHoldSession 发出 STOP 后、服务端回 DONE 前的这段
    // 时间。DONE 是唯一收尾信号，若 :speech 进程在此期间死亡或回信丢失，
    // 待定文字会永久挂在输入框里。故置位 awaitingDone 并挂超时：超时未
    // 收到 DONE 就在客户端强制 finishSession（服务端大概率已完成识别，
    // composingText 里的最新文本就是最终结果）。
    private val awaitingDone = AtomicBoolean(false)
    private val doneTimeoutHandler = Handler(Looper.getMainLooper())
    private const val DONE_TIMEOUT_MS = 3000L
    private val doneTimeoutRunnable = Runnable {
        if (!awaitingDone.compareAndSet(true, false)) return@Runnable
        // 超时期间用户已开始新会话：这声超时属于旧会话，不得结算新会话
        if (holding.get()) return@Runnable
        Timber.w("SpeechCli DONE timeout, force finishSession")
        finishSession()
    }

    private fun armDoneTimeout() {
        awaitingDone.set(true)
        doneTimeoutHandler.removeCallbacks(doneTimeoutRunnable)
        doneTimeoutHandler.postDelayed(doneTimeoutRunnable, DONE_TIMEOUT_MS)
    }

    private fun disarmDoneTimeout() {
        awaitingDone.set(false)
        doneTimeoutHandler.removeCallbacks(doneTimeoutRunnable)
    }

    /**
     * 文本到达信号（合并通道）：partial/final 写入 composingText 后发一个
     * 信号，消费协程据此上屏。未消费时只保留一个信号，天然合并高频到达；
     * 无信号时消费协程挂起等待，替代旧实现每 50ms 空转轮询 composingText。
     */
    private val composingSignal = Channel<Unit>(Channel.CONFLATED)

    // 会话代际：每次 startHoldSession 递增并记为 activeGen，随 START 发给服务端，
    // 服务端回信带回同一代次；handler 只接受 activeGen 的回信，旧会话迟到的
    // FINAL/ERROR/DONE 一律丢弃，不能污染新会话（结束/取消后 activeGen 清零，
    // 会话收尾之后到达的残余回信同样被丢弃）
    private val genCounter = java.util.concurrent.atomic.AtomicInteger(0)

    @Volatile
    private var activeGen = 0

    fun isHolding(): Boolean = holding.get()

    private var uiJob: Job? = null
    private var serviceRef: WeakReference<ImeInputMethodService>? = null

    private var speechMessenger: Messenger? = null
    private val clientMessenger = Messenger(
        Handler(
            Looper.getMainLooper(),
            { msg ->
                // 代际校验：回信代次与当前会话不一致（旧会话迟到/会话已收尾）直接丢弃。
                // gen==0 是对端未带代次的兼容情形（同版本发布，不应发生），放行保底。
                val gen = msg.data?.getInt(SpeechIpc.KEY_GEN, 0) ?: 0
                if (gen != 0 && gen != activeGen) {
                    Timber.d("SpeechCli drop stale msg what=%d gen=%d active=%d", msg.what, gen, activeGen)
                    return@Handler true
                }
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
    private val pending = mutableListOf<Pair<Int, Int>>()

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val messenger = Messenger(binder)
            Timber.d("SpeechCli %s", "onServiceConnected")
            var needsRebind = false
            synchronized(connectLock) {
                speechMessenger = messenger
                val actions: List<Pair<Int, Int>> = pending.toList()
                pending.clear()
                var failedAt = -1
                for (index in actions.indices) {
                    val what: Int = actions[index].first
                    val gen: Int = actions[index].second
                    // 待绑定的 START 若在绑定完成前已被松手/取消，绝不能重放：
                    // 否则客户端以为没在录，服务端却开始一段停不下来的幽灵录音。
                    // 代次也必须仍是当前会话：旧会话的 START 更不能重放。
                    if (what == SpeechIpc.MSG_START && (!holding.get() || gen != activeGen)) {
                        continue
                    }
                    val msg = SpeechIpc.message(what, gen = gen)
                    if (what == SpeechIpc.MSG_START) msg.replyTo = clientMessenger
                    if (runCatching { messenger.send(msg) }.isFailure) {
                        failedAt = index
                        break
                    }
                }
                if (failedAt >= 0) {
                    // 刚连上就发送失败（对端在绑定瞬间又被回收）：失败处起的
                    // 消息全部退回队列（下次重放时 START 的代次校验会再过滤
                    // 一遍，退回无害），连接整体作废重绑，不能假装发过。
                    for (i in failedAt until actions.size) {
                        val item: Pair<Int, Int> = actions[i]
                        pending.add(item)
                    }
                    speechMessenger = null
                    needsRebind = true
                }
            }
            if (needsRebind) {
                Timber.w("SpeechCli replay send failed; force reconnect")
                // 在 connection 对象内部不能按名引用 connection 本身（编译器
                // 类型推断自引用递归），this 即本连接对象
                runCatching { appContext.unbindService(this) }
                postBind()
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            Timber.d("SpeechCli %s", "onServiceDisconnected")
            synchronized(connectLock) { speechMessenger = null }
            // 录音中、或已停录在等 DONE 时断开都要收尾：后者 holding 已
            // false，只判 holding 会漏掉，DONE 永远等不到、待定文字挂死
            if (holding.get() || awaitingDone.get()) cancelSession()

            //异常退出。清理资源
//            runCatching {
//                App.speechModelDir.listFiles()?.forEach { it.deleteRecursively() }
//            }.onFailure { Timber.d("SpeechCli clearSpeechModelDir failed %s", it.message) }
        }
    }

    private fun removePending(what: Int) {
        synchronized(connectLock) { pending.removeAll { it.first == what } }
    }

    // 空闲卸载：:speech 进程与已加载模型（数百 MB 级）此前绑定后永不解绑、
    // 全程常驻。会话收尾后 3 分钟无新活动即解绑，系统可回收 :speech
    // 进程与模型内存；下次使用时 send() 会重新绑定（多一次冷启动成本）。
    private val idleHandler = Handler(Looper.getMainLooper())
    private const val IDLE_UNBIND_DELAY_MS = 3 * 60 * 1000L
    private val idleUnbindRunnable = Runnable {
        if (holding.get()) return@Runnable
        synchronized(connectLock) {
            speechMessenger = null
            pending.clear()
        }
        runCatching { appContext.unbindService(connection) }
        Timber.d("SpeechCli idle timeout: unbound :speech service")
    }

    private fun scheduleIdleUnbind() {
        idleHandler.removeCallbacks(idleUnbindRunnable)
        idleHandler.postDelayed(idleUnbindRunnable, IDLE_UNBIND_DELAY_MS)
    }

    private fun cancelIdleUnbind() {
        idleHandler.removeCallbacks(idleUnbindRunnable)
    }

    /** 作废当前连接：清掉本地 messenger 并向框架解绑，下一次 send 必然走全新绑定。 */
    private fun forceDisconnect() {
        synchronized(connectLock) { speechMessenger = null }
        runCatching { appContext.unbindService(connection) }
    }

    private fun postBind() {
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

    /** 无可用连接时：消息入待发队列并异步发起绑定，连上后由 onServiceConnected 重放。 */
    private fun enqueueAndBind(what: Int, gen: Int) {
        synchronized(connectLock) { pending.add(what to gen) }
        Timber.d("SpeechCli %s", "bind requested what=$what")
        postBind()
    }

    private fun send(what: Int, gen: Int = 0) {
        cancelIdleUnbind()
        val messenger = synchronized(connectLock) { speechMessenger }
        if (messenger != null) {
            val msg = SpeechIpc.message(what, gen = gen)
            if (what == SpeechIpc.MSG_START) msg.replyTo = clientMessenger
            if (runCatching { messenger.send(msg) }.isSuccess) return
            // 发送失败 = binder 已失效：长时间空闲后 :speech 进程被系统
            // 回收/冻结、服务实例已销毁，但本地 speechMessenger 仍非空，
            // onServiceDisconnected 在部分系统上也不触发。旧实现 runCatching
            // 静默吞掉失败：START 就此丢失，且此后每次 send 都继续信任这条
            // 死连接、永不重绑——语音永久拉不起，直到重启进程类操作碰巧
            // 把连接换掉。改为作废连接、重绑并把消息走待发队列重发，故障自愈。
            Timber.w("SpeechCli send failed what=%d; force reconnect", what)
            forceDisconnect()
            // STOP 丢失不重发到新连接：其收尾由调用方的 DONE 超时兜底完成；
            // 把旧 STOP 排进新连接反而可能误停刚开始的新会话
            if (what == SpeechIpc.MSG_STOP) return
        }
        enqueueAndBind(what, gen)
    }

    // 启动看门狗：START 发出后若迟迟没有 RECORDING_STARTED（连接僵尸、
    // 对端进程被冻结、消息石沉大海都会这样），旧实现无限期静默等待，
    // 用户只看到「拉不起」且无任何失败反馈。6 秒未开始 → 强制重连并
    // 重发一次 START；再失败 → 走 cancelSession 正常失败收尾（UI 收起、
    // 状态清干净），不留死会话。正常冷启动（绑服务+加载模型）在 6 秒
    // 内完成，看门狗不会误触发；万一误触发也只是多一次重连，不丢会话。
    private val startWatchdogHandler = Handler(Looper.getMainLooper())
    private const val START_WATCHDOG_MS = 6000L
    private var startWatchdogRetryUsed = false
    private val startWatchdogRunnable = Runnable {
        if (!holding.get()) return@Runnable
        if (!startWatchdogRetryUsed) {
            startWatchdogRetryUsed = true
            Timber.w("SpeechCli start watchdog: no RECORDING_STARTED, reconnect + resend START")
            forceDisconnect()
            send(SpeechIpc.MSG_LOAD)
            send(SpeechIpc.MSG_START, activeGen)
            armStartWatchdog()
        } else {
            Timber.e("SpeechCli start watchdog: retry exhausted, cancel session")
            cancelSession()
        }
    }

    private fun armStartWatchdog() {
        startWatchdogHandler.removeCallbacks(startWatchdogRunnable)
        startWatchdogHandler.postDelayed(startWatchdogRunnable, START_WATCHDOG_MS)
    }

    private fun disarmStartWatchdog() {
        startWatchdogHandler.removeCallbacks(startWatchdogRunnable)
    }

    /** 语音专名纠错词表：与识别解耦的独立小文件存储，损坏即整体不纠。 */
    private val correctionStore by lazy {
        VoiceCorrectionStore(File(appContext.filesDir, "voice_corrections.tsv"))
    }

    /** 输入法服务的选区探针回调：把编辑观察转给纠错沉淀会话。 */
    fun onEditorTextProbed(service: ImeInputMethodService) {
        VoiceCorrectionSession.onInputChanged(service)
    }

    /** 打字上屏词段回调（引擎搭配学习同一入口）：供纠错沉淀配对正形。 */
    fun notifyTypedSegment(segment: String) {
        VoiceCorrectionSession.onTypedWord(segment)
    }

    /** 输入视图结束/切换输入框：解除纠错沉淀观察。 */
    fun onInputViewFinished() {
        VoiceCorrectionSession.disarm()
    }

    private fun onRecordingStarted() {
        disarmStartWatchdog()
        runCatching { SpeechUiBridge.onRecordingStarted?.invoke() }
    }

    private fun onPartial(text: String?) {
        if (discarding.get()) return
        text?.takeIf { it.isNotEmpty() }?.let {
            composingText.set(it)
            composingSignal.trySend(Unit)
        }
    }

    private fun onFinal(text: String?) {
        if (discarding.get()) return
        text?.takeIf { it.isNotEmpty() }?.let {
            composingText.set(it)
            composingSignal.trySend(Unit)
        }
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
        disarmDoneTimeout()
        finishSession()
    }

    fun isQnnRuntimeSupported(context: Context): Boolean =
        android.os.Build.SUPPORTED_ABIS.contains("arm64-v8a")

    /** 进程内只预启动一次（触发点在键盘首次弹出，见 ImeInputMethodService） */
    private val preStartDone = AtomicBoolean(false)

    fun preStartSync(context: Context) {
        // 预启动时机后移到首次弹出键盘：App 冷启动（onCreate）阶段不再
        // 拉起 :speech 进程——键盘还没露面时这笔开销纯属浪费，且主进程
        // 被系统单独拉起（设置页/同步等）时根本用不到语音。
        // 门控不变：只对真正用过语音的用户预启动；从未用过时，首次长按
        // 语音键走 send() 的按需绑定路径（稍慢一次）。
        if (!preStartDone.compareAndSet(false, true)) return
        if (!hasUsedVoice(context)) return
        send(SpeechIpc.MSG_LOAD)
    }

    private const val PREFS_NAME = "speech_client"
    private const val KEY_VOICE_USED = "voice_used"

    private fun hasUsedVoice(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_VOICE_USED, false)

    private fun markVoiceUsed(context: Context) {
        if (!hasUsedVoice(context)) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_VOICE_USED, true).apply()
        }
    }

    fun initialize(context: Context) {
        send(SpeechIpc.MSG_LOAD)
    }

    fun isModelReady(context: Context): Boolean {
        val dir = App.speechModelDir
        return findModelFiles(dir, qnn = true) || findModelFiles(dir, qnn = false)
    }

    suspend fun downloadModel(
        context: Context,
        onProgress: (ModelDownloader.Progress) -> Unit = {},
        onExtract: (current: Long, total: Long) -> Unit = { _, _ -> },
    ): Boolean = ModelDownloader.download(context, onProgress, onExtract)

    /** 检查服务端是否有新版语音模型 */
    suspend fun checkModelUpdate(context: Context): ModelDownloader.UpdateCheckResult =
        ModelDownloader.checkForUpdate(context)

    fun startHoldSession(service: ImeInputMethodService) {
        // 密码框禁用语音（入口层已拦，这里再兜一层，任何调用方都绕不过）
        if (com.jobeen.ime.base.util.InputFieldPolicy.isPasswordField(service.currentInputEditorInfo)) {
            Timber.i("startHoldSession blocked: password field")
            return
        }
        if (!holding.compareAndSet(false, true)) return
        Timber.i("startHoldSession")
        // 新会话开始：上一会话若还留着等 DONE 的超时任务，一并清掉，
        // 防止它在新会话中途误触发 finishSession
        disarmDoneTimeout()
        val gen = genCounter.incrementAndGet()
        activeGen = gen
        serviceRef = WeakReference(service)
        composingText.set(null)
        discarding.set(false)

        uiJob = service.scope?.launch(Dispatchers.Main) {
            // 事件驱动上屏：信号到达才工作，第一份文本立即上屏；每轮后留
            // 50ms 节流窗口，窗口内的多次到达被通道合并为一次上屏，避免
            // 逐字 partial 高频刷新输入框。无新文本时挂起、零空转。
            for (signal in composingSignal) {
                if (!holding.get()) break
                // 繁简转换（含首次词表解析加载）挪到后台算完再回主线程
                // 上屏：循环仍逐条串行处理，出字顺序与 50ms 节流时机不变。
                // 先看不取、转换完再 CAS 清除：转换是挂起点，松手 cancel
                // 若落在转换途中，先取走的文本会随协程丢失（finishSession
                // 只能提交上一笔已显示内容，服务端 FINAL 缺席时无兜底）；
                // CAS 失败说明期间来了更新的 partial，不用旧结果覆盖，
                // 留给下一轮或 finishSession。CAS 成功后到上屏之间无
                // 挂起点，取消插不进来。
                val text = composingText.get()
                if (text != null) {
                    val displayText = withContext(Dispatchers.Default) { toDisplayText(text) }
                    if (composingText.compareAndSet(text, null)) {
                        service.activeInputConnection()?.setComposingText(displayText, 1)
                    }
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
            abortSessionForPermission()
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

        markVoiceUsed(service)
        send(SpeechIpc.MSG_LOAD)
        send(SpeechIpc.MSG_START, gen)
        startWatchdogRetryUsed = false
        armStartWatchdog()
    }

    fun stopHoldSession(discard: Boolean = false) {
        if (!holding.compareAndSet(true, false)) return
        disarmStartWatchdog()
        // 服务尚未绑定时 START 还在待发队列里：先撤掉，绑定完成后不得重放
        removePending(SpeechIpc.MSG_START)
        if (discard) {
            discarding.set(true)
            // 取消一成立立刻在主线程清空待定文字，文字马上消失，不依赖服务端回信。
            serviceRef?.get()?.let { service ->
                service.scope?.launch(Dispatchers.Main) {
                    service.activeInputConnection()?.setComposingText("", 1)
                }
            }
        }
        uiJob?.cancel()
        uiJob = null
        val messenger = synchronized(connectLock) { speechMessenger }
        if (messenger != null) {
            // 先 arm 再发 STOP：DONE 可能极快返回，先置位才不会被 onDone
            // 的 disarm 错过、反留下无人认领的超时任务
            armDoneTimeout()
            if (runCatching { messenger.send(SpeechIpc.message(SpeechIpc.MSG_STOP)) }.isFailure) {
                // STOP 没送达（连接已死）：作废连接，下次启动走全新绑定；
                // 本次收尾由上面已武装的 DONE 超时强制完成，不受影响
                forceDisconnect()
            }
        } else {
            finishSession()
        }
    }

    private fun finishSession() {
        val service = serviceRef?.get()
        service?.scope?.launch(Dispatchers.Main) {
            val text = composingText.getAndSet(null)
            val ic = service.activeInputConnection()
            var committed: CorrectResult? = null
            if (discarding.get()) {
                // 取消：先删掉已流式显示的待定文字，再收尾，否则 finishComposingText 会把待定文字确认上屏。
                ic?.setComposingText("", 1)
            } else if (!text.isNullOrBlank()) {
                // 定稿先过专名同音纠错（词表与规则均为简体，必须赶在
                // 繁简转换之前），再在后台转换，回主线程上屏收尾，
                // DONE 后的转换→上屏→finishComposingText 顺序不变
                val corrected = withContext(Dispatchers.Default) { correctionStore.correct(text) }
                val displayText = withContext(Dispatchers.Default) { toDisplayText(corrected.text) }
                ic?.setComposingText(displayText, 1)
                committed = corrected
            }
            ic?.finishComposingText()
            // 上屏后武装纠错沉淀：观察用户对这段文本的亲手修改，
            // 学成词对供下次定稿自动纠正（无观察条件时内部自行放弃）
            committed?.let {
                VoiceCorrectionSession.arm(service, correctionStore, it.text, it.applied)
            }
            SpeechUiBridge.onDone?.invoke()
            resetState()
        } ?: resetState()
    }

    /**
     * 首次无麦克风权限时的单独收尾：这是等用户授权的正常分支，不是会话
     * 失败——不清屏、不触发 onFailed（否则 UI 会闪一下失败态）。服务端
     * 此时尚无会话（START 还没发），无需 STOP。授权后用户再长按一次即可。
     */
    private fun abortSessionForPermission() {
        holding.set(false)
        uiJob?.cancel()
        uiJob = null
        runCatching { SpeechUiBridge.onDone?.invoke() }
        resetState()
        // resetState 会启动空闲卸载计时，但此刻 :speech 可能压根没绑定；
        // 授权通常很快，取消计时避免刚授权就把预热连接卸掉
        cancelIdleUnbind()
    }

    private fun cancelSession() {
        holding.set(false)
        removePending(SpeechIpc.MSG_START)
        // 必须通知服务端终止会话：旧实现只做本地收尾，服务端 sessionActive
        // 残留时下一次 START 会在服务端被 CAS 拒绝、静默无反馈
        synchronized(connectLock) { speechMessenger }?.let { messenger ->
            if (runCatching { messenger.send(SpeechIpc.message(SpeechIpc.MSG_STOP)) }.isFailure) {
                // 同 stopHoldSession：STOP 没送达说明连接已死，作废以免下次启动继续踩
                forceDisconnect()
            }
        }
        uiJob?.cancel()
        uiJob = null
        val service = serviceRef?.get()
        val runUi = Runnable {
            runCatching {
                // 出错/断开：先清空残留的待定文字，再收尾，避免下划线文字残留。
                service?.activeInputConnection()?.let { ic ->
                    ic.setComposingText("", 1)
                    ic.finishComposingText()
                }
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
        disarmStartWatchdog()
        // 所有收尾路径（finish/cancel/权限中止）都经这里：等 DONE 的
        // 标志与超时任务不得残留到下一会话
        disarmDoneTimeout()
        // 会话收尾：代次清零，此后到达的任何带代次回信都会被 handler 丢弃
        activeGen = 0
        uiJob?.cancel()
        uiJob = null
        serviceRef?.clear()
        serviceRef = null
        // 会话结束进入空闲计时（preStartSync 的预热加载不经会话收尾，
        // 不在此计时；真正用过语音后的常驻才是要回收的大头）
        scheduleIdleUnbind()
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