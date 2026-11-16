// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.input.keyboard

/**
 * 双拼韵母提示（小鹤双拼键位，纯逻辑）：
 *
 * - 韵母→键位取小鹤双拼标准键位表（与 Rime double_pinyin_flypy
 *   方案的 speller/algebra 同值；另核对过两处公开键位表，一致）：
 *   每键的韵母见 [finalsFor]；v/i/u 三键另兼声母 zh/ch/sh，
 *   提示只显示韵母侧（本项是“韵母提示”）。
 * - 键面只显示、不改动作：[hintFor] 的返回值只用作 26 键字母键
 *   的副标（alphabetKey 的 hintText），点按／长按动作一字不动；
 *   已有自定义角标的键仍显示自定义角标（KeySymbolOps 后套用），
 *   皮肤显式 subLabel 仍优先（装配器后套用），“隐藏按键提示”
 *   开关仍能藏起副标（KeyView 绘制期门控）。
 * - 双韵母键的显示只取首选韵母（角标一格容不下两个）：
 *   K 取 ing（不取 uai）、L 取 iang（不取 uang）、S 取 ong
 *   （不取 iong）、T 取 üe（不取 ue）、V 取 ui（不取 ü）、
 *   X 取 ia（不取 ua）；O 取 uo（o 与键同字，无信息量）。
 *   完整映射见 [finalsFor]，单测锁死。
 */
object ShuangpinHint {

    /** 方案名（预留：以后加微软／自然码等表时按名取表）。 */
    const val SCHEME_XIAOHE = "xiaohe"

    /** 每键的全部韵母（小鹤双拼标准键位表）。 */
    private val FINALS: Map<Char, List<String>> = mapOf(
        'a' to listOf("a"),
        'b' to listOf("in"),
        'c' to listOf("ao"),
        'd' to listOf("ai"),
        'e' to listOf("e"),
        'f' to listOf("en"),
        'g' to listOf("eng"),
        'h' to listOf("ang"),
        'i' to listOf("i"),
        'j' to listOf("an"),
        'k' to listOf("ing", "uai"),
        'l' to listOf("iang", "uang"),
        'm' to listOf("ian"),
        'n' to listOf("iao"),
        'o' to listOf("o", "uo"),
        'p' to listOf("ie"),
        'q' to listOf("iu"),
        'r' to listOf("uan"),
        's' to listOf("ong", "iong"),
        't' to listOf("ue", "üe"),
        'u' to listOf("u"),
        'v' to listOf("ui", "ü"),
        'w' to listOf("ei"),
        'x' to listOf("ia", "ua"),
        'y' to listOf("un"),
        'z' to listOf("ou"),
    )

    /** 键面显示的首选韵母（双韵母键见类注释取舍）；非字母返回 null。 */
    private val DISPLAY: Map<Char, String> = mapOf(
        'a' to "a",
        'b' to "in",
        'c' to "ao",
        'd' to "ai",
        'e' to "e",
        'f' to "en",
        'g' to "eng",
        'h' to "ang",
        'i' to "i",
        'j' to "an",
        'k' to "ing",
        'l' to "iang",
        'm' to "ian",
        'n' to "iao",
        'o' to "uo",
        'p' to "ie",
        'q' to "iu",
        'r' to "uan",
        's' to "ong",
        't' to "üe",
        'u' to "u",
        'v' to "ui",
        'w' to "ei",
        'x' to "ia",
        'y' to "un",
        'z' to "ou",
    )

    /** 某键的全部韵母；非字母返回空表。 */
    fun finalsFor(key: Char): List<String> =
        FINALS[key.lowercaseChar()].orEmpty()

    /** 某键键面显示的韵母注解；非字母返回 null。 */
    fun hintFor(key: Char): String? = DISPLAY[key.lowercaseChar()]
}
