package com.jobeen.ime.base.speech

/**
 * 语音识别文本的归一化处理（纯函数，便于单测）。
 *
 * [normalizeSpacing] 处理中英间距，实时预览与最终结果共用；
 * [normalizeFinal] 在其之上再做标点处理，只用于最终上屏文本——
 * 实时预览是临时显示，乱动标点会让预览跳动。
 *
 * 定稿标点分三步（顺序固定）：重复标点合并 → [addPunctuation]
 * 第二遍补标点 → [appendSentencePeriod] 文末收尾。第二遍只补
 * 模型没出的标点，不动模型已出的标点；规则全部是高精度的词法
 * 规则（长分句前加逗号、句末语气词收尾），不做猜测式断句。
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
     * 最终上屏文本：间距规整后做三步标点处理——
     * 1. 同一标点的连续重复合并为一个（「。。」→「。」，「，，」→「，」）；
     *    「……」「——」「！！！」这类有意的强调/省略写法不在此列，不动。
     * 2. 第二遍补标点（[addPunctuation]）：分句前补「，」、句中「吧」后
     *    补「。」；只补模型没出的标点，不动已有标点。
     * 3. 文末收尾（[appendSentencePeriod]）：中文结尾无标点时补「。」；
     *    以「吗」「么」结尾的补「？」；以英文字母/数字/符号结尾的一律
     *    不补，避免误伤代码、账号、英文句。
     */
    fun normalizeFinal(text: String): String {
        val spaced = normalizeSpacing(text)
        if (spaced.isEmpty()) return spaced
        val merged = mergeRepeatedPunctuation(spaced)
        val punctuated = addPunctuation(merged)
        return appendSentencePeriod(punctuated)
    }

    /**
     * 第二遍补标点：只在定稿上屏前跑一次，流式预览不跑。
     *
     * 规则 A——分句逗号：分句连接词（然后、但是、所以……）前、且自上
     * 一个标点（或文首）起的无标点段长度 ≥ [MIN_CLAUSE_RUN] 时，在
     * 连接词前补「，」。短分句不补（「你要茶还是咖啡」不动）；连接词
     * 在文首或已有标点后不补；「之所以」的「所以」、「是因为」
     * 的「因为」不补。
     *
     * 规则 B——句中「吧」后补句号：「吧」后还有 ≥ [MIN_AFTER_BA] 个字
     * 才到下一个标点或文末时补「。」（「你先走吧我等会就来」→
     * 「你先走吧。我等会就来」）；「酒吧」「吧台」不补；文末的「吧」
     * 由 [appendSentencePeriod] 收尾，这里不动。
     *
     * 两条规则都要求前一个字符是中文（ASCII／空格／标点后不补），且
     * 只插在无标点处；已带标点的文本原样通过，多次调用结果不变。
     */
    fun addPunctuation(text: String): String {
        if (text.length < 2) return text
        val out = StringBuilder(text.length + 8)
        var runLen = 0 // 自上一个标点（或文首）起的无标点段长度
        var i = 0
        while (i < text.length) {
            val ch = text[i]
            if (ch in PUNCT_BOUNDARIES) {
                out.append(ch)
                runLen = 0
                i++
                continue
            }
            // 规则 B：句中「吧」后补句号
            if (ch == '吧') {
                val prev = text.getOrNull(i - 1)
                val next = text.getOrNull(i + 1)
                val ahead = countUntilBoundary(text, i + 1)
                if (prev != null && isCjk(prev) && prev != '酒' && next != '台' && ahead >= MIN_AFTER_BA) {
                    out.append(ch)
                    out.append('。')
                    runLen = 0
                    i++
                    continue
                }
            }
            // 规则 A：分句连接词前补逗号
            if (i + 1 < text.length && runLen >= MIN_CLAUSE_RUN) {
                val two = text.substring(i, i + 2)
                if (two in CONNECTIVES) {
                    val prev = text.getOrNull(i - 1)
                    // 固定搭配不拆：之所以、是因为
                    val guarded = (two == "所以" && prev == '之') ||
                        (two == "因为" && prev == '是')
                    if (prev != null && isCjk(prev) && !guarded) {
                        out.append('，')
                        runLen = 0
                    }
                }
            }
            out.append(ch)
            runLen++
            i++
        }
        return out.toString()
    }

    /** 分句连接词（均为二字词）：出现在长分句前时前面补「，」。 */
    private val CONNECTIVES = setOf(
        "然后", "接着", "后来", "之后", "于是",
        "所以", "因此", "但是", "不过", "可是",
        "而且", "另外", "首先", "其次", "最后",
        "总之", "其实", "比如", "例如", "如果",
        "虽然", "尽管", "由于", "因为", "并且",
        "或者", "还是",
    )

    /** 分句逗号的最小无标点段长度：短分句不补逗号。 */
    private const val MIN_CLAUSE_RUN = 6

    /** 「吧」后补句号要求后面至少还有这么多个字才到标点或文末。 */
    private const val MIN_AFTER_BA = 4

    /** 视为分句边界的标点：逗号、句号、问号、感叹号、分号、冒号、顿号、省略号、破折号。 */
    private val PUNCT_BOUNDARIES = setOf(
        '，', '。', '？', '！', '；', '：', '、', '…', '—',
        ',', '.', '?', '!', ';', ':',
    )

    /** 从 [from] 起到下一个分句边界（或文末）之间的字符数。 */
    private fun countUntilBoundary(text: String, from: Int): Int {
        var n = 0
        var j = from
        while (j < text.length && text[j] !in PUNCT_BOUNDARIES) {
            n++
            j++
        }
        return n
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
        // 以「吗」「么」结尾是问句，补「？」而不是「。」；「呢」兼疑问
        // 与语气助词两种用法，保守起见仍走句号（已知局限）。
        if (last == '吗' || last == '么') return text + '？'
        // 仅中文字符结尾才补句号；英文/数字/其他符号结尾一律不动
        return if (isCjk(last)) text + '。' else text
    }

    private fun isCjk(ch: Char): Boolean =
        ch in '\u3400'..'\u4DBF' || ch in '\u4E00'..'\u9FFF' || ch in '\uF900'..'\uFAFF'

    private fun isCjkOrPunctuation(ch: Char): Boolean =
        isCjk(ch) || ch in '！'..'～' || ch in '\u3000'..'\u303F' || ch in '\uFF00'..'\uFFEF' || ch in '\uFE30'..'\uFE4F'
}
