package com.ninthsoft.ime.engine.manager

import android.content.Context
import com.ninthsoft.ime.base.ngram.GramDb
import com.ninthsoft.ime.base.priority.CandidateFeature
import com.ninthsoft.ime.base.priority.PriorityCalculator
import com.ninthsoft.ime.base.priority.WeightConfig
import com.ninthsoft.ime.data.database.AppDatabase
import com.ninthsoft.ime.engine.data.EngineMessage.Candidate

class CandidateRerankManager(private val context: Context) {
    private val calculator = PriorityCalculator()

    suspend fun rerank(
        candidates: List<Candidate>,
        inputContext: String,
        gramDb: GramDb?
    ): List<Candidate> {
        if (candidates.size <= 1) return candidates

        val restoreStart = 1
        val restoreEnd = minOf(25, candidates.size)
        if (restoreEnd <= restoreStart) return candidates

        val texts = candidates.subList(restoreStart, restoreEnd).map { it.text }
        val prefers = AppDatabase.getInstance(context)
            .candidatePreferDao()
            .getAllByTextIn(texts)
            .associate { it.text to it.count }

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
                    index = index,
                    text = it.text,
                    type = Candidate.CandidateType.Engine,
                    score = score
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