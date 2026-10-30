// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.data.manager

/**
 * 验证码抽取（重点候选 8-5，纯函数）：
 *
 * 只读入、不写回：输入字符串只作扫描（Kotlin 字符串不可变，
 * 本函数不持有、不回写、不记日志），误抽取的源文本绝不进入
 * 剪贴板历史（历史写入仍走 ClipboardManager.addEntry，与本
 * 函数无关）。
 *
 * 策略（关键词锚定优先于独立数字串）：
 * 1. 关键词锚定：关键词（验证码／校验码／动态码／verification
 *    code／auth code／code）后 10 字符内出现的数字段（数字间
 *    可含空格／横杠，如 "123 456"），去分隔后 4..8 位即命中；
 *    拉丁关键词要求左边界非字母（防 barcode 内含 code 误锚）；
 * 2. 独立数字串：首个长度 4..8 的纯数字段，且左右邻居均不是
 *    '-'／'／'．'（防日期 2026-10-29、版本号、小数误抽）；
 *    超长数字串（如 11 位手机号）整段不命中。
 * 3. 无命中返回 null；只扫描前 4096 字符（超长剪贴板不拖慢）。
 * 命中与否均不记日志——验证码只作提示用。
 */
object VerificationCodeExtractor {

    private const val MAX_SCAN_CHARS = 4096
    private const val MIN_CODE_LEN = 4
    private const val MAX_CODE_LEN = 8

    private val CJK_KEYWORDS = listOf("验证码", "校验码", "动态码")
    private val LATIN_KEYWORDS = listOf("verification code", "auth code", "code")

    fun extract(text: String): String? {
        if (text.isEmpty()) return null
        val scan = text.take(MAX_SCAN_CHARS)
        keywordAnchored(scan)?.let { return it }
        return standalone(scan)
    }

    private fun keywordAnchored(scan: String): String? {
        val lower = scan.lowercase()
        val positions = ArrayList<Pair<Int, Int>>()
        for (keyword in CJK_KEYWORDS) {
            var from = 0
            while (true) {
                val at = scan.indexOf(keyword, from)
                if (at < 0) break
                positions += at to at + keyword.length
                from = at + keyword.length
            }
        }
        for (keyword in LATIN_KEYWORDS) {
            var from = 0
            while (true) {
                val at = lower.indexOf(keyword, from)
                if (at < 0) break
                // 左边界非字母（防 barcode 之类词内含 code）
                if (at == 0 || !lower[at - 1].isLetter()) {
                    positions += at to at + keyword.length
                }
                from = at + keyword.length
            }
        }
        for ((_, end) in positions.sortedBy { it.first }) {
            candidateAfter(scan, end)?.let { return it }
        }
        return null
    }

    /** 关键词后：跳过至多 10 个非数字字符，随后数字／空格／横杠段去分隔后 4..8 位即命中。 */
    private fun candidateAfter(scan: String, from: Int): String? {
        var i = from
        var skipped = 0
        while (i < scan.length && scan[i] !in '0'..'9' && skipped < 10) {
            i++
            skipped++
        }
        if (i >= scan.length || scan[i] !in '0'..'9') return null
        val digits = StringBuilder()
        var j = i
        while (j < scan.length && (scan[j] in '0'..'9' || scan[j] == ' ' || scan[j] == '-')) {
            if (scan[j] in '0'..'9') digits.append(scan[j])
            j++
        }
        val code = digits.toString()
        return code.takeIf { it.length in MIN_CODE_LEN..MAX_CODE_LEN }
    }

    private fun standalone(scan: String): String? {
        var i = 0
        while (i < scan.length) {
            if (scan[i] !in '0'..'9') {
                i++
                continue
            }
            var j = i
            while (j < scan.length && scan[j] in '0'..'9') j++
            val run = scan.substring(i, j)
            val leftOk = i == 0 || scan[i - 1] !in "-/."
            val rightOk = j >= scan.length || scan[j] !in "-/."
            if (run.length in MIN_CODE_LEN..MAX_CODE_LEN && leftOk && rightOk) return run
            i = j
        }
        return null
    }
}
