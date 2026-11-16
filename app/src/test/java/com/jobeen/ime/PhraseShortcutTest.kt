// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime

import com.jobeen.ime.R
import com.jobeen.ime.data.database.PhraseDao
import com.jobeen.ime.data.database.PhraseVariable
import com.jobeen.ime.data.database.PhraseVariables
import com.jobeen.ime.data.manager.PhraseManager
import java.nio.charset.StandardCharsets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 常用语输入码（WO52 6.1）纯规则单测：
 * shortcut 校验、精确匹配、变量编解码、DAO 查询形态。
 * 不碰 Room（主机无 Room 运行环境，见 CandidatePreferDaoShapeTest
 * 同款做法：DAO 语句形态读编译后 class 文件常量池断言）。
 */
class PhraseShortcutTest {

    // ---- shortcut 校验（04 §2.2 报错文案原文口径） ----

    @Test
    fun validateShortcut_empty_isOk() {
        assertNull(PhraseManager.validateShortcut(""))
    }

    @Test
    fun validateShortcut_twoToTenLowercase_isOk() {
        assertNull(PhraseManager.validateShortcut("rq"))
        assertNull(PhraseManager.validateShortcut("abcdefghij"))
    }

    @Test
    fun normalizeShortcutCode_stripsSpacesAndApostrophes() {
        assertEquals("nihao", PhraseManager.normalizeShortcutCode("ni hao"))
        assertEquals("nihao", PhraseManager.normalizeShortcutCode("ni'hao"))
        assertEquals("dz", PhraseManager.normalizeShortcutCode("d z"))
        assertEquals("rq", PhraseManager.normalizeShortcutCode("rq"))
        assertEquals("rq", PhraseManager.normalizeShortcutCode("RQ"))
    }

    @Test
    fun isEmptyShortcutCode_emptyOrSeparatorsOnly_isEmpty() {
        // 空码不查库（2026-11-16 debug 问题 3）：空串／仅分隔符
        // 归一化后为空，查库会命中全部 shortcut="" 的种子默认常用语。
        assertTrue(PhraseManager.isEmptyShortcutCode(""))
        assertTrue(PhraseManager.isEmptyShortcutCode(" "))
        assertTrue(PhraseManager.isEmptyShortcutCode("'"))
        assertTrue(PhraseManager.isEmptyShortcutCode(" ' "))
        assertFalse(PhraseManager.isEmptyShortcutCode("rq"))
        assertFalse(PhraseManager.isEmptyShortcutCode("ni hao"))
    }

    @Test
    fun validateShortcut_tooShortOrTooLong_errors() {
        assertEquals(
            R.string.phrase_shortcut_length_error,
            PhraseManager.validateShortcut("r"),
        )
        assertEquals(
            R.string.phrase_shortcut_length_error,
            PhraseManager.validateShortcut("abcdefghijk"),
        )
    }

    @Test
    fun validateShortcut_uppercaseDigitOrSpace_errors() {
        assertEquals(
            R.string.phrase_shortcut_characters_error,
            PhraseManager.validateShortcut("Rq"),
        )
        assertEquals(
            R.string.phrase_shortcut_characters_error,
            PhraseManager.validateShortcut("r1"),
        )
        assertEquals(
            R.string.phrase_shortcut_characters_error,
            PhraseManager.validateShortcut("r q"),
        )
    }


    // ---- 变量编解码（key/value/isSecure） ----

    @Test
    fun variables_emptyEncodesToEmptyString() {
        assertEquals("", PhraseVariables.encode(emptyList()))
        assertEquals(emptyList<PhraseVariable>(), PhraseVariables.decode(""))
        assertEquals(emptyList<PhraseVariable>(), PhraseVariables.decode("   "))
    }

    @Test
    fun variables_roundTrip() {
        val vars = listOf(
            PhraseVariable("name", "张三"),
            PhraseVariable("token", "s3cr3t", isSecure = true),
        )
        assertEquals(vars, PhraseVariables.decode(PhraseVariables.encode(vars)))
    }

    @Test
    fun variables_blankKeyDropped_malformedReturnsEmpty() {
        assertEquals(
            listOf(PhraseVariable("a", "1")),
            PhraseVariables.decode(PhraseVariables.encode(listOf(PhraseVariable(" ", "x"), PhraseVariable("a", "1")))),
        )
        assertEquals(emptyList<PhraseVariable>(), PhraseVariables.decode("{not json"))
        assertEquals(emptyList<PhraseVariable>(), PhraseVariables.decode("[1, \"x\"]"))
    }

    // ---- DAO 查询形态（class 文件常量池断言） ----

    private val daoClassText: String by lazy {
        val path = "/com/jobeen/ime/data/database/PhraseDao.class"
        val bytes = PhraseDao::class.java.getResourceAsStream(path)?.readBytes()
        assertNotNull("应能从测试 classpath 读到 DAO 的 class 文件", bytes)
        String(bytes!!, StandardCharsets.ISO_8859_1).replace(Regex("\\s+"), " ")
    }

    @Test
    fun daoSearch_coversFiveFields() {
        assertTrue("搜索应含 shortcut 字段", daoClassText.contains("shortcut LIKE"))
        assertTrue("搜索应含 category 字段", daoClassText.contains("category LIKE"))
        assertTrue("搜索应含 tags 字段", daoClassText.contains("tags LIKE"))
        assertTrue("搜索应保留 label/text 字段", daoClassText.contains("label LIKE") && daoClassText.contains("text LIKE"))
    }

    @Test
    fun daoGetByShortcut_isExactMatchQuery() {
        assertTrue(
            "输入码查询应为 shortcut 精确等值",
            daoClassText.contains("WHERE shortcut = :code"),
        )
    }

    @Test
    fun daoAutoSorted_ordersByLastUsed() {
        assertTrue(
            "自动排序应按 lastUsedAt 倒序",
            daoClassText.contains("ORDER BY pinned DESC, lastUsedAt DESC, createdAt DESC"),
        )
    }
}
