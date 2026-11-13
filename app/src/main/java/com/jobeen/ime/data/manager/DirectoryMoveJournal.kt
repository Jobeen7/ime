// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.data.manager

import com.jobeen.ime.skin.store.AtomicFiles
import java.io.File
import timber.log.Timber

/**
 * 多目录改名／切换提交凭证（WO38 E1-5）：
 *
 * 背景：多目录的改名（目录 renameTo）与切换（指针翻转＋部署）都是
 * 多步落盘，进程在中途被杀会留半截状态——改名只剩一半无从分辨，
 * 切换指针已翻而部署未完成、镜像可能还是旧目录的。凭证文件是
 * 落盘前的提交记录：改名／切换先写凭证、做完清凭证；崩溃后下次
 * 启动（[SchemeDirs.ensureMigrated] 首访）或下次切换时按凭证
 * 两向恢复到一致态——要么改名／切换完成、要么回到改名／切换前。
 *
 * 凭证文件：根下隐藏文件 `.dir-move-<operationId>.journal`（与
 * [SchemePackageImport] 的 `.install-<op>.journal` 同口径：隐藏
 * 目录排除，[SchemeDirs.listDirs] 与迁移规划天然不计入；写盘走
 * [AtomicFiles] tmp＋fsync＋rename，凭证本身要么完整、要么不存在，
 * 绝不出现截断的半截凭证）。
 *
 * 阶段：INTENT（落盘前已写）→ MOVED（目录已改名／指针已翻转）→
 * 提交后删凭证。恢复判定不依赖阶段、只看目录与指针现状（阶段写
 * 本身也可能被崩溃打断；按现状判定则 INTENT 与 MOVED 的崩溃点
 * 收敛到同一结果），阶段只作诊断。
 *
 * 恢复规则（只动指针与凭证，绝不删用户目录——重放幂等）：
 * - RENAME from=A to=B：B 在且 A 不在 → 改名已落地，向前完成；
 *   A 在且 B 不在 → 改名未发生，回旧态；A、B 都在 → 改名未跑
 *   （同父目录 renameTo 落不到已存在的目标上），两边都保留、
 *   只清凭证；A、B 都不在 → 无可恢复，只清凭证。
 * - SWITCH from=A to=B：指针=B 且 B 在 → 切换已落地，向前完成
 *   （部署由部署指纹在下次启动时核对，未编满会补编，不由本处断言）；
 *   指针=B 但 B 不在 → 目标目录已失，把指针拨回 A；指针=A →
 *   切换未落地，回旧态；指针是其他值 → 已被后一次切换取代，凭证
 *   过期，只清凭证。
 * - 凭证无法解析：保留文件、记日志、不上报、不阻塞其他凭证（与
 *   安装日志同口径；写盘已是原子的，半截凭证本不应出现）。
 * - 凭证清不掉：不上报该条，下次启动／切换重放（重放无副作用）。
 */
object DirectoryMoveJournal {

    /** 改名／切换两种凭证。 */
    enum class MoveKind {
        RENAME,
        SWITCH,
    }

    /** 凭证阶段（只作诊断；恢复判定不依赖阶段，见文件头注释）。 */
    enum class MoveStage {
        INTENT,
        MOVED,
    }

    /** 恢复结果。 */
    enum class RecoveredOutcome {
        /** 回到改名／切换前的一致态。 */
        ROLLED_BACK,

        /** 改名／切换已落地，向前完成。 */
        COMPLETED_FORWARD,

        /** 改名双方目录都在：两边都保留、只清凭证，不擅自删目录。 */
        AMBIGUOUS_KEPT_BOTH,

        /** 凭证过期或无可恢复：只清凭证。 */
        STALE_CLEARED,
    }

    /** 一条凭证。 */
    data class MoveJournal(
        val operationId: String,
        val kind: MoveKind,
        val from: String,
        val to: String,
        val stage: MoveStage,
    )

    /** 一条已恢复的凭证。 */
    data class RecoveredMove(
        val operationId: String,
        val kind: MoveKind,
        val from: String,
        val to: String,
        val outcome: RecoveredOutcome,
    )

    private const val JOURNAL_PREFIX = ".dir-move-"
    private const val JOURNAL_SUFFIX = ".journal"

    internal fun journalFile(root: File, operationId: String): File =
        File(root, "$JOURNAL_PREFIX$operationId$JOURNAL_SUFFIX")

    /**
     * 写 INTENT 凭证（改名／切换落盘前调用），返回操作 id。
     * 调用方须先自行校验 from／to 合法（非法名直接抛错，不写凭证）。
     */
    fun begin(root: File, kind: MoveKind, from: String, to: String): String {
        require(SchemeDirs.isValidDirName(from)) { "invalid dir name: $from" }
        require(SchemeDirs.isValidDirName(to)) { "invalid dir name: $to" }
        val operationId = java.util.UUID.randomUUID().toString().replace("-", "").take(12)
        writeJournal(root, MoveJournal(operationId, kind, from, to, MoveStage.INTENT))
        return operationId
    }

    /**
     * 阶段推进到 MOVED（目录已改名／指针已翻转后调用）。
     * 写失败返回 false（不抛错）：恢复按目录与指针现状判定，与
     * 阶段无关，MOVED 写不上不影响一致性。
     */
    fun markMoved(root: File, operationId: String): Boolean {
        val journal = parseJournal(journalFile(root, operationId)) ?: return false
        return runCatching {
            writeJournal(root, journal.copy(stage = MoveStage.MOVED))
            true
        }.getOrDefault(false)
    }

