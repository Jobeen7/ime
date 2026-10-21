package com.jobeen.ime.engine.manager

import android.content.Context
import com.jobeen.ime.base.ngram.GramDb
import com.jobeen.ime.base.priority.CandidateFeature
import com.jobeen.ime.base.priority.PriorityCalculator
import com.jobeen.ime.base.priority.WeightConfig
import com.jobeen.ime.engine.data.EngineMessage.Candidate

class CandidateRerankManager(context: Context) {
    private val calculator = PriorityCalculator()

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
            val score = calculator.calculate(
                CandidateFeature(
                    frequency = preferCount.toLong(),
                    wordLength = textLen,
                    candidateCount = 0,
                    baseScore = gramScore
                ), cfg
            )
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