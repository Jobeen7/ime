package com.jobeen.ime.base.speech

/**
 * 语音定稿专名纠错规则：一条规则对应一个「正形」（用户亲手纠正到的词，
 * 如人名、公司名），以及识别端可能出现的「错形」在每个字位上学到的字集。
 *
 * [spanLength] 是错形的长度（与正形长度可以不同，如识别漏字）；
 * [positionChars] 与错形逐字位对应：某段文本在每个字位上的字都落进
 * 对应字集、且该段不等于正形本身时，整段替换为正形。
 *
 * 字集只来自用户自己的纠正记录（错形↔正形逐字位对齐学到），不引入
 * 通用拼音表：没被用户教过的同音关系绝不触发替换，误纠面最小。
 */
data class CorrectionRule(
    val rightForm: String,
    val spanLength: Int,
    val positionChars: List<Set<Char>>,
) {
    fun matchesAt(text: String, start: Int): Boolean {
        if (start < 0 || start + spanLength > text.length) return false
        for (i in 0 until spanLength) {
            if (text[start + i] !in positionChars[i]) return false
        }
        return true
    }
}

/** 一次实际发生的替换。[start] 是替换后文本（正形所在）的位置。 */
data class AppliedCorrection(
    val start: Int,
    val wrongForm: String,
    val rightForm: String,
)

data class CorrectResult(
    val text: String,
    val applied: List<AppliedCorrection>,
)

/**
 * 语音定稿文本的专名同音纠错（纯函数，便于单测）。
 *
 * 只作用于最终上屏文本，不碰流式预览、不碰打字链路。规则按错形
 * 长度降序应用（长段优先），已替换区间不再被其他规则重叠命中。
 *
 * 正形保护：待纠片段若恰好一字不差等于任一已学正形，永不改写——
 * 逐位字集泛化可能让别的规则的字集恰好罩住一个已教正形，若不加
 * 这层保护，用户刚教对的词会被另一条规则的泛化组合再次改错。
 */
object VoiceCorrector {

    const val MIN_FORM_LENGTH = 2
    const val MAX_FORM_LENGTH = 6

    /** 可作正形/错形的词形：2–6 字、全为 CJK 表意字（专名形态）。 */
    fun isValidForm(form: String): Boolean =
        form.length in MIN_FORM_LENGTH..MAX_FORM_LENGTH && form.all { isCjk(it) }

    fun isCjk(ch: Char): Boolean =
        ch in '\u3400'..'\u4DBF' || ch in '\u4E00'..'\u9FFF' || ch in '\uF900'..'\uFAFF'

    /**
     * 由同一正形的一组等长错形构建规则；错形长度与正形不同时由调用方
     * 按错形长度分组后分别调用。错形与正形完全相同的不计入（无意义）。
     * 无有效错形时返回 null。
     */
    fun buildRule(rightForm: String, wrongForms: Collection<String>): CorrectionRule? {
        val valid = wrongForms.filter { it != rightForm && isValidForm(it) }
        if (valid.isEmpty()) return null
        val spanLength = valid.first().length
        if (valid.any { it.length != spanLength }) return null
        val positionChars = (0 until spanLength).map { i ->
            valid.map { it[i] }.toSet()
        }
        return CorrectionRule(rightForm, spanLength, positionChars)
    }

    fun correct(text: String, rules: List<CorrectionRule>): CorrectResult {
        if (text.isEmpty() || rules.isEmpty()) return CorrectResult(text, emptyList())
        val sorted = rules.sortedWith(
            compareByDescending<CorrectionRule> { it.spanLength }
                .thenByDescending { it.rightForm.length }
        )
        // 全部已学正形集合：片段一字不差等于其中任何一个都受保护
        val protectedForms = rules.mapTo(HashSet()) { it.rightForm }
        val consumed = BooleanArray(text.length)
        // 输入坐标下的命中（start → 规则）
        val hits = ArrayList<Pair<Int, CorrectionRule>>()
        for (rule in sorted) {
            val len = rule.spanLength
            if (len > text.length) continue
            var start = 0
            while (start + len <= text.length) {
                var blocked = false
                for (i in start until start + len) {
                    if (consumed[i]) {
                        blocked = true
                        break
                    }
                }
                if (!blocked && rule.matchesAt(text, start)) {
                    val span = text.substring(start, start + len)
                    // 正形保护含本规则自己的正形（span == rightForm 同此）
                    if (span !in protectedForms) {
                        hits += start to rule
                        for (i in start until start + len) consumed[i] = true
                        start += len
                        continue
                    }
                }
                start++
            }
        }
        if (hits.isEmpty()) return CorrectResult(text, emptyList())
        hits.sortBy { it.first }
        val out = StringBuilder(text.length)
        val applied = ArrayList<AppliedCorrection>(hits.size)
        var pos = 0
        for ((start, rule) in hits) {
            out.append(text, pos, start)
            applied += AppliedCorrection(
                start = out.length,
                wrongForm = text.substring(start, start + rule.spanLength),
                rightForm = rule.rightForm,
            )
            out.append(rule.rightForm)
            pos = start + rule.spanLength
        }
        out.append(text, pos, text.length)
        return CorrectResult(out.toString(), applied)
    }
}
