package com.ninthsoft.ime.base.util

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

        '\\' to '＼'
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
     * 半角转全角
     */
    fun toFullWidth(
        text: String
    ): String {
        return convert(text, true)
    }


    /**
     * 全角转半角
     */
    fun toHalfWidth(
        text: String
    ): String {
        return convert(text, false)
    }


    /**
     * 单字符转换
     */
    fun convertChar(
        char: Char, toFull: Boolean
    ): Char {
        val map = if (toFull) {
            HALF_TO_FULL
        } else {
            FULL_TO_HALF
        }

        return map[char] ?: char
    }


    /**
     * 判断是否是全角标点
     */
    fun isFullWidth(
        char: Char
    ): Boolean {
        return FULL_TO_HALF.containsKey(char)
    }


    /**
     * 判断是否是半角标点
     */
    fun isHalfWidth(
        char: Char
    ): Boolean {
        return HALF_TO_FULL.containsKey(char)
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
