// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.engine.rime.data.userdict

import org.junit.Assert.assertEquals
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

    // ---- 转置：快照权重必须变成整数，否则合并校验整行跳过 ----

    private fun transposed(text: String) =
        WebDavSync.convertUserDbSnapshot(text.toByteArray(Charsets.UTF_8))
            .toString(Charsets.UTF_8)

    @Test
    fun snapshotWeightsConvertToCounts() {
        // 诊断 jime-diag-20261118-162554 的原样：c= 次数作整数权重。
        val text = "shi\t是\tc=723 d=6.14592 t=79121\n" +
            "chen\t陈\tc=14 d=0.00138093 t=79121\n" +
            "hao\t好\tc=1 d=1.67191e-47 t=79121\n"
        assertEquals(
            "是\tshi\t723\n陈\tchen\t14\n好\thao\t1\n",
            transposed(text),
        )
    }

    @Test
    fun integerWeightsAreKept() {
        assertEquals("是\tshi\t5\n", transposed("shi\t是\t5\n"))
    }

    @Test
    fun unparseableWeightIsDroppedNotCopied() {
        // 既无 c= 又不是整数：去掉权重列，不让整行被 BAD_WEIGHT 跳过。
        assertEquals("是\tshi\n", transposed("shi\t是\txyz\n"))
    }

    @Test
    fun phraseFirstFileIsNotTransposed() {
        assertEquals("是\tshi\t5\n", transposed("是\tshi\t5\n"))
    }

    // ---- 快照内容判定：只有实打实的码在前多数才算 ----

    @Test
    fun snapshotContentNeedsPositiveCodeFirstMajority() {
        val snapshot = "shi\t是\t1\nchen\t陈\t2\nhao\t好\t3\n是\tshi\t1\n"
        assertTrue(WebDavSync.isSnapshotContent(snapshot))
        val phraseFirst = "是\tshi\t1\n陈\tchen\t2\n好\thao\t3\nshi\t是\t1\n"
        assertFalse(WebDavSync.isSnapshotContent(phraseFirst))
    }

    @Test
    fun snapshotContentTieOrNoFeatureRowsIsFalse() {
        // 平票与无特征行不算：不能把无法判定的词在前文件误转置。
        val tie = "shi\t是\t1\n是\tshi\t1\n"
        assertFalse(WebDavSync.isSnapshotContent(tie))
        assertFalse(WebDavSync.isSnapshotContent("# comment\n\nabc\n123\t456\n"))
    }
}
