// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.base.util

/**
 * 邮箱后缀补全：光标前正在打「邮箱名@后缀」时，候选栏给常用邮箱后缀。
 *
 * 行为：
 * - 只看光标前的文字，不联网、不学习。
 * - 接着打的字母、数字、点只留开头对得上的后缀；打完的不再给。
 * - 点一个后缀，补上还没打的那段，不加空格；全角 ＠ 也认，选中时换成半角 @。
 * - 中文键盘下句号键、全角点在续打后缀时按半角 . 上屏。
 * - 九宫格数字键是拼音编码、不是字面数字，调用方须先按方案排除九宫格。
 */
object EmailSuffixes {

    /** 一次读光标前多少字：要能装下邮箱名、@ 和一段后缀。 */
    const val CONTEXT_LENGTH = 64

    /** 常用邮箱后缀，国内常用的在前。 */
    val DOMAINS = listOf(
        "qq.com", "163.com", "gmail.com", "126.com", "outlook.com",
        "foxmail.com", "icloud.com", "hotmail.com", "sina.com", "yahoo.com",
    )

    /** 续打后缀时按半角点算的字符：半角点、中文句号、全角点。 */
    val DOTS = setOf(".", "。", "．")

    /** 邮箱名里能出现的字符：字母、数字和 . _ % + -。 */
    private fun isLocalChar(c: Char): Boolean =
        c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' ||
            c == '.' || c == '_' || c == '%' || c == '+' || c == '-'

    /** 后缀里能出现的字符：字母、数字、点、连字符。 */
    private fun isDomainChar(c: Char): Boolean =
        c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c == '.' || c == '-'

    /** 光标前尾部的一段邮箱：@ 的位置与 @ 后已打的那段。 */
    private class Tail(val at: Int, val typed: String)

    /**
     * 从尾部往前找：先吃后缀字符，碰到 @/＠ 再吃邮箱名字符。
     * 尾部不是邮箱（没有 @、@ 前没有邮箱名、@ 后跟了别的字符）返回 null；
     * 有多个 @ 时只认最后一个。
     */
    private fun tailEmail(before: CharSequence): Tail? {
        var i = before.length - 1
        while (i >= 0 && isDomainChar(before[i])) i--
        if (i < 0 || (before[i] != '@' && before[i] != '＠')) return null
        val at = i
        var j = at - 1
        while (j >= 0 && isLocalChar(before[j])) j--
        if (j == at - 1) return null
        return Tail(at, before.subSequence(at + 1, before.length).toString())
    }

    /** 光标前正在打邮箱 @ 后面那段时，已经打了的那段（可以是空串）；不是邮箱返回 null。 */
    fun typedDomain(before: CharSequence?): String? =
        before?.let { tailEmail(it)?.typed }

    /** 开头对得上、还没打完的后缀；[typed] 为空时全部给。 */
    fun matching(typed: String): List<String> =
        DOMAINS.filter { domain ->
            domain.startsWith(typed, ignoreCase = true) && typed.length < domain.length
        }

    /** 光标前 [before] 正在打邮箱时给的后缀；不是邮箱时为空。 */
    fun suggestions(before: CharSequence?): List<String> =
        typedDomain(before)?.let(::matching).orEmpty()

    /** 选了 [domain] 还要补上的那段；光标前已经对不上（挪了光标、改了字）返回 null。 */
    fun remainder(before: CharSequence?, domain: String): String? {
        val typed = typedDomain(before) ?: return null
        return if (domain in matching(typed)) domain.drop(typed.length) else null
    }

    /**
     * 选了 [domain] 时光标前要换掉的那段和换成的字：「@ 已经打的」→「@ 整个后缀」（全角 ＠ 一起换成半角）。
     * 光标前已经对不上返回 null。
     */
    fun completion(before: CharSequence?, domain: String): Pair<String, String>? {
        if (before == null) return null
        val tail = tailEmail(before) ?: return null
        if (domain !in matching(tail.typed)) return null
        return before.subSequence(tail.at, before.length).toString() to "@$domain"
    }

    /** 接着打 [next] 后还有对得上的后缀（[before] 是光标前的文字）。 */
    fun continues(before: CharSequence?, next: String): Boolean {
        val typed = typedDomain(before) ?: return false
        return matching(typed + next).isNotEmpty()
    }

    /** 候选栏上现在是邮箱后缀（不是英文补全或中文联想）。 */
    fun isSuggestionList(words: List<String>): Boolean =
        words.isNotEmpty() && words.all { it in DOMAINS }

    /**
     * 单字续打归一化：字母／数字原样，全角点按半角 . 算；其余（@、多字、符号）返回 null。
     * 九宫格数字键是拼音编码、不是字面数字，调用方须先按方案排除九宫格。
     */
    fun normalizeDomainChar(text: String): String? {
        if (text.length != 1) return null
        return when (val c = text[0]) {
            in 'a'..'z', in 'A'..'Z', in '0'..'9' -> text
            '.', '。', '．' -> "."
            else -> null
        }
    }

    /** 九宫格家族方案 id：下划线分段后整段是 t9 或 t9i（不区分大小写）。 */
    fun isT9SchemaId(schemaId: String): Boolean =
        schemaId.split('_').any {
            it.equals("t9", ignoreCase = true) || it.equals("t9i", ignoreCase = true)
        }
}
