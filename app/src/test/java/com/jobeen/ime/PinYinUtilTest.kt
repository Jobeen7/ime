package com.jobeen.ime

import com.jobeen.ime.base.util.PinYinUtil
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PinYinUtil 的 T9 可能组合规格测试。
 *
 * 规格（从点选回写的消费方式推导）：左列条目会被点选回写成
 * 「拼写 + 分隔符」交给方案引擎，只有方案 speller 里有对应拼写
 * 的条目才可产出。万象 T9 的缩写拼写由实有音节派生，音节表没
 * 有以 i/u/v 开头的音节，单字母 "i"/"u"/"v" 无对应拼写、回写
 * 必然坏段，不得出现在结果里；其余单字母（有同首字母音节，如
 * j）与多字母音节不受影响。
 */
class PinYinUtilTest {

    @Test
    fun t9CombinationsExcludeSingleLettersWithoutSpelling() {
        // 4 键（GHI）左列不得含单字母 i
        val onFour = PinYinUtil.possibleCombinations(PinYinUtil.CandidateKindT9, "4")
        assertFalse(onFour.contains("i"))
        // 8 键（TUV）左列不得含单字母 u/v
        val onEight = PinYinUtil.possibleCombinations(PinYinUtil.CandidateKindT9, "8")
        assertFalse(onEight.contains("u"))
        assertFalse(onEight.contains("v"))
    }

    @Test
    fun t9CombinationsKeepValidSingleAndMultiLetterSyllables() {
        // 5 键（JKL）单字母 j 有同首字母音节（ji 等），缩写拼写
        // 存在，保留
        val onFive = PinYinUtil.possibleCombinations(PinYinUtil.CandidateKindT9, "5")
        assertTrue(onFive.contains("j"))
        // 多字母音节不受过滤影响：54 → ji
        val ji = PinYinUtil.possibleCombinations(PinYinUtil.CandidateKindT9, "54")
        assertTrue(ji.contains("ji"))
    }
}
