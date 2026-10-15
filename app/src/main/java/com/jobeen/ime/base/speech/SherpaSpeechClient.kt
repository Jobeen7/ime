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
        // DONE 没回来的一种可能是 STOP 在发送通道里丢了：服务端
        // 会话仍占位（sessionActive 残留），后续 START 会被它 CAS
        // 拒绝。补发一次本代 CANCEL 释放它（服务端按代次受理，
        // 正是这代泄漏会话的代次）；本地结算不依赖它的回信，紧
        // 接着照常 finishSession。activeGen 必须赶在 finishSession
        // 复位代次之前取
        sendCancelNow(activeGen)
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
        // 计数序列（清理过期→判上限→追加）整体原子：触发方有主
        // 线程的死亡回调与（经投递的）发送失败两路，序列被交错
        // 执行会丢计数、让上限判定失准。只锁这段序列本身（以
        // deque 为锁对象），不把后面的重绑/重发动作包进锁里
        val allowed = synchronized(deathResurrectTimes) {
            while (deathResurrectTimes.isNotEmpty() &&
                now - deathResurrectTimes.first() > DEATH_RESURRECT_WINDOW_MS
            ) {
                deathResurrectTimes.removeFirst()
            }
            if (deathResurrectTimes.size >= DEATH_RESURRECT_MAX) {
                false
            } else {
                deathResurrectTimes.addLast(now)
                true
            }
        }
        if (!allowed) {
            Timber.e("SpeechCli binder died too often; giving up this session")
            return false
        }
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
    private const val INPUT_SWITCHED_HINT = "已切换输入框，上一句语音已作废"

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
        // 本代已有文本真正显示过（慢机上自愈重绑＋模型重载耗尽
        // 截止、READY 迟迟不来）：走 finishSession 定稿——屏上
        // 组字由其尾部 finishComposingText 转正、快照文本不丢，
        // 失败提示照常由结算尾消费弹出；一字未显示时维持
        // cancelSession 清屏收尾
        if (lastDisplayedGen == gen && !lastDisplayedText.isNullOrEmpty()) {
            finishSession()
        } else {
            cancelSession()
        }
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

    /**
     * 待排空的上一代代次（0＝无）：连按时上一代 live 会话的 STOP
     * 已登记待发、尾段音频未发完，新会话不清队列（见
     * startHoldSession 的连按排空分支）——该代次的发送任务按
     * [drainBoundary] 把旧块排空、随后在本任务内发出其 STOP；新
     * 一代的块排在边界之后，归其自己的 READY 后发送。
     */
    @Volatile
    private var drainGen = 0

    /** 排空边界：[sessionAudio] 中下标小于此值的块属待排空代次；仅在 audioSendLock 内读写 */
    private var drainBoundary = 0

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
                // 标记挡下；退出后队列仍有未发块且会话还活着，补投
                // 一轮。另有同型窗口在 STOP 登记侧：本任务读走登记
                // 前（登记值为 0）就退出、stopHoldSession 随后登记
                // STOP 并调 scheduleFlush 却被在途标记挡下——退出后
                // 仍有待发 STOP 时同样补投，否则 STOP 永远发不出、
                // 服务端会话占位不释放（DONE 超时的补 CANCEL 是最
                // 后防线，非常态路径）
                // 未发量与 sessionLive 同在锁内读：补投当前代次的
                // 判定须与发送侧的代次/存活状态一致，锁外读到的
                // sessionLive 可能是复活刚摘除前的值、会把补投投
                // 给一个已不 live 的会话空转
                val (hasUnsent, liveNow) = synchronized(audioSendLock) {
                    (sendOffset < sessionAudio.size) to sessionLive
                }
                // 补投代次按优先级取，不再用退出任务自身的 gen：待
                // 发 STOP 的登记代次优先——登记只能由其代次（或负
                // 责其排空）的任务消费（见 flushLoop 的 STOP 门
                // 控），投旧 gen 只会空转一轮且接不上登记；其次才
                // 是仍有未发块的当前活会话
                val pendingStopGen = stopAfterFlushGen
                val retryGen = when {
                    pendingStopGen != 0 -> pendingStopGen
                    hasUnsent && liveNow -> activeGen
                    else -> 0
                }
                if (retryGen != 0) scheduleFlush(retryGen)
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
        // 本任务即排空任务时在此收尾——这是排空状态的唯一收口：
        // flushSomeLocked 只报单次发送结果、从不下排空结论，登记
        // 在 startHoldSession、整体复位在 resetState，都不是「排
        // 空完成」点。整段在 audioSendLock 内、且以锁内复查的
        // drainGen 为准：清零若留在锁外，下一代 startHoldSession
        // 刚在锁内登记的新 drainGen 会被本任务的迟到清零覆盖，
        // 新排空任务入口即判代次不符退出、旧尾块滞留；锁内复查
        // 后两种时序都安全（登记先行则本任务看到的已是新代次、
        // 整段跳过；清零先行则登记随后覆写 0 值）。其 STOP 紧接
        // 着在下文按登记发出，顺序不变
        synchronized(audioSendLock) {
            if (drainGen == gen) {
                if (sendOffset >= drainBoundary) {
                    // 排空成功：旧块已全数发出，物理裁剪队列头部
                    // [0, drainBoundary)——只靠 sendOffset 指向边
                    // 界区分不够：此后任何连接重绕（自愈重绑、
                    // binder 复活的 READY）都会把 sendOffset 归零
                    // 全量重发，留着的旧块会被整段以新代次重发
                    // 进新会话。裁剪后队列只剩新一代块；sendOffset
                    // 同步回退裁剪量，不变式不变：sendOffset
                    // 恒为「自队列头起已发出的块数」，新一代已
                    // 发块的计数（排空任务不越界发送、只可能由
                    // 新一代自己的发送推进过边界的部分）原样保留
                    val boundary = drainBoundary
                    var removed = 0
                    while (removed < boundary && sessionAudio.isNotEmpty()) {
                        sessionAudio.removeFirst()
                        removed++
                    }
                    sendOffset -= removed
                    drainBoundary = 0
                } else {
                    // 排空未及边界就退出（发送失败/连接已亡，见
                    // flushSomeLocked 的失败返回）：未发旧块就地
                    // 丢弃——上一代服务端会话已不存在（连接失效
                    // 即实例销毁），留着会在新一代 READY 重绕时
                    // 被当作新音频从头塞入新会话（旧句尾巴粘进
                    // 新句）；丢弃后新一代块的下标口径随之收
                    // 拢，偏移指向不变
                    val total = sessionAudio.size
                    val keep = ArrayDeque<ByteArray>(total)
                    var idx = 0
                    while (sessionAudio.isNotEmpty()) {
                        val block = sessionAudio.removeFirst()
                        if (idx < sendOffset || idx >= drainBoundary) keep.addLast(block)
                        idx++
                    }
                    sessionAudio.addAll(keep)
                    drainBoundary = sendOffset
                }
                drainGen = 0
            }
        }
        // 排空后补发 STOP：登记方只登记不直发，保证 STOP 不越过尚未
        // 发出的音频。消费按代次门控，读取、判门、清零在
        // audioSendLock 内一次完成（与上文排空收尾同口径）：登记
        // 只能由其代次的任务消费——旧代次任务若无条件按登记值代
        // 发，会在新一代音频还没发时就发出它的 STOP，服务端以空
        // 音频 DONE 结算，新一代整段确定性丢失。负责排空该代次的
        // 任务也可消费，但须锁内复查排空边界已被覆盖（该代次音
        // 频确已发完）才成立；未覆盖或不匹配时登记原样保留，其
        // 代次的任务由退出兜底按登记代次优先补投接手（见
        // scheduleFlush）
        val stopGen = synchronized(audioSendLock) {
            val registered = stopAfterFlushGen
            if (registered != 0 &&
                (registered == gen ||
                    (registered == drainGen && sendOffset >= drainBoundary))
            ) {
                stopAfterFlushGen = 0
                registered
            } else {
                0
            }
        }
        if (stopGen != 0) sendStopNow(stopGen)
    }

    /**
     * 按 sendOffset 顺序推送未发块，单次至多 [maxBlocks] 块；调用方
     * 须持有 audioSendLock。返回 true 表示本轮到此为止（已排空、或
     * 代次已变、或无连接、或发送失败已转入自愈），false 表示发满
     * 一片且仍有未发块。发送失败时偏移不前进：本代仍在录音/待结
     * 算时走启动复活同路径（作废重绑＋重发 START），新连接 READY
     * 后按连接代际重绕、偏移归零全量重发；已松手待 DONE 时补发
     * 失败同理（先把会话还原成待就绪形态再复活，见失败分支）。
     * 复活/收尾的执行体不在本线程（音频发送执行器）直接跑：投
     * 主线程复检代次后执行（见 postResurrectOrLinkLost），状态
     * 改写回到主线程串行。不适用该机制的形态（排空上一代的任
     * 务、会话已收尾）不在此处恢复，归既有结算口径（DONE 超时
     * 等）收尾。
     */
    private fun flushSomeLocked(gen: Int, maxBlocks: Int): Boolean {
        // 代次已变：本任务只剩「排空上一代待发尾段」一种合法身份
        //（drainGen，见 startHoldSession 的连按排空分支）；其余情形
        // 队列已属新会话（或已收尾清空），本轮按排空退出，新会话
        // 的发送由它自己的调度与 READY 重绕接管
        if (gen != activeGen && gen != drainGen) return true
        // 当前代次只在服务端会话 live 时发送：live 已被摘（发送
        // 失败自愈进行中、或连接已亡后状态未及复位）时到达的任
        // 务不得再往旧连接续发——偏移会在 READY 重绕前误前进，
        // 新会话头部就此缺失。排空上一代的任务（gen==drainGen）
        // 不受此门控：其发送资格由排空登记与边界定义
        if (gen == activeGen && !sessionLive) return true
        val messenger = synchronized(connectLock) { speechMessenger } ?: return true
        // 排空上一代时只发到排空边界：边界之后的块属新一代，归它
        // 自己的 READY 后发送，绝不能以旧代次提前塞给服务端
        val upper = if (gen == drainGen) {
            minOf(sessionAudio.size, drainBoundary)
        } else {
            sessionAudio.size
        }
        var sent = 0
        while (sendOffset < upper && sent < maxBlocks) {
            val chunk = sessionAudio[sendOffset]
            val msg = SpeechIpc.message(SpeechIpc.MSG_AUDIO, gen = gen, audio = chunk)
            if (runCatching { messenger.send(msg) }.isFailure) {
                Timber.w(
                    "voice-start audio send failed gen=%d offset=%d; resend on next READY",
                    gen, sendOffset,
                )
                if (gen == activeGen && (holding.get() || pendingFinish)) {
                    // 发送失败的自愈：连接已失效，而 forceDisconnect
                    // 的解绑会连服务端实例一并销毁（它只靠本绑定存
                    // 活）——旧注释所谓「同进程服务端会话还在、续块
                    // 直接续发」不成立：新实例没有本代会话，只补
                    // LOAD 不补 START 时，后续块会被新服务端以无会
                    // 话/代次不符静默丢弃、偏移却照常前进，整句烂
                    // 在半路。改走启动复活同路径：先摘 sessionLive
                    //（恢复完成前不再往任何连接续发，防偏移在重绕
                    // 前误前进），再作废重绑＋重发本代 START＋探针
                    //＋重挂启动截止；READY 后 onRecordingStarted 按
                    // 连接代际重绕、偏移归零全量重发（重绕在补发
                    // STOP 之前先行重置偏移，见彼处）。复活计数与
                    // binder 死亡复活共用，防服务端反复崩的循环；
                    // 复活超限（服务端连环崩）按连接丢失既有口径
                    // 收尾，不让会话摘了 live 后无限悬挂。复活的执
                    // 行体投主线程（本函数在发送执行器线程、且持
                    // audioSendLock）：resetState 等状态改写必须回
                    // 到主线程串行，且投递后先复检代次——期间会话
                    // 已被 DONE/新会话收尾的，本次失败不再动作
                    sessionLive = false
                    postResurrectOrLinkLost(gen)
                } else if (gen == activeGen && !holding.get() && stopAfterFlushGen == gen) {
                    // 已松手待 DONE 形态：READY 时 pendingFinish 已
                    // 先行清零、本分支的两条件全假，此前不进复活——
                    // 补发恰是最吃 binder 缓冲的时刻，失败即整句静
                    // 默丢失，而保留队列里整段音频俱在。还原成待就
                    // 绪形态再走同一复活：锁内撤 STOP 登记（防本任
                    // 务尾按登记把 STOP 发进死连接）、摘 live、恢复
                    // pendingFinish、拆 DONE 超时（复活自带启动截
                    // 止，DONE 超时会抢先按空文本结算）；新连接
                    // READY 后 pendingFinish 分支整段重发＋补 STOP
                    stopAfterFlushGen = 0
                    sessionLive = false
                    pendingFinish = true
                    disarmDoneTimeout()
                    postResurrectOrLinkLost(gen)
                }
                // 不适用复活机制的形态（排空上一代的任务失败）：连
                // 接已由随后的 sendStopNow/DONE 超时链处理，本轮就
                // 此退出，未发块由既有结算口径（快照结算＋DONE 超
                // 时补 CANCEL）兜底，不在此静默续发
                return true
            }
            sendOffset++
            sent++
        }
        return sendOffset >= upper
    }

    /**
     * 发送失败后的复活/收尾投递（V6 线程口径）：执行体（复活的
     * 作废重绑＋重发 START、超限后的 handleLinkLost 分流收尾）全
     * 是主线程状态改写（holding/serviceRef/activeGen/uiJob 等），
     * 不得在音频发送执行器线程、持 audioSendLock 时直接跑——与
     * 主线程的 startHoldSession/READY 处理并发改写同一批状态，
     * 且 resetState 无代次守卫会误伤刚开始的新会话。投主线程后
     * 先复检代次：投递期间本代已被结算（迟到 DONE 先行到达）或
     * 被新会话取代时，本次失败已无意义，整段跳过；再复检会话
     * 是否已被另一路复活恢复（sessionLive 已置真），是则同样
     * 跳过，避免双路复活互相拆台。与 binder 死亡回调投主线程
     * 的既有时效口径一致（主线程忙时延迟同量级）。
     */
    private fun postResurrectOrLinkLost(gen: Int) {
        mainHandler.post {
            if (gen != activeGen) return@post
            // 双路去重：投递后、执行前，另一路（binder 死亡回调在
            // 主线程直调复活）可能已先行复活成功——READY 到达即
            // sessionLive 置真（onRecordingStarted），会话已在新
            // 连接上恢复。此时再复活会对刚重绑的连接
            // forceDisconnect 并虚增复活计数，整段跳过。判据取
            // sessionLive 本身：它只由本代 READY 置真，是「会话
            // 已 live」的精确定义；待就绪形态下它本就为假（复活
            // 正是为把它等回来），不会误跳真正需要的复活
            if (sessionLive) return@post
            if (!tryResurrectStartupSession()) handleLinkLost()
        }
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

    /**
     * 输入视图结束/切换输入框：解除纠错沉淀观察；另作废上一框的
     * 显示快照与「已松手待就绪」的保留音频（见下）。
     */
    fun onInputViewFinished() {
        VoiceCorrectionSession.disarm()
        // 显示快照一并作废（不限于下方的待结算音频分支）：快照
        // 的成立前提是「文本显示在当前框的屏上」，视图结束即不
        // 成立——不清的话，切到新框后再按，接管结算会走快照
        // 支、以新框的现行连接把甲框的句子当作已落屏登记账本
        // 并设迟到对账基线，迟到 FINAL 的尾段随之补进乙框。
        // 清零口径与快照既有读写一致（@Volatile 直接赋值；写点
        // 在 uiJob 上屏处与会话入口/收尾，均为服务主线程），此
        // 处同为服务主线程回调，不另加锁
        lastDisplayedText = null
        lastDisplayedGen = 0
        // 跨框作废：会话已松手、服务端未就绪（pendingFinish）、等
        // READY 后结算——此时切到别的输入框，若不作废，READY 到
        // 达后这段话会经新框的连接结算落入新框（接管结算与 READY
        // 的 pendingFinish 分支都写当时的 activeInputConnection），
        // 在新框里再按还会经接续把保留音频并入新会话。按丢弃口
        // 径作废：置 cancelPending（READY 到达时服务端会话即被
        // CANCEL 释放，一直不来由启动截止补 CANCEL 收尾）、撤待
        // 发队列里的 START（未绑定时不让它重放出幽灵会话）、清
        // 保留音频。pendingFinish 保留置位：后续流程（接管结算
        // 的待结算分支、对账门控）仍按「有过一段待结算会话」处
        // 理，只是其音频与落字资格已作废。按住中切框不在此列：
        // 服务层 onFinishInputView 已先以 stopHoldSession(discard)
        // 丢弃（holding 随之落下、pendingFinish 不会为真），本支
        // 不重复处理；同框接续也不受影响（接续发生在按下时、不
        // 经输入视图结束）
        if (pendingFinish && !holding.get()) {
            cancelPending = true
            removePending(SpeechIpc.MSG_START)
            val hadRetainedAudio = synchronized(audioSendLock) {
                val had = sessionAudio.isNotEmpty()
                sessionAudio.clear()
                sendOffset = 0
                had
            }
            // 作废真正执行时给可见提示（此前只有日志，用户侧零
            // 感知）：借失败提示通道，由本代结算尾（READY 后
            // CANCEL 的 DONE、或启动截止收尾）消费弹出；用户随
            // 即在新框再按时，提示随新会话入口清理不残留。仅在
            // 确有保留音频被清时才置：无块可废（如按下即松手、
            // 一块未录到）属无损作废，置提示是误报
            if (hadRetainedAudio) startFailureHint = INPUT_SWITCHED_HINT
            Timber.i("SpeechCli pending-finish audio discarded on input view finish")
        }
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
            // 连接代际重绕先行判定：新连接上的首个 READY（初次绑
            // 定、binder 死亡复活、发送失败自愈重绑）一律先把偏移
            // 归零——旧连接上已「发出」的块对新服务端会话不存在
            //（旧实例已随解绑销毁、其会话与队列不复存在），不归
            // 零会把保留队列头部永久跳过。此前重绕只写在 when 的
            // 第三支里，pendingFinish 支（恢复期间又松手）带着旧
            // 偏移 flush 时，新会话头部音频就此丢失，故提至分支前
            val freshConnection = connGen != readyConnGen
            if (freshConnection) {
                readyConnGen = connGen
                sendOffset = 0
            }
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
                else -> {
                    // 全量重发（freshConnection：偏移已在上方归零）与
                    // 同连接内重复 READY（不重绕、只补发未发块）都
                    // 收敛到同一调度：发送在后台线程分片进行，本处
                    // 只切状态
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
     * 本代最后一次经显示循环真正写上屏的展示文本与所属代次：partial
     * 被显示循环 CAS 消费后 composingText 快照为空，但文本实际已
     * 在屏上（松手后 finishSession/settle 的 finishComposingText 把
     * 它转正落屏）——接管结算的对账基线须认这份快照，否则「已落
     * 屏却不被 landed 承认」会让迟到 FINAL 的尾段无从对账（见
     * settlePreviousSession 的快照支）。写点在 uiJob 的上屏处（主
     * 线程），读点在接管结算入口，代次不符不认。
     */
    @Volatile
    private var lastDisplayedText: String? = null

    @Volatile
    private var lastDisplayedGen = 0

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
            // 账本同步：基线非空时是已登记快照段的扩账（改为最终
            // 全文，日后上滑撤销仍按整段直删、不会只删掉一半）；
            // 空基线（无落字武装）时账本里根本没有对应段可改，
            // 须新登记整段，否则补回的句子不进撤销账本
            if (landedText.isEmpty()) {
                VoiceUndoLedger.recordCommittedSegment(displayFinal)
            } else {
                VoiceUndoLedger.amendLastSegment(landedText, displayFinal)
            }
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
        // 上一代遗留的失败提示不带入本代：提示文案归属「哪一代失
        // 败」，残留不清会让本代结算尾误判有待弹提示、把上一代的
        // 文案弹到本代头上（窗口层只经结算尾/onFailed 消费它）
        startFailureHint = null
        // 上一代待就绪结算（pendingFinish）留下的整段保留音频由本代
        // 接续（D7）：在 settlePreviousSession 清掉待结算标记之前先
        // 记下，下方音频状态复位时据此不清队列。已丢弃的不接续
        val inheritRetainedAudio = pendingFinish && !cancelPending
        // 上一代次：下方复位块判定连按排空（其 STOP 已登记待发、
        // 尾段未发完时不清队列）要用，须赶在代次递增前记下
        val prevGen = activeGen
        // 上一会话未结算的待定文本先按快照就地结算（不等 DONE），再
        // 开始新会话——直接清掉 composingText 会把已识别出的文字吞掉
        settlePreviousSession()
        // 接管结算已消费上一代的显示快照：就地作废，防本代 uiJob
        // 尚未首次上屏期间被误读（快照读点另有代次校验，此为双
        // 保险）；本代的上屏会由 uiJob 重新记上
        lastDisplayedText = null
        lastDisplayedGen = 0
        // 音频状态决定（接续/排空登记/清队）必须赶在代次切换之
        // 前、且与在飞发送任务的代次复检同锁完成：此前登记排在
        // activeGen 写入之后（中间还隔着权限检查与模型文件检
        // 查），在飞的上一代发送任务在片边界复检时会看到「代次
        // 已变、排空未登记」，按已排空退出并抢先消费上一代
        // STOP，随后这里只剩清队分支——排空机制整体落空、尾段
        // 确定丢失。挪到切换之前后，任务在锁内看到的状态只有
        // 两种一致形态：代次未变（它仍是当前代次、正常发送）
        // 或排空已登记（按边界排空）。settlePreviousSession 不
        // 碰音频队列与 STOP 登记，本块与它的相对次序不影响判
        // 定输入（prevGen/inheritRetainedAudio 均已在上方捕获）
        synchronized(audioSendLock) {
            if (inheritRetainedAudio) {
                // 接续上一代未就绪会话的保留音频（D7）：不清队列、
                // 不归零偏移——其偏移必为 0（从未就绪即从未发送），
                // 本代录音继续往后接，就绪后整段一并推送结算
                Timber.i(
                    "voice-start inherit retained audio prevGen=%d blocks=%d",
                    prevGen, sessionAudio.size,
                )
            } else if (prevGen != 0 && stopAfterFlushGen == prevGen &&
                sendOffset < sessionAudio.size
            ) {
                // 连按排空：上一代是 live 会话、STOP 已登记待发、
                // 尾段音频还没发完——此刻清队列会把这段尾音频整
                // 批截断（其 FINAL 只能按已发部分定稿）。改不清
                // 队列：以当前队列长为排空边界，上一代的发送任务
                // 按边界把旧块排空并发出 STOP（见 drainGen 与
                // flushSomeLocked 的边界口径）；本代录音块排在边
                // 界之后，本代 READY 后从边界起续发。上一代文本
                // 已在 settlePreviousSession 按快照结算，其 FINAL
                // 迟到时经对账补齐——排空使 FINAL 含完整尾段
                drainGen = prevGen
                drainBoundary = sessionAudio.size
                scheduleFlush(prevGen)
                Timber.i(
                    "voice-start drain previous gen=%d unsent=%d",
                    prevGen, drainBoundary - sendOffset,
                )
            } else {
                sessionAudio.clear()
                sendOffset = 0
            }
        }
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
                        // 记最后实际显示文本（配本代次）：composing
                        // 快照已被本次 CAS 消费，接管结算时以它为
                        // 已落屏基线（见 lastDisplayedText）
                        lastDisplayedText = displayText
                        lastDisplayedGen = gen
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
        // 音频队列的接续/排空/清队决定已在代次切换之前完成（见
        // 上方 settlePreviousSession 之后的 audioSendLock 块）；
        // 这里只复位本代的待结算标记与打点基线，不再碰队列
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
        // START 改经音频发送执行器发出，与 STOP 同一条串行通道：
        // 连按时上一代的 STOP 还在通道里（排空尾段后补发，或已登
        // 记待发），主线程直发 START 会越过它先到服务端——服务端
        // 上一代会话仍 active，新 START 被其 CAS 拒绝且不进任何
        // 重试队列，本代永远等不到 READY。执行器单线程 FIFO 保
        // 证 STOP（及其排空）必在 START 之前发出。入队任务执行
        // 前复检代次与待结算状态（与 onServiceConnected 的 START
        // 重放过滤同口径）：排队期间本代已被收尾（activeGen 变
        // 更/清零）或被丢弃（非待结算）的，不发幽灵 START。启动
        // 截止仍自按下时刻起算（下方 armStartDeadline 不变）：
        // START 在通道里最久只等上一代排空（有界），截止语义是
        // 「按下后 12 秒内未就绪」而非「START 发出后 12 秒」，
        // 截止先于 START 执行而触发时，上面的复检会把这条 START
        // 挡下、不会在收尾后又冒出一段服务端会话
        audioFlushExecutor.execute {
            if (gen == activeGen && (holding.get() || pendingFinish)) {
                send(SpeechIpc.MSG_START, gen)
            }
        }
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
        val gen = activeGen
        val bound = synchronized(connectLock) { speechMessenger } != null
        // 待结算形态先行置位（赶在 recorder.stop() 之前）：START
        // 经发送执行器排队发出，其任务复检读的是 holding 与
        // pendingFinish——holding 刚被 CAS 落下、pendingFinish 若
        // 等下方 when 才置位，中间隔着 recorder.stop() 的调用耗
        // 时，执行器恰在此窗执行 START 任务时会把两个标记都读
        // 成 false、静默丢弃 START，本代永远等不到 READY 只能等
        // 启动截止误报失败。故按此刻的 sessionLive/bound 先把本
        // 代的待结算形态定下来：丢弃且未就绪且已绑定→
        // cancelPending（READY 到达时补 CANCEL，一直不来由启动
        // 截止补 CANCEL 收尾）；未就绪（已绑定/未绑定同款）、
        // 或已就绪但连接已断（bound=false，STOP 无从投递，只能
        // 等重绑 READY 后按待就绪整段结算）→pendingFinish
        // （READY 到达时整段结算，一直不来由启动截止按可见失败
        // 收尾）；已就绪且已绑定（待 DONE）绝不置
        // pendingFinish——断连收尾会误判成待就绪而走复活重放。
        // 下方 when 只做其余动作，不再重复置位
        if (discard) {
            if (!sessionLive && bound) cancelPending = true
        } else if (!(sessionLive && bound)) {
            pendingFinish = true
        }
        // 丢弃时先撤掉待发队列里的 START：绑定完成后不得重放出一段
        // 幽灵录音。非丢弃的未绑定支恰恰相反——START 要留在队列里
        // 等绑定完成重放（见 onServiceConnected 的重放过滤）
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
        when {
            discard && sessionLive && bound -> {
                // 服务端会话在跑：先 arm DONE 超时再发 CANCEL 丢弃
                // （其 DONE 回信走 finishSession 的 discard 分支收尾）
                armDoneTimeout()
                sendCancelNow(gen)
            }
            discard && bound -> {
                // START 已在服务端占位但 READY 未到：cancelPending
                // 已在上方置位，READY 到达时补 CANCEL（见
                // onRecordingStarted），此处无其余动作
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
                // 松手先于就绪：pendingFinish 已在上方置位，READY
                // 到达时由其分支 flush＋STOP 结算并 arm DONE 超时
            }
            else -> {
                // 未绑定（绑定还在飞）就松手：pendingFinish 已在
                // 上方置位；不能就地 finishSession——服务端会话
                // 尚未建立，此刻只能按空文本收尾、整句静默丢失。
                // 待发队列里的 START 在绑定完成时照常重放（重放
                // 过滤已为此放行）
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
        // 本代最后实际显示文本快照（代次相符才认）：partial 已被
        // 显示循环 CAS 消费时 composingText 为空、但文本已经上
        // 屏，有快照就不能在下面的空快照门控处直接返回——须进异
        // 步尾确认转正并按已落屏登记对账基线
        val displayedSnapshot = if (lastDisplayedGen == prevGen) lastDisplayedText else null
        if (!awaitingDone.get() && composingText.get() == null &&
            displayedSnapshot == null && !discarding.get()
        ) return
        // 声明本代已结算：其 DONE/超时此后不得二次结算
        settledGen = prevGen
        val service = serviceRef?.get()
        val prevText = composingText.getAndSet(null)
        val prevDiscard = discarding.get()
        // 上一代是否处于等 DONE 状态在解除超时之前捕获：异步尾
        // 的无落字对账武装（见尾部）以它为条件，disarm 后读不到
        val prevAwaitingDone = awaitingDone.get()
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
            // 验代次：本结算未被更新的结算取代时才上屏；新会话
            // （本代的直接后继）已开始是预期情形，不作废本尾。
            // 作废判据只认发起时捕获的状态：上一代自身被丢弃已
            // 在同步段按 prevDiscard 处理（不会走到本尾）；尾内
            // 再读 live 的 discarding 读到的是后继会话的丢弃——
            // 它在纠错挂起期间被上滑丢弃时，按旧判据会把上一句
            // 的结算一并作废（连坐丢句），故此处不再查它
            if (settledGen != prevGen) return@launch
            var screenIc = service.activeInputConnection()
            var prevDisplay: String? = null
            // 本次是否真的对有效连接执行了上屏提交（账本登记门控）
            var landed = false
            if (!prevText.isNullOrBlank()) {
                val corrected = withContext(Dispatchers.Default) { correctionStore.correct(prevText) }
                val displayText = withContext(Dispatchers.Default) { toDisplayText(corrected.text) }
                // 与 finishSession 尾同构：两次挂起之后、写屏之前
                // 复验（验法与本尾入口一致）并重取连接——挂起期间
                // 状态已变（本结算被更新的结算取代）或连接已指向
                // 新框时，不得用旧连接幽灵上屏，更不得登记账本
                if (settledGen != prevGen) return@launch
                val liveIc = service.activeInputConnection()
                screenIc = liveIc
                if (liveIc != null) {
                    // 落屏判据认两类证据：本次写入被编辑器接受，
                    // 或本代最后显示文本恰与定稿相同（文本本已在
                    // 屏上组字态，下方 finishComposingText 即转正，
                    // 本次改写失败不否定已在屏的事实）。写入被拒
                    // 且无在屏证据时不算落屏：登记账本会产生幽灵
                    // 账目（其副作用见 finishSession 同款判据处）
                    val writeOk = liveIc.setComposingText(displayText, 1)
                    landed = writeOk || displayedSnapshot == displayText
                    if (landed) prevDisplay = displayText
                }
            } else if (displayedSnapshot != null) {
                // composing 快照为空、但本代确有文本经显示循环上
                // 屏：快照是未过专名纠错的展示文本，与主支、与迟
                // 到 FINAL 对账（其入参先过纠错）的口径不一致——
                // 纠错改动前缀字时对账的 startsWith 必失配、整段
                // 放弃。此处同走纠错＋繁简后重写落屏，对账基线记
                // 纠错后的展示文本，三方口径统一
                val corrected = withContext(Dispatchers.Default) {
                    correctionStore.correct(displayedSnapshot)
                }
                val displayText = withContext(Dispatchers.Default) { toDisplayText(corrected.text) }
                // 新增挂起点后同主支口径复验并重取连接
                if (settledGen != prevGen) return@launch
                val liveIc = service.activeInputConnection()
                screenIc = liveIc
                if (liveIc != null) {
                    // 落屏维持证据式判定：连接有效即屏上快照会被
                    // 下方 finishComposingText 转正落屏；纠错重写
                    // 成功时落的是纠错后文本，重写被拒时落的仍是
                    // 原快照——两种情形落屏文本不同，prevDisplay
                    // 须按实际落屏者记（对账基线与账本据此）
                    val writeOk = liveIc.setComposingText(displayText, 1)
                    prevDisplay = if (writeOk) displayText else displayedSnapshot
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
            } else if (prevAwaitingDone && screenIc != null && displayedSnapshot == null) {
                // 无落字对账武装：上一代已 STOP 在等 DONE、屏上一
                // 字未出（解码积压）时被再按接管——此前对账只在
                // landed 时武装，这句的迟到 FINAL 只能按代次整段
                // 丢弃、整句静默丢失。此形态下屏上确无任何已提交
                // 文本（快照为空是前提：有快照的形态走快照支、
                // 连接有效必 landed），以空基线武装：对账时增量
                // 即整句，经 commitText 落屏后新登记账本（见
                // tryReconcileLateFinal 的空基线分支）
                lateFinalGen = prevGen
                lateFinalLandedText = ""
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
        // 本代最后显示文本快照一并在复位前捕获：resetState 会清
        // 掉它，而异步尾的落屏判据要用它作「已在屏」证据（见尾
        // 部 landed 判据），尾内再读只会读到复位后的空值
        val displayedSnapshot = if (lastDisplayedGen == gen) lastDisplayedText else null
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
                    // 落屏判据认两类证据：本次写入被编辑器接受，
                    // 或本代最后显示文本恰与定稿相同（文本已在屏
                    // 上组字态、下方 finishComposingText 即转正，
                    // 改写被拒不否定在屏事实）。两类皆无（写入被
                    // 拒、屏上又不是这段文本）时不算落屏：
                    // committed 不置、账本不登记——按已落屏登记会
                    // 产生幽灵账目（撤销误删＋清恢复栈，见下方）
                    val writeOk = liveIc.setComposingText(displayText, 1)
                    landed = writeOk || displayedSnapshot == displayText
                    if (landed) {
                        committed = corrected
                        committedDisplay = displayText
                    }
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
        // 先停过，这里兜底错误/断连等未经 stop 的收尾路径）。排空
        // 标记一并撤：队列既已清空，排空任务此后只剩补发其 STOP
        // 的职责（flushLoop 按登记照常发出，排空判定自然落空）
        synchronized(audioSendLock) {
            sessionAudio.clear()
            sendOffset = 0
            drainGen = 0
            drainBoundary = 0
        }
        sessionLive = false
        pendingFinish = false
        cancelPending = false
        sessionHasPartial = false
        // 迟到 FINAL 对账窗口随任一会话收尾关闭：本代之后的会话若
        // 已结算过，编辑器内容已变迁，旧句增量再补必拼错位置
        lateFinalGen = 0
        lateFinalLandedText = null
        // 显示快照同理随收尾作废：它只对紧接着的接管结算有意义
        lastDisplayedText = null
        lastDisplayedGen = 0
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