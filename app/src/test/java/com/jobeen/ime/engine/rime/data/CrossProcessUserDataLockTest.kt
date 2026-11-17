// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.engine.rime.data

import java.io.File
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * B-X-1 跨进程用户目录锁：FileChannel.tryLock，锁文件与 LevelDB 同目录。
 *
 * 覆盖：取／放成对、进程内可重入（引用计数）、他路持锁时报 Busy
 * （同一 JVM 内他通道持锁抛 OverlappingFileLockException、他进程
 * 持锁时 tryLock 回 null，两种都落为 BUSY）、放后可重取。
 * 真机待验：两进程（应用进程 vs :ime 进程）交错取锁。
 */
class CrossProcessUserDataLockTest {

    private fun tempDir(prefix: String): File =
        Files.createTempDirectory(prefix).toFile().also { it.deleteOnExit() }

    @After
    fun releaseLeftovers() {
        // 单例跨测试共享：任一测试漏放都会污染后者，逐个放尽。
        while (CrossProcessUserDataLock.isHeld) CrossProcessUserDataLock.release()
    }

    @Test
    fun acquireRelease_allowsReacquire() {
        val dir = tempDir("xlock")
        assertEquals(
            CrossProcessUserDataLock.AcquireResult.ACQUIRED,
            CrossProcessUserDataLock.tryAcquire(dir),
        )
        assertTrue(CrossProcessUserDataLock.isHeld)
        assertTrue(File(dir, CrossProcessUserDataLock.LOCK_FILE_NAME).isFile)
        CrossProcessUserDataLock.release()
        assertFalse(CrossProcessUserDataLock.isHeld)
        assertEquals(
            CrossProcessUserDataLock.AcquireResult.ACQUIRED,
            CrossProcessUserDataLock.tryAcquire(dir),
        )
    }

    @Test
    fun reentrant_sameProcess_nestedAcquireNeedsMatchingReleases() {
        val dir = tempDir("xlock")
        assertEquals(
            CrossProcessUserDataLock.AcquireResult.ACQUIRED,
            CrossProcessUserDataLock.tryAcquire(dir),
        )
        // 同一进程内多层入口不自挡：第二次取锁同样成功，只加引用计数。
        assertEquals(
            CrossProcessUserDataLock.AcquireResult.ACQUIRED,
            CrossProcessUserDataLock.tryAcquire(dir),
        )
        CrossProcessUserDataLock.release()
        assertTrue(CrossProcessUserDataLock.isHeld)
        CrossProcessUserDataLock.release()
        assertFalse(CrossProcessUserDataLock.isHeld)
    }

    @Test
    fun releaseWithoutAcquire_isNoop() {
        CrossProcessUserDataLock.release()
        assertFalse(CrossProcessUserDataLock.isHeld)
    }

    @Test
    fun externalChannelHold_returnsBusyAndReacquirableAfterRelease() {
        val dir = tempDir("xlock")
        val external = FileChannel.open(
            File(dir, CrossProcessUserDataLock.LOCK_FILE_NAME).toPath(),
            StandardOpenOption.CREATE, StandardOpenOption.WRITE,
        )
        val externalLock = external.tryLock()
        try {
            // 他路（另一进程的同构行为：本 JVM 内他通道持锁）占锁时报 Busy。
            assertEquals(
                CrossProcessUserDataLock.AcquireResult.BUSY,
                CrossProcessUserDataLock.tryAcquire(dir),
            )
            assertFalse(CrossProcessUserDataLock.isHeld)
        } finally {
            externalLock?.release()
            external.close()
        }
        assertEquals(
            CrossProcessUserDataLock.AcquireResult.ACQUIRED,
            CrossProcessUserDataLock.tryAcquire(dir),
        )
    }

    /**
     * 指向锁文件的已打开描述符数（/proc/self/fd）：泄漏的通道只
     * 能经描述符计数观测——锁本身从未被它持有，isHeld 与重取都
     * 看不出泄漏；删除／重命名在 Linux 上也不拦已打开的描述符。
     */
    private fun openFdCountFor(file: File): Int {
        val canonical = file.canonicalPath
        val fdDir = File("/proc/self/fd")
        val entries = fdDir.listFiles() ?: return -1
        var count = 0
        for (entry in entries) {
            val target = runCatching {
                java.nio.file.Files.readSymbolicLink(entry.toPath()).toString()
            }.getOrNull() ?: continue
            if (target == canonical) count++
        }
        return count
    }

    @Test
    fun overlappingLockException_closesNewChannel() {
        // P1-5：他路持锁时本路 tryLock 抛 OverlappingFileLockException，
        // 异常分支必须关掉刚建好的通道，否则每次争用漏一个描述符。
        val dir = tempDir("xlock")
        val lockFile = File(dir, CrossProcessUserDataLock.LOCK_FILE_NAME)
        val external = FileChannel.open(
            lockFile.toPath(),
            StandardOpenOption.CREATE, StandardOpenOption.WRITE,
        )
        val externalLock = external.tryLock()
        assertTrue("external hold must acquire the file lock", externalLock != null)
        try {
            assertEquals(
                CrossProcessUserDataLock.AcquireResult.BUSY,
                CrossProcessUserDataLock.tryAcquire(dir),
            )
            assertFalse(CrossProcessUserDataLock.isHeld)
            // 修后只剩他路持有的 1 个描述符；漏关时为 2 个。
            assertEquals(1, openFdCountFor(lockFile))
        } finally {
            externalLock?.release()
            external.close()
        }
        assertEquals(
            CrossProcessUserDataLock.AcquireResult.ACQUIRED,
            CrossProcessUserDataLock.tryAcquire(dir),
        )
    }

    @Test
    fun ioExceptionOnLockFile_returnsFailedWithoutHoldingOrLeak() {
        // P1-5：锁文件位被目录占住，FileOutputStream 构造即抛
        // FileNotFoundException（IOException）：返回 FAILED、不持有、
        // 不污染引用计数（再取仍 FAILED），且不漏描述符。构造期抛错
        // 时通道尚未建好、catch 里的 close 为 no-op；真正关通道的
        // 分支由 overlappingLockException_closesNewChannel 覆盖。
        val dir = tempDir("xlock")
        val lockAsDir = File(dir, CrossProcessUserDataLock.LOCK_FILE_NAME)
        assertTrue(lockAsDir.mkdir())
        assertEquals(0, openFdCountFor(lockAsDir))
        assertEquals(
            CrossProcessUserDataLock.AcquireResult.FAILED,
            CrossProcessUserDataLock.tryAcquire(dir),
        )
        assertFalse(CrossProcessUserDataLock.isHeld)
        assertEquals(0, openFdCountFor(lockAsDir))
        assertEquals(
            CrossProcessUserDataLock.AcquireResult.FAILED,
            CrossProcessUserDataLock.tryAcquire(dir),
        )
        assertFalse(CrossProcessUserDataLock.isHeld)
    }
}
