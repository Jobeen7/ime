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
 * 候选帧版本号放行判定：revision 0 = 未版本化，恒放行（且调用方不
 * 推进已应用版本号）；其余仅当严格大于已应用版本号时放行——等于
 * （重复投递）与小于（迟到旧帧）一律丢弃。
 */
internal fun shouldApplyCandidateFrame(revision: Long, lastAppliedRevision: Long): Boolean =
    revision <= 0L || revision > lastAppliedRevision
