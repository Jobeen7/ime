// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.skin.layout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 紧凑布局自动生成（松烟 merged14/merged18 思路，自研）：
 * 9 键为既有 T9 分组锚点；14/18 键为 QWERTY 行内相邻合并。
 */
class CompactLayoutGeneratorTest {

    @Test
    fun t9GroupingMatchesLegacyKeyMap() {
        val grouping = CompactLayoutGenerator.groupingFor(9)!!
        assertEquals(9, grouping.keyCount)
        assertEquals(
            listOf("abc", "def", "ghi", "jkl", "mno", "pqrs", "tuv", "wxyz"),
            grouping.groups.map { it.letters },
        )
        // 组码即 t9KeyMap 的码：A/D/G/J/M/P/T/W。
        assertEquals("ADGJMPTW", grouping.groups.joinToString("") { it.code.toString() })
        assertEquals('A', grouping.codeForInput('b'))
        assertEquals('A', grouping.codeForInput('2'))
        assertEquals('W', grouping.codeForInput('9'))
        assertEquals('W', grouping.codeForInput('z'))
    }

    @Test
    fun t14GroupingMergesQwertyAdjacentPairs() {
        val grouping = CompactLayoutGenerator.groupingFor(14)!!
        assertEquals(14, grouping.keyCount)
        assertEquals(
            listOf("qw", "er", "ty", "ui", "op", "as", "df", "gh", "jk", "l", "zx", "cv", "bn", "m"),
            grouping.groups.map { it.letters },
        )
        // 26 个字母恰好一次，QWERTY 顺序。
        assertEquals("qwertyuiopasdfghjklzxcvbnm", grouping.letters)
        // 组码互不相同。
        assertEquals(14, grouping.groups.map { it.code }.toSet().size)
        // 数字不认（14 键无数字键）。
        assertNull(grouping.codeForInput('2'))
        assertEquals('Q', grouping.codeForInput('w'))
        assertEquals('B', grouping.codeForInput('n'))
    }

    @Test
    fun t18GroupingMergesFewerPairs() {
        val grouping = CompactLayoutGenerator.groupingFor(18)!!
        assertEquals(18, grouping.keyCount)
        assertEquals(
            listOf("qw", "er", "ty", "u", "i", "o", "p", "as", "df", "gh", "j", "k", "l", "zx", "cv", "b", "n", "m"),
            grouping.groups.map { it.letters },
        )
        assertEquals("qwertyuiopasdfghjklzxcvbnm", grouping.letters)
        assertEquals(18, grouping.groups.map { it.code }.toSet().size)
        // 18 键比 14 键少合并：u/i/o/p 各自成组。
        assertEquals('U', grouping.codeForInput('u'))
        assertEquals('I', grouping.codeForInput('i'))
    }

    @Test
    fun mergeRowMergesLeftmostPairsFirst() {
        assertEquals(
            listOf("qw", "er", "ty", "ui", "op"),
            CompactLayoutGenerator.mergeRow("qwertyuiop", 5).map { it.letters },
        )
        assertEquals(
            listOf("qw", "er", "ty", "u", "i", "o", "p"),
            CompactLayoutGenerator.mergeRow("qwertyuiop", 3).map { it.letters },
        )
        assertEquals(
            listOf("zx", "cv", "bn", "m"),
            CompactLayoutGenerator.mergeRow("zxcvbnm", 3).map { it.letters },
        )
        assertEquals(
            listOf("a", "b"),
            CompactLayoutGenerator.mergeRow("ab", 0).map { it.letters },
        )
    }

    @Test
    fun unknownKeyCountAndBadAlphabetReturnNull() {
        assertNull(CompactLayoutGenerator.groupingFor(17))
        assertNull(CompactLayoutGenerator.groupingFor(26))
        assertNull(CompactLayoutGenerator.groupingFor(14, "aabc"))
        // 字母表缺字母时组数不够 → null。
        assertNull(CompactLayoutGenerator.groupingFor(14, "abcdefghij"))
    }

    @Test
    fun groupFirstAndSecondLetters() {
        val pair = CompactKeyGroup("qw")
        assertEquals('q', pair.first)
        assertEquals('w', pair.second)
        assertEquals('Q', pair.code)
        val single = CompactKeyGroup("m")
        assertEquals('m', single.first)
        assertEquals('m', single.second)
    }

    @Test
    fun groupingCoversAlphabetExactlyOnce() {
        for (keyCount in listOf(9, 14, 18)) {
            val grouping = CompactLayoutGenerator.groupingFor(keyCount)!!
            assertEquals(26, grouping.letters.length)
            assertEquals(26, grouping.letters.toSet().size)
            assertTrue(grouping.letters.toSet() == ('a'..'z').toSet())
        }
    }
}
