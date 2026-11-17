package com.jobeen.ime.engine.data

sealed class EngineMessage {
    /**
     * @property direct 直送标记，由转换器自
     * [com.jobeen.ime.engine.rime.core.RimeMessage.CommitTextMessage]
     * 透传：true 时下游只落屏、不按「打字上屏」记账。引擎内部直接
     * 构造的 Commit（选词上屏、前端提交等）不经该记账路径，恒缺省。
     */
    data class Commit(val text: String, val direct: Boolean = false) : EngineMessage()

    data class Composition(val preedit: String, val cursorPos: Int) : EngineMessage()

    /**
     * 候选批次所属的组字快照（性能第 1 条）：随候选消息下发，供
     * restoreCandidates 直接取组字码／音节／方案 id，不再经 awaitJob
     * 回 jobs 取——按键不再排在上一批候选后处理后面。由转换器在同批
     * 组字／方案消息转换时暂存（见 CandidateCompositionState），
     * 与 KeycapFilterState 同口径；缺省 = 空组字（与旧 awaitJob
     * 的超时缺省同值），首批候选之前无组字消息时不阻塞落地。
     */
    data class CompositionSnapshot(
        val preedit: String = "",
        val spellings: List<String> = emptyList(),
        val firstRawInput: String = "",
        val schemaId: String = "",
    )

    data class Candidates(
        val list: List<Candidate>,
        val highlighted: Int,
        val page: Int,
        /** 本批候选总数：-1 表示总数未知（首屏取满、后面还有）；>=0 为确切总数 */
        val total: Int = -1,
        /** true 表示这是分页补取的追加页，UI 应并入当前列表而非整表替换 */
        val append: Boolean = false,
        /**
         * 帧版本号：首屏取 restoreCandidates 的 requestId，追加页取批次号，
         * 未版本化（合成帧如清空、联想）缺省 0。消费侧凭它丢弃迟到旧帧；
         * 追加页不走 setCandidates，版本号不与首屏混比。
         */
        val revision: Long = 0L,
        /**
         * native 分页末页标志（WO52 1.1）：引擎按页号×页宽与 total 算出，
         * 供翻页钮可见性（下一页仅非末页可见）；滑动模式恒 false
         * （末页由 hasMore 判定）。
         */
        val isLastPage: Boolean = false,
        /** 方案 page_size（快照 context.menu.pageSize）：native 分页末页判定与下标重映射用；0 = 未知 */
        val pageSize: Int = 0,
        /** 本批候选所属的组字快照（性能第 1 条）：只供 restoreCandidates 读，UI 侧忽略 */
        val composition: CompositionSnapshot = CompositionSnapshot(),
    ) : EngineMessage() {}

    data class Status(val isComposing: Boolean = false) : EngineMessage()
    data class InlinePreedit(val preedit: String) : EngineMessage()

    data class DynamicPreedit(val preedits: List<DynamicPreeditItem>) : EngineMessage() {
        enum class DynamicPreeditType {
            Normal, Secondary,
        }

        // data class：PreeditPinner 的测宽缓存按 item 相等性命中，普通 class
        // 无 equals 时每批都是新实例、缓存恒未命中（每键全量重测）
        data class DynamicPreeditItem(val text: String, val type: DynamicPreeditType)
    }

    data class Schema(
        val id: String, val name: String, val layout: String = "", val punctuation: String = "", val kind: String = ""
    ) : EngineMessage()

    data class Depoly(val state: State) : EngineMessage() {
        enum class State { Start, Success, Failure, Finish }
    }

    data class PossibleCandidatePinYin(val possibleCandidatePinYins: List<CandidatePinYin>) :
        EngineMessage()

    data class CandidateMenu(
        val pageSize: Int = 0,
        val pageNumber: Int = 0,
        val isLastPage: Boolean = false,
        val highlightedCandidateIndex: Int = 0,
        val selectKeys: String? = null,
        val selectLabels: List<String> = listOf(),
        val candidates: List<Candidate>,
    ) : EngineMessage() {
        data class Candidate(
            val index: Int, val text: String, val comment: String, val label: String,
            val type: String = "",
        )
    }

