package com.jobeen.ime

import com.jobeen.ime.base.ngram.DoubleArrayTrie
import java.nio.IntBuffer
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * DoubleArrayTrie.commonPrefixMaxValue 与「commonPrefixSearch 结果列表取
 * value 最大值」的等价性。生产 buffer 来自语法模型文件、仓库内无构建器，
 * 这里按 unit 编码（低 8 位 label、bit8 hasLeaf、bit10 起 offset，
 * base = 节点位置 xor offset，终结值存于 base 槽位）手工搭小型双数组。
 */
class DoubleArrayTrieMaxValueTest {

    private class Node {
        val children = sortedMapOf<Int, Node>()
        var terminalValue: Int? = null
    }

    private fun buildTrie(entries: Map<String, Int>): DoubleArrayTrie {
        val root = Node()
        for ((key, value) in entries) {
            var node = root
            for (b in key.toByteArray(Charsets.UTF_8)) {
                node = node.children.getOrPut(b.toInt() and 0xFF) { Node() }
            }
            node.terminalValue = value
        }
        val units = IntArray(1 shl 13)
        val used = BooleanArray(units.size)
        used[0] = true

        fun place(node: Node, pos: Int, labelByte: Int) {
            var base = 1
            while (true) {
                var ok = node.terminalValue == null || !used[base]
                if (ok) {
                    for (c in node.children.keys) {
                        if (used[base xor c]) {
                            ok = false
                            break
                        }
                    }
                }
                if (ok) break
                base++
            }
            val offset = pos xor base
            check(offset < (1 shl 21)) { "offset 超出测试编码范围: $offset" }
            var unit = (offset shl 10) or labelByte
            if (node.terminalValue != null) unit = unit or 0x100
            units[pos] = unit
            if (node.terminalValue != null) {
                units[base] = node.terminalValue!!
                used[base] = true
            }
            for (c in node.children.keys) used[base xor c] = true
            for ((c, child) in node.children) place(child, base xor c, c)
        }
        place(root, 0, 0)
        return DoubleArrayTrie(IntBuffer.wrap(units))
    }

    private fun assertEquivalent(trie: DoubleArrayTrie, key: ByteArray, maxResults: Int) {
        val listMax = trie.commonPrefixSearch(key, maxResults).maxOfOrNull { it.value }
        assertEquals(listMax, trie.commonPrefixMaxValue(key, maxResults))
    }

    @Test
    fun fixedKeysEquivalence() {
        val trie = buildTrie(
            mapOf(
                "a" to 10, "ab" to 30, "abc" to 5, "abd" to 50,
                "b" to 7, "bc" to 7, "c" to 0, "abcd" to 2_000_000_000,
            ),
        )
        val queries = listOf(
            "a", "ab", "abc", "abd", "abcd", "abcde", "abx", "b", "bc", "bd",
            "c", "d", "", "aa", "acb",
        )
        for (q in queries) {
            for (maxResults in intArrayOf(1, 2, 3, 8, 100)) {
                assertEquivalent(trie, q.toByteArray(Charsets.UTF_8), maxResults)
            }
        }
    }

    @Test
    fun maxResultsCapsCountedMatches() {
        // 截断口径：只计先命中的 maxResults 个，后出现的大值不算
        val trie = buildTrie(mapOf("a" to 1, "aa" to 100, "aaa" to 2))
        val key = "aaa".toByteArray(Charsets.UTF_8)
        assertEquals(1, trie.commonPrefixMaxValue(key, 1))
        assertEquals(100, trie.commonPrefixMaxValue(key, 2))
        assertEquals(100, trie.commonPrefixMaxValue(key, 8))
    }

    @Test
    fun noMatchReturnsNull() {
        val trie = buildTrie(mapOf("ab" to 3))
        val key = "xy".toByteArray(Charsets.UTF_8)
        assertTrue(trie.commonPrefixSearch(key, 8).isEmpty())
        assertEquals(null, trie.commonPrefixMaxValue(key, 8))
    }

    @Test
    fun nodePosStartEquivalence() {
        // 对齐 GramDb.query 的用法：从上下文落点出发查候选
        val trie = buildTrie(mapOf("xa" to 3, "xab" to 9, "xabc" to 4, "xb" to 12))
        val start = trie.traverse("x".toByteArray(Charsets.UTF_8))
        assertEquals(1, start.keyPos)
        val key = "abc".toByteArray(Charsets.UTF_8)
        val listMax = trie.commonPrefixSearch(key, 8, nodePosStart = start.nodePos)
            .maxOfOrNull { it.value }
        assertEquals(9, listMax)
        assertEquals(
            listMax,
            trie.commonPrefixMaxValue(key, 8, nodePosStart = start.nodePos),
        )
    }

    @Test
    fun randomKeysEquivalence() {
        val random = Random(42)
        val alphabet = "abcde"
        fun randomKey(maxLen: Int): String {
            val len = 1 + random.nextInt(maxLen)
            return buildString { repeat(len) { append(alphabet[random.nextInt(alphabet.length)]) } }
        }
        val entries = LinkedHashMap<String, Int>()
        repeat(200) { entries[randomKey(6)] = random.nextInt(0, 100_000) }
        val trie = buildTrie(entries)
        val queries = ArrayList<String>()
        queries += entries.keys
        queries += entries.keys.map { it.dropLast(1) }.filter { it.isNotEmpty() }
        repeat(200) { queries += randomKey(7) }
        for (q in queries) {
            for (maxResults in intArrayOf(1, 3, 8, 64)) {
                assertEquivalent(trie, q.toByteArray(Charsets.UTF_8), maxResults)
            }
        }
    }
}
