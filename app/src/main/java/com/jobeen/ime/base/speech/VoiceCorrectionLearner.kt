package com.jobeen.ime.base.speech

/**
 * 语音纠错「沉淀」的纯逻辑。
 *
 * 教学信号取自用户的打字上屏词段：语音上屏后，他若把某个听错的专名
 * 删掉重打，打出的词段就是亲手选定的正形。用它去配对语音段中
 * 「等长、过半字位相同」的片段，即得（错形 → 正形）词对——改述式
 * 编辑（如把一个词换成毫不相干的另一个词）字位重合度不够，永远
 * 配不上，不会被误学。
 */
object VoiceCorrectionLearner {

    /** 一次文本变更：[start] 起的 [oldText] 被替换为 [newText]。 */
    data class Change(
        val start: Int,
        val oldText: String,
        val newText: String,
    )

    /** 前后缀公共部分裁剪，提取两段文本之间的最小变更；相同则返回 null。 */
    fun extractChange(old: String, new: String): Change? {
        if (old == new) return null
        val maxPrefix = minOf(old.length, new.length)
        var prefix = 0
        while (prefix < maxPrefix && old[prefix] == new[prefix]) prefix++
        var suffix = 0
        while (suffix < old.length - prefix && suffix < new.length - prefix &&
            old[old.length - 1 - suffix] == new[new.length - 1 - suffix]
        ) {
            suffix++
        }
        return Change(
            start = prefix,
            oldText = old.substring(prefix, old.length - suffix),
            newText = new.substring(prefix, new.length - suffix),
        )
    }

    /** 配对成立后的处置动作。 */
    sealed interface PairingAction {
        /** 学入词对：[wrongForm]（语音段里的错形）→ [rightForm]（打出的正形）。 */
        data class Learn(val wrongForm: String, val rightForm: String) : PairingAction

        /** 他把自动纠正打回了纠正前的错形：停用该正形。 */
        data class Disable(val rightForm: String) : PairingAction

        /** 他把自动纠正改成了另一个词形：学新对，并停用被否决的正形。 */
        data class LearnAndDisable(
            val wrongForm: String,
            val rightForm: String,
            val disableRightForm: String,
        ) : PairingAction
    }

    data class Pairing(
        val start: Int,
        val span: String,
        val action: PairingAction,
    )

    /** 配对所需的同位相同字数下限：词长的半数（向上取整）。 */
    fun minSameChars(wordLength: Int): Int = (wordLength + 1) / 2

    /**
     * 在语音段 [segment] 中为打出的正形 [word] 找配对片段。
     *
     * 候选片段须与 [word] 等长、全 CJK、同位相同字数达到
     * [minSameChars] 且至少有一字不同（全同是本来就对，不学）。
     * 候选不唯一（歧义）时返回 null，宁可漏学不可误学。
     * 命中位置恰是某次自动纠正的正形时，按回退/改写语义处置。
     */
    fun decidePairing(
        segment: String,
        applied: List<AppliedCorrection>,
        word: String,
    ): Pairing? {
        if (!VoiceCorrector.isValidForm(word)) return null
        val len = word.length
        if (segment.length < len) return null
        val threshold = minSameChars(len)
        var foundStart = -1
        var foundSpan: String? = null
        for (start in 0..segment.length - len) {
            val span = segment.substring(start, start + len)
            if (!VoiceCorrector.isValidForm(span)) continue
            var same = 0
            for (i in 0 until len) {
                if (span[i] == word[i]) same++
            }
            if (same in threshold until len) {
                if (foundStart >= 0) return null // 歧义，放弃
                foundStart = start
                foundSpan = span
            }
        }
        val span = foundSpan ?: return null
        val hit = applied.firstOrNull { it.start == foundStart && it.rightForm == span }
        val action = when {
            hit == null -> PairingAction.Learn(span, word)
            word == hit.wrongForm -> PairingAction.Disable(hit.rightForm)
            else -> PairingAction.LearnAndDisable(span, word, hit.rightForm)
        }
        return Pairing(foundStart, span, action)
    }
}