    data object Unknown : EngineMessage()

    data class Candidate(
        val index: Int,
        val text: String,
        val comment: String = "",
        val type: String = "",
        var score: Double = 0.0,
        /**
         * 常用语输入码候选所属的常用语 id（WO52 6.1）：仅
         * [TYPE_PHRASE_SHORTCUT] 候选携带（>=0），其余恒为 -1；
         * 点选上屏后凭它记一次最近使用（自动排序键）。
         */
        val phraseId: Long = -1L,
    ) {
        companion object {
            const val TYPE_IME_PREDICTION = "imePrediction"
            /** 邮箱后缀候选（打 @ 后给出的域名，见 EmailSuffixes）：点选走替换上屏，不进 Rime 选词 */
            const val TYPE_EMAIL_SUFFIX = "emailSuffix"
            /** 生僻字补字候选（粤语常用字佢／嘅／咗／冇，见 RareCharPolicy）：点选走文本上屏，不进 Rime 选词 */
            const val TYPE_RARE_CHAR = "rareChar"
            /**
             * 常用语输入码候选（WO52 6.1）：组字码与常用语 shortcut
             * 完整匹配时拼到候选前；点选只把常用语文本上屏，不进
             * Rime 选词、不记偏好、不学搭配。
             */
            const val TYPE_PHRASE_SHORTCUT = "phraseShortcut"
            /**
             * 输入码候选序号基数：横条按 `Candidate.index` 做按压／
             * 命中身份（`KawaiiPanelRenderer`），同码多条必须各占
             * 不同序号；Rime 序号占 0..n-1，故输入码候选用此基数
             * 往上排，永不与 Rime 序号相撞，也永不进 `Selection`
             * （点选先按 type 分流）。
             */
            const val PHRASE_SHORTCUT_INDEX_BASE = 1_000_000
        }
    }
}

/**
 * 无变化候选帧比较：只看内容字段（index/text/comment/type，按序）。
 * 不能用 data class 自带 equals——score 是 var 评分，单调变化不代表
 * 内容变，用 equals 会让丢弃永不生效。调用方另比 hasMore（total 的
 * 首屏可见效果）；highlighted 不走 setCandidates 路径（UI 不消费
 * Candidates.highlighted），组字刷新走独立 DynamicPreedit 路径，
 * 故两者都不列入本比较。
 */
internal fun candidatesContentEqual(
    a: List<EngineMessage.Candidate>,
    b: List<EngineMessage.Candidate>,
): Boolean {
    if (a.size != b.size) return false
    return a.indices.all { i ->
        val x = a[i]
        val y = b[i]
        x.index == y.index && x.text == y.text &&
            x.comment == y.comment && x.type == y.type
    }
}

/**
 * 候选帧代次门（松烟模型，2026-11-16 取代纯 revision 比较）：
 * 纯状态机，不含 Android 依赖，可单测。窗口层 setCandidates 的
 * 唯一版本守卫——网格层另有自己的 CandidateGridRevisionGate，
 * 两道门各守各的入口，不互相推测。
 *
 * 为什么不用“revision 严格递增就丢弃”（.2 不能打字案的教训）：
 * 引擎重建后 requestId 从头计，新帧 revision 小于窗口已应用值；
 * 旧门把它当迟到旧帧丢掉，浮动窗网格永远等不到首帧，T9 "wo"
 * 只剩组字栏。松烟式做法是显式代次：会话边界调 [newGeneration]
 * 复位，复位后小 revision 是新代次的首帧、必须放行。
 *
 * 代次边界（调用方必须在这些点调 [newGeneration]）：
 * - 浮动窗实际解散（与网格 resetRevisionGate 同一点）；
 * - 其余会话边界若有显式信号，到时再接。
 * 除显式复位外，revision 小于已应用值时也按新 epoch 放行并复位
 * 基线——这是安全的：通道同类保序（EngineMessageChannel 同类
 * 严格保序）＋发射门（RimeEngine:1691 仅当 requestId ==
 * latestCandidateRequestId 才发 CandidatesReady），同一引擎
 * epoch 内到达 setCandidates 的 revision 单调递增（0 与同代次
 * 重投除外），更小值只可能是新引擎实例从头计数的首帧；把它丢
 * 掉就是 .2 案。复位时记 DiagLog 可观测（调用方打）。
 *
 * 口径：
 * - revision <= 0：未版本化合成帧（清空、联想），恒放行且不推进；
 * - revision > 已应用：放行，推进并存帧指纹；
 * - revision == 已应用：同代次重投——帧指纹（页号／末页／内容）
 *   不同才放行（翻页、末页翻转、重发新内容），指纹完全相同是
 *   幂等重投，丢弃（下游另有内容去重兜底，此处先省一次重绘）；
 * - revision < 已应用：新 epoch 首帧，放行并把基线复位到该值。
 */
