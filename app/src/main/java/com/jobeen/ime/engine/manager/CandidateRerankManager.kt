package com.jobeen.ime.engine.manager

import android.content.Context
import com.jobeen.ime.base.ngram.GramDb
import com.jobeen.ime.base.priority.CandidateFeature
import com.jobeen.ime.base.priority.PriorityCalculator
import com.jobeen.ime.base.priority.WeightConfig
import com.jobeen.ime.engine.data.EngineMessage.Candidate
import com.jobeen.ime.engine.rime.core.isKeycapSequence

class CandidateRerankManager(context: Context) {
    private val calculator = PriorityCalculator()

    private companion object {
        /** 键帽序列候选的固定沉底分：必低于 calculate 的任何产出（恒 ≥ 0）。 */
        const val KEYCAP_SUNK_SCORE = -1.0
    }

    suspend fun rerank(
        candidates: List<Candidate>, inputContext: String, gramDb: GramDb?,
        prefers: Map<String, Int>,
    ): List<Candidate> {
        if (candidates.size <= 1) return candidates

        val restoreStart = 1
        val restoreEnd = minOf(25, candidates.size)
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

        for (index in restoreStart until restoreEnd) {
            val it = candidates[index]
            val gramScore = preparedCtx?.let { ctx -> gramDb?.query(ctx, it.text) } ?: 0.0

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