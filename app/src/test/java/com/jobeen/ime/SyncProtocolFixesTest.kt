package com.jobeen.ime

import com.jobeen.ime.base.net.sanitizeServerMessage
import com.jobeen.ime.data.manager.DeletedWordEntry
import com.jobeen.ime.data.manager.decodeDeletedWordEntry
import com.jobeen.ime.data.manager.encodeDeletedWordEntry
import com.jobeen.ime.data.manager.mergeDeletedWordEntries
import com.jobeen.ime.engine.rime.data.userdict.formatHttpDate
import com.jobeen.ime.engine.rime.data.userdict.isWeakETag
import com.jobeen.ime.engine.rime.data.userdict.parseHttpDateMs
import com.jobeen.ime.engine.rime.data.userdict.putPrecondition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 第二轮安全/协议修复的纯函数测试：删词墓碑合并、弱 ETag 前置条件、
 * 服务端文案收口。
 */
class SyncProtocolFixesTest {

    // ---- 删词记录编解码 ----

    @Test
    fun deletedWordEntry_roundTrip() {
        val line = encodeDeletedWordEntry("词", DeletedWordEntry(true, 123456L))
        assertEquals("词\t1\t123456", line)
        val (word, entry) = decodeDeletedWordEntry(line!!)!!
        assertEquals("词", word)
        assertEquals(DeletedWordEntry(true, 123456L), entry)
        val restored = encodeDeletedWordEntry("词", DeletedWordEntry(false, 9L))
        assertEquals(DeletedWordEntry(false, 9L), decodeDeletedWordEntry(restored!!)!!.second)
    }

    @Test
    fun deletedWordEntry_absurdTimestampIsClamped() {
        // 对端写入的离谱时间戳（Long.MAX_VALUE 级）必须被钳到
        // 「现在 + 1 天」以内，否则它会在合并中永久压制该词、
        // 本机的新删除/恢复永远输；正常时间戳原样保留（roundTrip 用例）
        val (_, entry) = decodeDeletedWordEntry("词\t1\t${Long.MAX_VALUE}")!!
        val cap = System.currentTimeMillis() + 86_400_000L
        assertTrue(entry.updatedAt in 1..cap)
        val (_, neg) = decodeDeletedWordEntry("词\t1\t-5")!!
        assertEquals(0L, neg.updatedAt)
    }

    @Test
    fun deletedWordEntry_legacyLineIsDeletedAtZero() {
        val (word, entry) = decodeDeletedWordEntry("旧词")!!
        assertEquals("旧词", word)
        assertEquals(DeletedWordEntry(true, 0L), entry)
        assertNull(decodeDeletedWordEntry("  "))
        assertNull(encodeDeletedWordEntry("带\t制表符", DeletedWordEntry(true, 1L)))
    }

    // ---- 删词合并（墓碑协议）----

    @Test
    fun mergeDeletedWords_newerWinsBothWays() {
        val local = mapOf("甲" to DeletedWordEntry(true, 100L))
        val remote = mapOf("甲" to DeletedWordEntry(false, 200L))
        // 恢复时间更新 → 恢复赢；且合并与参数顺序无关（两台设备算出的结果必须一致）
        assertEquals(
            DeletedWordEntry(false, 200L),
            mergeDeletedWordEntries(local, remote)["甲"],
        )
        assertEquals(
            DeletedWordEntry(false, 200L),
            mergeDeletedWordEntries(remote, local)["甲"],
        )
    }

    @Test
    fun mergeDeletedWords_tiePrefersDeleted_andUnion() {
        val local = mapOf(
            "甲" to DeletedWordEntry(false, 50L),
            "乙" to DeletedWordEntry(true, 0L),
        )
        val remote = mapOf(
            "甲" to DeletedWordEntry(true, 50L),
            "丙" to DeletedWordEntry(true, 10L),
        )
        val merged = mergeDeletedWordEntries(local, remote)
        assertEquals(DeletedWordEntry(true, 50L), merged["甲"])
        assertEquals(DeletedWordEntry(true, 0L), merged["乙"])
        assertEquals(DeletedWordEntry(true, 10L), merged["丙"])
    }

    @Test
    fun mergeDeletedWords_legacyRemoteLosesToLocalRestore() {
        // 远端还是旧格式（已删@0），本地已恢复（@100）→ 恢复必须保留
        val local = mapOf("词" to DeletedWordEntry(false, 100L))
        val remote = mapOf("词" to DeletedWordEntry(true, 0L))
        assertEquals(
            DeletedWordEntry(false, 100L),
            mergeDeletedWordEntries(local, remote)["词"],
        )
    }

    // ---- 弱 ETag 前置条件 ----

    @Test
    fun weakETag_detection() {
        assertTrue(isWeakETag("W/\"abc\""))
        assertTrue(isWeakETag("  W/\"abc\""))
        assertFalse(isWeakETag("\"abc\""))
        assertFalse(isWeakETag(null))
    }

    @Test
    fun precondition_strongUsesIfMatch() {
        val pre = putPrecondition("abc123", etagWeak = false, lastModifiedMs = 1000L)
        assertEquals("\"abc123\"", pre.ifMatch)
        assertNull(pre.ifUnmodifiedSince)
    }

    @Test
    fun precondition_weakFallsBackToLastModified() {
        val lm = 1_700_000_000_000L
        val pre = putPrecondition("abc123", etagWeak = true, lastModifiedMs = lm)
        assertNull(pre.ifMatch)
        assertEquals(lm - lm % 1000, parseHttpDateMs(pre.ifUnmodifiedSince))
        // 弱 ETag 且无修改时间：不带前置条件（上传前已有变更检查兜底）
        val bare = putPrecondition("abc123", etagWeak = true, lastModifiedMs = 0L)
        assertNull(bare.ifMatch)
        assertNull(bare.ifUnmodifiedSince)
        // 无 ETag：同样不带
        assertEquals(
            putPrecondition(null, false, lm),
            putPrecondition("", true, lm),
        )
    }

    @Test
    fun httpDate_roundTrip() {
        val ms = 1_728_000_000_000L
        assertEquals(ms - ms % 1000, parseHttpDateMs(formatHttpDate(ms)))
        assertEquals(0L, parseHttpDateMs("not a date"))
        assertEquals(0L, parseHttpDateMs(null))
    }

    // ---- 服务端文案收口 ----

    @Test
    fun sanitizeServerMessage_collapsesAndTruncates() {
        assertEquals("系统 维护中 请稍后再试", sanitizeServerMessage("系统\n维护中\r\n  请稍后再试"))
        assertEquals(60, sanitizeServerMessage("长".repeat(100)).length)
        assertEquals("", sanitizeServerMessage("   "))
        assertEquals("参数错误", sanitizeServerMessage("参数错误"))
    }
}
