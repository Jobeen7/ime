// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.engine.rime.data

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * 跨进程用户目录锁（B-X-1）：`FileChannel.tryLock`，锁文件与
 * LevelDB 同目录（[DataManager.userDataDir] 下的 `.maintenance.lock`）。
 * 复用既有部署互斥语义，不另写锁服务。
 *
 * 覆盖：部署／同步（`:ime` 侧，经维护执行体）与方案导入／更新
 * （应用进程侧，UpdateImportGate 持有处）进事务前先取锁、出事务
 * 释放；应用进程直调部署经 `RimeEngine.deployAndAwait` 同样取锁
 * （Wi-Fi 直调部署即走此路，不改走 Service）。
 *
 * 忙与失败分开（与 MaintenanceResult 同一口径）：锁被他进程占用
 * → [AcquireResult.BUSY]；取锁本身抛错 → [AcquireResult.FAILED]。
 * 锁在手时他方合作进程进不来，此时执行体再失败即真失败，不再是争用。
 *
 * 进程内可重入（引用计数）：同一进程内多层入口（执行体持锁后调
 * `deployAndAwait` 再取一次）不自挡；只有引用计数归零才真正放锁。
 * 嵌套取锁的目录以首次持有者为准——生产入口取的都是当前 userDataDir，
 * 同一事务内指针切换不改锁文件。
 */
object CrossProcessUserDataLock {

    /** 锁文件名：与 LevelDB 同目录，部署指纹排除（见 DeployFingerprint）。 */
    const val LOCK_FILE_NAME = ".maintenance.lock"

    enum class AcquireResult { ACQUIRED, BUSY, FAILED }

    private val mutex = ReentrantLock()
    private var channel: FileChannel? = null
    private var fileLock: FileLock? = null
    private var refCount = 0

    fun tryAcquire(dir: File): AcquireResult = mutex.withLock {
        if (refCount > 0) {
            refCount++
            return AcquireResult.ACQUIRED
        }
        var newChannel: FileChannel? = null
        try {
            dir.mkdirs()
            // FileOutputStream.channel（API 1）：FileChannel.open／
            // StandardOpenOption 需 API 26，minSdk 24 上 lint NewApi
            // 报错；append 模式只建空文件、不写内容，锁语义与 open 同。
            val opened = FileOutputStream(File(dir, LOCK_FILE_NAME), true).channel
            newChannel = opened
            val newLock = opened.tryLock()
            if (newLock == null) {
                runCatching { opened.close() }
                return AcquireResult.BUSY
            }
            channel = opened
            fileLock = newLock
            refCount = 1
            AcquireResult.ACQUIRED
        } catch (e: OverlappingFileLockException) {
            // 同一 JVM 内已有他路持锁（本对象之外无他路取锁，防卫分支）。
            // 取锁前已建好的通道必须关掉，否则文件描述符泄漏。
            runCatching { newChannel?.close() }
            AcquireResult.BUSY
        } catch (e: IOException) {
            // 取锁本身抛错：已建好的通道同样必须关掉（构造期抛错时
            // newChannel 仍为 null，close 即 no-op）。
            runCatching { newChannel?.close() }
            AcquireResult.FAILED
        }
    }

    /** 释放一次持有；未持有或引用计数未归零时不碰文件锁。 */
    fun release() {
        mutex.withLock {
            if (refCount == 0) return
            if (--refCount > 0) return
            runCatching { fileLock?.release() }
            runCatching { channel?.close() }
            fileLock = null
            channel = null
        }
    }

    /** 当前进程是否持有锁（同步读，供测试与诊断）。 */
    val isHeld: Boolean get() = mutex.withLock { refCount > 0 }
}
