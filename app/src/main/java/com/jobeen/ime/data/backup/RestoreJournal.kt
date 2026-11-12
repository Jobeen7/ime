// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.data.backup

import com.jobeen.ime.skin.store.AtomicFiles
import timber.log.Timber
import java.io.File

/**
 * 还原 journal（D-S2，fcitx `UserDataRestoreJournal.kt` 形状）：
 * 还原前把“还原进行中＋快照引用”落盘，启动时对账。
 *
 * 文件形态（与 SchemePackageImport 的安装日志同规）：行式
 * `key=value` 文本，经 [AtomicFiles] 原子写（tmp＋fsync＋
 * rename），进程在任一步被杀，journal 要么是旧阶段的完整内容、
 * 要么是新阶段的完整内容，绝不出现截断的半截文件。
 *
 * 三阶段：STAGING（快照已落盘、尚未写任何数据）→ REPLACING
 * （applyPayload 进行中，各段完成记在 completedSections 里）
 * → COMMITTED（applyPayload 已返回）。成功后 journal＋快照
 * 一并删除；COMMITTED 是给“写完数据、未及删 journal 就被杀”
 * 的窗口留的标记，启动见 COMMITTED 直接清理、不提示。
 *
 * 快照文件：还原前状态的完整采集 JSON（`restore-snapshot-<token>.json`，
 * 与 journal 同目录），供中断后回滚回放。快照与 journal 之间
 * 以 token 所有权绑定（fcitx 的 `.restore-<token>.new`／`.old`
 * 同理）：journal 里的 token 即快照文件名里的 token。
 * 快照是明文 JSON，存在应用私有 filesDir 内，与现行剪贴板库、
 * prefs XML 同一保护级别（沙箱内私有、他应用不可读），不进
 * 系统云备份（见 backup_rules／data_extraction_rules 排除项）；
 * 还原完成／回滚成功／用户忽略后即删除，不长期留存。
 *
 * 除 read／decide 外的函数都只做文件操作，可主机单测（临时目录）。
 */
internal object RestoreJournal {

    const val JOURNAL_DIR_NAME = "restore_journal"
    const val JOURNAL_FILE_NAME = "restore.journal"
    const val SNAPSHOT_FILE_PREFIX = "restore-snapshot-"
    const val SNAPSHOT_FILE_SUFFIX = ".json"

    /** applyPayload 的写盘段（journal 里按此记录完成进度）。 */
    const val SECTION_CLIPBOARD = "clipboard"
    const val SECTION_APPDB = "appdb"
    const val SECTION_PREFS = "prefs"
    const val SECTION_INPUT_STATS = "input_stats"
    const val SECTION_SKIN_LIBRARY = "skin_library"
    const val SECTION_SKIN_SCRIPTS = "skin_scripts"

    enum class Phase {
        STAGING,
        REPLACING,
        COMMITTED,
    }

    /** 启动对账动作（纯决策，见 [decide]）。 */
    enum class RecoveryAction {
        /** 无 journal：无事可做（调用方仍清孤儿快照）。 */
        NONE,
        /** 已完成但未及清理：删 journal＋快照，不提示。 */
        CLEANUP_COMPLETED,
        /** 尚未写任何数据：删 journal＋快照，不提示。 */
        CLEANUP_STAGING,
        /** 中途被杀：留 journal，备份页提示用户重导／回滚。 */
        PROMPT_USER,
    }

    data class Entry(
        val token: String,
        val phase: Phase,
        val startedAt: Long,
        /** 备份文件字节的 SHA-256（hex），用于识别是哪一份备份。 */
        val backupFingerprint: String,
        /** 快照文件名（同目录内），空 = journal 里没记快照。 */
        val snapshotFile: String,
        /** 已完成的写盘段（SECTION_*）。 */
        val completedSections: Set<String>,
    )

    fun dir(filesDir: File): File = File(filesDir, JOURNAL_DIR_NAME)

    fun journalFile(dir: File): File = File(dir, JOURNAL_FILE_NAME)

    fun snapshotFile(dir: File, token: String): File =
        File(dir, "$SNAPSHOT_FILE_PREFIX$token$SNAPSHOT_FILE_SUFFIX")

