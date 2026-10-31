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
    data class Candidates(
        val list: List<Candidate>,
        val highlighted: Int,
        val page: Int,
        /** 本批候选总数：-1 表示总数未知（首屏取满、后面还有）；>=0 为确切总数 */
        val total: Int = -1,
        /** true 表示这是分页补取的追加页，UI 应并入当前列表而非整表替换 */
        val append: Boolean = false,
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
    ) {
        companion object {
            const val TYPE_IME_PREDICTION = "imePrediction"
            const val TYPE_USER_PHRASE = "user_phrase"
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
