// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime

import com.jobeen.ime.base.util.PinYinUtil
import com.jobeen.ime.skin.layout.CompactLayoutGenerator
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 紧凑布局组合消歧（PinYinUtil.possibleCompactCombinations）：
 * 与 possibleT9Combinations 同算法、同一音节表，只换分组。
 * 期望值用同一算法在音节表上算出（与 native CompactPinyinMap
 * 的 host 单测同值，两边绿即 Kotlin/native 一致）。
 */
class CompactPinyinExpanderTest {

    private val t9 = CompactLayoutGenerator.groupingFor(9)!!
    private val t14 = CompactLayoutGenerator.groupingFor(14)!!
    private val t18 = CompactLayoutGenerator.groupingFor(18)!!

    @Test
    fun t9GroupingMatchesLegacyT9Combinations() {
        // 9 键分组走新函数，与旧 T9 路径逐项一致（旧路径本身不动）。
        for (input in listOf("726", "54", "2", "4", "8", "26", "23", "9", "abc")) {
            assertEquals(
                "input $input",
                PinYinUtil.possibleCombinations(PinYinUtil.CandidateKindT9, input).toList(),
                PinYinUtil.possibleCompactCombinations(t9, input).toList(),
            )
        }
    }

    @Test
    fun t14QuOffersQiQuWu() {
        // qw 键＋ui 键：q/w × i/u 的组合——qi/qu/wu 同组码 QU。
        assertEquals(
            listOf("qi", "qu", "wu", "q", "w"),
            PinYinUtil.possibleCompactCombinations(t14, "qu").toList(),
        )
        assertEquals(
            listOf("q", "w"),
            PinYinUtil.possibleCompactCombinations(t14, "q").toList(),
        )
        // 14 键不认数字。
        assertEquals(
            emptyList<String>(),
            PinYinUtil.possibleCompactCombinations(t14, "726").toList(),
        )
    }

    @Test
    fun t18QuHasNoQi() {
        // 18 键的 i 自成一组：qi 的码是 QI，不在 QU 前缀里。
        assertEquals(
            listOf("qu", "wu", "q", "w"),
            PinYinUtil.possibleCompactCombinations(t18, "qu").toList(),
        )
        assertEquals(
            listOf("wa", "q", "w"),
            PinYinUtil.possibleCompactCombinations(t18, "qa").toList(),
        )
    }

    @Test
    fun emptyAndBlankInputReturnEmpty() {
        assertEquals(emptyList<String>(), PinYinUtil.possibleCompactCombinations(t14, null).toList())
        assertEquals(emptyList<String>(), PinYinUtil.possibleCompactCombinations(t14, "").toList())
        assertEquals(emptyList<String>(), PinYinUtil.possibleCompactCombinations(t14, "   ").toList())
    }

    @Test
    fun singleLettersWithoutSpellingAreFiltered() {
        // 与 T9 口径一致：单字母 i/u/v 无对应拼写，不产出。
        // "ui" 归一化为 UU（无音节）、再 U（只有单字母 u，被滤掉）。
        assertEquals(
            emptyList<String>(),
            PinYinUtil.possibleCompactCombinations(t14, "ui").toList(),
        )
        assertEquals(
            emptyList<String>(),
            PinYinUtil.possibleCompactCombinations(t14, "i").toList(),
        )
        // 对照：有拼写的单字母保留——"ty" 归一化为 TT（无音节）、
        // 再 T（单字母 t、y 都有同首字母音节）。
        assertEquals(
            listOf("t", "y"),
            PinYinUtil.possibleCompactCombinations(t14, "ty").toList(),
        )
        assertEquals(
            listOf("q", "w"),
            PinYinUtil.possibleCompactCombinations(t14, "qw").toList(),
        )
    }
}
