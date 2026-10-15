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

    // DONE 超时不再固定一刀切（D4）：短句 3 秒足够，长句在低端机上
    // 服务端还要排空队列＋收尾解码，固定 3 秒会把会话按未解码完的
    // partial 截断结算。改为基础值＋按本会话保留块数线性预留：基础
    // 3s 覆盖回信往返与收尾固定开销；每块 40ms 音频按最坏约半实时
    // 的解码速率折算 20ms/块；封顶 10s 防服务端真死时用户久等——
    // 超时后按已有文本强制结算，已识别部分不丢。
    private const val DONE_TIMEOUT_BASE_MS = 3000L
    private const val DONE_TIMEOUT_PER_BLOCK_MS = 20L
    private const val DONE_TIMEOUT_MAX_MS = 10_000L
    private val doneTimeoutRunnable = Runnable {
        if (!awaitingDone.compareAndSet(true, false)) return@Runnable
        // 超时期间用户已开始新会话：这声超时属于旧会话，不得结算新会话
        if (holding.get()) return@Runnable
        Timber.w("SpeechCli DONE timeout, force finishSession")
        finishSession()
    }

    private fun doneTimeoutMs(): Long {
        val blocks = synchronized(audioSendLock) { sessionAudio.size }
        return (DONE_TIMEOUT_BASE_MS + blocks * DONE_TIMEOUT_PER_BLOCK_MS)
            .coerceAtMost(DONE_TIMEOUT_MAX_MS)
    }

    private fun armDoneTimeout() {
        awaitingDone.set(true)
        doneTimeoutHandler.removeCallbacks(doneTimeoutRunnable)
        doneTimeoutHandler.postDelayed(doneTimeoutRunnable, doneTimeoutMs())
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
                    // 唯一例外：上一代在排空期被新会话接管结算（快照先行
                    // 上屏）后迟到的 FINAL——其增量尾段经对账补上，不
                    // 整段丢弃（见 tryReconcileLateFinal）；其余一律丢弃
                    if (msg.what == SpeechIpc.MSG_FINAL && gen == lateFinalGen) {
                        tryReconcileLateFinal(gen, msg.data?.getString(SpeechIpc.KEY_TEXT))
                    } else {
                        Timber.d("SpeechCli drop stale msg what=%d gen=%d active=%d", msg.what, gen, activeGen)
                    }
                    return@Handler true
                }
                when (msg.what) {
                    SpeechIpc.MSG_RECORDING_STARTED -> onRecordingStarted(gen)
                    SpeechIpc.MSG_PARTIAL -> onPartial(msg.data.getString(SpeechIpc.KEY_TEXT))
                    SpeechIpc.MSG_FINAL -> onFinal(msg.data.getString(SpeechIpc.KEY_TEXT))
                    // MSG_AMPLITUDE 已废除：音量改由本地录音直接供给
                    // （见 onRecorderAmplitude），服务端不再发
                    SpeechIpc.MSG_ERROR -> onError()
                    SpeechIpc.MSG_DONE -> onDone(
                        msg.data?.getBoolean(SpeechIpc.KEY_AUDIO_DROPPED, false) ?: false
                    )
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
            // 连接代际自增：READY 处理据此识别「新连接上的全新服务端
            // 会话」，触发保留音频的偏移重绕全量重发（见 onRecordingStarted）
            connGen++
            if (holding.get()) {
                Timber.i(
                    "voice-start bound gen=%d connGen=%d elapsed=%dms",
                    activeGen, connGen, SystemClock.uptimeMillis() - sessionStartUptimeMs,
                )
            }
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
                    // 待绑定的 START 若在绑定完成前已被丢弃，绝不能重放：
                    // 否则客户端以为没在录，服务端却开始一段停不下来的幽灵录音。
                    // 代次也必须仍是当前会话：旧会话的 START 更不能重放。
                    // 例外：松手先于绑定完成时本会话记了 pendingFinish
                    //（等就绪结算）——此时 START 必须照常重放，READY 后
                    // 由其 pendingFinish 分支整段结算（见 stopHoldSession）
                    if (what == SpeechIpc.MSG_START &&
                        (gen != activeGen || (!holding.get() && !pendingFinish))
                    ) {
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
            if (!tryResurrectStartupSession()) handleLinkLost()
        } else {
            handleLinkLost()
        }
    }

    /**
     * 启动阶段死亡的复活：作废重绑＋重发本代 START 并重新探活，
     * 保留音频不丢（新连接 READY 后靠连接代际重绕重发）。复活计
     * 次：:speech 若在启动阶段反复崩溃（如模型损坏），每次死亡都
     * 立即重绑重发会形成崩溃循环，且每次死亡都先于探针超时到达、
     * 把超时自愈短路掉。窗口内超限返回 false，调用方按连接丢失
     * 收尾，等用户下一次长按重新开始。
     */
    private fun tryResurrectStartupSession(): Boolean {
        val now = SystemClock.uptimeMillis()
        while (deathResurrectTimes.isNotEmpty() &&
            now - deathResurrectTimes.first() > DEATH_RESURRECT_WINDOW_MS
        ) {
            deathResurrectTimes.removeFirst()
        }
        if (deathResurrectTimes.size >= DEATH_RESURRECT_MAX) {
            Timber.e("SpeechCli binder died too often; giving up this session")
            return false
        }
        deathResurrectTimes.addLast(now)
        forceDisconnect()
        send(SpeechIpc.MSG_LOAD)
        send(SpeechIpc.MSG_START, activeGen)
        sendPing(activeGen)
        // 复活重发后给新连接一份完整的启动截止：原截止自会话开始
        // 计时，进程死亡重拉可能已耗掉大半，不能让会话刚复活就被
        // 旧截止误杀；成功判据仍只是本代 READY（其到达后靠连接
        // 代际触发保留音频重绕重发，见 onRecordingStarted）
        armStartDeadline(activeGen)
        return true
    }

    /**
     * 连接丢失时的会话分流收尾（断连回调与 binder 死亡共用）：
     * 已松手待就绪（pendingFinish）——走启动复活，整句保留音频等
     * 新连接 READY 后由既有 pendingFinish 逻辑 flush＋STOP 定稿；
     * 录音中丢失——服务端会话已随进程死亡，待定文字没有定稿来源，
     * 按取消清屏；等 DONE 中丢失——服务端大概率已完成识别，
     * composingText 里的最新文本就是最终结果，按 DONE 超时同款
     * 强制 finishSession 定稿，不清屏（一律 cancelSession 清屏会把
     * 已识别出的文字在断连瞬间吞掉）。
     */
    private fun handleLinkLost() {
        when {
            // 已松手、未就绪、待 READY 后发 STOP 结算的状态：此前
            // holding/awaitingDone 两分支都不命中——不断连、不重
            // 发，保留的整句音频无人来取，只能等启动截止按失败
            // 丢弃，与仍按住时同情形有完整复活形成无理由落差。
            // cancelPending（用户已主动丢弃）不复活：仍由启动截止
            // 补 CANCEL 静默收尾
            pendingFinish && !cancelPending -> {
                if (!tryResurrectStartupSession()) finishSession()
            }
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

    // 启动截止（整段替代旧会话内探针拆连接）：旧实现 START 后
    // 1 秒（未绑定 5 秒）无回音即拆掉尚在飞的绑定重发、重挂只给
    // 1 秒、再超整场放弃——冷启动（拉进程＋加载模型）动辄数秒，
    // 探针预算与绑定/加载状态不对齐，好会话会被自己的探针拆死
    // 且全程零提示（审计 C1）。改为单一截止：自 startHoldSession
    // 起 12 秒内未收到本代 RECORDING_STARTED 即失败收尾；PING 只
    // 作存活证据（兼截止分流），不再驱动拆连接。截止触发按状态
    // 分流：用户已主动丢弃（cancelPending）→ 补 CANCEL 释放服务
    // 端占位后静默收尾；其余（仍按住、或松手等结果的
    // pendingFinish）→ 可见失败：置失败文案供窗口 toast、
    // cancelSession，全程无 PONG 时追加 forceDisconnect（进程级
    // 僵尸），收到过 PONG 则保留连接（引擎侧问题、留下次重试）。
    // binder 死亡另有死亡监听即时处理（见 onSpeechBinderDied，
    // 复活重发后会重新武装本截止）。
    private val probeHandler = Handler(Looper.getMainLooper())

    /** 预检探针（preStartSync）的回音窗；会话内探针已废除，仅此一处沿用 */
    private const val PROBE_TIMEOUT_MS = 1000L
    private const val START_DEADLINE_MS = 12_000L
    private const val START_FAILURE_HINT = "语音启动失败，请重试"
    private const val RECORDER_FAILURE_HINT = "录音异常，请检查麦克风"
    private const val AUDIO_TOO_LONG_HINT = "录音过长，已识别已录部分"
    private const val NO_SOUND_HINT = "未检测到声音，请检查麦克风"

    /** 本会话是否已收到 RECORDING_STARTED（死亡监听据此分流启动途中/录音中） */
    @Volatile
    private var recordingStarted = false

    /** 最近一次确认对端存活的时刻（PONG/RECORDING_STARTED 更新），预检节流用 */
    @Volatile
    private var lastLivenessMs = 0L

    /** 当前启动截止所属代次（0＝未武装）；READY/收尾时解除 */
    @Volatile
    private var startDeadlineGen = 0

    /** 本代是否收到过 PONG（截止分流：从未收到＝对端进程级僵尸，连连接一并作废） */
    @Volatile
    private var sessionPongSeen = false

    private val startDeadlineRunnable = Runnable { onStartDeadline() }

    private fun armStartDeadline(gen: Int) {
        startDeadlineGen = gen
        sessionPongSeen = false
        probeHandler.removeCallbacks(startDeadlineRunnable)
        probeHandler.postDelayed(startDeadlineRunnable, START_DEADLINE_MS)
    }

    private fun disarmStartDeadline() {
        startDeadlineGen = 0
        probeHandler.removeCallbacks(startDeadlineRunnable)
    }

    private fun onStartDeadline() {
        val gen = startDeadlineGen
        // 代次已过（会话已收尾/被新会话取代）或服务端已就绪：无事可做
        if (gen == 0 || gen != activeGen || sessionLive) return
        startDeadlineGen = 0
        if (cancelPending) {
            // 用户早已主动丢弃：补 CANCEL 释放服务端占位会话后静默
            // 收尾，不提示失败（能走到这里说明 READY 一直没来；其
            // DONE 回信若迟到会被 finishSession 的代次去重挡下）
            Timber.w("voice-start deadline: gen=%d discarded by user; release server session", gen)
            sendCancelNow(gen)
            finishSession()
            return
        }
        val noPong = !sessionPongSeen
        Timber.e(
            "voice-start deadline failed gen=%d reason=%s elapsed=%dms",
            gen,
            if (noPong) "no-pong" else "no-ready",
            SystemClock.uptimeMillis() - sessionStartUptimeMs,
        )
        startFailureHint = START_FAILURE_HINT
        cancelSession()
        if (noPong) forceDisconnect()
    }

    /** 确认存活：更新存活时刻（预检节流据此判定是否需要补探针） */
    private fun onLivenessConfirmed() {
        lastLivenessMs = SystemClock.uptimeMillis()
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
        if (gen != 0 && gen == activeGen) {
            // 会话探针回音：只记存活证据供启动截止分流，不再驱动拆连接
            sessionPongSeen = true
            onLivenessConfirmed()
        }
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
        // 预检等待期间用户已开始会话：会话自带启动截止在管，不许预检拆连接
        if (holding.get()) return@Runnable
        Timber.w("SpeechCli prestart probe timeout; force reconnect + warm up")
        forceDisconnect()
        send(SpeechIpc.MSG_LOAD)
    }

    // ── 音频先行：本地录音＋全量保留＋就绪后推送 ──────────────
    // 录音在长按命中即本地启动（VoiceRecorder），不再等绑定与引擎
    // 加载；音频块全量保留在 sessionAudio 直到会话结算，服务端
    // 就绪（RECORDING_STARTED，新语义＝推流会话已建好、可以收
    // 音频）后才按 sendOffset 顺序推送。连接代际变化（binder 死
    // 亡复活后服务端是全新会话）时偏移归零全量重发，无损恢复；
    // 同连接内重复 READY 不重绕。队列上限 3000 块（40ms/块 ≈
    // 120s），超限按可见失败收尾——保留是恢复的本钱，不能无限涨。
    // 全部块发送（READY kick/实时续发/收尾 flush）经 audioSendLock
    // 串行，STOP/CANCEL 的先后由 stop 路径与 READY 处理在同一把
    // 锁内决定，不会乱序。
    private const val MAX_SESSION_AUDIO_BLOCKS = 3000
    private const val AMPLITUDE_POST_INTERVAL_MS = 100L
    private val audioSendLock = Any()
    private val sessionAudio = ArrayDeque<ByteArray>()
    private var sendOffset = 0

    // 音频发送统一走专用单线程执行器（4.1）：READY 时的全量补发可达
    // 数千块，在主线程同步跑完会冻住界面、长时间霸占 binder 通道；
    // 主线程只做状态切换，实际发送由此线程串行完成。发送仍经
    // audioSendLock 与采集入队串行化，偏移单调、块序不变。
    private val audioFlushExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "speech-audio-flush").apply { isDaemon = true }
    }

    /** flush 任务在途标记：合并高频调度（每入队一块都请求一次），任务退出前复查未发量兜底 */
    private val flushTaskActive = AtomicBoolean(false)

    /**
     * 待 flush 排空后补发的 STOP 代次（0＝无）：STOP 必须排在全部音频
     * 之后，由 flush 任务在发送线程内发；登记方（stopHoldSession 的
     * live 分支）只登记不直发。
     */
    @Volatile
    private var stopAfterFlushGen = 0

    /** 单次持锁连发上限：全量补发分片让出，避免长时间独占发送锁饿死采集入队 */
    private const val FLUSH_CHUNK_BLOCKS = 50

    /** 本代服务端会话已就绪、可收音频（收到 RECORDING_STARTED） */
    @Volatile
    private var sessionLive = false

    /** 松手先于就绪：READY 到达后 flush 全部未发块＋STOP 结算 */
    @Volatile
    private var pendingFinish = false

    /** 已本地丢弃/待丢弃：READY 到达或启动截止触发时补 CANCEL 释放服务端会话 */
    @Volatile
    private var cancelPending = false

    /** 连接代际：每次 onServiceConnected 自增，READY 据此判断是否需要重绕重发 */
    @Volatile
    private var connGen = 0

    /** 上一次触发（重绕）发送的连接代际；与 connGen 相同则同连接内不重绕 */
    private var readyConnGen = 0

    /** 本会话开始时刻（打点基线：绑定完成/READY/首 partial 距开始耗时） */
    @Volatile
    private var sessionStartUptimeMs = 0L

    /** 本代是否已接受过首个 partial（窗口层 W1 豁免判断＋首 partial 打点） */
    @Volatile
    var sessionHasPartial = false
        private set

    /** 本地录音首秒内的最大电平（疑似静音流打点用，只记日志不改行为） */
    @Volatile
    private var firstSecondMaxAmp = 0f

    /**
     * 本地录音全程的最大电平：结算时若恒为 0 即整段零电平（麦克风
     * 被占用/硬件无声），借失败提示通道给用户明确提示，不再只是
     * 首秒打点（见 finishSession 的整段静音结算）
     */
    @Volatile
    private var sessionMaxAmp = 0f

    @Volatile
    private var audioOverflowed = false

    /** 录音回调回主线程的统一投递口：块与音量在采集线程产生，其余会话状态切换都在主线程串行 */
    private val mainHandler = Handler(Looper.getMainLooper())

    /** 启动失败文案：失败收尾时置入，窗口层经 [consumeStartFailureHint] 取走展示（读后即清） */
    @Volatile
    private var startFailureHint: String? = null

    fun consumeStartFailureHint(): String? {
        val hint = startFailureHint
        startFailureHint = null
        return hint
    }

    private var recorderOrNull: VoiceRecorder? = null

    private fun recorder(): VoiceRecorder = recorderOrNull ?: VoiceRecorder(
        context = appContext,
        onChunk = { tag, chunk -> onRecorderChunk(tag, chunk) },
        onAmplitude = { tag, amp -> onRecorderAmplitude(tag, amp) },
        onInitFailed = { tag -> onRecorderInitFailed(tag) },
        onCaptureFailed = { tag -> onRecorderCaptureFailed(tag) },
        onFocusLost = { tag -> onRecorderFocusLost(tag) },
    ).also { recorderOrNull = it }

    private fun onRecorderChunk(tag: Int, chunk: ByteArray) {
        // 代次不符＝上一会话采集线程的迟到块（stop 的 join 超时后才
        // 退出）：直接丢弃，不串入本代保留队列
        if (tag != activeGen || audioOverflowed) return
        synchronized(audioSendLock) {
            if (sessionAudio.size >= MAX_SESSION_AUDIO_BLOCKS) {
                // 保留队列到顶（约 120 秒）：不再整段作废——已录部分
                // 是好的，按正常松手结算（flush＋STOP 定稿），只把
                // 提示换成「录音过长」；与采集中途失败的结算口径一致
                audioOverflowed = true
                Timber.e(
                    "voice-start audio queue overflow gen=%d blocks=%d; settle recorded part",
                    tag, sessionAudio.size,
                )
                mainHandler.post {
                    if (tag == activeGen && tag != settledGen) {
                        startFailureHint = AUDIO_TOO_LONG_HINT
                        stopHoldSession(discard = false)
                    }
                }
                return
            }
            sessionAudio.addLast(chunk)
            // 已就绪且仍在按住：新块即时续发；未就绪或已松手时只
            // 保留，发送时机由 READY 处理与 stop 路径统一决定（松手
            // 后残块只由那两条路径调度，防 STOP 之后再冒出音频）
            if (sessionLive && holding.get()) scheduleFlush(tag)
        }
    }

    /**
     * 调度一次后台 flush（见 [audioFlushExecutor]）：已有任务在途时
     * 直接返回——在途任务会把队列发送到排空为止，新入队的块自然被
     * 它带走；任务收尾时再按未发量复查兜底，两条合起来不丢调度。
     */
    private fun scheduleFlush(gen: Int) {
        if (!flushTaskActive.compareAndSet(false, true)) return
        audioFlushExecutor.execute {
            try {
                flushLoop(gen)
            } finally {
                flushTaskActive.set(false)
                // 退出兜底：在途期间新入队的块，其调度请求可能被在途
                // 标记挡下；退出后队列仍有未发块且会话还活着，补投一轮
                val hasUnsent = synchronized(audioSendLock) { sendOffset < sessionAudio.size }
                if (hasUnsent && sessionLive) scheduleFlush(gen)
            }
        }
    }

    /** flush 任务体：分片发送到排空，最后补发登记待发的 STOP。 */
    private fun flushLoop(gen: Int) {
        while (true) {
            val drained = synchronized(audioSendLock) {
                flushSomeLocked(gen, FLUSH_CHUNK_BLOCKS)
            }
            if (drained) break
            // 分片让出：采集入队等锁的间隙能插进来，不被长补发饿死
            Thread.yield()
        }
        // 排空后补发 STOP：登记方只登记不直发，保证 STOP 不越过尚未
        // 发出的音频；发哪个代次以登记值为准，不与本任务的代次比较
        val stopGen = stopAfterFlushGen
        if (stopGen != 0) {
            stopAfterFlushGen = 0
            sendStopNow(stopGen)
        }
    }

    /**
     * 按 sendOffset 顺序推送未发块，单次至多 [maxBlocks] 块；调用方
     * 须持有 audioSendLock。返回 true 表示本轮到此为止（已排空、或
     * 代次已变、或无连接、或发送失败已转入自愈），false 表示发满
     * 一片且仍有未发块。发送失败时偏移不前进：连接恢复后由新 READY
     * 触发重绕续发。
     */
    private fun flushSomeLocked(gen: Int, maxBlocks: Int): Boolean {
        // 代次已变：队列已属新会话（或已收尾清空），本轮按排空退出；
        // 新会话的发送由它自己的调度与 READY 重绕接管
        if (gen != activeGen) return true
        val messenger = synchronized(connectLock) { speechMessenger } ?: return true
        var sent = 0
        while (sendOffset < sessionAudio.size && sent < maxBlocks) {
            val chunk = sessionAudio[sendOffset]
            val msg = SpeechIpc.message(SpeechIpc.MSG_AUDIO, gen = gen, audio = chunk)
            if (runCatching { messenger.send(msg) }.isFailure) {
                Timber.w(
                    "voice-start audio send failed gen=%d offset=%d; resend on next READY",
                    gen, sendOffset,
                )
                forceDisconnect()
                // 发送失败的自愈补绑（D6）：只断开不重绑，会话会对着
                // 死连接静默空转到松手为止。用一条 LOAD 驱动重绑
                //（send 在无连接时入队并 postBind）：同进程服务端
                // 会话还在时新连接就绪后续块直接续发；进程已换新时
                // 靠 READY 的连接代际重绕全量重发，都能自愈
                send(SpeechIpc.MSG_LOAD)
                return true
            }
            sendOffset++
            sent++
        }
        return sendOffset >= sessionAudio.size
    }

    private fun sendStopNow(gen: Int) {
        val messenger = synchronized(connectLock) { speechMessenger } ?: return
        if (runCatching { messenger.send(SpeechIpc.message(SpeechIpc.MSG_STOP, gen = gen)) }
                .isFailure
        ) {
            // STOP 没送达（连接已死）：作废连接，本次收尾由 DONE 超时兜底
            forceDisconnect()
        }
    }

    private fun sendCancelNow(gen: Int) {
        val messenger = synchronized(connectLock) { speechMessenger } ?: return
        if (runCatching { messenger.send(SpeechIpc.message(SpeechIpc.MSG_CANCEL, gen = gen)) }
                .isFailure
        ) {
            forceDisconnect()
        }
    }

    // 音量本地供给：采集线程约 25Hz 产生，合流成约 10Hz 投主线程
    // （存最新值、已投递未执行不叠投、两次投递至少间隔 100ms）
    @Volatile
    private var latestAmplitude = 0f
    private var lastAmpPostedMs = 0L
    private val ampPostPending = AtomicBoolean(false)

    private fun onRecorderAmplitude(tag: Int, amp: Float) {
        if (tag != activeGen) return
        latestAmplitude = amp
        // 全程最大电平（结算时判整段静音用）：逐块峰值取大，任一块
        // 有声即非零，不会误伤正常会话
        if (amp > sessionMaxAmp) sessionMaxAmp = amp
        // 首秒电平留痕：供启动后 1.1s 的疑似静音检查点比对
        if (SystemClock.uptimeMillis() - sessionStartUptimeMs <= 1000L &&
            amp > firstSecondMaxAmp
        ) {
            firstSecondMaxAmp = amp
        }
        val now = SystemClock.uptimeMillis()
        if (now - lastAmpPostedMs < AMPLITUDE_POST_INTERVAL_MS) return
        if (!ampPostPending.compareAndSet(false, true)) return
        lastAmpPostedMs = now
        mainHandler.post {
            ampPostPending.set(false)
            runCatching { SpeechUiBridge.onAmplitude?.invoke(latestAmplitude) }
        }
    }

    private fun onRecorderInitFailed(tag: Int) {
        Timber.e("voice-start recorder init failed gen=%d", tag)
        mainHandler.post {
            // 已结算或已被新会话取代的迟到失败不处理
            if (tag != activeGen || tag == settledGen) return@post
            startFailureHint = RECORDER_FAILURE_HINT
            cancelSession()
        }
    }

    private fun onRecorderCaptureFailed(tag: Int) {
        Timber.e("voice-start recorder capture failed gen=%d", tag)
        mainHandler.post {
            // 已结算或已被新会话取代的迟到失败不处理
            if (tag != activeGen || tag == settledGen) return@post
            startFailureHint = RECORDER_FAILURE_HINT
            // 采集中途失败（read 报错/被外部停录）：已推送的音频
            // 是好的，按焦点被抢同口径走正常松手结算（flush＋STOP
            // 定稿已录部分），不整场作废；失败提示已置上，结算完成
            // 时由 finishSession 尾部补消费弹出（见彼处注释）。已
            // 松手（待就绪/等 DONE）时什么都不用做，结算自会推进，
            // 提示随 finishSession 一并弹出
            if (holding.get()) stopHoldSession(discard = false)
        }
    }

    private fun onRecorderFocusLost(tag: Int) {
        Timber.w("voice-start audio focus lost gen=%d", tag)
        mainHandler.post {
            if (tag != activeGen) return@post
            // 与旧服务端同语义：焦点被抢＝采集已被录音器停掉，按
            // 正常松手路径结算已录部分（flush＋STOP 等服务端定稿），
            // 不整场作废；用户已先松手时待结算标记已在，什么都不做
            if (holding.get()) stopHoldSession(discard = false)
        }
    }

    /** 语音专名纠错词表：与识别解耦的独立小文件存储，损坏即整体不纠。 */
    private val correctionStore by lazy {
        VoiceCorrectionStore.shared(File(appContext.filesDir, VoiceCorrectionStore.FILE_NAME))
    }

    /**
     * 输入法服务的选区探针回调：把编辑观察转给纠错沉淀会话。
     * [probeText]/[probeCursorPos] 是服务当帧探针的结果（1 字探针文本
     * 与光标位），随回调下传供沉淀侧复用，省掉同帧的重复跨进程读。
     */
    fun onEditorTextProbed(
        service: ImeInputMethodService,
        probeText: String,
        probeCursorPos: Int,
    ) {
        VoiceCorrectionSession.onInputChanged(service, probeText, probeCursorPos)
    }

    /** 打字上屏词段回调（引擎搭配学习同一入口）：供纠错沉淀配对正形。 */
    fun notifyTypedSegment(segment: String) {
        VoiceCorrectionSession.onTypedWord(segment)
    }

    /** 输入视图结束/切换输入框：解除纠错沉淀观察。 */
    fun onInputViewFinished() {
        VoiceCorrectionSession.disarm()
    }

    private fun onRecordingStarted(gen: Int) {
        recordingStarted = true
        sessionLive = true
        // RECORDING_STARTED 本身就是存活证据：解除启动截止
        onLivenessConfirmed()
        disarmStartDeadline()
        // gen==0 是对端未带代次的兼容情形（同版本发布，不应发生），以当前代次兜底
        val sessionGen = if (gen != 0) gen else activeGen
        Timber.i(
            "voice-start ready gen=%d connGen=%d elapsed=%dms",
            sessionGen, connGen, SystemClock.uptimeMillis() - sessionStartUptimeMs,
        )
        synchronized(audioSendLock) {
            when {
                cancelPending -> {
                    // 本地早已丢弃：服务端会话刚就绪即 CANCEL 释放；
                    // 先 arm DONE 超时再发，防其 DONE 回信丢失后会话悬挂
                    cancelPending = false
                    armDoneTimeout()
                    sendCancelNow(sessionGen)
                }
                pendingFinish -> {
                    // 松手先于就绪：先 arm DONE 超时，再登记 STOP 待
                    // flush 排空后由发送线程补发（纪律同「先 arm 再
                    // 发」：DONE 可能极快回来，超时必须先就位）
                    pendingFinish = false
                    armDoneTimeout()
                    stopAfterFlushGen = sessionGen
                    scheduleFlush(sessionGen)
                }
                connGen != readyConnGen -> {
                    // 新连接上的首个 READY（初次绑定，或 binder 死亡
                    // 复活后的全新服务端会话）：偏移归零、全量重发
                    //（发送在后台线程分片进行，本处只切状态）
                    readyConnGen = connGen
                    sendOffset = 0
                    scheduleFlush(sessionGen)
                }
                else -> {
                    // 同连接内重复 READY：不重绕，只补发未发块
                    scheduleFlush(sessionGen)
                }
            }
        }
        runCatching { SpeechUiBridge.onRecordingStarted?.invoke() }
    }

    private fun onPartial(text: String?) {
        if (discarding.get()) return
        text?.takeIf { it.isNotEmpty() }?.let {
            if (!sessionHasPartial) {
                sessionHasPartial = true
                Timber.i(
                    "voice-start first-partial gen=%d elapsed=%dms",
                    activeGen, SystemClock.uptimeMillis() - sessionStartUptimeMs,
                )
            }
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

    private fun onDone(audioDropped: Boolean = false) {
        // 松手链时序打点终点：与 stopHoldSession 的 release 打点、
        // 服务端的 STOP/drained 打点同前缀，logcat 时间戳即分段耗时
        Timber.i("voice-stop: DONE received")
        if (audioDropped) {
            // 本句解码期间服务端队列曾满、有音频块被丢弃：结果可能
            // 不完整。本轮只打点留痕，呈现层不做（见服务端计数处）
            Timber.w("voice-stop: DONE with dropped audio blocks; result may be incomplete")
        }
        disarmDoneTimeout()
        finishSession()
    }

    // ── 迟到 FINAL 对账（D3 客户端半） ─────────────────────
    // 上一代在排空期（已 STOP、DONE 未回）被新会话接管结算时，快照
    // 文本先行上屏；服务端随后补发的 FINAL 是那句的最终全文，若按
    // 代次过滤整段丢弃，快照之后多出的增量尾段就丢了。故接管结算
    // 落屏时把（代次、已落屏的展示文本）记在此处，迟到 FINAL 走
    // tryReconcileLateFinal 对账补尾。

    /** 待对账的上一代代次（0＝无）；迟到 FINAL 命中即一次性消费 */
    @Volatile
    private var lateFinalGen = 0

    /** 待对账时已落屏的展示文本（纠错＋繁简之后、与 FINAL 同口径可比） */
    @Volatile
    private var lateFinalLandedText: String? = null

    /**
     * 迟到 FINAL 对账：把上一代 FINAL 相对快照多出的尾段补到编辑器。
     * 严门控、任一不合即整段放弃（回落为丢弃，与旧行为一致）：
     * 新会话须仍在进行且尚未出任何 partial（光标必定位于已落屏段
     * 之后、组字区未被占用，补尾才不会拼错位置）；FINAL 经同款
     * 纠错/转换后的展示文本须以已落屏文本为前缀，增量才定义良好。
     */
    private fun tryReconcileLateFinal(gen: Int, rawFinal: String?) {
        val landedText = lateFinalLandedText
        // 一次性消费：无论对账成败都不再重试
        lateFinalGen = 0
        lateFinalLandedText = null
        if (rawFinal.isNullOrEmpty() || landedText == null) return
        if (activeGen == 0 || activeGen == gen || sessionHasPartial) return
        val service = serviceRef?.get() ?: return
        service.scope?.launch(Dispatchers.Main) {
            val corrected = withContext(Dispatchers.Default) { correctionStore.correct(rawFinal) }
            val displayFinal = withContext(Dispatchers.Default) { toDisplayText(corrected.text) }
            // 两次挂起后复验：期间新会话出了 partial 或已收尾，补尾
            // 位置不再可靠，放弃（与上方入口门控同一口径）
            if (activeGen == 0 || activeGen == gen || sessionHasPartial) return@launch
            if (displayFinal == landedText || !displayFinal.startsWith(landedText)) return@launch
            val increment = displayFinal.drop(landedText.length)
            if (increment.isEmpty()) return@launch
            val ic = service.activeInputConnection() ?: return@launch
            if (!runCatching { ic.commitText(increment, 1) }.getOrDefault(false)) return@launch
            // 账本同步扩账：已登记的快照段改为最终全文，日后上滑
            // 撤销仍按整段直删，不会只删掉一半
            VoiceUndoLedger.amendLastSegment(landedText, displayFinal)
            Timber.i(
                "voice-stop: late FINAL reconciled gen=%d increment=%d chars",
                gen, increment.length,
            )
        }
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
        // 上一代待就绪结算（pendingFinish）留下的整段保留音频由本代
        // 接续（D7）：在 settlePreviousSession 清掉待结算标记之前先
        // 记下，下方音频状态复位时据此不清队列。已丢弃的不接续
        val inheritRetainedAudio = pendingFinish && !cancelPending
        // 上一会话未结算的待定文本先按快照就地结算（不等 DONE），再
        // 开始新会话——直接清掉 composingText 会把已识别出的文字吞掉
        settlePreviousSession()
        // 预检探针若还在等回音就此作废：本会话自带启动截止，预检
        // 超时不得在会话途中拆连接
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
        // 音频先行状态复位：上一会话的保留队列/待结算标记/打点
        // 基线不得带入本代（settlePreviousSession 只接管文本侧状态）
        synchronized(audioSendLock) {
            if (inheritRetainedAudio) {
                // 接续上一代未就绪会话的保留音频（D7）：不清队列、
                // 不归零偏移——其偏移必为 0（从未就绪即从未发送），
                // 本代录音继续往后接，就绪后整段一并推送结算
                Timber.i(
                    "voice-start inherit retained audio gen=%d blocks=%d",
                    gen, sessionAudio.size,
                )
            } else {
                sessionAudio.clear()
                sendOffset = 0
            }
        }
        sessionLive = false
        pendingFinish = false
        cancelPending = false
        audioOverflowed = false
        sessionHasPartial = false
        firstSecondMaxAmp = 0f
        sessionMaxAmp = 0f
        sessionStartUptimeMs = SystemClock.uptimeMillis()
        // 录音立即本地启动，不等绑定与引擎加载：冷启动期间说的话
        // 进保留队列、就绪后回灌（音频先行）。init 失败经回调走
        // 可见失败收尾（见 onRecorderInitFailed）
        val rec = recorder()
        rec.sessionTag = gen
        rec.start()
        val boundAtStart = synchronized(connectLock) { speechMessenger } != null
        Timber.i("voice-start begin gen=%d bound=%s", gen, boundAtStart)
        // 首秒静音打点：1.1 秒后本代首秒最大电平仍为 0 则记疑似
        // 静音流（只打日志不改行为，供事后归因）
        probeHandler.postDelayed(
            {
                if (gen == activeGen && firstSecondMaxAmp == 0f) {
                    Timber.w(
                        "voice-start silent-stream suspected gen=%d: first-second max amplitude is 0",
                        gen,
                    )
                }
            },
            1100L,
        )
        send(SpeechIpc.MSG_LOAD)
        send(SpeechIpc.MSG_START, gen)
        // 同发存活探针：回音只作存活证据（启动截止据此分流），不再驱动拆连接
        sendPing(gen)
        armStartDeadline(gen)
    }

    fun stopHoldSession(discard: Boolean = false) {
        if (!holding.compareAndSet(true, false)) return
        // 松手链时序打点起点（voice-stop 前缀，与服务端各段同查）
        Timber.i("voice-stop: release discard=%b", discard)
        // 启动截止不在此解除：它自会话开始计时，松手后待结算
        // （pendingFinish/cancelPending）期间仍要靠它兜底，READY
        // 到达（onRecordingStarted）或会话收尾（resetState）才解除
        // 丢弃时先撤掉待发队列里的 START：绑定完成后不得重放出一段
        // 幽灵录音。非丢弃的未绑定支恰恰相反——START 要留在队列里
        // 等绑定完成重放（见 when 的最后一支）
        if (discard) removePending(SpeechIpc.MSG_START)
        // 先停录音（不阻塞等采集线程退出）：holding 已置 false，
        // 块到达路径的补发闸门随之关闭；STOP 经 stopAfterFlushGen
        // 登记、由发送线程在 flush 排空后补发，迟到入队的块只会
        // 被排在 STOP 之前一并发走、不会越过 STOP，保留队列以后
        // 续结算都以排空点为准
        recorderOrNull?.stop()
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
        val gen = activeGen
        val bound = synchronized(connectLock) { speechMessenger } != null
        when {
            discard && sessionLive && bound -> {
                // 服务端会话在跑：先 arm DONE 超时再发 CANCEL 丢弃
                // （其 DONE 回信走 finishSession 的 discard 分支收尾）
                armDoneTimeout()
                sendCancelNow(gen)
            }
            discard && bound -> {
                // START 已在服务端占位但 READY 未到，CANCEL 无从现在
                // 发（会话还没建好）：记 cancelPending——READY 到达
                // 时补 CANCEL（见 onRecordingStarted），一直不来由
                // 启动截止补 CANCEL 收尾（见 onStartDeadline）
                cancelPending = true
            }
            discard -> {
                // 未绑定：START 已随待发队列撤下，服务端永远不会有
                // 本会话，与既有「未绑即本地结算」同款就地收尾
                finishSession()
            }
            sessionLive -> {
                // 就绪后松手：先 arm DONE 超时（防极快 DONE 竞态），
                // 再登记 STOP 待 flush 排空后由发送线程补发——STOP
                // 必须排在全部未发音频之后（见 stopAfterFlushGen）
                armDoneTimeout()
                stopAfterFlushGen = gen
                scheduleFlush(gen)
            }
            bound -> {
                // 松手先于就绪：不发 STOP（服务端会话还没建好），
                // 记 pendingFinish——READY 到达时 flush＋STOP 结算
                // 并在彼处 arm DONE 超时；一直等不到 READY 由启动
                // 截止按可见失败收尾
                pendingFinish = true
            }
            else -> {
                // 未绑定（绑定还在飞）就松手：不能就地 finishSession
                //——服务端会话尚未建立，此刻只能按空文本收尾，整句
                // 静默丢失。与已绑定未就绪支同款记 pendingFinish：
                // 待发队列里的 START 在绑定完成时照常重放（重放过滤
                // 已为此放行，见 onServiceConnected），READY 后由其
                // pendingFinish 分支整段结算；一直等不到 READY 由
                // 启动截止按可见失败收尾（超过截止才提示失败）
                pendingFinish = true
            }
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
        if (pendingFinish || cancelPending) {
            // 上一代松手/丢弃后始终没等到 READY：它没有 partial、
            // 没有 composing 需要定稿（未就绪即无服务端文本），直接
            // 声明已结算并清待结算标记；其启动截止随之解除（本代会
            // 另行武装），服务端那份占位会话若还在则发 CANCEL 释放
            // ——迟到的 READY 会被代次过滤丢弃，不再触发本地动作
            settledGen = prevGen
            pendingFinish = false
            cancelPending = false
            disarmStartDeadline()
            sendCancelNow(prevGen)
        }
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
            var screenIc = service.activeInputConnection()
            var prevDisplay: String? = null
            // 本次是否真的对有效连接执行了上屏提交（账本登记门控）
            var landed = false
            if (!prevText.isNullOrBlank()) {
                val corrected = withContext(Dispatchers.Default) { correctionStore.correct(prevText) }
                val displayText = withContext(Dispatchers.Default) { toDisplayText(corrected.text) }
                // 与 finishSession 尾同构：两次挂起之后、写屏之前
                // 复验（验法与本尾入口一致）并重取连接——挂起期间
                // 状态已变（本结算被更新的结算取代/新会话被丢弃）
                // 或连接已指向新框时，不得用旧连接幽灵上屏，更
                // 不得登记账本
                if (settledGen != prevGen || discarding.get()) return@launch
                val liveIc = service.activeInputConnection()
                screenIc = liveIc
                if (liveIc != null) {
                    liveIc.setComposingText(displayText, 1)
                    prevDisplay = displayText
                    landed = true
                }
            }
            screenIc?.finishComposingText()
            // 账本登记：接管上屏的文本同样是真正落入编辑器的语音
            // 段，供退格上滑整段撤销（登记口径与 finishSession 一
            // 致，且同受 landed 门控：没真正落屏不登记）
            if (landed) {
                prevDisplay?.takeIf { it.isNotEmpty() }?.let {
                    VoiceUndoLedger.recordCommittedSegment(it)
                    // 记下待对账状态：本代服务端若随后补发 FINAL，
                    // 其增量尾段经对账补上而非按代次整段丢弃（D3）
                    lateFinalGen = prevGen
                    lateFinalLandedText = it
                }
            }
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
        // 整段零电平结算（编号 26）：有录音块、全程最大电平恒为 0
        //（麦克风被占用/硬件无声）时，借失败提示通道明确告知——
        // 文本该落的照常落（异步尾见 startFailureHint 改走 onFailed
        // 收尾弹提示，与采集中途失败同口径）。已有提示时不覆盖
        if (!discard && sessionMaxAmp == 0f && startFailureHint == null &&
            synchronized(audioSendLock) { sessionAudio.isNotEmpty() }
        ) {
            startFailureHint = NO_SOUND_HINT
        }
        resetState()
        if (service == null) return
        service.scope?.launch(Dispatchers.Main) {
            // 代次口径（D5）：本代被新会话取代只隔离 UI 收尾与沉淀
            // 武装，快照文本必须先上屏再退出——此前三处代次复验失败
            // 即整尾作废，DONE 已到、纠错已算完时用户恰好开始新会话，
            // 整句就此被吞。文本去留不再由代次决定（见下方各处）
            var screenIc = service.activeInputConnection()
            var committed: CorrectResult? = null
            var committedDisplay: String? = null
            // 本次是否真的对有效连接执行了上屏提交（账本登记门控）
            var landed = false
            if (discard) {
                // 取消：先删掉已流式显示的待定文字，再收尾，否则 finishComposingText 会把待定文字确认上屏。
                // 但本代已被新会话取代时组字区已归新会话（待弃文字在
                // 丢弃当时已即时清屏），再清会误伤新会话的待定文字
                if (activeGen == 0 || activeGen == gen) {
                    screenIc?.setComposingText("", 1)
                }
            } else if (!text.isNullOrBlank()) {
                // 定稿先过专名同音纠错（词表与规则均为简体，必须赶在
                // 繁简转换之前），再在后台转换，回主线程上屏收尾，
                // DONE 后的转换→上屏→finishComposingText 顺序不变
                val corrected = withContext(Dispatchers.Default) { correctionStore.correct(text) }
                val displayText = withContext(Dispatchers.Default) { toDisplayText(corrected.text) }
                // 两次挂起之后、写屏之前重取连接：挂起期间切框时
                // 先前抓的连接已指向旧框。连接失效（取不到）时本次
                // 提交不发生，committed 不置、账本不登记（见下方门控）
                val liveIc = service.activeInputConnection()
                screenIc = liveIc
                if (liveIc != null) {
                    liveIc.setComposingText(displayText, 1)
                    committed = corrected
                    committedDisplay = displayText
                    landed = true
                }
            }
            // 确认上屏的执行条件：本尾刚落了文本（必须 finish 把它
            // 转正，否则仍是组字态、会被新会话首个 partial 顶掉），
            // 或本代未被取代（常态收尾）；被取代且未落文本时编辑器
            // 归新会话，碰它会误确认新会话的待定文字
            if (landed || activeGen == 0 || activeGen == gen) {
                screenIc?.finishComposingText()
            }
            // 账本登记：文本真正落入编辑器之后记一笔（最终落下的
            // displayText，含规整/繁简转换），供退格上滑的语音段
            // 两级撤销整段直删；丢弃的文本不登记。上屏没真正发生
            // （连接失效）时不登记：幽灵账目会让后续上滑误删，且
            // 登记动作本身还会清掉恢复栈、抹掉用户待恢复的段
            if (landed) {
                committedDisplay?.takeIf { it.isNotEmpty() }?.let {
                    VoiceUndoLedger.recordCommittedSegment(it)
                }
            }
            // 本代已被新会话取代：沉淀武装与 UI 收尾归新会话管
            //（onDone 会把新会话的录音标志清掉、令其松手不再发
            // STOP），文本既已上屏，本尾到此退出
            if (activeGen != 0 && activeGen != gen) return@launch
            // 上屏后武装纠错沉淀：观察用户对这段文本的亲手修改，
            // 学成词对供下次定稿自动纠正（无观察条件时内部自行放弃）
            committed?.let {
                VoiceCorrectionSession.arm(service, correctionStore, it.text, it.applied)
            }
            if (startFailureHint != null) {
                // 结算完成路径的失败提示补消费：失败文案此前只在
                // 窗口层的 onFailed 回调里被取走展示，正常完成走
                // onDone 无人消费——采集中途失败「结算已录部分」
                // 正是走这条完成路径（见 onRecorderCaptureFailed），
                // 提示会滞留到下一次失败才被顺带带出。此处有待弹
                // 提示时改走 onFailed 收尾：窗口层据此取走文案弹
                // Toast，浮层收起/录音态复位与 onDone 等价；文本
                // 已在上方落屏，不受这次收尾口径影响
                SpeechUiBridge.onFailed?.invoke() ?: SpeechUiBridge.onDone?.invoke()
            } else {
                SpeechUiBridge.onDone?.invoke()
            }
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
        // 必须通知服务端丢弃会话：只做本地收尾会让服务端
        // sessionActive 残留，下一次 START 在服务端被 CAS 拒绝、
        // 静默无反馈。新协议下发 MSG_CANCEL（丢弃，而非 STOP 的
        // 排空定稿——取消路径本就不要结果）；发送失败说明连接已
        // 死，sendCancelNow 内一并作废连接
        sendCancelNow(activeGen)
        uiJob?.cancel()
        uiJob = null
        val service = serviceRef?.get()
        // 捕获本代代次供 runUi 复检：紧随其后的 resetState 会把
        // activeGen 清零，runUi 真正执行时它只可能是 0（本代已收尾）
        // 或新会话的代次
        val gen = activeGen
        val runUi = Runnable {
            // 复检代次（与 finishSession 异步尾同口径）：投递之后、执行
            // 之前用户若已开始新会话（activeGen 既非空闲的 0、也非本
            // 代），清待定文字与失败回调归新会话管，本尾整体作废
            if (activeGen == 0 || activeGen == gen) {
                runCatching {
                    // 出错/断开：先清空残留的待定文字，再收尾，避免下划线文字残留。
                    service?.activeInputConnection()?.let { ic ->
                        ic.setComposingText("", 1)
                        ic.finishComposingText()
                    }
                    SpeechUiBridge.onFailed?.invoke() ?: SpeechUiBridge.onDone?.invoke()
                }
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
        disarmStartDeadline()
        // 所有收尾路径（finish/cancel/权限中止）都经这里：等 DONE 的
        // 标志与超时任务不得残留到下一会话
        disarmDoneTimeout()
        // 音频先行状态一并复位：保留队列/发送偏移/待结算标记不得
        // 带入下一会话；录音确保停净（正常路径 stopHoldSession 已
        // 先停过，这里兜底错误/断连等未经 stop 的收尾路径）
        synchronized(audioSendLock) {
            sessionAudio.clear()
            sendOffset = 0
        }
        sessionLive = false
        pendingFinish = false
        cancelPending = false
        sessionHasPartial = false
        // 迟到 FINAL 对账窗口随任一会话收尾关闭：本代之后的会话若
        // 已结算过，编辑器内容已变迁，旧句增量再补必拼错位置
        lateFinalGen = 0
        lateFinalLandedText = null
        recorderOrNull?.stop()
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