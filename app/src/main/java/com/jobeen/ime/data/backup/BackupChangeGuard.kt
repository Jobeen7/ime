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
 * 结算点（WO33 第 1 项，D-M1 残余窗口的缓解）：
 * 每次 [guard]／[markForSettle] 都把（文件，读前标记）记入本实例
 * 的跟踪表；采集全部结束、清单附上之前，调用方调一次
 * [reverifyAll]，把所有已读文件在同一个点上再验一遍——三类源
 * （prefs／Room／文件）至此第一次有了统一的复核点。残余窗口由
 * “整个采集时长”缩到“最后一次重验之后”：只有一次写盘的落盘
 * 时刻落在最终重验之后、且其效果已进更早读到的另一源时才逃得过
 * （典型曾是 prefs 的 apply()：内存已改、Room 已提交、磁盘 flush
 * 仍挂在 QueuedWork 队列里跨过结算点）。WO36（D-P2）已把全仓
 * 唯一的真配对写逐个收口（PhraseManager.ensureSeeded 与
 * ClipboardManager.setMaxEntries 的 prefs 侧改同步 commit，见
 * BackupManager 的结算注释），配对撕裂不再逃逸；单源写晚落盘
 * 只是合法的更早快照。彻底消除仍需跨组件全局写锁／停写总闸
 * （写盘方散在百余处，见 BackupManager 的注释），不在本批。
 *
 * 口径：
 * - 元数据读不到（文件不存在或属性不可读）时快照为 null：调用方
 *   按“文件不存在”处理——读后文件仍不存在即未变，读后出现即变了。
 * - 长度、mtime（纳秒精度）、inode（fileKey）任一不同即变了；
 *   fileKey 在部分文件系统上为 null，该维即不参与比较。
 * - java.nio.file 只在 API 26+ 存在（WO47）：mark() 先探活，
 *   探不到走 io 回落（长度＋毫秒 mtime，无 inode；同毫秒内
 *   等长替换检不出，仅 API 24/25 受影响，见 mark 的注释）。
 * - SharedPreferences 的 apply() 是异步落盘：写盘落在快照与重验
 *   之间即被检出，落在重验之后则进不了本次检测——单次 guard 的
 *   残余由结算点 [reverifyAll] 收窄（见类头注释）；配对写一侧
 *   （WO36 已收口，见类头注释）之外，单源晚落盘只是更早快照。
 */
internal class BackupChangeGuard {

    /** 读前快照（null = 文件不存在或元数据读不到）。 */
    data class FileMark(
        val length: Long,
        val lastModifiedNanos: Long,
        val fileKey: Any?,
    )

    /**
     * 跟踪表：每次读前标记都追加一条（同一文件多次读各留一条，
     * 结算按最早的标记验——文件在两次读之间变了，第二次 guard
     * 自身的重验是干净的（标记取在变之后），但结算仍按第一次的
     * 标记抓住它；生产采集里同一文件只读一次，故无误报路径）。
     * 一个 guard 实例对应一次 collectPayload；跟踪表即本次采集的
     * 全部已读文件集合，[reverifyAll] 在结算点上逐项重验。
     */
    private val tracked = ArrayList<Pair<File, FileMark?>>()

    /** 已跟踪的标记条数（测试用：断言结算覆盖了全部已读源）。 */
    val trackedFileCount: Int get() = tracked.size

    /** 只记标记、不读文件：用于没有逐文件 guard 读的源（Room 库文件）。 */
    fun markForSettle(file: File) {
        tracked += file to mark(file)
    }

    // java.nio.file 只在 API 26+ 存在；24/25 上探活失败，走 io 回落。
    // 主机单测跑在 JDK 上，nio 恒可用，走 nio 路径（单测钉的是这条路）。
    private val hasNioFile: Boolean = try {
        Class.forName("java.nio.file.Files")
        true
    } catch (e: Exception) {
        false
    }

    fun mark(file: File): FileMark? = try {
        if (hasNioFile) nioMark(file) else ioMark(file)
    } catch (e: Exception) {
        null
    }

    // 探活已保证：hasNioFile 为 false 时不进此方法；lint 认不出探活，故压掉 NewApi。
    //noinspection NewApi
    private fun nioMark(file: File): FileMark {
        val attrs = Files.readAttributes(file.toPath(), BasicFileAttributes::class.java)
        return FileMark(
            length = attrs.size(),
            lastModifiedNanos = attrs.lastModifiedTime().to(TimeUnit.NANOSECONDS),
            fileKey = attrs.fileKey(),
        )
    }

    // API 24/25 回落：java.io 只能给毫秒 mtime、无 inode；不存在的文件
    // length()/lastModified() 返回 0 不抛错，故先判存在（否则快照应为 null）。
    private fun ioMark(file: File): FileMark? {
        if (!file.exists()) return null
        return FileMark(
            length = file.length(),
            lastModifiedNanos = TimeUnit.MILLISECONDS.toNanos(file.lastModified()),
            fileKey = null,
        )
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
        tracked += file to before
        val result = read()
        val changed = if (before == null) {
            file.exists()
        } else {
            !unchanged(file, before)
        }
        if (changed) throw BackupManager.BackupDataChangedException(operation)
        return result
    }

    /**
     * 结算重验（WO33 第 1 项）：把跟踪表里的全部文件在同一个点上
     * 再验一遍。任一文件自其读前标记之后变了（出现／消失／长度／
     * mtime／inode 任一维不同），即抛 [BackupManager.BackupDataChangedException]，
     * 整个备份失败、不产残包。调用方在采集全部结束、清单附上之前
     * 调一次；无跟踪文件时直接通过。
     *
     * 边界：本次调用返回之后才落盘的写，不在本次检测内——那是
     * 结算点之后的写，按快照语义本就不属于本次备份；配对写撕裂
     * 的残余已由 WO36 在写盘侧收口（见类 KDoc），单测
     * [BackupSettlePassTest] 把这条边界钉死。
     */
    fun reverifyAll(operation: String) {
        for ((file, before) in tracked) {
            val changed = if (before == null) {
                file.exists()
            } else {
                !unchanged(file, before)
            }
            if (changed) throw BackupManager.BackupDataChangedException(operation)
        }
    }
}
