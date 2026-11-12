package com.jobeen.ime.base.marisa

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** B-L3：parseSearchResults 不假设 native 返回数组形态合法。 */
class MarisaTrieTest {

    private val trie = MarisaTrie()

    @Test
    fun parse_nullAndEmpty() {
        assertTrue(trie.parseSearchResults(null).isEmpty())
        assertTrue(trie.parseSearchResults(emptyArray()).isEmpty())
    }

    @Test
    fun parse_validPairs() {
        val results = trie.parseSearchResults(arrayOf<Any>("你好", "1", "世界", "42"))
        assertEquals(
            listOf(MarisaTrie.MatchResult("你好", 1), MarisaTrie.MatchResult("世界", 42)),
            results,
        )
    }

    @Test
    fun parse_oddLength_rejected() {
        try {
            trie.parseSearchResults(arrayOf<Any>("a", "1", "b"))
        } catch (e: IllegalArgumentException) {
            return
        }
        fail("expected IllegalArgumentException for odd-length array")
    }

    @Test
    fun parse_nonStringElement_rejected() {
        try {
            trie.parseSearchResults(arrayOf<Any>("a", 1))
        } catch (e: IllegalArgumentException) {
            return
        }
        fail("expected IllegalArgumentException for non-String id element")
        try {
            trie.parseSearchResults(arrayOf<Any>(123, "1"))
        } catch (e: IllegalArgumentException) {
            return
        }
        fail("expected IllegalArgumentException for non-String key element")
    }

    @Test
    fun parse_nonNumericId_rejected() {
        try {
            trie.parseSearchResults(arrayOf<Any>("a", "not-a-number"))
        } catch (e: IllegalArgumentException) {
            return
        }
        fail("expected IllegalArgumentException for non-numeric id")
    }
}
