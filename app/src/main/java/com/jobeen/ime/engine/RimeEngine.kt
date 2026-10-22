package com.jobeen.ime.engine

import android.content.Context
import android.content.SharedPreferences
import android.inputmethodservice.InputMethodService
import android.os.SystemClock
import android.text.InputType
import android.view.KeyEvent.*
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import androidx.core.content.edit
import com.jobeen.ime.ImeApplication
import com.jobeen.ime.base.speech.VoiceUndoLedger
import com.jobeen.ime.base.util.InputConnectionUtil
import com.jobeen.ime.base.util.PinYinUtil
import com.jobeen.ime.base.util.TextUtil
import com.jobeen.ime.engine.behavior.IBehavior
import com.jobeen.ime.input.InputFieldKey
import com.jobeen.ime.input.isInputFieldChanged
import com.jobeen.ime.engine.rime.behavior.Segmentation
import com.jobeen.ime.data.database.AppDatabase
import com.jobeen.ime.data.manager.CandidateManager
import com.jobeen.ime.data.manager.CandidatePreferCache
import com.jobeen.ime.data.manager.CandidateSortingManager
import com.jobeen.ime.data.manager.applySavedCandidateOrder
import com.jobeen.ime.data.manager.candidateSortingKey
import com.jobeen.ime.data.manager.DeletedWordsStore
import com.jobeen.ime.data.manager.SchemaManager
import com.jobeen.ime.engine.event.KeyEvent
import com.jobeen.ime.engine.event.KeyModifiers
import com.jobeen.ime.engine.rime.host.BehaviorHost
import com.jobeen.ime.engine.data.CandidatePinYin
import com.jobeen.ime.engine.data.EngineMessage
import com.jobeen.ime.engine.data.EngineMessage.Candidate
import com.jobeen.ime.engine.rime.behavior.InputKey
import com.jobeen.ime.engine.rime.behavior.InputString
import com.jobeen.ime.engine.rime.behavior.Reset
import com.jobeen.ime.engine.rime.behavior.SelectPinYin
import com.jobeen.ime.engine.rime.behavior.Selection
import com.jobeen.ime.engine.rime.core.IRimeJob
import com.jobeen.ime.engine.rime.core.RimeApi
import com.jobeen.ime.engine.rime.daemon.RimeDaemon
import com.jobeen.ime.engine.rime.daemon.RimeSession
import com.jobeen.ime.engine.manager.CandidateRerankManager
import com.jobeen.ime.engine.manager.PredictionManager
import com.jobeen.ime.engine.rime.core.KeyMapping
import com.jobeen.ime.engine.rime.core.EngineMessageConverter
import com.jobeen.ime.engine.rime.core.KeycapFilterState
import com.jobeen.ime.engine.rime.core.Rime
import com.jobeen.ime.engine.rime.core.RimeConfig
import com.jobeen.ime.engine.rime.core.RimeMessage
import com.jobeen.ime.data.App.modelDir
import com.jobeen.ime.engine.rime.data.DataManager.sharedDataDir
import com.jobeen.ime.base.util.TraditionalConverter
import com.jobeen.ime.engine.rime.util.OptionsApplier
import com.jobeen.ime.input.ImeInputMethodService
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import java.lang.ref.WeakReference
import kotlin.lazy

/**
 * 借道 DEL 的来源标记：占 KeyModifiers 的 bit 29（既有命名位只到
 * bit 12 与 24–28、30，metaState 映射也不含此位，不影响透传给宿
 * 主的修饰状态）。只有 handleBackspaceInJob 产生的借道 DEL 带此
 * 标记——它在 jobs 串行执行时点已确认组字为空。标记是「组字为空
 * 已确立」这一前提的来源凭据：forwardUnhandledKey 的选区判定对
 * 带标记与无标记（native 未消费透传）两路同口径（判定表见该函数），
 * 不再按标记分流处理，但两路的组字前提不同，凭据保留备查。
 */
internal const val BORROWED_DEL_MARKER: Int = 1 shl 29

class RimeEngine : IEngine, IBehaviorHost, IRimeJob {

    companion object {
        /**
         * librime native 层“禁止个性化学习”运行时开关
         *（见 cpp/patches/librime-no-personalized-learning.patch，
         * Memory::OnCommit 为 true 时跳过用户词典自学习）。
         * 非 schema switch，切换方案不会被重置；每次 onStartInputView 按输入框重设。
         */
        private const val NO_PERSONALIZED_LEARNING_OPTION = "__no_personalized_learning"

        /** 选词偏好表容量上限与裁剪检查节流间隔（按 upsert 次数计）。 */
        /** 分页补取的单页条数（与首屏 bulk 上限分开：续页可以大些，减少往返） */
        private const val CANDIDATE_PAGE_SIZE = 48
        /** 每批候选补取封顶：再深处是字体无字形的扩展区生僻字，不加载 */
        private const val CANDIDATE_BATCH_CAP = 200
        /** 动作/任务队列容量：远超正常深度，仅作异常堆积兜底 */
        private const val ACTION_QUEUE_CAPACITY = 512
        private const val JOB_QUEUE_CAPACITY = 256
        /** 清空输入框时的分段删除：每段字符数与最大段数 */
        private const val CLEAR_CHUNK_CHARS = 1000
        private const val MAX_CLEAR_CHUNKS = 64
    }

    private data class EngineState(
        var initialized: Boolean = false,
        // 联想可见标志跨线程读写：写点分布在 reduce、jobs（退格
        // 处理）与主线程（onFinishInputView 切框收尾），读点在 jobs
        // 与主线程（loadMoreCandidates 的防串味守卫）。普通 var 无
        // 可见性保证，读到过期值会串味（联想显示期误放行分页补取
        // 拼进组字候选）或吞退格。单写单读的布尔标志无复合操作，
        // @Volatile 的可见性即足够，不需要 AtomicBoolean
        @Volatile
        var predictionVisible: Boolean = false,
        var suppressNextEmptyCandidates: Boolean = false,
        var initHookTriggered: Boolean = false,
        // 代次计数器被主线程（切框失效）与 jobs/协程并发推进：普通
        // Long 的 ++ 是读-改-写，极端时序下两代同号会让迟到的旧结果
        // 穿过 Ready 门控；用 AtomicLong 保证 incrementAndGet 不重号
        // （字段与 Action.requestId 均为 Long，故用 AtomicLong 而非
        // AtomicInteger，免去每处取值转换）
        val candidateRequestId: java.util.concurrent.atomic.AtomicLong =
            java.util.concurrent.atomic.AtomicLong(0L),
        // 「最新代次」的发布（还原入口/失效路径在 Default 线程）与
        // 读取（reduce/jobs 线程）跨线程交接。两个发布方都是「在
        // candidateRequestId 上 incrementAndGet 取新号、再回写本
        // 字段」的两步操作：若回写用 set，两步交错（甲取 11、乙取
        // 12、乙先 set(12)、甲后 set(11)）会让已发布的最新代次回退
        // 到 11，最新一批候选被 Ready 门控误丢、或已被作废的旧批
        // 反而穿过门控。故发布一律走 accumulateAndGet(maxOf)：本
        // 字段单调不减，任何交错下都不回退；AtomicLong 同时保证
        // 单次读写的原子性与跨线程可见性
        val latestCandidateRequestId: java.util.concurrent.atomic.AtomicLong =
            java.util.concurrent.atomic.AtomicLong(0L),
        val predictionRequestId: java.util.concurrent.atomic.AtomicLong =
            java.util.concurrent.atomic.AtomicLong(0L),
        // 与 latestCandidateRequestId 同口径：「最新代次」的发布方
        // （requestPrediction 在 reduce 线程、invalidatePending-
        // Prediction 在主线程）都是先 incrementAndGet 取新号再回写
        // 本字段的两步操作，普通 var 回写交错会让最新代次回退——
        // 已作废的旧联想穿过 PredictionReady 门控在切框后复现，或
        // 最新一批联想被误丢。发布一律 accumulateAndGet(maxOf)，
        // 单调不减、读写原子且跨线程可见
        val latestPredictionRequestId: java.util.concurrent.atomic.AtomicLong =
            java.util.concurrent.atomic.AtomicLong(0L),
    )

    private sealed interface Action {
        data class ProcessKey(val service: InputMethodService, val key: KeyEvent) : Action

        /**
         * 状态键（空格/回车/退格）的二次分发：分类在 [processKeyInternal]
         * 完成，但真正入 jobs 的动作推迟到 reduce 处理本 Action 时才做。
         * 普通键经 Action.Behavior 绕 actions 一圈后才在 reduce 里入
         * jobs，状态键若在分类当时直接 sendJob，就会插到排在前面的
         * 普通键之前（积压时空格先于字母被处理）。两路都在 reduce 的
         * 同一串行点入队，按键到达顺序才等于 jobs 执行顺序。
         */
        data class StatefulKey(val key: KeyEvent.CodeEvent) : Action
        data class RimeMessage(val message: com.jobeen.ime.engine.rime.core.RimeMessage<*>) :
            Action

        data class Behavior(val behavior: IBehavior) : Action
        data class SelectCandidate(val candidate: Candidate) : Action
        data class Clear(val service: InputMethodService) : Action
        data class Undo(val service: InputMethodService) : Action
        data class Redo(val service: InputMethodService) : Action
        /**
         * @property fieldGen 发起时刻的输入框代次（在分发点捕获）：
         * 预测的基底读取与框复检都以这一代为准——代次若等到
         * requestPrediction 执行时才取，分发与执行之间发生的换框
         * 会被误读成「仍是发起时的框」。null 表示在 reduce 内直
         * 接发起、无分发时差，执行时现取即可。
         */
        data class Predict(val commit: String, val fieldGen: Long? = null) : Action
        data class PredictionReady(
            val requestId: Long,
            val candidates: List<Candidate>,
            /** 发起预测时的输入框代次：交付时与当前代次复检，防旧框联想落到新框 */
            val fieldGen: Long,
        ) : Action
        data class EmitMessage(val message: EngineMessage) : Action
        data class CandidatesReady(val requestId: Long, val message: EngineMessage.Candidates) :
            Action

        /**
         * 偏好快照加载完成信号：加载监听只投递本 Action，待刷新登
         * 记的取出、批次复检与 restoreCandidates 重跑全部在 reduce
         * 串行点内做——监听在缓存的加载线程上同步执行，若在那里
         * 「比较代次通过→直接调 restoreCandidates」，比较与重跑不
         * 是原子的（其间插入的新批次会被旧登记的重跑取消并抢发），
         * 且 restoreCandidates 会脱离 reduce 线程跨线程触碰
         * candidateRestoreJob。经本 Action 排队后，三者与新批次的
         * 还原同在一个串行点上，交错不复存在。
         */
        data object PreferLoaded : Action

        data class PossibleCandidatePinYinSnapshot(
            val candidatePinYinType: String,
            val currentInput: String,
            val confirmedLen: Int,
        ) : Action

        data object Reset : Action
        data class SelectCandidatePinYin(val pinYin: CandidatePinYin) : Action
        data object Segment : Action
        data class SelectSchema(val schemaId: String) : Action
        data class Commit(val text: String) : Action
        data object InputCleared : Action
        data object Reload : Action
    }