internal class CandidateFrameGate {

    companion object {
        private const val FNV_OFFSET_BASIS = -3750763034362895579L
        private const val FNV_PRIME = 1099511628211L
    }

    /** 已应用的最大代次（-1 = 尚未应用任何版本化帧）。 */
    var lastAppliedRevision: Long = -1L
        private set

    /**
     * 帧指纹（P1-2）：只存页号／末页／hasMore 与内容哈希，不复制
     * 整个候选列表（旧实现每放行一帧即 `list.toList()` 一次）。
     * 内容哈希只对 [candidatesContentEqual] 同口径的四字段
     * （index/text/comment/type）求值：`Candidate.score` 是 var
     * 评分、单调变化不代表内容变，`phraseId` 亦与内容无关——
     * 两者一旦进哈希，分数一变即永不判等、去重失效（故不许用
     * `list.hashCode()`，data class 的 hashCode 含这两字段）。
     * 64 位 FNV-1a：顺序与条数都进哈希，碰撞概率可忽略；下游
     * 另有内容去重兜底，万一碰撞也只省一次重绘。
     */
    private data class FrameFingerprint(
        val pageNo: Int,
        val isLastPage: Boolean,
        val hasMore: Boolean,
        val contentHash: Long,
    )

    private fun contentHash(list: List<EngineMessage.Candidate>): Long {
        var h = FNV_OFFSET_BASIS xor list.size.toLong()
        for (c in list) {
            h = h xor c.index.toLong()
            h *= FNV_PRIME
            h = h xor c.text.hashCode().toLong()
            h *= FNV_PRIME
            h = h xor c.comment.hashCode().toLong()
            h *= FNV_PRIME
            h = h xor c.type.hashCode().toLong()
            h *= FNV_PRIME
        }
        return h
    }

    private var lastFrame: FrameFingerprint? = null

    /** 新代次：复位已应用代次与帧指纹（浮动窗解散等会话边界用）。 */
    fun newGeneration() {
        lastAppliedRevision = -1L
        lastFrame = null
    }

    /**
     * 帧是否可应用：可应用时更新已应用代次与帧指纹并返回 true；
     * 幂等重投不更新、不应用，返回 false。
     */
    fun shouldApply(
        revision: Long,
        pageNo: Int = 0,
        isLastPage: Boolean = false,
        hasMore: Boolean = false,
        list: List<EngineMessage.Candidate> = emptyList(),
    ): Boolean {
        if (revision <= 0L) return true
        if (revision < lastAppliedRevision) {
            // 新引擎 epoch 首帧（见类注释的单调性证明）：放行并复位基线。
            lastAppliedRevision = revision
            lastFrame = FrameFingerprint(pageNo, isLastPage, hasMore, contentHash(list))
            return true
        }
        if (revision > lastAppliedRevision) {
            lastAppliedRevision = revision
            lastFrame = FrameFingerprint(pageNo, isLastPage, hasMore, contentHash(list))
            return true
        }
        // 同代次重投：指纹不同（翻页／末页翻转／内容更新）才放行。
        val last = lastFrame
        if (last == null ||
            last.pageNo != pageNo || last.isLastPage != isLastPage ||
            last.hasMore != hasMore || last.contentHash != contentHash(list)
        ) {
            lastFrame = FrameFingerprint(pageNo, isLastPage, hasMore, contentHash(list))
            return true
        }
        return false
    }
}