    /** 提交：删凭证。删后凭证已不存在时返回 true。 */
    fun commit(root: File, operationId: String): Boolean {
        val file = journalFile(root, operationId)
        if (file.exists() && !file.delete()) return false
        return !file.exists()
    }

    /** 根下全部可解析的待恢复凭证（按文件名排序；不可解析的保留并跳过）。 */
    fun pending(root: File): List<MoveJournal> {
        if (!root.isDirectory) return emptyList()
        return root.listFiles()
            ?.filter { it.name.startsWith(JOURNAL_PREFIX) && it.name.endsWith(JOURNAL_SUFFIX) }
            ?.sortedBy { it.name }
            ?.mapNotNull { file ->
                parseJournal(file) ?: run {
                    Timber.w("Unparseable dir-move journal kept for diagnosis: ${file.absolutePath}")
                    null
                }
            }
            .orEmpty()
    }

    /**
     * 两向恢复全部待恢复凭证（幂等：重复重放无副作用——第二次已无凭证可扫）。
     * currentDir／setCurrentDir 由调用方注入（生产走偏好，单测走内存）：
     * 只有 SWITCH 回滚（指针=B 但目标目录已失）才会写指针，其余
     * 判定只读指针与目录现状。setCurrentDir 须在返回前把指针写盘
     *（生产用同步 commit）：凭证在回滚成功后立即删除，指针若未落盘
     * 就删凭证、进程再崩一次会丢回滚。任一条处置失败（回滚写失败、
     * 凭证清不掉）都不上报该条、整条原样保留，下次启动／切换重试。
     */
    fun recover(
        root: File,
        currentDir: () -> String,
        setCurrentDir: (String) -> Unit,
    ): List<RecoveredMove> {
        if (!root.isDirectory) return emptyList()
        val results = mutableListOf<RecoveredMove>()
        for (journal in pending(root)) {
            val outcome = when (journal.kind) {
                MoveKind.RENAME -> recoverRename(root, journal)
                MoveKind.SWITCH -> recoverSwitch(root, journal, currentDir, setCurrentDir)
            } ?: continue
            val file = journalFile(root, journal.operationId)
            if (file.exists() && !file.delete()) continue
            results.add(
                RecoveredMove(
                    journal.operationId,
                    journal.kind,
                    journal.from,
                    journal.to,
                    outcome,
                ),
            )
        }
        return results
    }

    private fun recoverRename(root: File, journal: MoveJournal): RecoveredOutcome {
        val fromDir = File(root, journal.from)
        val toDir = File(root, journal.to)
        return when {
            !fromDir.exists() && toDir.exists() -> RecoveredOutcome.COMPLETED_FORWARD
            fromDir.exists() && !toDir.exists() -> RecoveredOutcome.ROLLED_BACK
            fromDir.exists() && toDir.exists() -> RecoveredOutcome.AMBIGUOUS_KEPT_BOTH
            else -> RecoveredOutcome.STALE_CLEARED
        }
    }

    /**
     * SWITCH 恢复：指针写失败时返回 null（整条保留、下次重试），
     * 其余判定只读现状。部署本身不由本处断言：向前完成时部署指纹
     * 会在下次启动核对，未编满即补编。
     */
    private fun recoverSwitch(
        root: File,
        journal: MoveJournal,
        currentDir: () -> String,
        setCurrentDir: (String) -> Unit,
    ): RecoveredOutcome? {
        val current = runCatching { currentDir() }.getOrElse { return null }
        val targetDir = File(root, journal.to)
        return when {
            current == journal.to && targetDir.exists() -> RecoveredOutcome.COMPLETED_FORWARD
            current == journal.to -> {
                runCatching { setCurrentDir(journal.from) }.getOrElse { return null }
                RecoveredOutcome.ROLLED_BACK
            }
            current == journal.from -> RecoveredOutcome.ROLLED_BACK
            else -> RecoveredOutcome.STALE_CLEARED
        }
    }

    private fun writeJournal(root: File, journal: MoveJournal) {
        val text = buildString {
            appendLine("operationId=${journal.operationId}")
            appendLine("kind=${journal.kind.name}")
            appendLine("from=${journal.from}")
            appendLine("to=${journal.to}")
            appendLine("stage=${journal.stage.name}")
        }
        AtomicFiles.write(journalFile(root, journal.operationId), text)
    }

    internal fun parseJournal(file: File): MoveJournal? {
        val values = runCatching {
            file.readLines().mapNotNull { line ->
                val key = line.substringBefore("=", "")
                val value = line.substringAfter("=", "")
                if (key.isEmpty() || value.isEmpty()) null else key to value
            }.toMap()
        }.getOrNull() ?: return null
        val operationId = values["operationId"] ?: return null
        val kind = runCatching { MoveKind.valueOf(values["kind"].orEmpty()) }.getOrNull()
            ?: return null
        val stage = runCatching { MoveStage.valueOf(values["stage"].orEmpty()) }.getOrNull()
            ?: return null
        val from = values["from"] ?: return null
        val to = values["to"] ?: return null
        if (!SchemeDirs.isValidDirName(from) || !SchemeDirs.isValidDirName(to)) return null
        return MoveJournal(operationId, kind, from, to, stage)
    }
}
