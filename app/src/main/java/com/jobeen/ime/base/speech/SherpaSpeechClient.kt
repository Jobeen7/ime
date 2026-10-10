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
import android.os.SystemClock
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

    // 已结算代次：finishSession 与新会话接管结算都在入口按代次声明，
    // 同一代次只结算一次——DONE 超时强制结算与迟到的 DONE/FINAL 交错
    // 到达时，后到的一律被代次去重挡下，不再重复上屏/收尾
    @Volatile
    private var settledGen = 0

    // 当前连接的原始 binder 与其死亡监听引用：binder 死亡即 :speech
    // 进程死亡，死亡回调里立即作废连接（不等下一次 send 失败才发现）；
    // 每次连接新 binder 注册新监听，旧监听随旧 binder 死亡自动失效
    @Volatile
    private var speechBinder: IBinder? = null

    @Volatile
    private var deathRecipient: IBinder.DeathRecipient? = null

    fun isHolding(): Boolean = holding.get()

    private var uiJob: Job? = null
    private var serviceRef: WeakReference<ImeInputMethodService>? = null

    private var speechMessenger: Messenger? = null
    private val clientMessenger = Messenger(
        Handler(
            Looper.getMainLooper(),
            { msg ->
                val gen = msg.data?.getInt(SpeechIpc.KEY_GEN, 0) ?: 0
                // 存活探针回信先行处理：预检探针的代次是保留值（非会话
                // 代次），不能走下面的会话代际校验
                if (msg.what == SpeechIpc.MSG_PONG) {
                    onPong(gen)
                    return@Handler true
                }
                // 代际校验：回信代次与当前会话不一致（旧会话迟到/会话已收尾）直接丢弃。
                // gen==0 是对端未带代次的兼容情形（同版本发布，不应发生），放行保底。
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
                if (binder != null) {
                    // 死亡监听：回调里按 binder 身份复检，旧 binder 的
                    // 迟到死亡通知不得误伤已重绑的新连接
                    val recipient = IBinder.DeathRecipient {
                        Handler(Looper.getMainLooper()).post {
                            if (speechBinder === binder) onSpeechBinderDied()
                        }
                    }
                    if (runCatching { binder.linkToDeath(recipient, 0) }.isSuccess) {
                        speechBinder = binder
                        deathRecipient = recipient
                    }
                }
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
                    // 探针同理只重放仍有效的：会话探针须仍是当前代次，
                    // 预检探针须仍是待回音的那一条
                    if (what == SpeechIpc.MSG_PING && gen != activeGen && gen != prestartProbeGen) {
                        continue
                    }
                    val msg = SpeechIpc.message(what, gen = gen)
                    if (what == SpeechIpc.MSG_START || what == SpeechIpc.MSG_PING) {
                        msg.replyTo = clientMessenger
                    }
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
            } else if (probeArmed) {
                // 待发 PING 刚在上面真正发出：把绑定阶段的 5 秒预算
                // 换成从此刻起算的 1 秒回音窗，避免冷启动被误判僵尸
                probeHandler.removeCallbacks(probeTimeoutRunnable)
                probeHandler.postDelayed(probeTimeoutRunnable, PROBE_TIMEOUT_MS)
            }
            if (!needsRebind && !holding.get() && !awaitingDone.get()) {
                // 预热绑定（initialize/preStartSync 的 LOAD）同样进入空闲
                // 卸载计时：无任何会话活动时 3 分钟后一并解绑回收，不再
                // 只有走过会话收尾的连接才计时（会话绑定的连接走到这里
                // 时 holding 已置位，不会误挂计时）
                scheduleIdleUnbind()
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            Timber.d("SpeechCli %s", "onServiceDisconnected")
            synchronized(connectLock) { speechMessenger = null }
            speechBinder = null
            deathRecipient = null
            // 录音中、或已停录在等 DONE 时断开都要收尾（分流见 handleLinkLost）
            handleLinkLost()

            //异常退出。清理资源
//            runCatching {
//                App.speechModelDir.listFiles()?.forEach { it.deleteRecursively() }
//            }.onFailure { Timber.d("SpeechCli clearSpeechModelDir failed %s", it.message) }
        }
    }

    private fun removePending(what: Int) {
        synchronized(connectLock) { pending.removeAll { it.first == what } }
    }

    /**
     * :speech 进程死亡（binderDied，主线程执行）：立即作废本地连接。
     * 启动途中死亡（录音尚未开始）走作废重绑+重发本会话并重新探活
     * 自愈；其余情形与 onServiceDisconnected 同款分流收尾。
     */
    /** binder 死亡后「启动途中复活」的时刻窗：崩溃循环时不能无限复活拉起进程 */
    private val deathResurrectTimes = ArrayDeque<Long>()
    private const val DEATH_RESURRECT_WINDOW_MS = 60_000L
    private const val DEATH_RESURRECT_MAX = 3

    private fun onSpeechBinderDied() {
        Timber.w("SpeechCli speech binder died")
        speechBinder = null
        deathRecipient = null
        synchronized(connectLock) { speechMessenger = null }
        lastLivenessMs = 0L
        if (holding.get() && !recordingStarted) {
            // 复活计次：:speech 若在启动阶段反复崩溃（如模型损坏），
            // 每次死亡都立即重绑重发会形成崩溃循环，且每次死亡都先于
            // 探针超时到达、把超时自愈短路掉。窗口内超限即放弃本次
            // 会话，按连接丢失收尾，等用户下一次长按重新开始。
            val now = SystemClock.uptimeMillis()
            while (deathResurrectTimes.isNotEmpty() &&
                now - deathResurrectTimes.first() > DEATH_RESURRECT_WINDOW_MS
            ) {
                deathResurrectTimes.removeFirst()
            }
            if (deathResurrectTimes.size >= DEATH_RESURRECT_MAX) {
                Timber.e("SpeechCli binder died too often; giving up this session")
                handleLinkLost()
                return
            }
            deathResurrectTimes.addLast(now)
            forceDisconnect()
            send(SpeechIpc.MSG_LOAD)
            send(SpeechIpc.MSG_START, activeGen)
            sendPing(activeGen)
            probeHandler.removeCallbacks(probeTimeoutRunnable)
            probeHandler.postDelayed(probeTimeoutRunnable, PROBE_TIMEOUT_MS)
        } else {
            handleLinkLost()
        }
    }

    /**
     * 连接丢失时的会话分流收尾（断连回调与 binder 死亡共用）：
     * 录音中丢失——服务端会话已随进程死亡，待定文字没有定稿来源，
     * 按取消清屏；等 DONE 中丢失——服务端大概率已完成识别，
     * composingText 里的最新文本就是最终结果，按 DONE 超时同款
     * 强制 finishSession 定稿，不清屏（一律 cancelSession 清屏会把
     * 已识别出的文字在断连瞬间吞掉）。
     */
    private fun handleLinkLost() {
        when {
            holding.get() -> cancelSession()
            awaitingDone.get() -> {
                disarmDoneTimeout()
                finishSession()
            }
        }
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
        // PING 只是存活探针，不是会话活动：不取消空闲卸载计时。
        // 旧实现任何 send 都取消计时，预检 PING 发出后 PONG 又不重挂，
        // 用过一次语音后模型实际永不卸载、:speech 进程常驻数百 MB。
        if (what != SpeechIpc.MSG_PING) cancelIdleUnbind()
        val messenger = synchronized(connectLock) { speechMessenger }
        if (messenger != null) {
            val msg = SpeechIpc.message(what, gen = gen)
            if (what == SpeechIpc.MSG_START || what == SpeechIpc.MSG_PING) {
                msg.replyTo = clientMessenger
            }
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

    // 存活探针（整段替代旧 6 秒启动看门狗）：看门狗只能等满 6 秒才
    // 发现僵尸连接。改为：START 发出后同发一条 PING，1 秒内收到
    // PONG 或 RECORDING_STARTED 即确认对端存活、解除探针，只留
    // 15 秒长兜底纯防引擎初始化真卡死；1 秒无回音 → 作废重绑、重发
    // LOAD/START/PING 再探一次；第二次仍无回音 → cancelSession 失败
    // 收尾，不留死会话。binder 死亡另有死亡监听即时处理（见
    // onSpeechBinderDied），探针负责 binder 未死但对端冻结/消息
    // 石沉大海的僵尸连接。v1.1.5.1 的 send() 发送失败即时重连保留
    // 不动，与探针互为补充（send 失败是本地即时可知，探针管送达后无回音）。
    private val probeHandler = Handler(Looper.getMainLooper())
    private const val PROBE_TIMEOUT_MS = 1000L

    /** 未绑定时探针的绑定阶段预算：冷启动 :speech 进程（拉起+加载）远超 1 秒，计时须从 PING 真正发出起算 */
    private const val BIND_PROBE_TIMEOUT_MS = 5000L
    private const val START_FALLBACK_MS = 15000L

    /** 本会话是否已收到 RECORDING_STARTED（死亡监听据此分流启动途中/录音中） */
    @Volatile
    private var recordingStarted = false

    /** 最近一次确认对端存活的时刻（PONG/RECORDING_STARTED 更新），预检节流用 */
    @Volatile
    private var lastLivenessMs = 0L

    private var probeArmed = false
    private var probeRetryUsed = false

    private val probeTimeoutRunnable = Runnable {
        if (!holding.get() || recordingStarted) return@Runnable
        if (!probeRetryUsed) {
            probeRetryUsed = true
            Timber.w("SpeechCli start probe timeout; reconnect + resend START/PING")
            forceDisconnect()
            send(SpeechIpc.MSG_LOAD)
            send(SpeechIpc.MSG_START, activeGen)
            sendPing(activeGen)
            repostProbeTimeout()
        } else {
            Timber.e("SpeechCli start probe failed twice; cancel session")
            cancelSession()
        }
    }

    // 经函数中转重挂探针：Runnable 初始化块内直接自引用会触发编译器
    // 类型推断自引用递归，须隔一层函数调用
    private fun repostProbeTimeout() {
        probeHandler.postDelayed(probeTimeoutRunnable, PROBE_TIMEOUT_MS)
    }

    private val startFallbackRunnable = Runnable {
        if (!holding.get() || recordingStarted) return@Runnable
        Timber.e("SpeechCli start fallback timeout; cancel session")
        cancelSession()
    }

    private fun armStartProbe() {
        probeArmed = true
        probeRetryUsed = false
        probeHandler.removeCallbacks(probeTimeoutRunnable)
        probeHandler.removeCallbacks(startFallbackRunnable)
        // 计时口径：已绑定时 PING 随 START 即时发出，1 秒回音窗成立；
        // 未绑定时 PING 还在待发队列里，先给绑定阶段 5 秒预算，待
        // onServiceConnected 真正重放 PING 后再换回 1 秒窗（见彼处）
        val bound = synchronized(connectLock) { speechMessenger } != null
        probeHandler.postDelayed(
            probeTimeoutRunnable,
            if (bound) PROBE_TIMEOUT_MS else BIND_PROBE_TIMEOUT_MS,
        )
    }

    private fun disarmStartProbe() {
        probeArmed = false
        probeHandler.removeCallbacks(probeTimeoutRunnable)
        probeHandler.removeCallbacks(startFallbackRunnable)
    }

    /** 确认存活：更新存活时刻；探针武装中则解除短探针、换 15 秒长兜底 */
    private fun onLivenessConfirmed() {
        lastLivenessMs = SystemClock.uptimeMillis()
        if (!probeArmed) return
        probeArmed = false
        probeHandler.removeCallbacks(probeTimeoutRunnable)
        if (holding.get() && !recordingStarted) {
            probeHandler.postDelayed(startFallbackRunnable, START_FALLBACK_MS)
        }
    }

    private fun sendPing(gen: Int) {
        send(SpeechIpc.MSG_PING, gen)
    }

    private fun onPong(gen: Int) {
        // 预检探针回音：只更新存活时刻，不碰会话状态
        if (gen != 0 && gen == prestartProbeGen) {
            prestartProbeGen = 0
            probeHandler.removeCallbacks(prestartProbeTimeoutRunnable)
            lastLivenessMs = SystemClock.uptimeMillis()
            // 探针不算会话活动：无会话时把空闲卸载计时挂回去，
            // 与 send() 不再取消 PING 的计时配套
            if (!holding.get()) scheduleIdleUnbind()
            return
        }
        if (gen != 0 && gen == activeGen) onLivenessConfirmed()
    }

    // 键盘弹起预检探针：已绑定但久未确认存活时发一条 PING，1 秒无
    // 回音即判僵尸连接、作废重绑预热（见 preStartSync）。代次用保留
    // 值与会话代次（自 1 递增）区分，服务端原样带回。
    private const val PRESTART_PROBE_GEN = -1

    @Volatile
    private var prestartProbeGen = 0

    private val prestartProbeTimeoutRunnable = Runnable {
        if (prestartProbeGen == 0) return@Runnable
        prestartProbeGen = 0
        // 预检等待期间用户已开始会话：会话自带探针在管，不许预检拆连接
        if (holding.get()) return@Runnable
        Timber.w("SpeechCli prestart probe timeout; force reconnect + warm up")
        forceDisconnect()
        send(SpeechIpc.MSG_LOAD)
    }

    /** 语音专名纠错词表：与识别解耦的独立小文件存储，损坏即整体不纠。 */
    private val correctionStore by lazy {
        VoiceCorrectionStore.shared(File(appContext.filesDir, VoiceCorrectionStore.FILE_NAME))
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
        recordingStarted = true
        // RECORDING_STARTED 本身就是存活证据：解除启动探针
        onLivenessConfirmed()
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

    /** 已绑定连接的存活预检间隔：距上次确认存活超过此时长才发探针 */
    private const val LIVENESS_CHECK_INTERVAL_MS = 30_000L

    /** 未绑定时补预热的最短间隔：防绑定进行中被高频弹起叠发，也不闩死 */
    private const val WARMUP_RETRY_INTERVAL_MS = 5_000L

    /** 最近一次补预热发起时刻，未绑定分支的节流（自愈式，不用闩锁） */
    @Volatile
    private var lastWarmupAttemptMs = 0L

    fun preStartSync(context: Context) {
        // 预启动时机后移到键盘弹起：App 冷启动（onCreate）阶段不再
        // 拉起 :speech 进程——键盘还没露面时这笔开销纯属浪费，且主进程
        // 被系统单独拉起（设置页/同步等）时根本用不到语音。
        // 门控：只对真正用过语音的用户预热；从未用过时，首次长按
        // 语音键走 send() 的按需绑定路径（稍慢一次）。
        // 每次调用都做状态检查（调用点在 onStartInput/onStartInputView、
        // 频率高，故必须轻量）：
        // - 未绑定（空闲卸载后、进程死亡后）：重新预热。旧实现这里挂了
        //   「进程内只预热一次」的闩锁，长闲后绑定早已被空闲卸载拆掉、
        //   闩锁却不许补，长按只能当场付全程冷启动（拉进程+加载模型
        //   数秒）。改为按 5 秒节流补发：绑定进行中不会叠发，绑定迟迟
        //   不成也会自动重试，不留闩死状态。
        // - 已绑定：仅在距上次确认存活超过 30 秒才发一条 PING 探针，
        //   免得每次切输入框都发探针；探针 1 秒无 PONG 即判僵尸连接、
        //   作废重绑预热，让死连接在用户长按之前就自愈。
        val bound = synchronized(connectLock) { speechMessenger } != null
        if (!bound) {
            if (!hasUsedVoice(context)) return
            val now = SystemClock.uptimeMillis()
            if (now - lastWarmupAttemptMs < WARMUP_RETRY_INTERVAL_MS) return
            lastWarmupAttemptMs = now
            send(SpeechIpc.MSG_LOAD)
            return
        }
        if (holding.get()) {
            // 会话进行中：会话自身的回信就是存活证据
            lastLivenessMs = SystemClock.uptimeMillis()
            return
        }
        if (SystemClock.uptimeMillis() - lastLivenessMs <= LIVENESS_CHECK_INTERVAL_MS) return
        if (prestartProbeGen != 0) return // 上一条预检探针还在等回音
        prestartProbeGen = PRESTART_PROBE_GEN
        sendPing(PRESTART_PROBE_GEN)
        probeHandler.removeCallbacks(prestartProbeTimeoutRunnable)
        probeHandler.postDelayed(prestartProbeTimeoutRunnable, PROBE_TIMEOUT_MS)
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
        // 上一会话未结算的待定文本先按快照就地结算（不等 DONE），再
        // 开始新会话——直接清掉 composingText 会把已识别出的文字吞掉
        settlePreviousSession()
        // 预检探针若还在等回音就此作废：本会话自带探针，预检超时
        // 不得在会话途中拆连接
        prestartProbeGen = 0
        probeHandler.removeCallbacks(prestartProbeTimeoutRunnable)
        val gen = genCounter.incrementAndGet()
        activeGen = gen
        serviceRef = WeakReference(service)
        composingText.set(null)
        discarding.set(false)
        recordingStarted = false

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
        // 同发存活探针：1 秒无 PONG/RECORDING_STARTED 即判僵尸连接自愈
        sendPing(gen)
        armStartProbe()
    }

    fun stopHoldSession(discard: Boolean = false) {
        if (!holding.compareAndSet(true, false)) return
        disarmStartProbe()
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

    /**
     * 新会话开始前的上一会话接管结算：上一会话松手后 DONE 未回（或
     * 回信丢失）时用户又长按，旧实现直接清 composingText 开始新会话，
     * 未定稿文本被吞。改为先按快照就地结算（不等 DONE）：待弃文本
     * 同步清屏，待定稿文本走与 finishSession 同款的异步纠错/转换尾
     * 上屏；结算在入口按代次声明，迟到的 DONE 此后被代次去重挡下。
     * 状态字段不在此复位——紧接着的 startHoldSession 会逐项覆写。
     */
    private fun settlePreviousSession() {
        val prevGen = activeGen
        if (prevGen == 0) return
        if (!awaitingDone.get() && composingText.get() == null && !discarding.get()) return
        // 声明本代已结算：其 DONE/超时此后不得二次结算
        settledGen = prevGen
        val service = serviceRef?.get()
        val prevText = composingText.getAndSet(null)
        val prevDiscard = discarding.get()
        disarmDoneTimeout()
        if (prevDiscard) {
            // 待弃文本同步清屏（与 stopHoldSession 的即时清屏同款），
            // 赶在新会话开始前完成，不留异步尾巴误清新会话的待定文字
            service?.activeInputConnection()?.let { ic ->
                ic.setComposingText("", 1)
                ic.finishComposingText()
            }
            return
        }
        if (service == null) return
        service.scope?.launch(Dispatchers.Main) {
            // 验代次：本结算未被更新的结算取代、且当前会话未被用户
            // 丢弃时才上屏；新会话（本代的直接后继）已开始是预期
            // 情形，不作废本尾
            if (settledGen != prevGen || discarding.get()) return@launch
            val ic = service.activeInputConnection()
            if (!prevText.isNullOrBlank()) {
                val corrected = withContext(Dispatchers.Default) { correctionStore.correct(prevText) }
                val displayText = withContext(Dispatchers.Default) { toDisplayText(corrected.text) }
                ic?.setComposingText(displayText, 1)
            }
            ic?.finishComposingText()
            // 不触发 onDone、不武装沉淀：新会话正在进行，UI 收尾与
            // 沉淀观察归新会话自己的结算
        }
    }

    /**
     * 会话结算单一入口（DONE 回信 / DONE 超时 / 等 DONE 断连分流都
     * 走这里）：入口先同步快照（text/discarding/service/gen）并同步
     * 复位本代状态，纠错/繁简/上屏走异步尾；同一代次只结算一次，
     * 超时强制结算与迟到的 DONE 交错到达时后到的一律在入口被代次
     * 去重丢弃，不重复上屏。
     */
    private fun finishSession() {
        val gen = activeGen
        if (gen != 0 && gen == settledGen) return
        settledGen = gen
        // 同步快照 + 同步复位：快照之后到达的回信会被代次校验丢弃，
        // 异步尾只认这份快照，不再读共享状态
        val service = serviceRef?.get()
        val text = composingText.getAndSet(null)
        val discard = discarding.get()
        resetState()
        if (service == null) return
        service.scope?.launch(Dispatchers.Main) {
            // 回到主线程先验代次：结算期间用户已开始新会话（activeGen
            // 既非本代、也非空闲的 0）则本尾整体作废——输入框归新
            // 会话，其上屏与收尾不归本代管
            if (activeGen != 0 && activeGen != gen) return@launch
            val ic = service.activeInputConnection()
            var committed: CorrectResult? = null
            if (discard) {
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
            // 触发 UI 收尾前再验一次代次：本尾中途在后台做过纠错与
            // 繁简转换（两次挂起），期间用户可能已经停掉旧会话并开始
            // 新会话——此时调 onDone 会把新会话的录音标志清掉，松手
            // 不再发 STOP。开头验过不算数，临门一脚必须复验。
            if (activeGen != 0 && activeGen != gen) return@launch
            SpeechUiBridge.onDone?.invoke()
            // 不再 resetState：本代状态已在入口同步复位，此处再复位
            // 会误伤可能已经开始的新会话
        }
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
        disarmStartProbe()
        // 所有收尾路径（finish/cancel/权限中止）都经这里：等 DONE 的
        // 标志与超时任务不得残留到下一会话
        disarmDoneTimeout()
        // 会话收尾：代次清零，此后到达的任何带代次回信都会被 handler 丢弃
        activeGen = 0
        uiJob?.cancel()
        uiJob = null
        serviceRef?.clear()
        serviceRef = null
        // 会话结束进入空闲计时；预热绑定的连接在 onServiceConnected
        // 里同样武装此计时，无会话活动时一并在 3 分钟后回收
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