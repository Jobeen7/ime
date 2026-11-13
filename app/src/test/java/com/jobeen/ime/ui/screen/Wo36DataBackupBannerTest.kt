// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.ui.screen

import com.jobeen.ime.data.backup.BackupManager
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * D-L1 中断还原横幅主文案（WO36）：按损坏情形区分——回滚入口禁用
 * 时主文案不再提回滚。
 *
 * 状态空间（决定因子只有 snapshotAvailable，回滚钮的启用条件与
 * 此同值，文案与按钮永不同步错位）：
 * - 快照在位 → 主文案提回滚（旧串）；
 * - 快照丢失 → 主文案不提回滚（新串）；
 * - journal 损坏（reconcile 置 snapshotAvailable=false）→
 *   主文案不提回滚（新串）。
 *
 * 覆盖不到：Compose 层按本函数选串的接线——JVM 单测跑不通
 * Compose，需真机看一次损坏 journal 横幅（主文案无“或回滚”，
 * 附注仍是损坏提示、回滚钮禁用）。
 */
class Wo36DataBackupBannerTest {

    private fun info(snapshotAvailable: Boolean, journalCorrupt: Boolean = false) =
        BackupManager.InterruptedRestoreInfo(
            token = "t1",
            startedAt = 1_700_000_000_000L,
            completedSections = emptySet(),
            snapshotAvailable = snapshotAvailable,
            journalCorrupt = journalCorrupt,
        )

    @Test
    fun snapshotAvailable_messageMentionsRollback() {
        assertTrue(interruptedRestoreMessageMentionsRollback(info(snapshotAvailable = true)))
    }

    @Test
    fun snapshotMissing_messageOmitsRollback() {
        assertFalse(interruptedRestoreMessageMentionsRollback(info(snapshotAvailable = false)))
    }

    @Test
    fun journalCorrupt_messageOmitsRollback() {
        // 损坏 journal 时 reconcile 置 snapshotAvailable=false。
        assertFalse(
            interruptedRestoreMessageMentionsRollback(
                info(snapshotAvailable = false, journalCorrupt = true),
            ),
        )
    }

    @Test
    fun decisionKeysOnlyOnSnapshotAvailable() {
        // 规则只看快照在位与否，不另看 journalCorrupt：损坏情形
        // 的 snapshotAvailable 恒为 false（见 reconcile），此处
        // 钉住“单信号”本身，防日后有人按 corrupt 再分一支。
        assertTrue(
            interruptedRestoreMessageMentionsRollback(
                info(snapshotAvailable = true, journalCorrupt = true),
            ),
        )
    }
}
