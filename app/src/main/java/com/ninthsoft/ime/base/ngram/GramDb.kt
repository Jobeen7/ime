package com.ninthsoft.ime.base.ngram

import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import kotlin.math.exp

class GramDb(filePath: String) {

    private val file: RandomAccessFile
    private val trie: DoubleArrayTrie

    init {
        file = RandomAccessFile(filePath, "r")
        val size = file.length()
        val mapped = file.channel.map(FileChannel.MapMode.READ_ONLY, 0, size)
            .order(ByteOrder.LITTLE_ENDIAN)

        val fmt = ByteArray(FORMAT_MAX_LEN)
        mapped.get(fmt)
        val format = String(fmt).trimEnd('\u0000')
        require(format.startsWith(PREFIX)) { "invalid gram db format: $format" }

        mapped.position(36)
        val arraySize = mapped.int
        mapped.position(40)
        val arrayOffset = mapped.int

        val arrayStart = 40 + arrayOffset
        mapped.limit(mapped.capacity())
        mapped.position(arrayStart)
        val slice = mapped.slice().order(ByteOrder.LITTLE_ENDIAN)
        slice.limit(arraySize * 4)
        trie = DoubleArrayTrie(slice.asIntBuffer())
    }

    data class Match(val suffix: String, val prob: Double, val logProb: Double, val length: Int)

    data class Suffix(val suffix: String, val prob: Double, val logProb: Double)

    fun lookup(context: String, word: String, maxResults: Int = MAX_RESULTS): List<Match> {
        val n = minOf(GramEncoding.MAX_ENCODED_UNICODE, 8)
        val ctxTail = lastNCodePoints(context, n)
        val wordHead = firstNCodePoints(word, n)
        val encCtx = GramEncoding.encode(ctxTail)
        val encWord = GramEncoding.encode(wordHead)

        val all = mutableListOf<Match>()
        var p = 0
        while (p < encCtx.size) {
            val subCtx = encCtx.copyOfRange(p, encCtx.size)
            val t = trie.traverse(subCtx)
            if (t.keyPos == subCtx.size) {
                for (m in trie.commonPrefixSearch(encWord, maxResults, nodePosStart = t.nodePos)) {
                    val suffixBytes = encWord.copyOf(m.length)
                    val decoded = GramEncoding.decode(suffixBytes)
                    val logProb = scaleValue(m.value)
                    all.add(Match(suffix = decoded, prob = exp(logProb), logProb = logProb, length = m.length))
                }
            }
            p = GramEncoding.nextUnicode(encCtx, p)
        }
        return all
    }

    fun query(context: String, word: String, isRear: Boolean = false): Double {
        val n = minOf(GramEncoding.MAX_ENCODED_UNICODE, 8)
        val ctxTail = lastNCodePoints(context, n)
        val wordHead = firstNCodePoints(word, n)
        val encCtx = GramEncoding.encode(ctxTail)
        val encWord = GramEncoding.encode(wordHead)

        var best = -Double.MAX_VALUE
        var p = 0
        while (p < encCtx.size) {
            val subCtx = encCtx.copyOfRange(p, encCtx.size)
            val t = trie.traverse(subCtx)
            if (t.keyPos == subCtx.size) {
                for (m in trie.commonPrefixSearch(encWord, MAX_RESULTS, nodePosStart = t.nodePos)) {
                    val s = scaleValue(m.value)
                    if (s > best) best = s
                }
            }
            p = GramEncoding.nextUnicode(encCtx, p)
        }

        if (isRear) {
            val wordCpLen = word.codePointCount(0, word.length)
            if (wordHead.codePointCount(0, wordHead.length) == wordCpLen) {
                val dollarEnc = GramEncoding.encode("$")
                val wt = trie.traverse(encWord)
                if (wt.keyPos == encWord.size) {
                    val m = trie.commonPrefixSearch(dollarEnc, 1, nodePosStart = wt.nodePos)
                    if (m.isNotEmpty()) {
                        val s = scaleValue(m[0].value)
                        if (s > best) best = s
                    }
                }
            }
        }
        return if (best == -Double.MAX_VALUE) 0.0 else best
    }

    fun enumerateSuffixes(context: String): List<Suffix> {
        val encCtx = GramEncoding.encode(context)
        val t = trie.traverse(encCtx)
        if (t.keyPos != encCtx.size) return emptyList()
        val results = mutableListOf<Suffix>()
        trie.enumerateSuffixes(t.nodePos) { suffixBytes, rawValue ->
            val decoded = GramEncoding.decode(suffixBytes)
            val logProb = scaleValue(rawValue)
            results.add(Suffix(suffix = decoded, prob = exp(logProb), logProb = logProb))
        }
        results.sortByDescending { it.prob }
        return results
    }

    companion object {
        const val MAX_RESULTS = 8
        private const val FORMAT_MAX_LEN = 32
        private const val PREFIX = "Rime::Grammar/"

        fun scaleValue(raw: Int): Double =
            if (raw >= 0) raw.toDouble() / DoubleArrayTrie.VALUE_SCALE else -1.0

        fun lastNCodePoints(s: String, n: Int): String {
            val total = s.codePointCount(0, s.length)
            val take = minOf(n, total)
            if (take <= 0) return ""
            var pos = s.length
            repeat(take) { pos = s.offsetByCodePoints(pos, -1) }
            return s.substring(pos)
        }

        fun firstNCodePoints(s: String, n: Int): String {
            val total = s.codePointCount(0, s.length)
            val take = minOf(n, total)
            if (take <= 0) return ""
            val pos = s.offsetByCodePoints(0, take)
            return s.substring(0, pos)
        }
    }
}