    private val daemon by lazy { RimeDaemon }
    private val scope by lazy { CoroutineScope(SupervisorJob() + Dispatchers.Default) }

    /**
     * 选词偏好写的发起通道：实际落库、裁剪与计数落点由
     * [CandidatePreferCache.recordClick] 在缓存侧的串行通道内成对
     * 执行，本 scope 只负责把调用投递出去（不占 jobs 队列，慢盘
     * 不堵后续按键）并作异常兜底。用 limitedParallelism(1) 保住
     * 多次投递的相对顺序与原先在 jobs 内时一致。
     */
    private val preferDbScope by lazy {
        CoroutineScope(
            SupervisorJob() + Dispatchers.IO.limitedParallelism(1) +
                // 兜底：本通道的 launch 块应各自 try/catch（见
                // selectCandidateInternal）；万一有漏网异常，默认
                // 未捕获异常处理会把进程杀掉——选词偏好这种尽力而为
                // 的写不值得赔上键盘进程，只记日志吞掉
                kotlinx.coroutines.CoroutineExceptionHandler { _, e ->
                    if (e !is CancellationException) {
                        Timber.w(e, "Uncaught failure in preferDbScope")
                    }
                }
        )
    }
    // 两条队列都有界（旧实现 UNLIMITED）：正常深度个位数，容量只为异常
    // 生产者（引擎通知风暴、长任务堵住消费端）兜底防无限堆积。溢出策略
    // 分两边：jobs 全是控制任务，走 sendJob/awaitJob 的异步补投、永不丢；
    // actions 经 dispatchAction 分发，易变类满时自丢、控制类异步补投。
    private val actions = Channel<Action>(ACTION_QUEUE_CAPACITY)
    private val jobs by lazy { Channel<suspend RimeApi.() -> Unit>(JOB_QUEUE_CAPACITY) }
    private var session: RimeSession? = null
    private var behaviorHosted: BehaviorHost? = null
    private var context: Context? = null

    @Volatile
    private var inputConnection: InputConnection? = null
    @Volatile
    private var editorInfo: EditorInfo? = null

    // 用户搭配学习链：上一个打字上屏的词段（简体形）。只由打字路径
    // （native 上屏、预测候选点选）推进；前端提交（剪贴板/常用语/语音
    // 等 CommitAction）不学且打断此链；输入结束/隐私闸门时清空。
    private var lastLearnSegment: String? = null
    // 弱引用：EngineFactory 单例持有 RimeEngine，强引用 service 会导致 service 销毁后泄漏
    private var serviceRef: WeakReference<ImeInputMethodService>? = null

