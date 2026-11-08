package com.jobeen.ime.base.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EmailSuffixesTest {

    @Test
    fun typedDomain_matchesEmailTail() {
        assertEquals("", EmailSuffixes.typedDomain("abc@"))
        assertEquals("q", EmailSuffixes.typedDomain("abc@q"))
        assertEquals("qq.com", EmailSuffixes.typedDomain("abc@qq.com"))
        assertEquals("q", EmailSuffixes.typedDomain("abc＠q"))
        assertEquals("", EmailSuffixes.typedDomain("abc＠"))
        // @ 前必须有邮箱名
        assertNull(EmailSuffixes.typedDomain("@"))
        assertNull(EmailSuffixes.typedDomain("@q"))
        // 不在尾部不算
        assertNull(EmailSuffixes.typedDomain("abc@q "))
        assertNull(EmailSuffixes.typedDomain("abc"))
        assertNull(EmailSuffixes.typedDomain(null))
        // 只认最后一个 @
        assertEquals("d", EmailSuffixes.typedDomain("a b@c@d"))
    }

    @Test
    fun matching_filtersByPrefixCaseInsensitive() {
        assertEquals(EmailSuffixes.DOMAINS, EmailSuffixes.matching(""))
        assertEquals(listOf("qq.com"), EmailSuffixes.matching("q"))
        assertEquals(listOf("qq.com"), EmailSuffixes.matching("Q"))
        assertEquals(listOf("163.com", "126.com"), EmailSuffixes.matching("1"))
        assertEquals(listOf("163.com"), EmailSuffixes.matching("16"))
        // 已打完的不再给
        assertEquals(emptyList<String>(), EmailSuffixes.matching("qq.com"))
        assertEquals(emptyList<String>(), EmailSuffixes.matching("z"))
    }

    @Test
    fun suggestions_fullWidthAtRecognized() {
        assertEquals(EmailSuffixes.DOMAINS, EmailSuffixes.suggestions("user@"))
        assertEquals(listOf("gmail.com"), EmailSuffixes.suggestions("user＠g"))
        assertEquals(listOf("163.com", "126.com"), EmailSuffixes.suggestions("user@1"))
        assertEquals(emptyList<String>(), EmailSuffixes.suggestions("hello"))
    }

    @Test
    fun remainder_onlyUntypedPart() {
        assertEquals("q.com", EmailSuffixes.remainder("user@q", "qq.com"))
        assertEquals("m", EmailSuffixes.remainder("user@qq.co", "qq.com"))
        assertNull(EmailSuffixes.remainder("user@qq.com", "qq.com"))
        assertNull(EmailSuffixes.remainder("user@z", "qq.com"))
        assertNull(EmailSuffixes.remainder("hello", "qq.com"))
    }

    @Test
    fun completion_replacesTypedSpanWithFullDomain() {
        assertEquals("@q" to "@qq.com", EmailSuffixes.completion("user@q", "qq.com"))
        // 全角 ＠ 一起换成半角
        assertEquals("＠q" to "@qq.com", EmailSuffixes.completion("user＠q", "qq.com"))
        assertEquals("@" to "@qq.com", EmailSuffixes.completion("user@", "qq.com"))
        assertNull(EmailSuffixes.completion("user@z", "qq.com"))
        assertNull(EmailSuffixes.completion("hello", "qq.com"))
    }

    @Test
    fun continues_checksNextCharKeepsAMatch() {
        assertTrue(EmailSuffixes.continues("user@q", "q"))
        assertTrue(EmailSuffixes.continues("user@", "1"))
        assertFalse(EmailSuffixes.continues("user@q", "z"))
        assertFalse(EmailSuffixes.continues("user@", "."))
        assertFalse(EmailSuffixes.continues("hello", "q"))
    }

    @Test
    fun isSuggestionList_onlyAllDomains() {
        assertTrue(EmailSuffixes.isSuggestionList(listOf("qq.com", "163.com")))
        assertFalse(EmailSuffixes.isSuggestionList(listOf("qq.com", "hello")))
        assertFalse(EmailSuffixes.isSuggestionList(emptyList()))
    }

    @Test
    fun normalizeDomainChar_lettersDigitsAndDots() {
        assertEquals("q", EmailSuffixes.normalizeDomainChar("q"))
        assertEquals("2", EmailSuffixes.normalizeDomainChar("2"))
        assertEquals(".", EmailSuffixes.normalizeDomainChar("."))
        assertEquals(".", EmailSuffixes.normalizeDomainChar("。"))
        assertEquals(".", EmailSuffixes.normalizeDomainChar("．"))
        assertNull(EmailSuffixes.normalizeDomainChar("@"))
        assertNull(EmailSuffixes.normalizeDomainChar("ab"))
        assertNull(EmailSuffixes.normalizeDomainChar(""))
    }

    @Test
    fun isT9SchemaId_onlyWholeT9Segments() {
        assertTrue(EmailSuffixes.isT9SchemaId("wanxiang_t9"))
        assertTrue(EmailSuffixes.isT9SchemaId("wanxiang_t9i"))
        assertTrue(EmailSuffixes.isT9SchemaId("wanxiang_abbrev_t9"))
        assertFalse(EmailSuffixes.isT9SchemaId("wanxiang_lite"))
        assertFalse(EmailSuffixes.isT9SchemaId("wanxiang"))
        assertFalse(EmailSuffixes.isT9SchemaId("pinyin_t9like"))
        assertFalse(EmailSuffixes.isT9SchemaId("my_t9x"))
    }
}
