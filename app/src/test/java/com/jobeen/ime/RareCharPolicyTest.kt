package com.jobeen.ime

import com.jobeen.ime.data.manager.RareCharPolicy
import com.jobeen.ime.engine.data.EngineMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 生僻字开关＋排序（施工单 10 H2-12，CyIME R2＋D17）：
 * 纯函数策略单测。事实锚点：佢／嘅／咗在万象 Lite 单字表里没有，
 * 冇只有 mao 读音——D17 的四个字须由开关补，不能只靠提位。
 */
class RareCharPolicyTest {

    private fun bulk(n: Int): List<EngineMessage.Candidate> =
        (0 until n).map { EngineMessage.Candidate(index = it, text = "c$it") }

    @Test
    fun isRareChar_extensionAreaSingleChar() {
        assertTrue(RareCharPolicy.isRareChar("𠼞")) // U+20E1E，扩展 B
        assertTrue(RareCharPolicy.isRareChar("𰀀")) // U+30000，扩展 G
        assertTrue(RareCharPolicy.isRareChar("㐀")) // U+3400，扩展 A 起
    }

    @Test
    fun isRareChar_commonCharAndOthersAreNotRare() {
        assertFalse(RareCharPolicy.isRareChar("佢")) // U+4F62，常用区
        assertFalse(RareCharPolicy.isRareChar("去"))
        assertFalse(RareCharPolicy.isRareChar("한")) // 韩文音节 U+D55C
        assertFalse(RareCharPolicy.isRareChar("高槻")) // 多字词不动
        assertFalse(RareCharPolicy.isRareChar("a"))
        assertFalse(RareCharPolicy.isRareChar("😀")) // U+1F600，不在扩展区
        assertFalse(RareCharPolicy.isRareChar(""))
    }

    @Test
    fun targetSupplement_spellingPath() {
        assertEquals(
            RareCharPolicy.SupplementTarget("qu", fromDigits = false),
            RareCharPolicy.targetSupplement(listOf("qu"), "qu"),
        )
        assertEquals(
            RareCharPolicy.SupplementTarget("ge", fromDigits = false),
            RareCharPolicy.targetSupplement(listOf("ge"), "ge"),
        )
        assertEquals(
            RareCharPolicy.SupplementTarget("zuo", fromDigits = false),
            RareCharPolicy.targetSupplement(listOf("zuo"), "zuo"),
        )
        assertEquals(
            RareCharPolicy.SupplementTarget("mou", fromDigits = false),
            RareCharPolicy.targetSupplement(listOf("mou"), "mou"),
        )
    }

    @Test
    fun targetSupplement_t9SelectedSpelling() {
        // 九宫格左栏选定音节后 spelling 已解析，rawInput 带分词符
        assertEquals(
            RareCharPolicy.SupplementTarget("qu", fromDigits = false),
            RareCharPolicy.targetSupplement(listOf("qu"), "78'"),
        )
    }

    @Test
    fun targetSupplement_t9DigitPath() {
        // 未解析数字码按 T9 数字码表补，读音进注释
        assertEquals(
            RareCharPolicy.SupplementTarget("qu", fromDigits = true),
            RareCharPolicy.targetSupplement(listOf(""), "78"),
        )
        assertEquals(
            RareCharPolicy.SupplementTarget("qu", fromDigits = true),
            RareCharPolicy.targetSupplement(listOf("78"), "78"),
        )
        assertEquals(
            RareCharPolicy.SupplementTarget("ge", fromDigits = true),
            RareCharPolicy.targetSupplement(listOf(""), "43"),
        )
        assertEquals(
            RareCharPolicy.SupplementTarget("zuo", fromDigits = true),
            RareCharPolicy.targetSupplement(listOf(""), "986"),
        )
        assertEquals(
            RareCharPolicy.SupplementTarget("mou", fromDigits = true),
            RareCharPolicy.targetSupplement(listOf(""), "668"),
        )
    }

