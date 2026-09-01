package com.ninthsoft.ime.base.marisa

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.ln

class Prediction(private val modelFile: File) {

    private val trie = MarisaTrie()
    private val valueSeparator = 0xFF.toByte()

    private val Context
        get() = Dispatchers.IO

    suspend fun load() = withContext(Context) {
        require(modelFile.exists()) { "Model file not found: ${modelFile.absolutePath}" }
        trie.create()
        trie.load(modelFile.absolutePath)
    }

    fun isLoaded() = !trie.isEmpty() && !trie.empty

    suspend fun predictNextWords(text: String, topK: Int = TOP_K): List<Candidate> =
        withContext(Context) {
            val tokens = text.trim().split(" ").filter { it.isNotEmpty() }
            if (tokens.isEmpty() || !isLoaded()) return@withContext emptyList()

            val maxLen = minOf(MAX_ORDER, tokens.size)
            for (size in maxLen downTo 1) {
                val keyBase = tokens.takeLast(size).joinToString(" ")
                val searchPrefix = "$keyBase\t"

                val rawMatches = trie.predictiveSearchBytes(searchPrefix.toByteArray())
                if (rawMatches.isNotEmpty()) {
                    return@withContext parseCandidates(rawMatches, topK)
                }
            }
            emptyList()
        }

    fun destroy() {
        trie.destroy()
    }

    private fun parseCandidates(rawMatches: List<ByteArray>, topK: Int): List<Candidate> {
        val seen = mutableSetOf<String>()
        val allCandidates = mutableListOf<Candidate>()
        for (raw in rawMatches) {
            val nullIdx = raw.indexOf(valueSeparator)
            var count = 0
            var keyBytes: ByteArray

            if (nullIdx >= 0) {
                keyBytes = raw.copyOfRange(0, nullIdx)
                val valuePart = raw.copyOfRange(nullIdx + 1, raw.size).toString(Charsets.UTF_8)
                count = valuePart.toIntOrNull() ?: 0
            } else {
                keyBytes = raw
            }

            val keyPart = keyBytes.toString(Charsets.UTF_8)
            val lastTabIdx = keyPart.lastIndexOf('\t')
            if (lastTabIdx < 0) continue
            val word = keyPart.substring(lastTabIdx + 1)

            if (word.isNotEmpty() && seen.add(word)) {
                if (count <= 0) count = 1
                val score = calcScore(word, count)
                allCandidates.add(Candidate(word, count, score))
            }
        }

        allCandidates.sortByDescending { it.score }
        return allCandidates.take(topK)
    }

    data class Candidate(val word: String, val count: Int, var score: Double)

    companion object {
        private const val TOP_K = 100
        private const val MAX_ORDER = 5

        private fun calcScore(word: String, count: Int): Double {
            val cleanWord = word.replace(" ", "")
            val length = cleanWord.length
            val baseScore = ln(1.0 + count)
            val alpha = 0.15
            var lengthFactor = 1.0 + alpha * (length - 1)
            if (length == 1) lengthFactor *= 0.85
            return baseScore * lengthFactor
        }
    }
}
