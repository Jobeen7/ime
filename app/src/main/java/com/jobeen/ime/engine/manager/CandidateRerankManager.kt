package com.jobeen.ime.engine.manager

import android.content.Context
import com.jobeen.ime.base.ngram.GramDb
import com.jobeen.ime.base.priority.CandidateFeature
import com.jobeen.ime.base.priority.PriorityCalculator
import com.jobeen.ime.base.priority.WeightConfig
import com.jobeen.ime.data.manager.CandidatePreferCache
import com.jobeen.ime.engine.data.EngineMessage.Candidate
import timber.log.Timber

class CandidateRerankManager(private val context: Context) {
    private val calculator = PriorityCalculator()

    suspend fun rerank(
        candidates: List<Candidate>, inputContext: String, gramDb: GramDb?
    ): List<Candidate> {
        if (candidates.size <= 1) return candidates

        val restoreStart = 1
        val restoreEnd = minOf(25, candidates.size)
        if (restoreEnd <= restoreStart) return candidates

        // 偏好走内存缓存，不再每轮查库
        val prefers = CandidatePreferCache.snapshot(context)

        val cfg = WeightConfig()
        val restored = ArrayList<Candidate>(restoreEnd - restoreStart)

        for (index in restoreStart until restoreEnd) {
            val it = candidates[index]
            val gramScore = if (inputContext.isNotEmpty()) {
                gramDb?.query(inputContext, it.text) ?: 0.0
            } else {
                0.0
            }

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