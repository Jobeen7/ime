// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.data.manager

/**
 * 分词多选（WO52 1.9，对端 split_string_panel）：
 *
 * 切分：Android 内置 `android.icu.text.BreakIterator.getWordInstance(ULocale.CHINESE)`
 * 按词切分，不引 ICU4J 库。纯空白片段丢弃，其余片段（词、数字串、
 * 标点）各成一格。选中文本按原串下标取回，空格等分隔符不丢。
 *
 * 注意：主机单测跑在 JVM 上，`android.icu` 是桩（无实现），故单测
 * 用 [segment] 的 `java.text.BreakIterator` 重载走同一份切分核心；
 * java.text 的中文词典比 ICU 粗（连续汉字常切成一整段，如“你好世界”
 * 为一段），真机 ICU 会切得更细（“你好”／“世界”）。核心逻辑
 * （边界遍历、去空白、区间选择、滚动触发）两边同一份，单测覆盖它。
 *
 * 未定论（WO52 §15-13 `so0.O` 之外的分词调用点）：本文件只实现
 * 分词页本身；其余调用点遇上再标，不硬编。
 */
object WordSegmenter {

    /** 分词结果：文本＋在原串中的半开区间 [start, end)。 */
    data class Word(val text: String, val start: Int, val end: Int)

    /** 生产入口：Android 内置 ICU 中文分词。 */
    fun segment(text: String): List<Word> {
        val iterator = android.icu.text.BreakIterator
            .getWordInstance(android.icu.util.ULocale.CHINESE)
        iterator.setText(text)
        val bounds = ArrayList<Int>()
        var b = iterator.first()
        while (b != android.icu.text.BreakIterator.DONE) {
            bounds += b
            b = iterator.next()
        }
        return splitByBoundaries(text, bounds)
    }

    /** JVM 单测入口：java.text 分词，走同一份切分核心。 */
    fun segment(text: String, iterator: java.text.BreakIterator): List<Word> {
        iterator.setText(text)
        val bounds = ArrayList<Int>()
        var b = iterator.first()
        while (b != java.text.BreakIterator.DONE) {
            bounds += b
            b = iterator.next()
        }
        return splitByBoundaries(text, bounds)
    }

    /** 按边界表切分（纯函数）：空片段与纯空白片段丢弃，其余保留。 */
    internal fun splitByBoundaries(text: String, bounds: List<Int>): List<Word> {
        if (text.isEmpty() || bounds.size < 2) return emptyList()
        val words = ArrayList<Word>()
        for (i in 0 until bounds.size - 1) {
            val start = bounds[i].coerceIn(0, text.length)
            val end = bounds[i + 1].coerceIn(0, text.length)
            if (end <= start) continue
            val piece = text.substring(start, end)
            if (piece.isBlank()) continue
            words += Word(piece, start, end)
        }
        return words
    }

    /** 锚点区间选择（纯函数）：锚点与当前格之间闭区间，钳制到表内。 */
    fun selectionRange(anchor: Int, current: Int, count: Int): IntRange {
        if (count <= 0) return IntRange.EMPTY
        val lo = minOf(anchor, current).coerceIn(0, count - 1)
        val hi = maxOf(anchor, current).coerceIn(0, count - 1)
        return lo..hi
    }

    /** 选中文本：按原串下标取回锚点区间，中间的空格／标点原样保留。 */
    fun selectedText(text: String, words: List<Word>, range: IntRange): String {
        if (range.isEmpty() || words.isEmpty()) return ""
        val lo = words.getOrNull(range.first) ?: return ""
        val hi = words.getOrNull(range.last) ?: return ""
        return text.substring(lo.start, hi.end)
    }

    /**
     * 边缘自动滚动（纯函数）：手指距上下沿 16dp 内滚动，每帧至多 12dp；
     * 越近边缘越快，沿内 16dp 处为 0。返回本帧滚动 dp（上负下正），0 = 不滚动。
     */
    fun autoScrollDeltaDp(yDp: Float, viewHeightDp: Float): Float {
        val edge = 16f
        val maxStep = 12f
        return when {
            yDp < edge -> -maxStep * (1f - yDp.coerceAtLeast(0f) / edge)
            yDp > viewHeightDp - edge ->
                maxStep * (1f - (viewHeightDp - yDp).coerceAtLeast(0f) / edge)
            else -> 0f
        }
    }
}