    @Test
    fun targetSupplement_noSupplement() {
        assertNull(RareCharPolicy.targetSupplement(listOf("qu", "shi"), "qushi"))
        assertNull(RareCharPolicy.targetSupplement(emptyList(), ""))
        assertNull(RareCharPolicy.targetSupplement(listOf("ni"), "ni"))
        assertNull(RareCharPolicy.targetSupplement(listOf(""), "77"))
        assertNull(RareCharPolicy.targetSupplement(listOf(""), "7896"))
        assertNull(RareCharPolicy.targetSupplement(listOf(""), "abc"))
    }

    @Test
    fun applySupplement_insertsAtTargetIndex() {
        val outcome = RareCharPolicy.applySupplement(
            bulk(24), listOf("qu"), "qu", enabled = true, isDeleted = { false },
        )
        assertEquals(9, outcome.insertedAt)
        assertEquals(-1, outcome.movedFrom)
        assertEquals(25, outcome.list.size)
        val added = outcome.list[9]
        assertEquals("佢", added.text)
        assertEquals(9, added.index)
        assertEquals("", added.comment)
        assertEquals(EngineMessage.Candidate.TYPE_RARE_CHAR, added.type)
        // 插入位之后原项顺移一位
        assertEquals("c9", outcome.list[10].text)
        assertEquals("c23", outcome.list[24].text)
    }

    @Test
    fun applySupplement_digitPathCarriesReadingAsComment() {
        val outcome = RareCharPolicy.applySupplement(
            bulk(24), listOf(""), "78", enabled = true, isDeleted = { false },
        )
        assertEquals("佢", outcome.list[9].text)
        assertEquals("qu", outcome.list[9].comment)
    }

    @Test
    fun applySupplement_movesExistingCharToTarget() {
        val list = bulk(24).toMutableList()
        list[20] = EngineMessage.Candidate(index = 20, text = "佢")
        val outcome = RareCharPolicy.applySupplement(
            list, listOf("qu"), "qu", enabled = true, isDeleted = { false },
        )
        assertEquals(9, outcome.insertedAt)
        assertEquals(20, outcome.movedFrom)
        assertEquals(24, outcome.list.size)
        assertEquals("佢", outcome.list[9].text)
        // 已在表里的项移位后仍带原 Rime 下标，点选按原下标定位
        assertEquals(20, outcome.list[9].index)
        // 20 号被抽走、9 号插入：10–19 位是 c9–c18，20 位是 c19
        assertEquals("c18", outcome.list[19].text)
        assertEquals("c19", outcome.list[20].text)
    }

    @Test
    fun applySupplement_alreadyEarlyStaysPut() {
        val list = bulk(24).toMutableList()
        list[2] = EngineMessage.Candidate(index = 2, text = "佢")
        val outcome = RareCharPolicy.applySupplement(
            list, listOf("qu"), "qu", enabled = true, isDeleted = { false },
        )
        assertEquals(-1, outcome.insertedAt)
        assertSame(list, outcome.list)
    }

    @Test
    fun applySupplement_shortListAppendsAtEnd() {
        val outcome = RareCharPolicy.applySupplement(
            bulk(5), listOf("ge"), "ge", enabled = true, isDeleted = { false },
        )
        assertEquals(5, outcome.insertedAt)
        assertEquals(6, outcome.list.size)
        assertEquals("嘅", outcome.list[5].text)
        assertEquals(5, outcome.list[5].index)
    }

    @Test
    fun applySupplement_emptyList() {
        val outcome = RareCharPolicy.applySupplement(
            emptyList(), listOf("mou"), "mou", enabled = true, isDeleted = { false },
        )
        assertEquals(0, outcome.insertedAt)
        assertEquals(listOf("冇"), outcome.list.map { it.text })
    }

    @Test
    fun applySupplement_disabledOrDeletedReturnsSameList() {
        val list = bulk(24)
        val disabled = RareCharPolicy.applySupplement(
            list, listOf("qu"), "qu", enabled = false, isDeleted = { false },
        )
        assertEquals(-1, disabled.insertedAt)
        assertSame(list, disabled.list)
        val deleted = RareCharPolicy.applySupplement(
            list, listOf("qu"), "qu", enabled = true, isDeleted = { it == "佢" },
        )
        assertEquals(-1, deleted.insertedAt)
        assertSame(list, deleted.list)
        val noTarget = RareCharPolicy.applySupplement(
            list, listOf("ni"), "ni", enabled = true, isDeleted = { false },
        )
        assertEquals(-1, noTarget.insertedAt)
        assertSame(list, noTarget.list)
    }

