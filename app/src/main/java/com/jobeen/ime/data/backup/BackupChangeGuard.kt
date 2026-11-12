// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.data.backup

import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.TimeUnit

/**
 * 逐文件改动检测（D-M1，fcitx `BackupFileTree.write()` 形状）：
 * 每源读前取元数据（长度／mtime／inode），读后重验，变了就整个
 * 备份失败、不产残包。只用 java.io/java.nio，不碰 Android，
 * 可主机单测。
 *
 * 口径：
 * - 元数据读不到（文件不存在或属性不可读）时快照为 null：调用方
 *   按“文件不存在”处理——读后文件仍不存在即未变，读后出现即变了。
 * - 长度、mtime（纳秒精度）、inode（fileKey）任一不同即变了；
 *   fileKey 在部分文件系统上为 null，该维即不参与比较。
 * - SharedPreferences 的 apply() 是异步落盘：写盘落在快照与重验
 *   之间即被检出，落在重验之后则进不了本次检测——残余窗口由
 *   还原合并语义兜底，彻底消除需全局写锁（已知差距，不在本批）。
 */
internal class BackupChangeGuard {

    /** 读前快照（null = 文件不存在或元数据读不到）。 */
    data class FileMark(
        val length: Long,
        val lastModifiedNanos: Long,
        val fileKey: Any?,
    )

    fun mark(file: File): FileMark? = try {
        val attrs = Files.readAttributes(file.toPath(), BasicFileAttributes::class.java)
        FileMark(
            length = attrs.size(),
            lastModifiedNanos = attrs.lastModifiedTime().to(TimeUnit.NANOSECONDS),
            fileKey = attrs.fileKey(),
        )
    } catch (e: Exception) {
        null
    }

    /** 与快照比对：文件消失、元数据读不到、任一维不同都算变了。 */
    fun unchanged(file: File, mark: FileMark): Boolean {
        val now = mark(file) ?: return false
        if (now.length != mark.length) return false
        if (now.lastModifiedNanos != mark.lastModifiedNanos) return false
        if (mark.fileKey != null && now.fileKey != mark.fileKey) return false
        return true
    }

    /**
     * 读前快照 → 读 → 重验；变了抛 [BackupManager.BackupDataChangedException]。
     * 快照为 null（文件读前不存在）时：读后文件仍不存在即未变，读后
     * 出现即变了。读本身抛错时原样抛出，不做改动判定。
     *
     * @param operation 报错文案里的操作名（“备份”／“还原”）。
     */
    inline fun <T> guard(file: File, operation: String, read: () -> T): T {
        val before = mark(file)
        val result = read()
        val changed = if (before == null) {
            file.exists()
        } else {
            !unchanged(file, before)
        }
        if (changed) throw BackupManager.BackupDataChangedException(operation)
        return result
    }
}
