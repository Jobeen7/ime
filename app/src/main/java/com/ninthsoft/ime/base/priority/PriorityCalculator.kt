package com.ninthsoft.ime.base.priority

import kotlin.math.ln1p
import kotlin.math.tanh

data class CandidateFeature(
    val frequency: Long, val wordLength: Int, val candidateCount: Int, val baseScore: Double = 0.0
)

data class WeightConfig(
    val baseScore: Double = 0.5,
    val frequency: Double = 0.3,
    val wordLength: Double = 0.1,
    val candidateCount: Double = 0.1
) {
    init {
        require(baseScore >= 0.0)
        require(frequency >= 0.0)
        require(wordLength >= 0.0)
        require(candidateCount >= 0.0)
        require(baseScore + frequency + wordLength + candidateCount > 0.0)
    }
}

class PriorityCalculator {
    companion object {
        private const val MAX_LOG_FREQUENCY = 15.0
        private const val WORD_LENGTH_SCALE = 4.0
        private const val CANDIDATE_COUNT_SCALE = 8.0
    }

    fun calculate(candidate: CandidateFeature, weights: WeightConfig = WeightConfig()): Double {
        val baseScore = normalizeBaseScore(candidate.baseScore)
        val frequency = normalizeFrequency(candidate.frequency)
        val wordLength = normalizeLength(candidate.wordLength, WORD_LENGTH_SCALE)
        val candidateCount = normalizeCount(candidate.candidateCount, CANDIDATE_COUNT_SCALE)
        return baseScore * weights.baseScore + frequency * weights.frequency + wordLength * weights.wordLength + candidateCount * weights.candidateCount
    }

    private fun normalizeBaseScore(value: Double): Double {
        if (value <= 0.0) return 0.0
        return ln1p(value)
    }

    private fun normalizeFrequency(value: Long): Double {
        if (value <= 0L) return 0.0
        return (ln1p(value.toDouble()) / MAX_LOG_FREQUENCY).coerceIn(0.0, 1.0)
    }

    private fun normalizeLength(value: Int, scale: Double): Double {
        if (value <= 0) return 0.0
        return tanh(value / scale)
    }

    private fun normalizeCount(value: Int, scale: Double): Double {
        if (value <= 0) return 0.0
        return tanh(value / scale)
    }
}