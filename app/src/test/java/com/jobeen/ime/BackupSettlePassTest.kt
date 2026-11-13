// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime

import com.jobeen.ime.data.backup.BackupChangeGuard
import com.jobeen.ime.data.backup.BackupManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * 结算重验（WO33 第 1 项，D-M1 残余窗口的缓解）：
 * guard()／markForSettle() 记下的全部文件，在采集末尾由
 * reverifyAll() 在同一个点上再验一遍；任一文件自读前标记之后
 * 变了，整个备份失败。本文件钉死结算语义与残余窗口的边界：
 * 结算点之前落盘的写必被检出，结算点之后才落盘的写不在本次
 * 检测内（快照语义上正确排除；配对写跨结算点的残余见
 * [writeAfterSettlePointEscapes] 的注释）。
 */
class BackupSettlePassTest {

    private fun tempDir(): File =
        Files.createTempDirectory("settle-pass-test").toFile().also { it.deleteOnExit() }

    @Test
    fun reverifyAll_noTrackedFiles_passes() {
        BackupChangeGuard().reverifyAll("备份")
    }

    @Test
    fun reverifyAll_unchangedTrackedFiles_passes() {
        val dir = tempDir()
        val guard = BackupChangeGuard()
        val a = File(dir, "a.txt").apply { writeText("a") }
        val b = File(dir, "b.txt").apply { writeText("b") }
        guard.guard(a, "备份") { "a" }
        guard.guard(b, "备份") { "b" }
        assertEquals(2, guard.trackedFileCount)
        guard.reverifyAll("备份")
    }

    @Test
    fun reverifyAll_fileChangedAfterGuardedRead_throws() {
        val dir = tempDir()
        val guard = BackupChangeGuard()
        val file = File(dir, "a.txt").apply { writeText("hello") }
        guard.guard(file, "备份") { "read-value" }
        // 模拟 apply() 落盘发生在逐文件重验之后、结算之前：旧实现
        // 此时已漏检，结算重验必须抓住。
        file.appendText(" world")
        val e = assertThrows(BackupManager.BackupDataChangedException::class.java) {
            guard.reverifyAll("备份")
        }
        assertEquals("备份中数据变了，请重试", e.message)
    }

    @Test
    fun reverifyAll_secondFileChanged_throws() {
        val dir = tempDir()
        val guard = BackupChangeGuard()
        val a = File(dir, "a.txt").apply { writeText("a") }
        val b = File(dir, "b.txt").apply { writeText("b") }
        guard.guard(a, "备份") { "a" }
        guard.guard(b, "备份") { "b" }
        b.appendText("!")
        assertThrows(BackupManager.BackupDataChangedException::class.java) {
            guard.reverifyAll("还原")
        }
    }

    @Test
    fun reverifyAll_fileAppearsAfterNullMark_throws() {
        val dir = tempDir()
        val guard = BackupChangeGuard()
        val wal = File(dir, "db-wal")
        guard.markForSettle(wal)
        // 模拟 Room 写新建 -wal：标记时不存在，结算时出现即变了。
        wal.writeText("wal-frame")
        assertThrows(BackupManager.BackupDataChangedException::class.java) {
            guard.reverifyAll("备份")
        }
    }

    @Test
    fun reverifyAll_fileDeletedAfterMark_throws() {
        val dir = tempDir()
        val guard = BackupChangeGuard()
        val file = File(dir, "a.txt").apply { writeText("hello") }
        guard.markForSettle(file)
        file.delete()
        assertThrows(BackupManager.BackupDataChangedException::class.java) {
            guard.reverifyAll("备份")
        }
    }

    @Test
    fun reverifyAll_walAppendedAfterMark_throws() {
        val dir = tempDir()
        val guard = BackupChangeGuard()
        // 模拟 WAL 下 Room 提交：主库文件不动，-wal 追加一帧。
        val db = File(dir, "ime_database").apply { writeText("db") }
        val wal = File(dir, "ime_database-wal").apply { writeText("frame1") }
        guard.markForSettle(db)
        guard.markForSettle(wal)
        wal.appendText("frame2")
        assertThrows(BackupManager.BackupDataChangedException::class.java) {
            guard.reverifyAll("备份")
        }
    }

    @Test
    fun reverifyAll_dbFileChangedAfterMark_throws() {
        val dir = tempDir()
        val guard = BackupChangeGuard()
        // 模拟非 WAL 下 Room 提交／checkpoint：主库文件本身变了。
        val db = File(dir, "ime_database").apply { writeText("db-v1") }
        guard.markForSettle(db)
        db.writeText("db-v2")
        assertThrows(BackupManager.BackupDataChangedException::class.java) {
            guard.reverifyAll("备份")
        }
    }

    @Test
    fun markForSettle_recordsCurrentMark() {
        val dir = tempDir()
        val guard = BackupChangeGuard()
        val file = File(dir, "a.txt").apply { writeText("hello") }
        guard.markForSettle(file)
        assertEquals(1, guard.trackedFileCount)
        assertTrue(guard.unchanged(file, guard.mark(file)!!))
        guard.reverifyAll("备份")
    }

    @Test
    fun guardTwice_sameFile_settleCatchesChangeBetweenReads() {
        val dir = tempDir()
        val guard = BackupChangeGuard()
        val file = File(dir, "a.txt").apply { writeText("v1") }
        guard.guard(file, "备份") { "v1" }
        file.writeText("v2")
        // 第二次 guard 自身的重验是干净的（标记取在变之后），读到 v2；
        // 但结算按第一次的标记验——文件在两次读之间变过，结算必须抛。
        // 生产采集里同一文件只读一次，故这条无误报路径。
        guard.guard(file, "备份") { "v2" }
        assertEquals(2, guard.trackedFileCount)
        assertThrows(BackupManager.BackupDataChangedException::class.java) {
            guard.reverifyAll("备份")
        }
    }

    @Test
    fun writeAfterSettlePointEscapes() {
        val dir = tempDir()
        val guard = BackupChangeGuard()
        val file = File(dir, "a.txt").apply { writeText("hello") }
        val mark = guard.mark(file)!!
        guard.guard(file, "备份") { "read-value" }
        // 结算点本身通过：结算之前无任何落盘。
        guard.reverifyAll("备份")
        // ——残余边界（WO33 已知差距，非 bug）：结算返回之后才落盘
        // 的写，本次结算检测不到。快照语义上它属于“结算点之后的
        // 写”，正确排除；只有配对写（prefs 内存已改＋Room 已提交、
        // prefs flush 仍挂在队列里跨过结算点）才构成跨源不一致，
        // 那需要写盘线程被拖住横跨剩余采集，正常 flush 毫秒级即落盘。
        file.appendText(" after-settle")
        // 该写确实发生了（事后用旧标记一验即知），只是已结算的
        // 本次检测不再回头看——边界钉死于此。
        assertFalse(guard.unchanged(file, mark))
    }
}
