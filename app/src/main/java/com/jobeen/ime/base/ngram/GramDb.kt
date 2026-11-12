package com.jobeen.ime.base.ngram

import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import kotlin.math.exp

class GramDb(filePath: String) {

    private val trie: DoubleArrayTrie

    init {
        // mmap 完成后立即关 FD：映射在 Linux/Android 上独立于文件描述符存活，
        // 长期持有 RandomAccessFile 只会白占一个 FD（且本类没有 close 入口）
        val file = RandomAccessFile(filePath, "r")
        val mapped = try {
            val size = file.length()
            // 文件头校验（B-S3）：格式串 32 字节＋36 处与 40 处的两个
            // int，文件不足 44 字节时直接判无效，不走到 mmap 越界读。
            require(size >= HEADER_SIZE) { "gram db too small: $size bytes" }
            file.channel.map(FileChannel.MapMode.READ_ONLY, 0, size)
                .order(ByteOrder.LITTLE_ENDIAN)
        } finally {
            file.close()
        }

        val fmt = ByteArray(FORMAT_MAX_LEN)
        mapped.get(fmt)
        val format = String(fmt).trimEnd('\u0000')
        require(format.startsWith(PREFIX)) { "invalid gram db format: $format" }

        mapped.position(36)
        val arraySize = mapped.int
        mapped.position(40)
        val arrayOffset = mapped.int

        // 数组边界校验（B-S3）：偏移／长度为负、越出文件、长度×4
        // 溢出时判无效，不走到 slice.limit 越界抛错。长度乘法按
        // Long 算，避免 Int 溢出绕过检查。
        require(arraySize >= 0) { "gram db has negative array size: $arraySize" }
        require(arrayOffset >= 0) { "gram db has negative array offset: $arrayOffset" }
        val arrayStart = 40L + arrayOffset
        val capacity = mapped.capacity().toLong()
        require(arrayStart <= capacity) {
            "gram db array start out of bounds: start=$arrayStart capacity=$capacity"
        }
        require(arraySize.toLong() * INT_BYTES <= capacity - arrayStart) {
            "gram db array out of bounds: size=$arraySize start=$arrayStart capacity=$capacity"
        }
        mapped.limit(mapped.capacity())
        mapped.position(arrayStart.toInt())
        val slice = mapped.slice().order(ByteOrder.LITTLE_ENDIAN)
        slice.limit(arraySize * INT_BYTES)
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

    /**
     * 一次预测的上下文预计算：上下文编码与逐后缀步的 trie 落点只随上下文
     * 变、与候选词无关。旧实现在 [query] 里对每个候选（一次预测最多
     * 25 个）把同一份上下文从头重算一遍；改为在预测入口算一次、各候选
     * 复用。trie 的 traverse/commonPrefixSearch 只读无共享可变状态，
     * 预计算与逐候选内联计算的结果逐位一致。
     */
    class PreparedContext internal constructor(
        internal val nodePositions: IntArray,
    )

    fun prepareContext(context: String): PreparedContext {
        val n = minOf(GramEncoding.MAX_ENCODED_UNICODE, 8)
        val ctxTail = lastNCodePoints(context, n)
        val encCtx = GramEncoding.encode(ctxTail)

        val positions = ArrayList<Int>()
        var p = 0
        while (p < encCtx.size) {
            val subCtx = encCtx.copyOfRange(p, encCtx.size)
            val t = trie.traverse(subCtx)
            if (t.keyPos == subCtx.size) {
                positions.add(t.nodePos)
            }
            p = GramEncoding.nextUnicode(encCtx, p)
        }
        return PreparedContext(positions.toIntArray())
    }

    fun query(context: String, word: String, isRear: Boolean = false): Double =
        query(prepareContext(context), word, isRear)

    fun query(prepared: PreparedContext, word: String, isRear: Boolean = false): Double {
        val n = minOf(GramEncoding.MAX_ENCODED_UNICODE, 8)
        val wordHead = firstNCodePoints(word, n)
        val encWord = GramEncoding.encode(wordHead)

        var best = -Double.MAX_VALUE
        for (nodePos in prepared.nodePositions) {
            // 只需要最大值，走无分配的最大值遍历；scaleValue 随 raw 单调
            // 不减，取 raw 最大值再换算与逐条换算取最大逐位等价
            val maxRaw = trie.commonPrefixMaxValue(encWord, MAX_RESULTS, nodePosStart = nodePos)
            if (maxRaw != null) {
                val s = scaleValue(maxRaw)
                if (s > best) best = s
            }
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
        /** 文件头最小长度：32 字节格式串＋36 处 int＋40 处 int。 */
        private const val HEADER_SIZE = 44L
        private const val INT_BYTES = 4

        /**
         * 损坏／截断文件不抛错的加载口：校验失败返回 null，调用方
         * 按“无语法模型”降级（B-S3）。构造器本身仍抛
         * IllegalArgumentException，便于诊断时拿到明确原因。
         */
        fun loadOrNull(filePath: String): GramDb? =
            runCatching { GramDb(filePath) }.getOrNull()

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