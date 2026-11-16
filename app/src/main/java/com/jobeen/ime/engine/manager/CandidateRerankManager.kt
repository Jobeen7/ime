package com.jobeen.ime.engine.manager

import android.content.Context
import androidx.annotation.VisibleForTesting
import com.jobeen.ime.base.ngram.GramDb
import com.jobeen.ime.base.priority.CandidateFeature
import com.jobeen.ime.base.priority.PriorityCalculator
import com.jobeen.ime.base.priority.WeightConfig
import com.jobeen.ime.engine.data.EngineMessage.Candidate
import com.jobeen.ime.engine.rime.core.isKeycapSequence
import java.util.Collections

class CandidateRerankManager(context: Context) {
    private val calculator = PriorityCalculator()

    private companion object {
        /** 键帽序列候选的固定沉底分：必低于 calculate 的任何产出（恒 ≥ 0）。 */
        const val KEYCAP_SUNK_SCORE = -1.0

        /**
         * 重排窗口上限（2026-11-16 性能 ②）：只重排首屏可见数——
         * 下标 0 固定，1..11 进窗；窗口外保持引擎原始序。旧值 25
         * 时每批对 24 个候选各做一次 gram 查询＋偏好查表。
         */
        const val RERANK_WINDOW_END = 11

        /** gram 分缓存上限（条）。 */
        const val GRAM_CACHE_MAX = 512
    }

    /**
     * gram 分缓存（2026-11-16 性能 ②）：同一组字会话内按（上下文，
     * 词）缓存 gram 分——query 是 (preparedCtx, 词, gramDb 文件) 的
     * 纯函数（trie 只读、prepareContext 只随上下文变），同一上下文
     * 重复查同一词必得同一分；命中后不再走 trie。上限 512 条 LRU；
     * 两路 restore job 可并发调 rerank（循环内无挂起点、但 job 可
     * 重叠），故用 synchronizedMap。gramDb 实例变化时整表清掉
     * （文件运行期不变，引用比对只是兜底；落点时再校验一次引用，
     * 防“清表后旧实例的查询结果又写回”的交错）。
     */
    private val gramScoreCache: MutableMap<Pair<String, String>, Double> =
        Collections.synchronizedMap(
            object : LinkedHashMap<Pair<String, String>, Double>(16, 0.75f, true) {
                override fun removeEldestEntry(
                    eldest: MutableMap.MutableEntry<Pair<String, String>, Double>?,
                ): Boolean = size > GRAM_CACHE_MAX
            },
        )
    private var cachedGramDb: GramDb? = null

    @VisibleForTesting
    internal fun gramCacheSize(): Int = gramScoreCache.size

    suspend fun rerank(
        candidates: List<Candidate>, inputContext: String, gramDb: GramDb?,
        prefers: Map<String, Int>,
    ): List<Candidate> {
        if (candidates.size <= 1) return candidates

        val restoreStart = 1
        val restoreEnd = minOf(RERANK_WINDOW_END, candidates.size)
        if (restoreEnd <= restoreStart) return candidates

        // 偏好快照由调用方传入：已加载时是内存直读；未加载时调用
        // 方先用当前缓存出候选、加载完成后另行重排刷新（见
        // RimeEngine.restoreCandidates），本函数不在候选首屏路径
        // 上等偏好加载（加载与写事务互斥，等它等于等写事务整段）

        val cfg = WeightConfig()
        val restored = ArrayList<Candidate>(restoreEnd - restoreStart)
        // 同一上下文逐候选打分：上下文预计算一次、整表复用
        val preparedCtx = if (inputContext.isNotEmpty()) {
            gramDb?.prepareContext(inputContext)
        } else {
            null
        }
        // gramDb 实例变化时清掉旧文件的分（见 gramScoreCache 说明）。
        if (gramDb != null && preparedCtx != null) {
            synchronized(gramScoreCache) {
                if (gramDb !== cachedGramDb) {
                    gramScoreCache.clear()
                    cachedGramDb = gramDb
                }
            }
        }

        for (index in restoreStart until restoreEnd) {
            val it = candidates[index]
            val gramScore = if (preparedCtx != null && gramDb != null) {
                val key = inputContext to it.text
                gramScoreCache[key] ?: run {
                    val score = gramDb.query(preparedCtx, it.text)
                    synchronized(gramScoreCache) {
                        if (gramDb === cachedGramDb) gramScoreCache[key] = score
                    }
                    score
                }
            } else {
                0.0
            }

            val preferCount = prefers[it.text] ?: 0
            val textLen = it.text.codePointCount(0, it.text.length)
            val rawScore = calculator.calculate(
                CandidateFeature(
                    frequency = preferCount.toLong(),
                    wordLength = textLen,
                    candidateCount = 0,
                    baseScore = gramScore
                ), cfg
            )
            // 键帽序列（如 7️⃣）是基础字（七）的 emoji 派生候选：引擎
            // 原始序里它恒紧跟在基础字之后，但重排会把它抬到基础字
            // 之前——其一，组合序列被 codePointCount 算成 3 个码点，
            // 长度项虚高（单字约 2.6 倍），零偏好历史时就已越过基础
            // 字；其二，用户点过一次后偏好计数再叠一层。第十七批定
            // 责（主机台运行时判别）：Lua context_reorder 的可重排
            // 白名单只有 user_phrase，simplified 类型的键帽它动不了，
            // 抬升只来自本重排。口径：键帽候选不参加分数竞争，沉到
            // 重排窗口末尾（calculate 的分数恒 ≥ 0，-1 必垫底；稳定
            // 排序保持多个键帽之间的原始相对序），恒在基础字之后。
            val score = if (isKeycapSequence(it.text)) KEYCAP_SUNK_SCORE else rawScore
            restored.add(
                Candidate(
                    // 必须保留 Rime 全局 index：选词/删词都按它定位，改成局部位置会打错对象
                    index = it.index, text = it.text, comment = it.comment, type = it.type, score = score
                )
            )
        }
        restored.sortByDescending { it.score }

        val result = ArrayList<Candidate>(candidates.size)
        result.add(candidates[0])
        result.addAll(restored)
        for (index in restoreEnd until candidates.size) {
            result.add(candidates[index])
        }
        return result
    }
}