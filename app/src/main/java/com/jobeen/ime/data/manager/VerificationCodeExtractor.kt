// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.data.manager

/**
 * 验证码抽取（WO52 5.2，与对端 j90 一致）：
 *
 * 三正则（与对端口径一致）：
 * - a 网址：`(?i)\b(?:https?://|www\.)[^\s<>"'，。；、]+`
 * - b 关键词：简繁中文验证码类＋ `verification|passcode|\b(?:code|otp|pin)\b`
 * - c 数字：`(?<![A-Za-z0-9*])[0-9]{4,6}(?![A-Za-z0-9*])`（4–6 位）
 *
 * 取数：先找锚点（关键词命中＋网址命中），再取离任一锚点最近的
 * 4–6 位数字（按与网址/关键词的距离取最近者；并列取最早出现者）。
 * 网址内的数字不算候选（URL 排除）。关键词后允许数字间夹空格／
 * 横杠（如 "123 456" 计 6 位），与旧口径一致。
 *
 * 改动点（与旧版对照）：
 * - 数字 4–8 位 → 4–6 位是收窄（与对端口径一致）：7–8 位数字串不再命中。
 * - 无锚点时不再抽取：旧版对无关键词的独立数字串（如 "复制这个
 *   123456 粘贴"）也抽取；新版要求必须有关键词或网址锚点，裸数字
 *   不再命中（日期 "2026-10-29"、版本号 "1.2345" 由此也不再误抽）。
 *
 * 只读入、不写回：输入字符串只作扫描，误抽取的源文本绝不进入
 * 剪贴板历史（历史写入仍走 ClipboardManager.addEntry，与本函数
 * 无关）。命中与否均不记日志——验证码只作提示用。
 */
object VerificationCodeExtractor {

    private const val MAX_SCAN_CHARS = 4096

    private val URL_REGEX =
        Regex("""(?i)\b(?:https?://|www\.)[^\s<>"'，。；、]+""")

    private val KEYWORD_REGEX = Regex(
        "(?i)验证码|驗證碼|校验码|确认码|激活码|安全码|识别码|动态码|动态密码" +
            "|验证密码|一次性密码|短信码|取件码|提货码|verification|passcode" +
            """|\b(?:code|otp|pin)\b"""
    )

    private val DIGIT_REGEX = Regex("""(?<![A-Za-z0-9*])[0-9]{4,6}(?![A-Za-z0-9*])""")

    private data class Span(val start: Int, val end: Int, val value: String)

    fun extract(text: String): String? {
        if (text.isEmpty()) return null
        val scan = text.take(MAX_SCAN_CHARS)
        val urls = URL_REGEX.findAll(scan).map { Span(it.range.first, it.range.last + 1, it.value) }.toList()
        val keywords = KEYWORD_REGEX.findAll(scan).map { Span(it.range.first, it.range.last + 1, it.value) }.toList()
        val anchors = urls + keywords
        if (anchors.isEmpty()) return null
        val candidates = ArrayList<Span>()
        for (m in DIGIT_REGEX.findAll(scan)) {
            val start = m.range.first
            val end = m.range.last + 1
            // URL 排除：落在网址内的数字不是验证码
            if (urls.any { start >= it.start && end <= it.end }) continue
            candidates += Span(start, end, m.value)
        }
        // 关键词后夹空格／横杠的数字组（如 "123 456"）：关键词后跳过至多
        // 10 个非数字字符，随后数字／空格／横杠段去分隔后 4–6 位即命中。
        for (keyword in keywords) {
            spacedAfter(scan, keyword.end)?.let { candidates += it }
        }
        if (candidates.isEmpty()) return null
        return candidates
            .distinctBy { it.start to it.end }
            .minWithOrNull(
                compareBy<Span> { candidate ->
                    anchors.minOf { anchor -> gap(candidate, anchor) }
                }.thenBy { it.start }
            )?.value
    }

    /** 文本中第一个 URL（j90.a 正则），无则 null；供长按菜单 Open link 条件用。 */
    fun findUrl(text: String): String? =
        URL_REGEX.find(text.take(MAX_SCAN_CHARS))?.value

    /** 字符下标落在哪个 URL 内就返回该 URL（编辑页点链接用），无则 null。 */
    fun findUrlAt(text: String, index: Int): String? {
        if (index < 0 || index > text.length) return null
        return URL_REGEX.findAll(text.take(MAX_SCAN_CHARS)).firstOrNull { match ->
            index in match.range.first..match.range.last + 1
        }?.value
    }

    /** 两段之间的字符距离：相邻／重叠为 0，否则为间隔字符数。 */
    private fun gap(a: Span, b: Span): Int = when {
        a.end <= b.start -> b.start - a.end
        b.end <= a.start -> a.start - b.end
        else -> 0
    }

    /** 关键词后：跳过至多 10 个非数字字符，随后数字／空格／横杠段去分隔后 4–6 位即命中。 */
    private fun spacedAfter(scan: String, from: Int): Span? {
        var i = from
        var skipped = 0
        while (i < scan.length && scan[i] !in '0'..'9' && skipped < 10) {
            i++
            skipped++
        }
        if (i >= scan.length || scan[i] !in '0'..'9') return null
        val digits = StringBuilder()
        var j = i
        var lastDigit = i
        while (j < scan.length && (scan[j] in '0'..'9' || scan[j] == ' ' || scan[j] == '-')) {
            if (scan[j] in '0'..'9') {
                digits.append(scan[j])
                lastDigit = j + 1
            }
            j++
        }
        val code = digits.toString()
        if (code.length !in 4..6) return null
        return Span(i, lastDigit, code)
    }
}

/**
 * 验证码提示门禁（WO52 5.2，与对端 to1 case 2 一致）：
 * 全为真才展示——监听开关开＋不是手动来源＋提示条开关开＋文本
 * 长度 ≤300＋当前内容有工具栏＋提取成功＋写入成功。
 *
 * [shouldShow] 是七门合取；[passesStaticGates] 是除写入外的六门，
 * 供写入前判断（写入成功是第七门，写完才知道）。
 */
object VerificationCodeGate {

    /** 提示条文本长度上限（对端口径）：超长文本不展示验证码提示。 */
    const val MAX_PROMPT_TEXT_LENGTH = 300

    fun shouldShow(
        listeningEnabled: Boolean,
        isManualSource: Boolean,
        promptEnabled: Boolean,
        textLength: Int,
        hasToolbar: Boolean,
        extracted: Boolean,
        written: Boolean,
    ): Boolean = passesStaticGates(
        listeningEnabled, isManualSource, promptEnabled, textLength, hasToolbar, extracted
    ) && written

    fun passesStaticGates(
        listeningEnabled: Boolean,
        isManualSource: Boolean,
        promptEnabled: Boolean,
        textLength: Int,
        hasToolbar: Boolean,
        extracted: Boolean,
    ): Boolean = listeningEnabled &&
        !isManualSource &&
        promptEnabled &&
        textLength <= MAX_PROMPT_TEXT_LENGTH &&
        hasToolbar &&
        extracted
}
