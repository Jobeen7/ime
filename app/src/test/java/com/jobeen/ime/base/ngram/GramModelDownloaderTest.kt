package com.jobeen.ime.base.ngram

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** B-L2：language 拼进目标文件名之前必须校验，防路径穿越。 */
class GramModelDownloaderTest {

    @Test
    fun validLanguages_accepted() {
        assertTrue(isValidGramLanguage("wanxiang-lts-zh-hans"))
        assertTrue(isValidGramLanguage("wanxiang-lts-zh-hant"))
        assertTrue(isValidGramLanguage("en"))
        assertTrue(isValidGramLanguage("zh-Hans_CN.1"))
    }

    @Test
    fun traversalLanguages_rejected() {
        assertFalse(isValidGramLanguage(""))
        assertFalse(isValidGramLanguage("."))
        assertFalse(isValidGramLanguage(".."))
        assertFalse(isValidGramLanguage("../etc"))
        assertFalse(isValidGramLanguage("a/b"))
        assertFalse(isValidGramLanguage("a/../b"))
        assertFalse(isValidGramLanguage("..\\windows"))
        assertFalse(isValidGramLanguage("a b"))
        assertFalse(isValidGramLanguage("a/b.gram"))
        assertFalse(isValidGramLanguage("/abs/path"))
        assertFalse(isValidGramLanguage("a".repeat(65)))
    }
}
