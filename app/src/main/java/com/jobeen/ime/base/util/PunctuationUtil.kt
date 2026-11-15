package com.jobeen.ime.base.util

object PunctuationUtil {

    /**
     * 半角 -> 全角
     */
    private val HALF_TO_FULL = mapOf(
        '~' to '～',
        '!' to '！',
        '@' to '＠',
        '#' to '＃',
        '$' to '＄',
        '%' to '％',
        '^' to '＾',
        '&' to '＆',
        '*' to '＊',

        '(' to '（',
        ')' to '）',

        ':' to '：',
        ';' to '；',

        ',' to '，',
        '.' to '。',

        '?' to '？',
        '/' to '／',

        '"' to '＂',
        '\'' to '＇',

        '[' to '［',
        ']' to '］',

        '{' to '｛',
        '}' to '｝',

        '+' to '＋',
        '-' to '－',
        '=' to '＝',

        '<' to '＜',
        '>' to '＞',

        '_' to '＿',
        '|' to '｜',

        '\\' to '＼',

        // 「、」(U+3001) 的单字符半角形是「､」(U+FF64
        // HALFWIDTH IDEOGRAPHIC COMMA，兼容分解 <narrow>
        // 3001，NFKC→U+3001；WO33 核实：交付门 .11 第 9 条
        // “顿号无单字符半角形”是错的）。FULL_TO_HALF 由本表
        // 反推，故一半角、半角两向同补。「。」不补：它已有
        // ASCII 半角等价形 '.'（本表既有映射），与「、」
        // 无 ASCII 等价形不同。
        '､' to '、'
    )

    /**
     * 全角 -> 半角
     */
    private val FULL_TO_HALF = HALF_TO_FULL.entries.associate { (half, full) ->
        full to half
    }

    /**
     * 转换字符串
     *
     * @param text 输入文本
     * @param toFull 是否转全角
     */
    fun convert(
        text: String, toFull: Boolean
    ): String {
        val map = if (toFull) {
            HALF_TO_FULL
        } else {
            FULL_TO_HALF
        }

        return buildString(text.length) {
            text.forEach { char ->
                append(map[char] ?: char)
            }
        }
    }

    /**
     * 获取对应字符
     *
     * 例如:
     * getPair('#') -> '＃'
     * getPair('＃') -> '#'
     */
    fun getPair(
        char: Char
    ): Char? {
        return HALF_TO_FULL[char] ?: FULL_TO_HALF[char]
    }
}