    //引擎相关配置监控
    private var prefs: SharedPreferences? = null
    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (OptionsApplier.isOptionDependency(key)) {
            sendJob {
                currentSchema().applyOptions(this)
                // 与 SelectSchema/Schema 消息同口径补刷字段选项：
                // applyOptions 按方案 lock 值整表重刷运行时选项，会
                // 覆盖密码框强制英文（ascii_mode）等字段选项，必须
                // 随后按当前输入框重放一次（形态与 SelectSchema 的
                // 补刷一致）
                editorInfo?.let { applyFieldOptions(it) }
            }
        }
    }

    /** 桥接模式下返回虚拟连接，否则返回真实连接。 */
    private fun inputConnection(): InputConnection? =
        serviceRef?.get()?.activeInputConnection() ?: inputConnection

    private val rerankManager by lazy { context?.let { CandidateRerankManager(it) } }
    private val predictionManager by lazy { context?.let { PredictionManager(it) } }
    private val state = EngineState()

    /**
     * 光标前文本缓存：getTextBeforeCursor() 是跨进程 Binder 调用，
     * 之前每次产生候选都会调一次。这里缓存最近 64 个字符，TTL 800ms；
     * 新输入框 / 上屏 / 删除文本时主动失效。
     *
     * 隐私与代次复检在真正读 IC 的主线程块内做（而非只在调用线程
     * 预检）：预检与读之间隔着主线程队列，其间可能已切框或切到
     * 密码框——队列里排在读之前的 onStartInputView 会先更新
     * editorInfo/代次，读点复检才拦得住。[expectedGen] 非空时还
     * 要求当前代次与之相等（预测 job 以发起代次读基底：基底与代
     * 次在读点同处校验，不再有「发起点先读基底、代次随后才取」
     * 的时点差）。任一复检不过返回 null 且不写缓存，调用方应整
     * 轮作废；缓存新鲜（TTL 内且代次吻合）时直接切片返回，不重
     * 复读 IC。
     *
     * 缓存以「内容＋读取时刻＋所属代次」的单个快照发布（见
     * [BeforeCursorCache]），命中与提交都核对代次。此前实现把
     * 内容与时间存成两个独立字段，提交又发生在主线程块返回后
     * 的调用线程上、无条件回填：读取飞行中若已换框（代次推进
     * ＋失效），旧框前文仍会被写回、把已失效的缓存复活，新框
     * 后续读取命中旧框上下文（R4）。故提交前以读取时捕获的代
     * 次再核一次当前代次，不一致整轮作废、绝不回填；命中路径
     * 同样先核对快照代次，不再短路掉本应执行的代次校验。
     */
    private val beforeCursorCache = BeforeCursorCache()

    private suspend fun peekTextBeforeCursor(n: Int, expectedGen: Long? = null): String? {
        val now = SystemClock.uptimeMillis()
        val currentGen = predictionFieldGen.get()
        beforeCursorCache.freshText(now, currentGen)?.let { cached ->
            // 命中也不短路代次校验：调用方带着发起代次来读，代次
            // 已被推进时这份上下文不属于它，整轮作废
            if (expectedGen != null && expectedGen != currentGen) return null
            return sliceBeforeCursor(cached, n)
        }
        // 本轮读取所属代次：调用方给了发起代次就用它（与主线程
        // 块内复检、提交复检同一口径），没给（重排）就以发起时
        // 点的当前代次为准
        val readGen = expectedGen ?: currentGen
        // InputConnection 非线程安全、契约要求主线程访问：本类其余
        // 读写点都显式 withContext(Main.immediate)，此探针原先在
        // Default 线程直调，是唯一例外。只有真正读 IC 的填缓存段
        // 切主线程；TTL 判定与切片留在调用线程，800ms 语义不变
        val fresh = withContext(Dispatchers.Main.immediate) {
            if (expectedGen != null && predictionFieldGen.get() != expectedGen) {
                return@withContext null
            }
            if (isNoPersonalizedLearning(editorInfo)) {
                return@withContext null
            }
            runCatching {
                inputConnection()?.getTextBeforeCursor(64, 0)?.toString() ?: ""
            }.getOrDefault("")
        } ?: return null
        // 提交前复检（R4）：读取期间代次已被推进（换框）则整轮
        // 作废——这份前文属旧框，既不交付本轮、也不回填缓存
        if (!beforeCursorCache.publish(fresh, now, readGen, predictionFieldGen.get())) {
            return null
        }
        return sliceBeforeCursor(fresh, n)
    }

    private fun sliceBeforeCursor(text: String, n: Int): String {
        val want = n.coerceIn(0, 64)
        return if (text.length <= want) text else text.takeLast(want)
    }

    private fun invalidateBeforeCursorCache() {
        beforeCursorCache.invalidate()
    }
    /**
     * 输入框代次：换框时推进——onStartInputView(!restarting)，以及
     * restarting 但关键字段已变（实为换框，见 [InputFieldKey]）。
     * 预测发起时捕获，交付复检与 job 内复检都按代次比对，取代旧的
     * EditorInfo 引用同一性（===）——同一性比较把「同框 restartInput
     * 换发新 EditorInfo 实例」与「换框」混为一谈，且无法表达代次
     * 先后。同框重启（字段未变）不推进，框没换、在途联想仍属本框。
     */
    private val predictionFieldGen = java.util.concurrent.atomic.AtomicLong(0L)

    /**
     * 前端提交（剪贴板/常用语/语音/符号键等经 [requestCommit]）待
     * 发起预测的文本队列（FIFO，规格见 [FrontendPredictionQueue]）：
     * requestCommit 的 job 先把文本入队、再 emitCommit 产生消息
     * （条目先于消息可见；emit 抛错按令牌回滚本条目），
     * handleRimeMessage 在对应直送 Commit 落屏（emitMessage）之
     * 后认领并发起预测——预测的发起点由此落在 reduce 的落屏之
     * 后，job 内读到的是落屏后的真实前文。条目带入队时的输入框
     * 代次，换框推进代次时整队清空，认领时队首文本与代次都要
     * 对上；空格/回车的直送 Commit 不入队，其文本（" "/"\n"）与
     * 队首不等时不认领。残存巧合：前端文本恰为 " "/"\n" 且同一时
     * 窗有空格/回车直送 Commit 在途时，可能由后者提前认领——后
     * 果仅为一轮以落屏后真实前文计算的预测提前/缺席，不产生错
     * 误上屏、不参与搭配学习（本路径 learnable 恒 false）。
     */
    private val pendingFrontendPredictions = FrontendPredictionQueue()

    /** 上一个输入框的身份快照：restarting 重启时比对是否实为换框（见 [InputFieldKey]）。 */
    @Volatile
    private var lastInputFieldKey: InputFieldKey? = null

    /**
     * 偏好缓存加载完成监听只注册一次（见 restoreCandidates 的待刷新
     * 登记）。监听注册在进程级 [CandidatePreferCache] 上，注销句柄保
     * 存在 [preferLoadListenerUnregister]，由 [finalize] 在引擎销毁
     * 时调用注销——不注销则引擎每次重建叠加一条，旧实例被监听闭包
     * 持有滞留，且其投递的 PreferLoaded 虽会被已关闭的 actions 通
     * 道丢弃，仍是无谓的跨实例噪声。
     */
    private val preferLoadListenerRegistered =
        java.util.concurrent.atomic.AtomicBoolean(false)

    /** [ensurePreferLoadListener] 注册返回的注销句柄（注册在还原 job 线程、注销在销毁线程，须原子交接）。 */
    private val preferLoadListenerUnregister =
        java.util.concurrent.atomic.AtomicReference<(() -> Unit)?>(null)

    /**
     * 偏好未加载时先行出候选的最新一批（msg 与其代次）：加载完成
     * 后若它仍是最新批次，重跑一次 restoreCandidates 补上偏好重
     * 排。已被更新批次取代的登记在 reduce 处理
     * [Action.PreferLoaded] 时按代次比对丢弃（取出/复检/重跑同在
     * 串行点内，见该 Action 的说明）。
     */
    private val pendingPreferRefresh =
        java.util.concurrent.atomic.AtomicReference<Pair<EngineMessage.Candidates, Long>?>(null)

    @Volatile
    private var predictionJob: Job? = null

    /**
     * 候选还原 job 句柄。写点只剩两处：[restoreCandidates]（reduce
     * 串行点，cancel 旧的再赋新的）与 [invalidatePendingCandidates]
     * （reduce 多路与 onFinishInputView 的主线程收尾都会调，cancel
     * 后置空）。偏好加载监听曾是第三个跨线程触点（在加载线程上直
     * 接重跑 restoreCandidates)，已改为只投递 [Action.PreferLoaded]
     * 回到 reduce 内重跑，消除。余下两处以 @Volatile 保可见性即
     * 够：Job.cancel 本身线程安全；赋值交错最坏让一次作废漏取消一
     * 个刚启动的还原 job，其结果仍被 CandidatesReady 的 requestId
     * 门控按代次丢弃，不会把旧批次候选发出去。
     */
    @Volatile
    private var candidateRestoreJob: Job? = null
    /** 分页补取的批次状态：已从引擎取到的原始候选条数（分页 start 必须用原始计数，不能用 UI 过滤后的条数） */
    @Volatile
    private var candidateBatchLoaded: Int = 0
    /** 本批候选总数：-1 = 未知（首屏取满、后面可能还有） */
    @Volatile
    private var candidateBatchTotal: Int = -1
    /** 分页补取在飞标记（批次代次）：-1 = 无在飞，否则为在飞批次的 latestCandidateRequestId */
    private val loadMoreBatch = java.util.concurrent.atomic.AtomicLong(-1L)
    /** 候选拖拽排序表判空缓存：null 语义用 false+首次查询实现，保存时由 resortCandidates 失效 */
    @Volatile
    private var sortingTableEmpty: Boolean = false
    // UI 消息通道：控制类 emit 保序不丢、易变类（状态型）最新值
    // 合流必达，分流与合流的规格见 EngineMessageChannel——旧实现
    // 易变类走 tryEmit、缓冲满静默丢自己，一串快打的最后一批组
    // 字被丢后无下一批自愈、胶囊永久滞留旧值（wode 截图定案，
    // 第十六批改为合流通道）
    private val messageChannel = EngineMessageChannel()

    /** 全部 UI 消息的统一出口（语义见 [EngineMessageChannel]） */
    private suspend fun emitMessage(msg: EngineMessage) {
        messageChannel.emit(msg)
    }

    override fun initialize(context: Context) {
        val appContext = context.applicationContext
        this@RimeEngine.context = appContext

        val app = this@RimeEngine.context as ImeApplication
        app.notifyState(ImeApplication.AppState.EngineStarting)
        behaviorHosted = BehaviorHost(this)

        scope.launch {
            for (action in actions) reduce(action)
        }

        //observe engine Messages at first.
        scope.launch {
            daemon.observeMessages { actions.send(Action.RimeMessage(it)) }
        }
        session = daemon.createSession(javaClass.name)
        scope.launch {
            for (job in jobs) {
                try {
                    session?.runOnReady {
                        // 字段选项（禁个性化学习等隐私项）待补设时，在
                        // 本 job 之前先行应用：不依赖补设 job 在队列中
                        // 与按键 job 的相对位置——队列满时 sendJob 的
                        // 异步补投会让两者乱序，密码框最初几键可能在
                        // native 学习仍开启时被处理。消费端是唯一串行
                        // 执行点，在这里补设顺序即确定。
                        if (fieldOptionsPending) {
                            fieldOptionsPending = false
                            editorInfo?.let { applyFieldOptions(it) }
                        }
                        job()
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // 单个 job 失败（如引擎在就绪前被停掉，whenReady 抛
                    // RimeStoppedException）只丢这一个 job 并记日志，
                    // 不能让异常杀掉整个 job 消费循环、堵死后续所有引擎操作
                    Timber.w(e, "Rime job failed")
                }
            }
        }
        //监听引擎注册事件
        prefs = appContext.getSharedPreferences(
            CandidateManager.PREFS_NAME, Context.MODE_PRIVATE
        ).also {
            it.registerOnSharedPreferenceChangeListener(prefsListener)
        }
        //以结束为号
        sendJob {
            joinMaintenanceThread()
            actions.send(
                Action.RimeMessage(
                    RimeMessage.DeployMessage(RimeMessage.DeployMessage.State.Finish)
                )
            )
            currentSchema().applyOptions(this)
        }
    }

    override fun finalize() {
        prefs?.unregisterOnSharedPreferenceChangeListener(prefsListener)
        prefs = null
        // 偏好加载监听注册在进程级 CandidatePreferCache 上：与上方
        // prefs 监听同口径在此注销，否则引擎重建逐次叠加、旧实例
        // 被监听闭包滞留（见 preferLoadListenerRegistered 的说明）。
        // 注销后复位注册标记，本实例若被复用可重新注册
        preferLoadListenerUnregister.getAndSet(null)?.invoke()
        preferLoadListenerRegistered.set(false)
        actions.close()
        jobs.close()
        scope.cancel()
        preferDbScope.cancel()
        predictionManager?.destroy()
        daemon.destroySession(javaClass.name)
    }

    override fun processKey(service: InputMethodService, key: KeyEvent) {
        dispatchAction(Action.ProcessKey(service, key))
    }

    private fun processKeyInternal(key: KeyEvent) {
        if (!state.initialized) {
            return
        }
        when (key) {
            is KeyEvent.SequenceEvent -> {
                // 序列键在分类 job 内原本即无条件转发、不读任何状态：
                // 直接分发 Behavior，省去纯转发的一跳。行为的 native
                // 工作仍由 Behavior 经 sendJob 入 jobs 队列执行。
                dispatchAction(Action.Behavior(InputString(key.sequence)))
            }

            is KeyEvent.CodeEvent -> {
                when (key.keyCode) {
                    // 空格/回车/退格的去向取决于执行时点的实时组字，
                    // 只能留在 job 内串行判定（见 sendStatefulKeyJob）；
                    // 但入 job 的动作本身经 Action 二次分发，与普通键
                    // 的 Behavior 在 reduce 同一点入队，避免状态键凭
                    // 直达 sendJob 插到在途普通键之前（见 StatefulKey）
                    KEYCODE_SPACE, KEYCODE_ENTER, KEYCODE_DEL ->
                        dispatchAction(Action.StatefulKey(key))

                    KEYCODE_APOSTROPHE -> {
                        // 分隔符键在分类 job 内同样是无条件转发
                        dispatchAction(Action.Behavior(Segmentation()))
                    }

                    else -> {
                        // 其余普通键在分类 job 内不读状态、只转发
                        // InputKey：直接分发，绕开纯转发 job
                        dispatchAction(
                            Action.Behavior(
                                InputKey(key.keyCode, key.modifiers.toInt(), key.isVirtual)
                            )
                        )
                    }
                }
            }
        }
    }

    /**
     * 空格/回车/退格的分类 job（经 Action.StatefulKey 在 reduce 里
     * 调用，入队点与普通键的 Behavior 对齐，见 StatefulKey）：三者
     * 的去向都取决于 jobs 串行执行时点的实时组字（getRawInput），
     * 不能在分发侧凭组字缓存预判——compositionCached.preedit 是
     * 展示串而非原始输入，且分发时点上先行按键的 job 可能尚未
     * 执行、缓存滞后于在途按键，预判会重蹈退格注释里跨队列按旧
     * 状态误判的覆辙。空格/回车在空组字时直通文本、非空时在本 job
     * 内同步转交 Rime（与退格的 handleBackspaceInJob 同口径，见
     * 下方非空分支）；退格的判断与处理更必须同 job 完成
     * （见 handleBackspaceInJob）。
     */
    private fun sendStatefulKeyJob(key: KeyEvent.CodeEvent) {
        sendJob {
            when (key.keyCode) {
                KEYCODE_SPACE -> {
                    if (getRawInput().isEmpty()) {
                        // 经 emitCommit 走引擎消息管线而非直送 actions：
                        // 上一键的 native Commit 尚在管线中（转换/分发
                        // 含 rime-main 补取）时，直送会超车它先落屏，
                        // 上屏顺序错乱（如「你好」未落、空格先行）
                        emitCommit(" ")
                        return@sendJob
                    }
                }

                KEYCODE_DEL -> {
                    // 判断与处理收进本 job 内完成（见 handleBackspaceInJob）：
                    // 不再先在本 job 取组字状态、再经 actions 队列转发处理，
                    // 避免跨队列期间组字变化导致按旧状态误判
                    handleBackspaceInJob()
                    return@sendJob
                }

                KEYCODE_ENTER -> {
                    if (getRawInput().isEmpty()) {
                        // 同空格分支：经 emitCommit 与 native 上屏同
                        // FIFO，避免回车超车上一键的文字先行触发发送
                        emitCommit("\n")
                        return@sendJob
                    }
                }
            }
            // 组字非空的空格/回车（退格已在上方分支内处理）：在本 job
            // 内同步转交 Rime，与 InputKey.invoke() 的转交内容逐字
            // 等价（键码映射＋修饰键＋isVirtual），只是不再绕回
            // actions 队尾另发一个 job。此前绕回 actions 时，本键的
            // 处理 job 要排到 reduce 消费完在途动作之后才入队，积压
            // 时落在本键之后到达的普通键 job 之后（a、空格、b 连按
            // 会先把 b 并入组字、再拿 "ab" 的首选上屏），即按键被
            // 后续键超车。InputKey 在 BehaviorHost 里不登记任何队列
            // 状态（flowedLocked 的 InputKey 分支只是 invoke 转交），
            // 跳过 flowBehavior 这一跳不丢队列维护，与退格经
            // backspaceInJob 在本 job 内直达 Rime 同构。
            processKey(
                KeyMapping.keyCodeToVal(key.keyCode),
                key.modifiers.toInt().toUInt(),
                key.isVirtual,
            )
            return@sendJob
        }
    }

    override fun selectCandidate(candidate: Candidate) {
        dispatchAction(Action.SelectCandidate(candidate))
    }

    private suspend fun selectCandidateInternal(candidate: Candidate) {
        // 选词后旧组字的候选还原结果作废，防止迟到结果把已清空的候选复活
        invalidatePendingCandidates()
        // 隐私：密码框 / 声明 IME_FLAG_NO_PERSONALIZED_LEARNING 的输入框不做任何学习
        //（不读前文、不写偏好表、不跑预测上下文）；正常上屏不受影响
        val noLearn = isNoPersonalizedLearning(editorInfo)
        if (candidate.type == Candidate.TYPE_IME_PREDICTION) {
            // 落屏之后再发起预测（与打字上屏路径同口径）：job 内
            // 读到的是落屏后的真实前文，已含本次点选词，不在
            // reduce 上、落屏之前同步读前文（那会让本次上屏等一
            // 次主线程 IPC 往返）
            emitMessage(EngineMessage.Commit(candidate.text))
            invalidateBeforeCursorCache()
            // 预测候选点选属于打字流上屏：参与搭配学习
            requestPrediction(candidate.text, learnable = true)
            return
        }

        // Only Rime selection produces the empty candidate response that must be hidden.
        // Prediction candidates bypass Rime and must not arm this flag.
        state.suppressNextEmptyCandidates = true
        if (!noLearn) {
            // 偏好写走独立串行 IO 通道（见 preferDbScope），不再占
            // jobs 队列堵后续按键；落库、节流裁剪与计数落点由缓存
            // 侧在同一串行通道内成对执行（见
            // CandidatePreferCache.recordClick），不再拆成「本通道
            // upsert、缓存通道落点」两段——两段分属不同通道时，首
            // 次加载的读库段可插进对子中间造成双计
            val ctx = context
            if (ctx != null) {
                preferDbScope.launch {
                    // 整段兜住：盘满（SQLiteFullException）、还原期间
                    // 库被替换等失败只丢这一次偏好，异常不得逃出
                    // launch——此前写在 jobs 里时异常被 jobs 消费循环
                    // 的通用 catch 吸收，移出后必须在本处补上同等吸收，
                    // 否则未捕获异常会杀进程（scope 上另有
                    // CoroutineExceptionHandler 作第二层兜底）
                    try {
                        val dao = AppDatabase.getInstance(ctx).candidatePreferDao()
                        CandidatePreferCache.recordClick(candidate.text, dao)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Timber.w(e, "Failed to persist candidate preference")
                    }
                }
            }
        }
        flowBehavior(Selection(candidate.index))
    }

    /**
     * 是否禁止个性化学习：统一判定在 [InputFieldPolicy]（密码类输入框，
     * 或输入框声明了 IME_FLAG_NO_PERSONALIZED_LEARNING）。
     * 生效范围：本 engine 的选词偏好表/缓存/预测/重排，以及经由
     * [NO_PERSONALIZED_LEARNING_OPTION] 控制的 librime native 用户词典自学习；
     * 语音纠错学习（VoiceCorrectionSession）也过同一判定。
     */
    private fun isNoPersonalizedLearning(info: EditorInfo?): Boolean =
        com.jobeen.ime.base.util.InputFieldPolicy.isNoPersonalizedLearning(info)

    /** 是否为文本类密码框（数字密码走数字键盘，不在此列）。 */
    private fun isPasswordTextField(info: EditorInfo?): Boolean {
        if (info == null) return false
        if ((info.inputType and InputType.TYPE_MASK_CLASS) != InputType.TYPE_CLASS_TEXT) return false
        return when (info.inputType and InputType.TYPE_MASK_VARIATION) {
            InputType.TYPE_TEXT_VARIATION_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD -> true
            else -> false
        }
    }

    override fun resetComposition() {
        dispatchAction(Action.Reset)
    }

    override fun moveCursor(service: InputMethodService, direction: Int) {
        sendJob {
            if (compositionCached.preedit?.isNotEmpty() == true) {
                // 正在输入拼音：交给 Rime 处理方向键，移动 preedit 内的 caret（Rime 原生行为）
                processKeyInternal(
                    KeyEvent.CodeEvent(
                        if (direction < 0) KEYCODE_DPAD_LEFT else KEYCODE_DPAD_RIGHT,
                        KeyModifiers.Empty,
                    )
                )
            } else {
                withContext(Dispatchers.Main.immediate) {
                    val ic = inputConnection() ?: return@withContext
                    // 结束编辑器侧 composing（如语音听写），避免与编辑器光标状态错位
                    ic.finishComposingText()
                    val keyCode = if (direction < 0) KEYCODE_DPAD_LEFT else KEYCODE_DPAD_RIGHT
                    ic.sendKeyEvent(android.view.KeyEvent(ACTION_DOWN, keyCode))
                    ic.sendKeyEvent(android.view.KeyEvent(ACTION_UP, keyCode))
                }
            }
        }
    }

    override fun selectCandidatePinYin(pinYin: CandidatePinYin) {
        dispatchAction(Action.SelectCandidatePinYin(pinYin))
    }

    override fun segement() {
        dispatchAction(Action.Segment)
    }

    override fun selectSchema(schemaId: String) {
        dispatchAction(Action.SelectSchema(schemaId))
    }

    override fun undo(service: InputMethodService) {
        dispatchAction(Action.Undo(service))
    }

    override fun redo(service: InputMethodService) {
        dispatchAction(Action.Redo(service))
    }

    override fun resortCandidates(candidates: List<Candidate>) {
        val ctx = context ?: return
        if (candidates.isEmpty()) return
        val db = AppDatabase.getInstance(ctx)
        // 有保存动作即排序表非空，失效 restoreCandidates 的判空缓存
        sortingTableEmpty = false
        sendJob {
            // 键的两个输入只在 RimeApi 作用域内可读，必须在本 job
            // 内取齐：组字串读 compositionCached（与组字产生同步
            // 赋值的缓存），方案读 schemaCached（方案变更消息处理点
            // 同步回写的缓存，与还原端同源同值）；无组字（预测态）
            // 时键为空，save 内部据此跳过。候选列表在此拷一份快照，
            // 写库移出 jobs 后由 preferDbScope 异步消费，调用方的
            // 列表对象不能再被原地改动影响到落库内容
            val key = candidateSortingKey(
                compositionCached.preedit.orEmpty(), schemaCached.schemaId
            )
            val snapshot = candidates.toList()
            // 写库与选词偏好同走 preferDbScope 串行 IO 通道（与 28
            // 号原则一致：Room 写不占 jobs、不堵后续按键）。代价是
            // 保存与还原之间不再有 jobs FIFO 的先后保证：还原端
            // load 读的是 Room 而非内存表，紧随保存触发的同一批
            // 还原可能读到落库前的值（本批沿用旧序、下一批起按新
            // 序，属有界的最终一致，不会错套到别的组字上——键含
            // 组字串与方案，两端同源）
            preferDbScope.launch {
                try {
                    CandidateSortingManager(db).save(snapshot, key)
                    // 落库成功后把判空缓存再置一次非空：入口处置 false
                    // 与真正落库之间隔着 jobs→preferDbScope 两跳，其间
                    // 若有一次还原执行到 isTableEmpty()，读到的仍是空
                    // 表、会把缓存打回 true 并在此后一直短路还原——刚
                    // 保存的顺序在本进程内永不生效，直到再拖一次或重
                    // 启。落库已成事实的时点再置一次，缓存与库内事实
                    // 收敛（save 内部因空键跳过时表内仍可能有旧记录，
                    // 置 false 同样正确；表真为空时下一次还原查库即把
                    // 缓存重新置 true）。restore 端读点口径不变。
                    sortingTableEmpty = false
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Timber.w(e, "Failed to save candidate sorting")
                }
            }
        }
    }

    override fun deleteCandidate(index: Int) {
        sendJob { deleteCandidate(index, global = true) }
    }


    override fun flowed(behavior: IBehavior): Boolean {
        dispatchAction(Action.Behavior(behavior))
        return true
    }

    private fun flowBehavior(behavior: IBehavior): Boolean =
        behaviorHosted?.flowed(behavior) == true

    override fun resetState() {
        dispatchAction(Action.Reset)
    }

    private fun possibleCandidatePinYin() {
        sendJob {
            if (!PinYinUtil.isValidType(schemaCached.candidateKind)) {
                return@sendJob
            }
            val currentInput = getRawInput()
            val confirmedLen = getInputConfirmedPosition()
            dispatchAction(
                Action.PossibleCandidatePinYinSnapshot(
                    schemaCached.candidateKind, currentInput, confirmedLen
                )
            )
        }
    }

    private suspend fun reduce(action: Action) {
        when (action) {
            is Action.ProcessKey -> {
                serviceRef = (action.service as? ImeInputMethodService)?.let(::WeakReference)
                processKeyInternal(action.key)
            }

            is Action.StatefulKey -> sendStatefulKeyJob(action.key)

            is Action.RimeMessage -> handleRimeMessage(action.message)
            is Action.Behavior -> flowBehavior(action.behavior)
            is Action.SelectCandidate -> selectCandidateInternal(action.candidate)
            is Action.Clear -> {
                serviceRef = (action.service as? ImeInputMethodService)?.let(::WeakReference)
                clearInternal()
            }

            is Action.Undo -> {
                serviceRef = (action.service as? ImeInputMethodService)?.let(::WeakReference)
                sendJob {
                    if (compositionCached.preedit?.isNotEmpty() == true) {
                        // 正在输入拼音：上滑撤销=清除当前正在打的拼音。
                        // 不能给编辑器发 Ctrl+Z，拼音在输入法内部，编辑器撤销够不着还会产生副作用。
                        actions.send(Action.Reset)
                    } else {
                        withContext(Dispatchers.Main.immediate) {
                            val ic = inputConnection()
                            // 先查语音上屏账本：最近一段语音文字还在光标前
                            // 原样躺着就整段直删。语音走组字转正，编辑器
                            // 撤销栈的记账口径不一（分多步、甚至不入栈），
                            // 盲发 Ctrl+Z 经常删不完一整段。不命中（上屏
                            // 后打过字/移过光标/账本无账）才回落 Ctrl+Z。
                            val undoneByLedger = ic != null && VoiceUndoLedger.tryUndo(ic)
                            if (!undoneByLedger) {
                                InputConnectionUtil.sendCombinationKeyEvent(
                                    ic, KEYCODE_Z, ctrl = true
                                )
                            }
                        }
                    }
                }
            }

            is Action.Redo -> {
                serviceRef = (action.service as? ImeInputMethodService)?.let(::WeakReference)
                sendJob {
                    if (compositionCached.preedit?.isNotEmpty() == true) {
                        // 组字中恢复与旧实现同口径：盲发组合键，不查账本
                        // （组字是输入法内部状态，账本恢复够不着也不该动）
                        InputConnectionUtil.sendCombinationKeyEvent(
                            inputConnection(), KEYCODE_Z, ctrl = true, shift = true
                        )
                    } else {
                        withContext(Dispatchers.Main.immediate) {
                            val ic = inputConnection()
                            // 先查语音账本恢复栈：账本直删绕开了编辑器
                            // 的撤销栈，Ctrl+Shift+Z 对这笔删除无账可
                            // 恢复；删除后状态未被动过时由账本整串写回。
                            // 不命中（期间打过字/删过字/无恢复账）才回落
                            // 组合键——打字撤销的恢复仍走编辑器自己的栈
                            val redoneByLedger = ic != null && VoiceUndoLedger.tryRedo(ic)
                            if (!redoneByLedger) {
                                InputConnectionUtil.sendCombinationKeyEvent(
                                    ic, KEYCODE_Z, ctrl = true, shift = true
                                )
                            }
                        }
                    }
                }
            }

            is Action.Predict -> requestPrediction(
                action.commit,
                fieldGenOverride = action.fieldGen,
            )
            is Action.PredictionReady -> {
                // requestId 只防同框内的请求交错；输入框已切换
                // （代次推进）时旧框联想直接丢弃，不落到新框
                if (action.requestId == state.latestPredictionRequestId.get() &&
                    action.fieldGen == predictionFieldGen.get()
                ) {
                    // 预测路径同样要过滤已删除词（删除过滤不能只挂在 Rime 候选还原上）
                    val visible = action.candidates.filterNot { DeletedWordsStore.isDeleted(it.text) }
                    state.predictionVisible = visible.isNotEmpty()
                    // 预测候选是本地联想的完整列表、没有分页：total 显式给
                    // 确切条数，避免下游按 -1（未知）误判"还有更多"而在
                    // 预测态触发引擎分页补取、把组字候选拼进联想列表
                    emitMessage(EngineMessage.Candidates(visible, 0, 0, total = visible.size))
                }
            }

            is Action.EmitMessage -> emitMessage(action.message)

            is Action.PossibleCandidatePinYinSnapshot -> {
                val pinYins = behaviorHosted?.possiblePinYin(
                    action.candidatePinYinType, action.currentInput, action.confirmedLen
                ) ?: emptyList()
                emitMessage(EngineMessage.PossibleCandidatePinYin(pinYins))
            }

            is Action.CandidatesReady -> {
                if (action.requestId == state.latestCandidateRequestId.get()) {
                    emitMessage(action.message)
                }
            }

            Action.PreferLoaded -> {
                // 待刷新登记的取出、批次复检与重跑同在本串行点内，
                // 与新批次的还原天然互斥（见 Action.PreferLoaded）。
                // 已被更新批次取代的登记直接丢弃：新批次自己会走已
                // 加载的正常路径或重新登记
                val pending = pendingPreferRefresh.getAndSet(null)
                if (pending != null &&
                    pending.second == state.latestCandidateRequestId.get()
                ) {
                    restoreCandidates(pending.first)
                }
            }

            Action.Reset -> {
                invalidatePendingCandidates()
                invalidatePendingPrediction()
                // resetState/resetComposition 两个入口都汇入本分
                // 支（总复位点）：一并清消息通道的待补发槽，防
                // 复位前滞留的易变旧帧抢在新组字前补发（审查 A-2）
                messageChannel.clearPending()
                flowBehavior(Reset())
            }

            is Action.SelectCandidatePinYin -> flowBehavior(SelectPinYin(action.pinYin))
            Action.Segment -> flowBehavior(Segmentation())
            is Action.SelectSchema -> {
                flowBehavior(Reset())
                sendJob {
                    selectSchema(action.schemaId)
                    // ApplySchema 会清掉 librime 的 "_" 开头 transient options（含
                    // __no_personalized_learning），密码框 ascii 强制也可能被重置：
                    // 同一输入框内切方案后按当前输入框统一重设字段选项
                    editorInfo?.let { applyFieldOptions(it) }
                }
            }

            is Action.Commit -> requestCommit(action.text)
            Action.InputCleared -> {
                invalidatePendingCandidates()
                invalidatePendingPrediction()
                if (state.predictionVisible) {
                    state.predictionVisible = false
                    emitMessage(EngineMessage.Candidates(emptyList(), 0, 0))
                }
            }

            Action.Reload -> {
                behaviorHosted?.resetState()
                sendJob {
                    deploy()
                    joinMaintenanceThread()
                    actions.send(
                        Action.RimeMessage(
                            RimeMessage.DeployMessage(RimeMessage.DeployMessage.State.Finish)
                        )
                    )
                }
            }
        }
    }

    /**
     * 退格处理（在 processKeyInternal 的 rime job 内同步执行）：「组字是否
     * 为空」的判断与后续处理落在同一个 job 里，不再跨 actions/jobs 两条
     * 队列——旧实现先取状态再转发，期间组字被其它按键改掉就会按旧状态
     * 误判（组字已空仍转交 Rime、或组字未空却删了宿主文本）。
     * 组字非空时交给 [BehaviorHost.backspaceInJob] 在本 job 内同步消化
     * （其队列维护的第二套状态也在同一 job 内一并推进）；组字为空时才
     * 走原有出口：先收联想候选，再按选区/宿主 DEL 处理。
     */
    private suspend fun RimeApi.handleBackspaceInJob() {
        if (getRawInput().isNotEmpty()) {
            behaviorHosted?.backspaceInJob(this)
            return
        }
        if (state.predictionVisible) {
            state.predictionVisible = false
            emitMessage(EngineMessage.Candidates(emptyList(), 0, 0))
            return
        }
        // 「有选区只删选区、无选区删一字」的判定不在本 job 内做：
        // 上一键的 native Commit 尚在消息管线中未落屏时，宿主里还
        // 是替换前的旧选区，按它判定走「删选区」分支，等 Commit
        // 落屏后选区已不存在，这次退格会被白白吞掉。统一借道
        // KeyMessage 透传，由 forwardUnhandledKey 在实际执行删除
        // 的时点重读选区再决定（其判定点排在在途 Commit 的消息
        // 之后）。不经 native processKey，避免被方案处理器吞掉
        // 后宿主收不到键。借道 DEL 带来源标记（见
        // BORROWED_DEL_MARKER）：本 job 内刚确认组字为空，是透传
        // 点区分「借道」与「native 未消费透传」的唯一凭据
        emitKey(KeyMapping.keyCodeToVal(KEYCODE_DEL), BORROWED_DEL_MARKER, true)
        invalidateBeforeCursorCache()
    }

    /**
     * 引擎未处理按键的透传：native 拒绝的按键原先经 converter 落到
     * EngineMessage.Unknown，在消费端被直接丢弃，目标应用收不到
     * （个别按键在应用里无反应）。这里按 Android 键事件补发给输入连接。
     * 修饰键状态由 [KeyModifiers.metaState] 还原；Release 修饰只补发抬起。
     * 映射不回 Android 键码的（[KeyMapping.Key_VoidSymbol] 等）原始键码在
     * 进入引擎的映射阶段已经丢失、无法还原，仍只能丢弃并记日志。
     */
    private suspend fun forwardUnhandledKey(message: RimeMessage.KeyMessage) {
        // 内部合成按键（isVirtual=false，如部署 hook 的 processKey
        // (Key_Delete,…,false)、退格在 job 内重走的 BackSpace）不是用户
        // 真实按键：native 未处理也不透传给宿主，避免凭空删字/触发宿主按键
        if (!message.data.isVirtual) return
        val keyCode = message.data.value.keyCode
        if (keyCode == KEYCODE_UNKNOWN) {
            Timber.d("Drop unhandled key without Android mapping: %s", message.data.value)
            return
        }
        val metaState = message.data.modifiers.metaState
        val now = SystemClock.uptimeMillis()
        if (keyCode == KEYCODE_DEL && !message.data.modifiers.release) {
            // 退格删除判定表（DEL 且非 release 的三条路径）：
            //  一、组字非空：不经本函数——handleBackspaceInJob 在
            //      job 内由 BehaviorHost 同步消化（删组字码），不产
            //      生透传消息。
            //  二、带标记借道 DEL（见 BORROWED_DEL_MARKER）：组字为
            //      空已由 handleBackspaceInJob 在同一 job 内确立（判
            //      定与 emitKey 之间无其他组字变更点，组字变更全在
            //      rime-main 串行线；消息管线全局 FIFO 保证本消息排
            //      在该 job 之后产生的组字消息之前到达 reduce）。
            //  三、无标记 virtual DEL（native 未消费透传）：组字状
            //      态未经 job 确立，但 native 在组字非空时消费
            //      BackSpace，透传到此的实际形态即组字为空（唯一的
            //      isVirtual=true behavior 来源 Backspace 无构造点、
            //      不可达）；若此路不判选区径发键事件，「组字空＋有
            //      选区」的删除会绕过下方 Commit 的三件套（改道/记
            //      账/探针通知），与路径二及旧路径口径不一致。
            // 路径二、三在此同口径判定：删除形态在执行时点按当前
            // 选区决定——有选区只删选区、无选区才落到下方按 DEL
            // 键事件删光标前一字。判定点在 reduce 消费流内、排在在
            // 途 Commit 的消息之后：在 job 内提前判定会读到 Commit
            // 落屏前的旧选区，按它走删选区分支，等落屏后选区已不
            // 存在，这次退格会被白白吞掉。
            // 严禁在此回 jobs 查组字（awaitJob）：那读到的是 jobs
            // 队尾执行时点的状态、与消息时点错位（后续按键已并入
            // 组字时会误判有组字），还让整个 reduce 最坏干等 2 秒
            // ——已随第十三批 L2 废除，不得恢复。
            val hasSelection = withContext(Dispatchers.Main.immediate) {
                !runCatching { inputConnection()?.getSelectedText(0) }
                    .getOrNull().isNullOrEmpty()
            }
            if (hasSelection) {
                // 选区删除不直达 InputConnection，而是以直送
                // Commit 送回引擎消息管线：MessageHandler 对
                // Commit 有剪贴板搜索态改道（interceptCommit）、
                // 本地写代次记账（noteLocalWrite）与探针通知合并
                // （scheduleNotifyInputChanged）三件套处理，直达
                // commitText 会整段绕过。此处本就在 reduce 内，
                // 直接 emitMessage 与借道管线落到同一条下游处
                // 理，不必再绕 jobs→rime-main 一圈（绕行期间第
                // 二次退格的判定会赶在第一个 Commit("") 落屏前
                // 重复走删选区分支，两次退格只删一次选区）。
                emitMessage(EngineMessage.Commit("", direct = true))
                // 与旧删选区路径同口径：宿主文本已变，前文缓存失效
                invalidateBeforeCursorCache()
                return
            }
        }
        withContext(Dispatchers.Main.immediate) {
            val ic = inputConnection() ?: return@withContext
            if (!message.data.modifiers.release) {
                ic.sendKeyEvent(
                    android.view.KeyEvent(now, now, ACTION_DOWN, keyCode, 0, metaState)
                )
            }
            ic.sendKeyEvent(
                android.view.KeyEvent(now, now, ACTION_UP, keyCode, 0, metaState)
            )
        }
    }

    private suspend fun handleRimeMessage(message: RimeMessage<*>) {
        if (message is RimeMessage.KeyMessage) {
            forwardUnhandledKey(message)
            return
        }
        val msg = EngineMessageConverter.convert(message)
        // 打字上屏的预测在函数尾部发起（见尾部说明）：发起点落在
        // emitMessage 之后，预测 job 内读到的是落屏后的真实前文
        // （含本次上屏词），不在此同步读前文——那会让本次上屏在
        // reduce 串行点上等一次主线程 IPC 往返才落屏
        var predictionText: String? = null
        when (msg) {
            is EngineMessage.InlinePreedit -> {
                if (msg.preedit.isEmpty()) {
                    behaviorHosted?.resetState()
                }
                possibleCandidatePinYin()
                return
            }

            is EngineMessage.Commit -> {
                // 直送 Commit（空组字空格/回车经 emitCommit 借道管线）
                // 只为与 native Commit 同 FIFO 排序：旧直送路径
                // （Action.EmitMessage）在 reduce 里只做本函数尾部的
                // emitMessage、不触任何记账，这里同口径整段跳过——
                // 否则空格/回车会被记成一次打字上屏：suppress 标记
                // 无随行候选可消耗、会滞留吞掉后续空候选更新；
                // requestPrediction 还会多跑一轮必空预测（并清掉上
                // 一轮联想显示）、且把空格/回车作为可学段打断候选
                // 学习链。落屏不受影响：emitMessage(msg) 照常执行，
                // 其下游消费者（MessageHandler 落屏、KeyboardState-
                // Manager 组字态）旧路径同样经 emitMessage 到达，
                // 行为不变
                if (!msg.direct) {
                    invalidatePendingCandidates()
                    candidateBatchLoaded = 0
                    candidateBatchTotal = -1
                    state.suppressNextEmptyCandidates = true
                    // native 刚上屏：前文缓存失效，后续读到新鲜前文
                    invalidateBeforeCursorCache()
                    // 打字上屏：参与搭配学习。只记下文本，实际发起
                    // 在本函数尾部（见 predictionText）
                    predictionText = msg.text
                }
            }

            is EngineMessage.Candidates -> {
                // 记录分页批次：首屏 bulk 的原始条数与总数信号（total=-1 为
                // 取满上限、后面可能还有），供 loadMoreCandidates 续取
                candidateBatchLoaded = msg.list.size
                candidateBatchTotal = msg.total
                if (msg.list.isNotEmpty()) {
                    // Rime candidates take over the panel from prediction candidates.
                    state.predictionVisible = false
                    invalidatePendingPrediction()
                }
                if (state.suppressNextEmptyCandidates) {
                    state.suppressNextEmptyCandidates = false
                    if (msg.list.isEmpty()) {
                        return
                    }
                }
                restoreCandidates(msg)
                return
            }

            is EngineMessage.Schema -> {
                // librime 内部切方案（switcher/lua 等）不走 Action.SelectSchema：
                // ApplySchema 会 ClearTransientOptions 清掉 "_" 开头的运行时
                // 选项（含 __no_personalized_learning），且没有上层路径补设，
                // 密码框等场景的禁学习会被内部切方案绕过。这里在方案变更
                // 消息路径上按 SelectSchema/Deploy 的同款方式补设一次。
                sendJob { editorInfo?.let { applyFieldOptions(it) } }
            }

            is EngineMessage.Depoly -> {
                when (msg.state) {
                    EngineMessage.Depoly.State.Start -> state.initialized = false
                    EngineMessage.Depoly.State.Finish -> {
                        state.initialized = true
                        val triggerHook = !state.initHookTriggered
                        state.initHookTriggered = true
                        sendJob {
                            val prefs = context?.getSharedPreferences(
                                SchemaManager.PREFS_NAME, Context.MODE_PRIVATE
                            )
                            val enabledIds =
                                prefs?.getString(SchemaManager.KEY_ENABLED_IDS, "")?.split(",")
                                    ?.filter { it.isNotBlank() }
                            val schemas = enabledSchemata()
                            if (enabledIds.isNullOrEmpty()) {
                                val ids = schemas.joinToString(",") { it.id }
                                prefs?.edit { putString(SchemaManager.KEY_ENABLED_IDS, ids) }
                            }
                            val currentSchema = currentSchema()
                            // openSchema 是 librime native 入口：本 job 运行在
                            // jobs 线程，直调会绕开 rime-main 串行线。派发到
                            // rime-main 执行并等待结果，读取失败按无语法模型降级
                            val grammarLanguage = runCatching {
                                Rime.runOnRimeMain {
                                    RimeConfig.openSchema(currentSchema.schemaId).use { config ->
                                        config.getString("grammar/language")
                                    }
                                }
                            }.getOrNull()
                            grammarLanguage?.let {
                                Timber.d("predictionManager load model %s.gram", it)
                                predictionManager?.loadModels(modelDir, sharedDataDir, it)
                            }

                            if (triggerHook) {
                                processKey(KeyMapping.Key_Delete, 0U, false)
                                context?.let { it as ImeApplication }
                                    ?.notifyState(ImeApplication.AppState.Finished)
                            }

                            // 部署重建了 native 会话：字段选项（禁个性化学习、
                            // 密码框强制英文）随会话丢失，按当前输入框重设
                            // （Reload 的完成路径也走这里）。初始化部署时
                            // editorInfo 为空，自动跳过。
                            editorInfo?.let { applyFieldOptions(it) }
                        }
                    }

                    else -> {}
                }
            }

            else -> {}
        }
        emitMessage(msg)
        // 发起预测（打字上屏）：emitMessage 返回时本次 Commit 已
        // 投递，collector 在 Main 的落屏处理排在预测 job 随后投递
        // 的 withContext(Main) 之前，job 内读到的前文必然含本次
        // 词——上下文在 job 内读落屏后的真实文本，不在发起点拼
        // 装（发起点同步读会把一次 IPC 往返压在落屏之前；拼装则
        // 会把宿主过滤器/自动纠正改过的落屏结果排除在外）
        predictionText?.let { text ->
            requestPrediction(text, learnable = true)
        }
        // 前端提交的预测发起点（见 pendingFrontendPredictions）：
        // 直送 Commit 已在上方 emitMessage 落屏，此刻认领并发起
        // ——job 内同读落屏后前文，不拼接。认领须队首文本与当
        // 前输入框代次都对上（换框时队列已整队清空，认领侧再
        // 按代次挡一层，见 FrontendPredictionQueue）
        if (msg is EngineMessage.Commit && msg.direct &&
            pendingFrontendPredictions.claim(msg.text, predictionFieldGen.get())
        ) {
            requestPrediction(msg.text)
        }
    }

    override fun observeMessages(
        scope: CoroutineScope, onMessage: suspend (EngineMessage) -> Unit
    ): Job = messageChannel.observe(scope, onMessage)

    override suspend fun schemasList(): List<EngineMessage.Schema> =
        awaitJob(emptyList()) {
            enabledSchemata().map {
                EngineMessage.Schema(
                    it.id, it.name, it.layout, it.punctuation, it.kind
                )
            }
        }


    override fun clear(service: InputMethodService) {
        dispatchAction(Action.Clear(service))
    }

    private suspend fun clearInternal() {
        invalidatePendingCandidates()
        invalidatePendingPrediction()
        val clearPredictions = state.predictionVisible
        state.predictionVisible = false
        if (clearPredictions) {
            emitMessage(EngineMessage.Candidates(emptyList(), 0, 0))
        }
        sendJob {
            if (compositionCached.preedit?.isNotEmpty() == true) {
                actions.send(Action.Reset)
            } else {
                withContext(Dispatchers.Main.immediate) {
                    val ic = inputConnection()
                    if (ic != null && !ic.getTextBeforeCursor(1, 0).isNullOrEmpty()) {
                        // 分段删除：旧实现单次 deleteSurroundingText(MAX, MAX)
                        // 在部分 App 的自绘输入框上会只删一部分或触发全量文本
                        // 处理造成卡顿。按段删（前文→后文），每轮先探长度、
                        // 探到 0 或删除无进展即停，段数设上限防异常输入框
                        // 让循环失控；极端长文删不完时用户可再触发一次。
                        var chunks = 0
                        while (chunks++ < MAX_CLEAR_CHUNKS) {
                            val n = ic.getTextBeforeCursor(CLEAR_CHUNK_CHARS, 0)?.length ?: 0
                            if (n == 0 || !ic.deleteSurroundingText(n, 0)) break
                        }
                        chunks = 0
                        while (chunks++ < MAX_CLEAR_CHUNKS) {
                            val n = ic.getTextAfterCursor(CLEAR_CHUNK_CHARS, 0)?.length ?: 0
                            if (n == 0 || !ic.deleteSurroundingText(0, n)) break
                        }
                        invalidateBeforeCursorCache()
                    }
                }
            }
        }
    }

    /**
     * 动作队列的非挂起分发入口：先 trySend；队列满时易变类直接丢弃
     * （预测/候选回执有 requestId 守卫，候选/组字类引擎通知会被下一批
     * 取代），控制类（按键、选词、切方案、上屏、清空等）转异步挂起
     * 补投、永不丢。协程上下文内可直接 actions.send 挂起等待。
     */
    private fun dispatchAction(action: Action) {
        if (actions.trySend(action).isSuccess) return
        if (action.isDroppable()) return
        scope.launch { actions.send(action) }
    }

    private fun Action.isDroppable(): Boolean = when (this) {
        // PreferLoaded 是刷新提示：丢了只是这批候选不补重排，下一批
        // 在已加载后走快照直读自愈（未加载则重新登记），可丢
        is Action.PredictionReady, is Action.CandidatesReady, is Action.PreferLoaded -> true
        is Action.RimeMessage -> when (message.messageType) {
            RimeMessage.MessageType.Candidate, RimeMessage.MessageType.Composition,
            RimeMessage.MessageType.InlinePreedit, RimeMessage.MessageType.DynamicPreedit,
            RimeMessage.MessageType.Menu -> true
            else -> false
        }
        else -> false
    }

    override fun sendJob(block: suspend RimeApi.() -> Unit) {
        if (jobs.trySend(block).isFailure) {
            // 队列满说明引擎正被长任务堵住：任务都是控制类（导词典、
            // 清屏、切方案等），不许丢，转异步挂起入队等空位
            scope.launch { jobs.send(block) }
        }
    }

    override suspend fun <T> awaitJob(defaultValue: T, block: suspend RimeApi.() -> T): T {
        val deferred = CompletableDeferred<T>()
        val task: suspend RimeApi.() -> Unit = {
            try {
                deferred.complete(block())
            } catch (t: Throwable) {
                // 捕获面保持 Throwable、等待方仍以默认值收尾，行为
                // 不变；这里只补可诊断性。捕获面不能收窄到
                // Exception：Error（OOM 等）一旦穿透 task，会落进
                // jobs 消费循环并杀死它（其 catch 只有
                // CancellationException 与 Exception 两档），后果远
                // 比静默降级严重。CancellationException 是引擎收尾
                // 的正常路径，不打错误日志
                if (t !is CancellationException) {
                    if (t is Error) {
                        Timber.e(t, "awaitJob task failed with an Error; completing with default value")
                    } else {
                        Timber.e(t, "awaitJob task failed; completing with default value")
                    }
                }
                deferred.complete(defaultValue)
            }
        }
        if (jobs.trySend(task).isFailure) {
            // 同 sendJob：满时异步补投，任务仍会执行；等待方有 2 秒
            // 超时兜底，超时先返回默认值，迟到的 complete 自动忽略
            scope.launch { jobs.send(task) }
        }
        return withTimeoutOrNull(2000L) { deferred.await() } ?: defaultValue
    }

    /**
     * 分页补取：首屏 bulk 只取少量候选（native 上限），UI 滚动到底时经此
     * 按已加载原始条数续取下一页。批次锚点用 latestCandidateRequestId：
     * 补取在途时用户又打了键，新批次会 ++ 该值，回来时对不上即丢弃，
     * 不会把上一编码的候选拼进新列表。追加页不走 restoreCandidates 的
     * 重排/排序还原（那套只管首屏），仅过滤已删词与（触发条件成立时）
     * 键帽表情、保持引擎原序。
     */
    override fun loadMoreCandidates() {
        // 预测（联想）显示期间不补取：分页补的是引擎组字候选，拼进
        // 联想列表会串味。正常由消息侧 total 保证预测态不触发，这里
        // 再兜一道（批次计数是上一组字批次的残留值）
        if (state.predictionVisible) return
        val start = candidateBatchLoaded
        if (start <= 0) return
        val total = candidateBatchTotal
        if (total >= 0 && start >= total) return
        val batch = state.latestCandidateRequestId.get()
        // 在飞守卫按批次代次判定：同批次已有补取在飞才跳过；标记停在
        // 旧批次（旧 job 被停机吞掉、其 finally 没跑到）时与当前批次
        // 天然不等，直接接管放行，不会永久滞留
        while (true) {
            val inFlight = loadMoreBatch.get()
            if (inFlight == batch) return
            if (loadMoreBatch.compareAndSet(inFlight, batch)) break
        }
        // 封顶：每批最多累计取 CANDIDATE_BATCH_CAP 个候选。词库深处是
        // 设备字体无字形的扩展区生僻字（渲染为方框、打出也无处可用），
        // 200 已覆盖全部真实输入需求，不再往深处加载
        val limit = minOf(CANDIDATE_PAGE_SIZE, CANDIDATE_BATCH_CAP - start)
        sendJob {
            try {
                if (limit <= 0) {
                    // 已达封顶：回空追加页把 UI 的「还有更多」关掉
                    candidateBatchTotal = start
                    emitMessage(
                        EngineMessage.Candidates(
                            list = emptyList(),
                            highlighted = 0,
                            page = 0,
                            total = start,
                            append = true,
                        )
                    )
                    return@sendJob
                }
                val page = getCandidates(start, limit)
                if (state.latestCandidateRequestId.get() != batch) return@sendJob
                val raw = page.size
                if (raw == 0) {
                    candidateBatchTotal = start
                    return@sendJob
                }
                candidateBatchLoaded = start + raw
                if (raw < limit || candidateBatchLoaded >= CANDIDATE_BATCH_CAP) {
                    candidateBatchTotal = candidateBatchLoaded
                }
                val rawItems = page.mapIndexed { i, c ->
                    EngineMessage.Candidate(
                        index = start + i,
                        text = c.text,
                        comment = c.comment,
                        type = c.type,
                    )
                }.filterNot { DeletedWordsStore.isDeleted(it.text) }
                // 键帽表情过滤与首屏同口径（第十七批补覆盖）：触发
                // 状态在 KeycapFilterState（转换器随组字消息刷新），
                // 本页在批次校验之后才构造；极窄窗口内可能读到下
                // 一轮已刷新的状态，后果仅限本页且随新首屏自愈；
                // 只摘条目、保留其余条目的引擎全局下标，分页计数
                // （candidateBatchLoaded 按 raw 条数推进）不受过滤影响
                val items = KeycapFilterState.applyTo(rawItems)
                if (items.isNotEmpty()) {
                    emitMessage(
                        EngineMessage.Candidates(
                            list = items,
                            highlighted = 0,
                            page = 0,
                            total = candidateBatchTotal,
                            append = true,
                        )
                    )
                }
            } finally {
                // 只清自己批次的标记：批次已推进时标记多半已被新批次
                // 接管，无条件清零会误清新批次的在飞守卫，同一页被
                // 两条 job 各取一次、重复追加
                loadMoreBatch.compareAndSet(batch, -1L)
            }
        }
    }

    private fun restoreCandidates(msg: EngineMessage.Candidates) {
        // 分页在飞标记已按批次代次化（loadMoreBatch）：新批次到来时
        // 下方推进的 latestCandidateRequestId 与标记天然不等，
        // loadMoreCandidates 直接接管放行——旧 job 在停机窗口被吞掉
        // 也不会永久滞留，无需在此复位；其 finally 也只清自己批次
        val requestId = state.candidateRequestId.incrementAndGet()
        // 本批所属的输入框代次：job 内读前文时随 peek 一并校验
        // （与联想 job 的发起代次同口径）——换框后本批要么读到新
        // 框前文、要么整轮作废，不得消费旧框的缓存前文（R4）
        val fieldGen = predictionFieldGen.get()
        state.latestCandidateRequestId.accumulateAndGet(requestId) { a, b -> maxOf(a, b) }
        candidateRestoreJob?.cancel()
        candidateRestoreJob = scope.launch {
            // 过滤用户长按删除的词（Rime 墓碑不隐藏系统词，App 层过滤）；
            // 兜底分支也要用过滤后的列表，故在 try 外先算好
            val filtered = msg.list.filterNot { DeletedWordsStore.isDeleted(it.text) }
            val filteredMsg = if (filtered.size == msg.list.size) msg
                else EngineMessage.Candidates(filtered, msg.highlighted, msg.page, total = msg.total)
            try {
                val ctx = context
                if (ctx == null) {
                    actions.send(Action.CandidatesReady(requestId, filteredMsg))
                    return@launch
                }

                val db = AppDatabase.getInstance(ctx)
                // 用户拖拽保存的顺序是对该候选集的显式意愿：无论是否开启
                // 智能重排都要应用（此前重排分支直接丢弃保存顺序，而重排
                // 默认开启，拖拽排序存进数据库也永不生效、拖完必回原位）。
                // 排序表为空时（绝大多数用户从未保存过）跳过查询：旧实现
                // 每键都算一次指纹 + 查一次 Room，结果注定为 null
                val savedOrder = if (sortingTableEmpty) {
                    null
                } else {
                    val mgr = CandidateSortingManager(db)
                    if (mgr.isTableEmpty()) {
                        sortingTableEmpty = true
                        null
                    } else {
                        // 按本批候选所属的组字串＋方案查保存顺序（与保存
                        // 端同源同值）；保存的文本哈希序列可套到本批
                        // 子集上。组字缓存与当前方案只在 RimeApi 作用域
                        // 内可读，经 awaitJob 一次取齐——仅排序表非空
                        // （用户真拖过）时才走这里，从未拖过的用户被
                        // 上面的判空缓存短路、热路径无感
                        val key = awaitJob("") {
                            candidateSortingKey(
                                compositionCached.preedit.orEmpty(),
                                schemaCached.schemaId,
                            )
                        }
                        // awaitJob 与按键共用 jobs FIFO：它排队期间，
                        // 后续按键可能已把组字推进到下一批，此时读到的
                        // 组字属于新组字、其保存顺序套到本批候选上即
                        // 错套。故落地前验本批代次：本批已被更新代次
                        // 取代（新批已开始还原）则弃用这份保存顺序，
                        // 不让它借本批的落地通道把新组字的顺序套进来
                        if (state.latestCandidateRequestId.get() != requestId) {
                            null
                        } else {
                            mgr.load(key)
                        }
                    }
                }
                val rerankEnabled = CandidateManager.isRerankEnabled(ctx)
                if (rerankEnabled) {
                    // 隐私模式下不读光标前文（gramDb 为空时前文本来也不参与打分）；
                    // 非隐私模式走前文缓存，避免每次候选刷新都跨进程读一次
                    val inputContext = if (isNoPersonalizedLearning(editorInfo)) "" else
                        peekTextBeforeCursor(20, expectedGen = fieldGen) ?: ""
                    // 偏好快照：已加载时是内存直读；未加载时不在本
                    // job 里等加载——加载段与点击写事务同在缓存的
                    // 跨挂起互斥内，首屏等它就等于等在途写事务整
                    // 段。先用当前缓存（可能为空）出候选，并登记本
                    // 批待刷新：后台加载完成后，监听投递的
                    // Action.PreferLoaded 在 reduce 内复检它仍是最新
                    // 批次时重跑一次 restoreCandidates 补上偏好重排
                    // （见 pendingPreferRefresh）
                    val prefers = if (CandidatePreferCache.isLoaded()) {
                        CandidatePreferCache.snapshot(ctx)
                    } else {
                        ensurePreferLoadListener()
                        pendingPreferRefresh.set(msg to requestId)
                        CandidatePreferCache.ensureLoadInBackground(ctx)
                        CandidatePreferCache.currentCache()
                    }
                    // gram 分数此前一直传 null 恒为 0：模型由 PredictionManager
                    // 加载并持有，这里取同一实例接进重排打分
                    val sortedList = rerankManager?.rerank(
                        filteredMsg.list, inputContext, predictionManager?.gramDb(), prefers
                    )
                    val baseList = sortedList ?: filteredMsg.list
                    actions.send(
                        Action.CandidatesReady(
                            requestId, EngineMessage.Candidates(
                                if (savedOrder.isNullOrEmpty()) baseList
                                else applySavedCandidateOrder(baseList, savedOrder),
                                0, 0, total = filteredMsg.total
                            )
                        )
                    )
                } else {
                    actions.send(
                        Action.CandidatesReady(
                            requestId, if (savedOrder.isNullOrEmpty()) filteredMsg
                            else EngineMessage.Candidates(
                                applySavedCandidateOrder(filteredMsg.list, savedOrder), 0, 0,
                                total = filteredMsg.total
                            )
                        )
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Timber.e(error, "Failed to restore candidates; using filtered list")
                actions.send(Action.CandidatesReady(requestId, filteredMsg))
            }
        }
    }

    /** 取消在途的候选还原并推进代次：迟到的旧还原结果会被 CandidatesReady 门控丢弃。 */
    private fun invalidatePendingCandidates() {
        candidateRestoreJob?.cancel()
        candidateRestoreJob = null
        state.latestCandidateRequestId.accumulateAndGet(
            state.candidateRequestId.incrementAndGet()
        ) { a, b -> maxOf(a, b) }
    }

    /**
     * 注册偏好缓存的加载完成监听（本实例只注册一次，销毁时经
     * [preferLoadListenerUnregister] 注销）：监听本体在缓存的加载
     * 线程上同步执行，只投递 [Action.PreferLoaded]——登记的取出、
     * 批次复检与 restoreCandidates 重跑全部回到 reduce 串行点内
     * 做。在监听里直接「比较代次→调 restoreCandidates」不是原子
     * 的：比较通过后、调用前插入的新批次会被旧登记的重跑取消并
     * 抢发；且 restoreCandidates 会脱离 reduce 线程跨线程触碰
     * candidateRestoreJob（见其字段说明）。
     */
    private fun ensurePreferLoadListener() {
        if (!preferLoadListenerRegistered.compareAndSet(false, true)) return
        preferLoadListenerUnregister.set(
            CandidatePreferCache.registerLoadListener {
                dispatchAction(Action.PreferLoaded)
            }
        )
    }

    /** 取消在途的预测并推进代次，避免迟到预测覆盖新候选或在清空后重现。 */
    private fun invalidatePendingPrediction() {
        predictionJob?.cancel()
        predictionJob = null
        state.latestPredictionRequestId.accumulateAndGet(
            state.predictionRequestId.incrementAndGet()
        ) { a, b -> maxOf(a, b) }
    }

    override fun onFinishInputView() {
        inputConnection = null
        editorInfo = null
        lastLearnSegment = null
        // 联想可见标志是上一个输入框的状态：切框必须复位，否则新框
        // 非空（不触发 InputCleared 顺带清）时，第一次退格会只清掉
        // 这个残留标志而不删字。invalidatePendingPrediction 只取消
        // 在途预测、不动此标志，故在此与连接/缓存一并收尾
        state.predictionVisible = false
        invalidateBeforeCursorCache()
        invalidatePendingCandidates()
        invalidatePendingPrediction()
    }

    override fun onSelectionChanged() {
        // 光标移动后前文变了：缓存失效，避免 800ms 内读到旧前文
        invalidateBeforeCursorCache()
    }

    /** 有字段选项等待在下一个 job 之前补设（见 jobs 消费循环）。 */
    @Volatile
    private var fieldOptionsPending = false

    override fun onStartInputView(ic: InputConnection, info: EditorInfo, restarting: Boolean) {
        inputConnection = ic
        editorInfo = info
        // 输入框代次推进：在途预测的交付/job 复检按代次作废旧框结果。
        // 同框重启（restarting 且关键字段未变）不推进：框没换，在
        // 途联想仍属本框，推进会误杀（旧实现以 EditorInfo === 比
        // 对有同一问题）。但 restarting 只代表宿主在同一 View 上
        // 重启输入：WebView 内不同 <input> 切换焦点正是 restarting
        // =true、且不经 onFinishInputView——关键字段（见
        // InputFieldKey）已变时按换框推进，否则旧框联想会照常交
        // 付到新框（代次与禁学习复检都拦不住同为可学习框的切换）
        val fieldKey = InputFieldKey.of(info)
        if (!restarting || isInputFieldChanged(lastInputFieldKey, fieldKey)) {
            predictionFieldGen.incrementAndGet()
            // 前端提交队列整队清空：条目带的是旧框代次、再无认
            // 领方，留着只会占住新框的队首（认领侧另有代次校
            // 验兜底，见 FrontendPredictionQueue）
            pendingFrontendPredictions.clear()
        }
        lastInputFieldKey = fieldKey
        invalidateBeforeCursorCache()
        // 隐私：把当前输入框的学习开关同步给 librime native 层。
        // 密码框 / NO_PERSONALIZED_LEARNING 输入框内 native 用户词典不再自学习；
        // 文本密码框进出时保存/恢复中英状态（见 applyAsciiModeForField）。
        // 只置待补设标记：实际补设由 jobs 消费端在下一个 job（含本框
        // 的第一个按键）执行之前完成，顺序确定，不走 sendJob 的异步
        // 队列位置竞争。
        fieldOptionsPending = true
    }

    /**
     * 按输入框应用字段相关的运行时选项：禁个性化学习 + 密码框中英处理。
     * 输入开始时调一次；部署/切方案会重建或重置 native 侧状态，这些选项
     * 随之丢失，必须按当前输入框重设（Deploy Finish 与 SelectSchema 共用）。
     */
    private suspend fun RimeApi.applyFieldOptions(info: EditorInfo) {
        setRuntimeOption(NO_PERSONALIZED_LEARNING_OPTION, isNoPersonalizedLearning(info))
        applyAsciiModeForField(info)
    }

    /**
     * 密码框强制英文前保存的中英状态；null 表示当前不在"密码框强制英文"中。
     * 只在 jobs 通道消费者内读写（串行），无需同步。
     */
    private var asciiModeBeforePassword: Boolean? = null

    private suspend fun RimeApi.applyAsciiModeForField(info: EditorInfo) {
        if (isPasswordTextField(info)) {
            // 进入文本密码框：首次进入时保存当前中英状态（连续两个密码框不重复保存），
            // 然后强制英文直输。数字密码已走数字键盘，不在此处理。
            if (asciiModeBeforePassword == null) {
                asciiModeBeforePassword = getRuntimeOption("ascii_mode")
            }
            setRuntimeOption("ascii_mode", true)
        } else {
            // 离开密码框：恢复进入前保存的状态。
            // 非密码框之间切换不再按偏好重设：Shift 临时切换、方案锁定（如万象锁定中文）
            // 的状态都得以保留。
            val saved = asciiModeBeforePassword
            if (saved != null) {
                asciiModeBeforePassword = null
                setRuntimeOption("ascii_mode", saved)
            }
        }
    }

    override fun predict(commit: String) {
        // 代次在分发点捕获、随 Action 带到 reduce（见 Action.Predict）：
        // 等 reduce 执行 requestPrediction 时才取，分发与执行之间
        // 的换框会被误读成发起时的框
        dispatchAction(Action.Predict(commit, predictionFieldGen.get()))
    }

    private suspend fun requestPrediction(
        commit: String,
        learnable: Boolean = false,
        fieldGenOverride: Long? = null,
    ) {
        // 隐私：密码框 / 声明 IME_FLAG_NO_PERSONALIZED_LEARNING 的输入框不读前文、不跑预测、不学搭配
        if (isNoPersonalizedLearning(editorInfo)) {
            lastLearnSegment = null
            return
        }
        // 搭配学习（仅打字上屏路径 learnable=true 时）：与上一个打字
        // 词段组成词对计数；本段不合规（标点等）则断链。查询键取更新
        // 后的链尾，即刚上屏的词段。
        val userKey: String?
        if (learnable) {
            val segment = TraditionalConverter.toSimplified(commit)
            // 语音纠错沉淀：打字上屏词段是用户亲手选定的正形，与搭配
            // 学习共用此入口，交给语音侧与刚上屏的语音段配对（未武装
            // 时为空转检查）。密码/免学习输入框已在函数头统一拦下。
            com.jobeen.ime.base.speech.SherpaSpeechClient.notifyTypedSegment(segment)
            if (com.jobeen.ime.base.ngram.UserCollocationStore.isLearnableSegment(segment)) {
                lastLearnSegment?.let { prev ->
                    predictionManager?.learnCollocation(prev, segment)
                }
                lastLearnSegment = segment
            } else {
                lastLearnSegment = null
            }
            userKey = lastLearnSegment
        } else {
            userKey = null
        }
        val requestId = state.predictionRequestId.incrementAndGet()
        state.latestPredictionRequestId.accumulateAndGet(requestId) { a, b -> maxOf(a, b) }
        // 发起时的输入框代次随结果带回：交付时复检代次未变才落屏，
        // 切框后到达的旧联想直接丢弃（见 PredictionReady 分支）；
        // job 内读前文前与出结果前也按代次复检（见 job 内注释）。
        // 经 Action.Predict 分发的预测以分发点捕获的代次为准（见
        // Action.Predict 的 fieldGen），reduce 内直接发起的现取
        val startFieldGen = fieldGenOverride ?: predictionFieldGen.get()
        predictionJob?.cancel()
        predictionJob = scope.launch {
            try {
                if (context?.let { !CandidateManager.isPredictionEnabled(it) } == true) {
                    actions.send(Action.PredictionReady(requestId, emptyList(), startFieldGen))
                    return@launch
                }
                // 复检一（用上下文之前）：发起后已切框（代次推进）或
                // 当前框已变为禁学习（密码框等）时整轮作废——不在
                // 新框上读前文、不写前文缓存。函数头的隐私闸门只覆
                // 盖发起时刻，job 排队/执行期间的切框靠这里兜底
                if (!isPredictionFieldCurrent(startFieldGen)) return@launch
                // 上下文在 job 内读落屏后的真实前文：三条发起路径
                // （打字上屏、点联想词、前端提交）都把发起点放在
                // 本次上屏的 emitMessage/落屏之后，collector 在
                // Main 的落屏处理排在本 job 随后投递的
                // withContext(Main) 之前，读到的前文必然含本次词，
                // 且就是宿主实际落屏的文本（过滤器/自动纠正改过
                // 的结果也在内）——不在发起点拼装基底、更不在
                // reduce 落屏前同步读（那会把一次 IPC 往返压在落
                // 屏之前）。基底与代次同处捕获：peek 以发起代次在
                // 真正读 IC 的主线程块内复检代次与隐私（见
                // peekTextBeforeCursor），不过返回 null、整轮作废。
                // 预测模型基于简体训练，先转简体再推导，以支持繁
                // 体输入下的候选预测
                val baseContext = peekTextBeforeCursor(20, expectedGen = startFieldGen)
                    ?: return@launch
                val inputContext = TraditionalConverter.toSimplified(baseContext)
                var candidates: List<Candidate> = emptyList()
                if (inputContext.isNotEmpty() && !TextUtil.isSymbol(inputContext.last()) && !TextUtil.isAlphabet(
                        inputContext.last()
                    )
                ) {
                    candidates = predictionManager?.makePredictions(inputContext, userKey) ?: emptyList()
                }
                if (context?.let { CandidateManager.isTraditionalChineseEnabled(it) } == true) {
                    candidates = candidates.map {
                        it.copy(
                            text = TraditionalConverter.toTraditional(it.text),
                            comment = it.comment.takeIf(String::isNotEmpty)
                                ?.let(TraditionalConverter::toTraditional) ?: it.comment,
                        )
                    }
                }
                // 复检二（出结果之前）：读前文与计算期间切了框，
                // 同样整轮作废，不把旧框联想送去交付（交付侧还有
                // 代次门控，此处提前丢弃省掉无谓的 actions 投递）
                if (!isPredictionFieldCurrent(startFieldGen)) return@launch
                actions.send(Action.PredictionReady(requestId, candidates, startFieldGen))
            } catch (error: CancellationException) {
                throw error
            }
        }
    }

    /**
     * 预测 job 的框复检：代次未变且当前输入框非禁学习。发起时点的
     * 隐私闸门（[requestPrediction] 函数头）只覆盖发起时刻；job 在
     * Default 线程异步执行，其间用户可能已切到密码框——读前文（写
     * [beforeCursorCache]）与出结果前各复检一次，任一不过整轮丢弃
     * 且不写缓存。
     */
    private fun isPredictionFieldCurrent(startFieldGen: Long): Boolean =
        predictionFieldGen.get() == startFieldGen && !isNoPersonalizedLearning(editorInfo)

    override fun reload() {
        dispatchAction(Action.Reload)
    }

    //前端提交
    override fun commit(text: String) {
        dispatchAction(Action.Commit(text))
    }

    private fun requestCommit(text: String) {
        // 前端提交（剪贴板/常用语/语音/符号键等）不参与搭配学习，
        // 且打断打字学习链：粘贴的文本不作为下一个打字词的搭配前文
        lastLearnSegment = null
        sendJob {
            // 直接上屏：前文缓存失效，后续预测读新鲜前文
            invalidateBeforeCursorCache()
            if (compositionCached.preedit?.isNotEmpty() == true) {
                // 组字中：选中词与 text 由 native 合成一条 Commit
                // 落屏，其在 handleRimeMessage 的 Commit 分支按打
                // 字上屏路径于落屏后发起预测——job 内读到的前文
                // 已含「选中词+text」，是最终结果；此处不再另发
                // 预测（另发一轮的上下文只能在落屏前拼装，必缺刚
                // 上屏的组字词）
                commitCurrentSelection(text)
                return@sendJob
            }
            // 经 emitCommit 走引擎消息管线而非直送 actions：上一键的
            // native Commit 尚在管线中时直送会超车先落屏（如粘贴的
            // 标点排到刚打的字前面）。直送标记使下游只排序与落屏、
            // 不按打字上屏记账，与旧直送路径行为等价（见 emitCommit）。
            // 预测不在此另发 Action：入队待发后由 handleRimeMessage
            // 在这条直送 Commit 落屏之后认领并发起（见
            // pendingFrontendPredictions）——旧形态在此读落屏前基
            // 底、与 Predict 一并定格带走，基底必然缺管线中尚未落
            // 屏的上一键上屏词。入队排在 emitCommit 之前（条目先
            // 于消息可见，见 FrontendPredictionQueue.offerAndEmit）：
            // 消息在 emit 返回前就已对消费者可见，认领可能抢先到
            // 达，入队在后则认领落空、条目滞留队首；emitCommit
            // 抛错时按令牌回滚本条目（消息不会产生、无人认领）
            pendingFrontendPredictions.offerAndEmit(
                text, predictionFieldGen.get(),
            ) { emitCommit(text) }
        }
    }

    override fun onInputCleared() {
        dispatchAction(Action.InputCleared)
    }
}
