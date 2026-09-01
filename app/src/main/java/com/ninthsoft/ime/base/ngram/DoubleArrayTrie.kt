package com.ninthsoft.ime.base.ngram

import java.nio.IntBuffer

class DoubleArrayTrie(private val buf: IntBuffer) {

    private val limit get() = buf.limit()

    private fun valid(idx: Int) = idx in 0 until limit

    fun traverse(key: ByteArray, startPos: Int = 0, keyStart: Int = 0): TraverseResult {
        var nodePos = startPos
        var keyPos = keyStart
        if (!valid(nodePos)) return TraverseResult(-2, nodePos, keyPos)
        var unit = buf.get(nodePos)
        while (keyPos < key.size) {
            val c = (key[keyPos].toInt() and 0xFF)
            nodePos = nodePos xor offset(unit) xor c
            if (!valid(nodePos)) return TraverseResult(-2, nodePos, keyPos)
            unit = buf.get(nodePos)
            if (label(unit) != c) return TraverseResult(-2, nodePos, keyPos)
            keyPos++
        }
        if (hasLeaf(unit)) {
            val leafIdx = nodePos xor offset(unit)
            return if (valid(leafIdx)) TraverseResult(value(buf.get(leafIdx)), nodePos, keyPos)
            else TraverseResult(-1, nodePos, keyPos)
        }
        return TraverseResult(-1, nodePos, keyPos)
    }

    fun commonPrefixSearch(
        key: ByteArray,
        maxResults: Int,
        length: Int = key.size,
        nodePosStart: Int = 0,
    ): List<MatchPair> {
        val results = mutableListOf<MatchPair>()
        val keyLen = minOf(length, key.size)
        if (!valid(nodePosStart)) return results

        var nodePos = nodePosStart
        var unit = buf.get(nodePos)
        nodePos = nodePos xor offset(unit)
        if (!valid(nodePos)) return results

        for (i in 0 until keyLen) {
            val c = (key[i].toInt() and 0xFF)
            nodePos = nodePos xor c
            if (!valid(nodePos)) return results
            unit = buf.get(nodePos)
            if (label(unit) != c) return results

            nodePos = nodePos xor offset(unit)
            if (!valid(nodePos)) return results
            if (hasLeaf(unit) && results.size < maxResults) {
                results.add(MatchPair(value(buf.get(nodePos)), i + 1))
            }
        }
        return results
    }

    fun enumerateSuffixes(nodePosStart: Int, callback: (ByteArray, Int) -> Unit) {
        if (!valid(nodePosStart)) return
        val base = nodePosStart xor offset(buf.get(nodePosStart))
        val suffix = mutableListOf<Byte>()

        fun dfs(node: Int) {
            if (!valid(node)) return
            val unit = buf.get(node)
            if (hasLeaf(unit)) {
                val leafIdx = node xor offset(unit)
                if (valid(leafIdx)) {
                    callback(suffix.toByteArray(), value(buf.get(leafIdx)))
                }
            }
            val childBase = node xor offset(unit)
            for (c in 1 until 256) {
                val child = childBase xor c
                if (valid(child) && label(buf.get(child)) == c) {
                    suffix.add(c.toByte())
                    dfs(child)
                    suffix.removeAt(suffix.size - 1)
                }
            }
        }

        for (c in 1 until 256) {
            val child = base xor c
            if (valid(child) && label(buf.get(child)) == c) {
                suffix.add(c.toByte())
                dfs(child)
                suffix.removeAt(suffix.size - 1)
            }
        }
    }

    data class TraverseResult(val value: Int, val nodePos: Int, val keyPos: Int)
    data class MatchPair(val value: Int, val length: Int)

    companion object {
        const val VALUE_SCALE = 10000

        fun offset(unit: Int): Int {
            val shift = if ((unit and 0x200) != 0) 8 else 0
            return (unit ushr 10) shl shift
        }
        fun hasLeaf(unit: Int): Boolean = ((unit ushr 8) and 1) == 1
        fun value(unit: Int): Int = unit and 0x7FFFFFFF
        fun label(unit: Int): Int = unit and (0x80000000.toInt() or 0xFF)
    }
}