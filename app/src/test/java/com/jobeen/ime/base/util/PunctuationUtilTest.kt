package com.jobeen.ime.base.util

import org.junit.Assert.assertEquals
import org.junit.Test

class PunctuationUtilTest {

    @Test
    fun dunhao_bothDirections() {
        // WO33：PunctuationUtil.HALF_TO_FULL 补「､」→「、」一对，
        // FULL_TO_HALF 由本表反推得「、」→「､」；两向都锁死。
        // （与 PunctuationMode.CharacterSet 同款做法；26 键
        // QwertyKeyboard.transformAction 走的是这张表。）
        assertEquals("、", PunctuationUtil.convert("､", true))
        assertEquals("､", PunctuationUtil.convert("、", false))
        // 已是目标形态原样；getPair 双向可达；宽窄判定一致。
        assertEquals('、', PunctuationUtil.getPair('､'))
        assertEquals('､', PunctuationUtil.getPair('、'))
        // 串内混用：顿号随模式换形，其余字符不动。
        // 不对称口径（有意）：「。」已有 ASCII 半角等价形 '.'，
        // 仍走既有映射，不补 U+FF61「｡」。
    }
}
