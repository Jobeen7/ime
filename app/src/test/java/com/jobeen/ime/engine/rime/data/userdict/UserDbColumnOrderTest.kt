// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.engine.rime.data.userdict

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * decideUserDbColumnOrder 全量采样（E-L3）：.userdb.txt 的列序判定
 * 必须全文件特征行投票、多数为准。旧实现 200 票即停采，文件前部
 * 特征行不足或前后列序不一致时，整文件按错误列序转置导入。
 * 平票或无特征行时默认 Rime 原生「码在前」，与之前行为一致。
 */
class UserDbColumnOrderTest {

    private fun codeFirstRow(i: Int) = "shi\t是\t$i\n"
    private fun phraseFirstRow(i: Int) = "是\tshi\t$i\n"

    @Test
    fun majorityCodeFirstWins() {
        val text = buildString {
            repeat(3) { append(codeFirstRow(it)) }
            repeat(2) { append(phraseFirstRow(it)) }
        }
        assertTrue(WebDavSync.decideUserDbColumnOrder(text))
    }

    @Test
    fun majorityPhraseFirstWins() {
        val text = buildString {
            repeat(2) { append(codeFirstRow(it)) }
            repeat(3) { append(phraseFirstRow(it)) }
        }
        assertFalse(WebDavSync.decideUserDbColumnOrder(text))
    }

    @Test
    fun lateMajorityBeatsEarlyMinority() {
        // 回归：旧实现 200 票即停采，前 200 行词在前会直接判错。
        val text = buildString {
            repeat(200) { append(phraseFirstRow(it)) }
            repeat(300) { append(codeFirstRow(it)) }
        }
        assertTrue(WebDavSync.decideUserDbColumnOrder(text))
    }

    @Test
    fun tieDefaultsToCodeFirst() {
        val text = buildString {
            repeat(5) { append(codeFirstRow(it)) }
            repeat(5) { append(phraseFirstRow(it)) }
        }
        assertTrue(WebDavSync.decideUserDbColumnOrder(text))
    }

    @Test
    fun noFeatureRowsDefaultsToCodeFirst() {
        val text = "# comment\n\nabc\n123\t456\n"
        assertTrue(WebDavSync.decideUserDbColumnOrder(text))
    }

    @Test
    fun tonedCodesCountAsCodeFirst() {
        // 电脑端编码可能带声调（"chén yào"），投票前先去声调
        val text = "chén yào\t陈\t5\nchén\t陈\t3\n"
        assertTrue(WebDavSync.decideUserDbColumnOrder(text))
    }

    @Test
    fun headerAndBlankLinesDoNotVote() {
        val text = "# userdb\n\n" + codeFirstRow(0) + phraseFirstRow(0) + phraseFirstRow(1)
        assertFalse(WebDavSync.decideUserDbColumnOrder(text))
    }
}
