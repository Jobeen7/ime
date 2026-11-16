// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.skin.layout

/**
 * 紧凑拼音布局自动生成（松烟 merged14/merged18 思路，自研重写，未搬代码）：
 *
 * - 字母表来源：方案字母表（默认 a–z，即万象拼音方案的字母表；
 *   PinYinUtil.allPinyin 的全部音节只用这 26 个字母）。
 * - 9 键：既有 T9 分组（ABC/DEF/GHI/JKL/MNO/PQRS/TUV/WXYZ），
 *   与 PinYinUtil.t9KeyMap 同值，只作生成器的回归锚点，不改 T9。
 * - 14/18 键：QWERTY 行内相邻键合并（merged 的本义）——
 *   按 `qwertyuiop / asdfghjkl / zxcvbnm` 三行，从左起每行合并
 *   若干相邻对，余下单键。14 键每行合并 (5, 4, 3) 对，
 *   18 键每行合并 (3, 3, 2) 对；同一词库、同一组字逻辑，
 *   组合消歧见 [com.jobeen.ime.base.util.PinYinUtil.possibleCompactCombinations]
 *   与 native CompactPinyinMap（同一算法、同一音节表）。
 *
 * 分组码：每组首字母的大写（qw→Q、er→E……）；同一分组内字母
 * 互不相同、组码互不相同（生成器校验，不满足返回 null）。
 * 键面动作（见 KeyPreset.compactAlphabetKey）：点按发首字母、
 * 长按发次字母（单字母组长按同首字母），26 个字母全部可达。
 */
data class CompactKeyGroup(val letters: String) {
    init {
        require(letters.isNotEmpty()) { "empty group" }
        require(letters.all { it in 'a'..'z' }) { "group must be lowercase a-z" }
    }

    /** 分组码：首字母大写。 */
    val code: Char get() = letters[0].uppercaseChar()

    /** 点按发的字母。 */
    val first: Char get() = letters[0]

    /** 长按发的字母（单字母组与点按相同）。 */
    val second: Char get() = if (letters.length > 1) letters[1] else letters[0]
}

/**
 * 一份紧凑分组：键数＋按 QWERTY 顺序排列的分组。
 *
 * [codeForInput] 是引擎侧归一化的唯一入口：小写字母→所属组码；
 * 9 键分组另认数字 2–9（第 i 组认数字 i+2，与既有 T9 同口径）；
 * 其余字符原样返回，查表时自然落空（与 possibleT9Combinations 同口径）。
 */
data class CompactGrouping(val keyCount: Int, val groups: List<CompactKeyGroup>) {

    /** 分组覆盖的全部字母（QWERTY 顺序，恰好一次）。 */
    val letters: String get() = groups.joinToString("") { it.letters }

    fun codeForInput(c: Char): Char? {
        val lower = c.lowercaseChar()
        if (lower in 'a'..'z') {
            for (group in groups) {
                if (lower in group.letters) return group.code
            }
            return null
        }
        // 只有 8 组的 9 键分组认数字（T9 口径）；14/18 键无数字键。
        if (groups.size == 8 && c in '2'..'9') {
            return groups[c - '2'].code
        }
        return null
    }
}

object CompactLayoutGenerator {

    /** QWERTY 三行（合并只发生在行内，不跨行）。 */
    val QWERTY_ROWS: List<String> = listOf("qwertyuiop", "asdfghjkl", "zxcvbnm")

    const val DEFAULT_ALPHABET = "abcdefghijklmnopqrstuvwxyz"

    /** 每行合并的相邻对数（14 键 / 18 键）。 */
    private val MERGES_PER_ROW: Map<Int, List<Int>> = mapOf(
        14 to listOf(5, 4, 3),
        18 to listOf(3, 3, 2),
    )

    /** 既有 T9 分组（回归锚点，与 t9KeyMap 同值）。 */
    private val T9_GROUPS: List<String> = listOf(
        "abc", "def", "ghi", "jkl", "mno", "pqrs", "tuv", "wxyz",
    )

    /**
     * @param keyCount 只接受 9 / 14 / 18，其余返回 null。
     * @param alphabet 方案字母表：缺字母时按剩余字母合并（仍要求
     *   生成组数恰为 keyCount，否则 null）；重复字母直接 null。
     */
    fun groupingFor(keyCount: Int, alphabet: String = DEFAULT_ALPHABET): CompactGrouping? {
        if (keyCount != 9 && keyCount !in MERGES_PER_ROW) return null
        if (alphabet.length != alphabet.toSet().size) return null
        val alphabetSet = alphabet.toSet()
        val groups: List<CompactKeyGroup> = when (keyCount) {
            9 -> {
                if (!T9_GROUPS.all { group -> group.all { it in alphabetSet } }) return null
                T9_GROUPS.map { CompactKeyGroup(it) }
            }
            else -> {
                val merges = MERGES_PER_ROW.getValue(keyCount)
                QWERTY_ROWS.mapIndexed { index, row ->
                    mergeRow(row.filter { it in alphabetSet }, merges[index])
                }.flatten()
            }
        }
        // 9 键只有 8 个字母组（"1" 键不带字母）；14/18 键组数即键数。
        val expectedGroups = if (keyCount == 9) 8 else keyCount
        if (groups.size != expectedGroups) return null
        if (groups.map { it.code }.toSet().size != groups.size) return null
        return CompactGrouping(keyCount, groups)
    }

    /**
     * 行内从左起合并 [merges] 对相邻键，余下单键。
     * 如 10 字母合并 5 对 → 5 组；9 字母合并 4 对 → 4 对＋1 单键。
     */
    fun mergeRow(letters: String, merges: Int): List<CompactKeyGroup> {
        val out = ArrayList<CompactKeyGroup>()
        var i = 0
        var merged = 0
        while (i < letters.length) {
            if (merged < merges && i + 1 < letters.length) {
                out += CompactKeyGroup(letters.substring(i, i + 2))
                i += 2
                merged++
            } else {
                out += CompactKeyGroup(letters.substring(i, i + 1))
                i += 1
            }
        }
        return out
    }
}
