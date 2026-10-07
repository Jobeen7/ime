package com.jobeen.ime.base.speech

/**
 * 语音识别文本的归一化处理（纯函数，便于单测）。
 *
 * [normalizeSpacing] 处理中英间距，实时预览与最终结果共用；
 * [normalizeFinal] 在其之上再做标点规整，只用于最终上屏文本——
 * 实时预览是临时显示，乱动标点会让预览跳动。
 */
object FinalTextNormalizer {

    /** 中英间距规整：中文与中文/中文标点之间的空格去掉，英文标点前的空格去掉。 */
    fun normalizeSpacing(text: String): String {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return trimmed
        val chars = trimmed.toCharArray()
        val output = StringBuilder(trimmed.length)
        var i = 0
        while (i < chars.size) {
            if (chars[i].isWhitespace()) {
                var nextIndex = i + 1
                while (nextIndex < chars.size && chars[nextIndex].isWhitespace()) nextIndex++
                val previous = output.lastOrNull()
                val next = chars.getOrNull(nextIndex)
                val betweenCjk =
                    previous != null && next != null && isCjkOrPunctuation(previous) && isCjkOrPunctuation(
                        next
                    )
                val beforeAsciiPunctuation = next != null && next in ".,!?;:%)]}"
                if (!betweenCjk && !beforeAsciiPunctuation) {
                    repeat(nextIndex - i) { output.append(' ') }
                }
                i = nextIndex
            } else {
                output.append(chars[i++])
            }
        }
        return output.toString()
    }

    /**
     * 最终上屏文本：间距规整后再做两条保守的标点规整——
     * 1. 同一标点的连续重复合并为一个（「。。」→「。」，「，，」→「，」）；
     *    「……」「——」「！！！」这类有意的强调/省略写法不在此列，不动。
     * 2. 文本以中文字符结尾、长度 ≥ 2、且没有结尾标点时补「。」；
     *    以英文字母/数字/符号结尾的一律不补，避免误伤代码、账号、英文句。
     */
    fun normalizeFinal(text: String): String {
        val spaced = normalizeSpacing(text)
        if (spaced.isEmpty()) return spaced
        val merged = mergeRepeatedPunctuation(spaced)
        return appendSentencePeriod(merged)
    }

    private val MERGEABLE_PUNCT = setOf('。', '，', '、', '；', '：', '．', ',', ';', ':')

    private fun mergeRepeatedPunctuation(text: String): String {
        val out = StringBuilder(text.length)
        for (ch in text) {
            if (ch in MERGEABLE_PUNCT && out.isNotEmpty() && out.last() == ch) continue
            out.append(ch)
        }
        return out.toString()
    }

    private val SENTENCE_ENDINGS = setOf('。', '！', '？', '…', '”', '」', '』', '.', '!', '?')

    private fun appendSentencePeriod(text: String): String {
        if (text.length < 2) return text
        val last = text.last()
        if (last in SENTENCE_ENDINGS) return text
        // 仅中文字符结尾才补句号；英文/数字/其他符号结尾一律不动
        return if (isCjk(last)) text + '。' else text
    }

    private fun isCjk(ch: Char): Boolean =
        ch in '\u3400'..'\u4DBF' || ch in '\u4E00'..'\u9FFF' || ch in '\uF900'..'\uFAFF'

    private fun isCjkOrPunctuation(ch: Char): Boolean =
        isCjk(ch) || ch in '！'..'～' || ch in '\u3000'..'\u303F' || ch in '\uFF00'..'\uFFEF' || ch in '\uFE30'..'\uFE4F'
}