    @Test
    fun remapHighlight_insertion() {
        val outcome = RareCharPolicy.SupplementOutcome(bulk(25), insertedAt = 9)
        assertEquals(0, RareCharPolicy.remapHighlight(0, outcome))
        assertEquals(8, RareCharPolicy.remapHighlight(8, outcome))
        assertEquals(10, RareCharPolicy.remapHighlight(9, outcome))
        assertEquals(16, RareCharPolicy.remapHighlight(15, outcome))
    }

    @Test
    fun remapHighlight_move() {
        val outcome = RareCharPolicy.SupplementOutcome(bulk(24), insertedAt = 9, movedFrom = 20)
        assertEquals(9, RareCharPolicy.remapHighlight(20, outcome))
        assertEquals(13, RareCharPolicy.remapHighlight(12, outcome))
        assertEquals(10, RareCharPolicy.remapHighlight(9, outcome))
        assertEquals(5, RareCharPolicy.remapHighlight(5, outcome))
        assertEquals(21, RareCharPolicy.remapHighlight(21, outcome))
    }

    @Test
    fun remapHighlight_unchanged() {
        val outcome = RareCharPolicy.SupplementOutcome(bulk(24))
        assertEquals(5, RareCharPolicy.remapHighlight(5, outcome))
        assertEquals(-1, RareCharPolicy.remapHighlight(-1, outcome))
    }

    @Test
    fun adjustedTotal_newInsertIncrementsTotal() {
        val outcome = RareCharPolicy.applySupplement(
            bulk(24), listOf("qu"), "qu", enabled = true, isDeleted = { false },
        )
        assertEquals(9, outcome.insertedAt)
        assertEquals(-1, outcome.movedFrom)
        assertEquals(25, outcome.list.size)
        assertEquals(25, RareCharPolicy.adjustedTotal(24, outcome))
    }

    @Test
    fun adjustedTotal_moveKeepsTotal() {
        val list = bulk(24).toMutableList()
        list[20] = EngineMessage.Candidate(index = 20, text = "佢")
        val outcome = RareCharPolicy.applySupplement(
            list, listOf("qu"), "qu", enabled = true, isDeleted = { false },
        )
        assertEquals(9, outcome.insertedAt)
        assertEquals(20, outcome.movedFrom)
        assertEquals(24, outcome.list.size)
        // 移位不加总数：否则 hasMore = list.size < total 恒真，面板多发一次分页
        assertEquals(24, RareCharPolicy.adjustedTotal(24, outcome))
    }

    @Test
    fun adjustedTotal_alreadyAtTargetKeepsTotal() {
        val list = bulk(24).toMutableList()
        list[2] = EngineMessage.Candidate(index = 2, text = "佢")
        val outcome = RareCharPolicy.applySupplement(
            list, listOf("qu"), "qu", enabled = true, isDeleted = { false },
        )
        assertEquals(-1, outcome.insertedAt)
        assertEquals(24, RareCharPolicy.adjustedTotal(24, outcome))
    }

    @Test
    fun adjustedTotal_unknownTotalStaysUnknown() {
        val inserted = RareCharPolicy.applySupplement(
            bulk(24), listOf("qu"), "qu", enabled = true, isDeleted = { false },
        )
        assertEquals(-1, RareCharPolicy.adjustedTotal(-1, inserted))
        val list = bulk(24).toMutableList()
        list[20] = EngineMessage.Candidate(index = 20, text = "佢")
        val moved = RareCharPolicy.applySupplement(
            list, listOf("qu"), "qu", enabled = true, isDeleted = { false },
        )
        assertEquals(-1, RareCharPolicy.adjustedTotal(-1, moved))
    }
}
