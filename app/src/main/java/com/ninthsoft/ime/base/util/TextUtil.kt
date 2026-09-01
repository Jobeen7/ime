package com.ninthsoft.ime.base.util

object TextUtil {
    // 标点符号与边界字符集合
    private val BOUNDARY = setOf(
        '。',
        '，',
        '！',
        '？',
        '；',
        '：',
        '.',
        ',',
        '!',
        '?',
        ';',
        ':',
        '、',
        '“',
        '”',
        '‘',
        '’',
        '（',
        '）',
        '【',
        '】',
        '-',
        '_',
        '~',
        '～'
    )

    /**
     * 判断单个字符是否为符号（包含自定义的 BOUNDARY 以及 Unicode 标点/符号分类）
     */
    fun isSymbol(char: Char): Boolean {
        if (char in BOUNDARY) return true
        val type = CharCategory.valueOf(char.category.name)
        return when (type) {
            CharCategory.CONNECTOR_PUNCTUATION,      // 连接符标点（如下划线）
            CharCategory.DASH_PUNCTUATION,         // 破折号标点
            CharCategory.END_PUNCTUATION,          // 结束标点（如右括号、右引号）
            CharCategory.FINAL_QUOTE_PUNCTUATION,  // 结束引号
            CharCategory.INITIAL_QUOTE_PUNCTUATION,// 起始引号
            CharCategory.OTHER_PUNCTUATION,        // 其他标点（如句号、逗号、感叹号）
            CharCategory.START_PUNCTUATION,        // 起始标点（如左括号、左引号）
            CharCategory.MATH_SYMBOL,              // 数学符号
            CharCategory.CURRENCY_SYMBOL,          // 货币符号
            CharCategory.MODIFIER_SYMBOL,          // 修改符号
            CharCategory.OTHER_SYMBOL -> true      // 其他符号
            else -> false
        }
    }

    fun isAlphabet(char: Char): Boolean {
        return char in 'a'..'z' || char in 'A'..'Z'
    }

    /**
     * 判断字符串是否全部由符号组成（或者针对单个字符的重载）
     */
    fun isSymbol(text: String): Boolean {
        if (text.isEmpty()) return false
        return text.all { isSymbol(it) }
    }

    fun contextSubstrings(inputContext: String, last: Int = 4): List<String> {
        val realContext = inputContext.takeLast(last)
        val result = mutableListOf<String>()
        for (i in 1..realContext.length) {
            val sub = realContext.takeLast(i)
            result.add(sub)
        }
        return result.reversed()
    }

    fun <T> mostSimilarToFirst(
        list: List<T>,
        target: String,
        value: (T) -> String,
    ): List<T> {
        if (list.isEmpty()) return list
        var bestIndex = 0
        var bestScore = -1.0
        for (i in list.indices) {
            val score = similarity(value(list[i]), target)
            if (score > bestScore) {
                bestScore = score
                bestIndex = i
            }
        }
        if (bestIndex == 0) return list
        return buildList(list.size) {
            add(list[bestIndex])
            for (i in list.indices) {
                if (i != bestIndex) add(list[i])
            }
        }
    }

    fun similarity(a: String, b: String): Double {
        if (a == b) return 1.0
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val distance = levenshtein(a, b)
        return 1.0 - distance.toDouble() / maxOf(a.length, b.length)
    }

    fun levenshtein(a: String, b: String): Int {
        var prev = IntArray(b.length + 1) { it }
        var curr = IntArray(b.length + 1)
        for (i in a.indices) {
            curr[0] = i + 1
            for (j in b.indices) {
                curr[j + 1] =
                    if (a[i] == b[j]) prev[j] else minOf(prev[j + 1], curr[j], prev[j]) + 1
            }
            val tmp = prev
            prev = curr
            curr = tmp
        }
        return prev[b.length]
    }
}
