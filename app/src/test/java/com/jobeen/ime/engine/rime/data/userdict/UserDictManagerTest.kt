// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.engine.rime.data.userdict

import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import java.io.InputStream
import java.io.OutputStream

/**
 * 单文件大小上限的边界测试（2026-11-18，报告二 P2）：
 * - 64 MiB：手动导入落盘／单词典下载（UserDictManager.MAX_DICT_FILE_BYTES）；
 * - 256 MiB：目录同步单文件（WebDavSync.MAX_SYNC_FILE_BYTES）。
 * 上限本身由 [copyToWithLimit] 强制执行（两处调用方都走它），故边界
 * 在此测；整轮 512 MiB 配额（SyncQuota）见 WebDavSyncTest。
 * 流式生成器＋丢弃输出，不分配大数组。
 */
class UserDictManagerTest {

    /** 产生恰好 [total] 字节 'a' 的输入流（不持有数据）。 */
    private class GeneratorInputStream(private val total: Long) : InputStream() {
        private var emitted = 0L

        override fun read(): Int {
            if (emitted >= total) return -1
            emitted++
            return 'a'.code
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (emitted >= total) return -1
            val n = minOf(len.toLong(), total - emitted).toInt()
            b.fill('a'.code.toByte(), off, off + n)
            emitted += n
            return n
        }
    }

    /** 只计数、不保存的输出流。 */
    private class DiscardingOutputStream : OutputStream() {
        var bytes = 0L
            private set

        override fun write(b: Int) {
            bytes++
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            bytes += len
        }
    }

    @Test
    fun copyToWithLimitAllowsExactly64MiB() {
        val out = DiscardingOutputStream()
        val copied = GeneratorInputStream(UserDictManager.MAX_DICT_FILE_BYTES)
            .copyToWithLimit(out, UserDictManager.MAX_DICT_FILE_BYTES)
        assertEquals(UserDictManager.MAX_DICT_FILE_BYTES, copied)
        assertEquals(UserDictManager.MAX_DICT_FILE_BYTES, out.bytes)
    }

    @Test
    fun copyToWithLimitRejects64MiBPlusOne() {
        try {
            GeneratorInputStream(UserDictManager.MAX_DICT_FILE_BYTES + 1)
                .copyToWithLimit(DiscardingOutputStream(), UserDictManager.MAX_DICT_FILE_BYTES)
            fail("expected IllegalStateException")
        } catch (e: IllegalStateException) {
            assertEquals(
                "文件超过大小上限（64MB）",
                e.message,
            )
        }
    }

    @Test
    fun copyToWithLimitAllowsExactly256MiB() {
        val out = DiscardingOutputStream()
        val copied = GeneratorInputStream(WebDavSync.MAX_SYNC_FILE_BYTES)
            .copyToWithLimit(out, WebDavSync.MAX_SYNC_FILE_BYTES)
        assertEquals(WebDavSync.MAX_SYNC_FILE_BYTES, copied)
        assertEquals(WebDavSync.MAX_SYNC_FILE_BYTES, out.bytes)
    }

    @Test
    fun copyToWithLimitRejects256MiBPlusOne() {
        try {
            GeneratorInputStream(WebDavSync.MAX_SYNC_FILE_BYTES + 1)
                .copyToWithLimit(DiscardingOutputStream(), WebDavSync.MAX_SYNC_FILE_BYTES)
            fail("expected IllegalStateException")
        } catch (e: IllegalStateException) {
            assertEquals(
                "文件超过大小上限（256MB）",
                e.message,
            )
        }
    }
}
