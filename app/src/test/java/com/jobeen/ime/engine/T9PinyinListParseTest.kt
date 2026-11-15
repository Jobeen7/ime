package com.jobeen.ime.engine

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * WO52 1.5：native t9PinyinList 的空格切分（与对端“按空格切分”
 * 同口径）。切分结果直接喂侧栏拼音列表，空条目绝不能漏进 UI。
 */
class T9PinyinListParseTest {

    @Test
    fun normalList() {
        assertEquals(
            listOf("pan", "pao", "ran", "rao", "san", "sao", "pa", "sa", "p", "q", "r", "s"),
            parseT9PinyinList("pan pao ran rao san sao pa sa p q r s"),
        )
    }

    @Test
    fun emptyString() {
        assertEquals(emptyList<String>(), parseT9PinyinList(""))
    }

    @Test
    fun blankOnly() {
        assertEquals(emptyList<String>(), parseT9PinyinList("   "))
    }

    @Test
    fun extraSpacesProduceNoEmptyItems() {
        assertEquals(
            listOf("ji", "li", "j", "k", "l"),
            parseT9PinyinList("ji  li   j k  l "),
        )
    }

    @Test
    fun singleItem() {
        assertEquals(listOf("t"), parseT9PinyinList("t"))
    }
}