    /** 原子写 journal（失败抛 IOException，调用方按强制／尽力而为区分处置）。 */
    fun write(dir: File, entry: Entry) {
        val content = buildString {
            appendLine("token=${entry.token}")
            appendLine("phase=${entry.phase.name}")
            appendLine("startedAt=${entry.startedAt}")
            appendLine("backupFingerprint=${entry.backupFingerprint}")
            appendLine("snapshotFile=${entry.snapshotFile}")
            appendLine("completedSections=${entry.completedSections.sorted().joinToString(",")}")
        }
        AtomicFiles.write(journalFile(dir), content)
    }

    /**
     * 读 journal：不存在或损坏返回 null（损坏按“无 journal”处理，
     * 不阻塞启动；损坏的 journal 文件本身随下次成功还原被覆盖）。
     * token 与 phase 是必需项，其余缺失即取缺省（startedAt=0、
     * 指纹空、快照空、完成段空）——缺省的 journal 仍可用于提示，
     * 只是回滚入口不可用。
     */
    fun read(dir: File): Entry? {
        val values = mutableMapOf<String, String>()
        runCatching {
            journalFile(dir).readLines().forEach { line ->
                val idx = line.indexOf('=')
                if (idx > 0) values[line.substring(0, idx)] = line.substring(idx + 1)
            }
        }.getOrNull() ?: return null
        val token = values["token"].orEmpty()
        if (token.isEmpty()) return null
        val phase = runCatching {
            Phase.valueOf(values["phase"].orEmpty())
        }.getOrNull() ?: return null
        return Entry(
            token = token,
            phase = phase,
            startedAt = values["startedAt"]?.toLongOrNull() ?: 0L,
            backupFingerprint = values["backupFingerprint"].orEmpty(),
            snapshotFile = values["snapshotFile"].orEmpty(),
            completedSections = values["completedSections"].orEmpty()
                .split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet(),
        )
    }

    /** 删 journal＋其引用的快照（完成／回滚成功／用户忽略时）。 */
    fun clear(dir: File, entry: Entry) {
        runCatching {
            if (!journalFile(dir).delete() && journalFile(dir).exists()) {
                Timber.w("restore journal delete failed")
            }
        }
        if (entry.snapshotFile.isNotEmpty()) {
            runCatching {
                val snapshot = File(dir, entry.snapshotFile)
                if (!snapshot.delete() && snapshot.exists()) {
                    Timber.w("restore snapshot delete failed")
                }
            }
        }
    }

    /** 删无 journal 认领的孤儿快照（keepToken 对应的保留，其余删）。 */
    fun sweepOrphanSnapshots(dir: File, keepToken: String?) {
        if (!dir.isDirectory) return
        dir.listFiles { f ->
            f.isFile && f.name.startsWith(SNAPSHOT_FILE_PREFIX) &&
                f.name.endsWith(SNAPSHOT_FILE_SUFFIX)
        }.orEmpty().forEach { file ->
            val token = file.name
                .removePrefix(SNAPSHOT_FILE_PREFIX)
                .removeSuffix(SNAPSHOT_FILE_SUFFIX)
            if (token != keepToken) {
                runCatching {
                    if (!file.delete() && file.exists()) {
                        Timber.w("orphan restore snapshot delete failed: ${file.name}")
                    }
                }
            }
        }
    }

    fun snapshotExists(dir: File, entry: Entry): Boolean =
        entry.snapshotFile.isNotEmpty() && File(dir, entry.snapshotFile).isFile

    /**
     * 启动对账决策（纯函数）：
     * - 无 journal → NONE；
     * - COMMITTED → CLEANUP_COMPLETED（已写完，只差删 journal）；
     * - STAGING → CLEANUP_STAGING（尚未写任何数据）；
     * - REPLACING → PROMPT_USER（可能已写部分数据，快照在与不在
     *   都提示；快照不在时回滚入口不可用，只给重导）。
     */
    fun decide(entry: Entry?, snapshotExists: Boolean): RecoveryAction = when {
        entry == null -> RecoveryAction.NONE
        entry.phase == Phase.COMMITTED -> RecoveryAction.CLEANUP_COMPLETED
        entry.phase == Phase.STAGING -> RecoveryAction.CLEANUP_STAGING
        else -> RecoveryAction.PROMPT_USER
    }
}
