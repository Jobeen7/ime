// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime

import com.jobeen.ime.data.backup.BackupManager
import com.jobeen.ime.data.database.CandidatePrefer
import com.jobeen.ime.data.database.CandidateSorting
import com.jobeen.ime.data.database.ClipboardRecord
import com.jobeen.ime.data.database.PhraseRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 备份还原合并语义（D31）：Room 四表“当前优先、备份补缺”取并集——
 * 备份后新学的内容留在当前表里，备份里有、当前已删的内容补回来。
 * 合并函数是纯函数，测它即测还原入口的合并行为（applyPayload 本体
 * 是整表替换、走 SharedPreferences 与 Room，本地 JVM 单测跑不通）。
 */
class BackupMergeRestoreTest {

    // —— 选词偏好：text 主键，当前行优先 ——

    @Test
    fun prefers_currentWinsOnConflict_backupOnlyAppended() {
        val current = listOf(
            CandidatePrefer(text = "看", count = 5, context = "", createdAt = 100, updatedAt = 200),
        )
        val backup = listOf(
            CandidatePrefer(text = "看", count = 2, context = "", createdAt = 100, updatedAt = 150),
            CandidatePrefer(text = "刊", count = 3, context = "", createdAt = 90, updatedAt = 90),
        )
        val merged = BackupManager.mergePrefersForRestore(current, backup)
        assertEquals(listOf("看", "刊"), merged.map { it.text })
        // 冲突行取当前（备份后多选了 3 次的计数不被备份时点的旧数覆盖）
        assertEquals(5, merged[0].count)
        assertEquals(3, merged[1].count)
    }

    @Test
    fun prefers_emptySides() {
        val backup = listOf(
            CandidatePrefer(text = "看", count = 2, context = "", createdAt = 100, updatedAt = 150),
        )
        assertEquals(backup, BackupManager.mergePrefersForRestore(emptyList(), backup))
        val current = listOf(
            CandidatePrefer(text = "看", count = 5, context = "", createdAt = 100, updatedAt = 200),
        )
        assertEquals(current, BackupManager.mergePrefersForRestore(current, emptyList()))
        assertTrue(BackupManager.mergePrefersForRestore(emptyList(), emptyList()).isEmpty())
    }

    // —— 候选排序：key 主键，当前行优先 ——

    @Test
    fun sortings_currentWinsOnConflict_backupOnlyAppended() {
        val current = listOf(CandidateSorting(key = "p:kan", candidateIds = listOf(1, 2)))
        val backup = listOf(
            CandidateSorting(key = "p:kan", candidateIds = listOf(2, 1)),
            CandidateSorting(key = "p:jin", candidateIds = listOf(3)),
        )
        val merged = BackupManager.mergeSortingsForRestore(current, backup)
        assertEquals(listOf("p:kan", "p:jin"), merged.map { it.key })
        assertEquals(listOf(1, 2), merged[0].candidateIds)
    }

    // —— 剪贴板：text 唯一键，当前行优先，补入行 id 清零 ——

    @Test
    fun clipboard_currentWinsOnConflict_backupOnlyAppendedWithFreshId() {
        val current = listOf(
            ClipboardRecord(id = 50, text = "新词", timestamp = 300, sortOrder = -300),
        )
        val backup = listOf(
            ClipboardRecord(id = 3, text = "新词", timestamp = 100, sortOrder = -100),
            ClipboardRecord(id = 2, text = "旧词", timestamp = 90, sortOrder = -90),
        )
        val merged = BackupManager.mergeClipboardForRestore(current, backup)
        assertEquals(listOf("新词", "旧词"), merged.map { it.text })
        // 冲突行取当前整行（时间戳与排序序号都不回退）
        assertEquals(50L, merged[0].id)
        assertEquals(300L, merged[0].timestamp)
        // 补入行 id 清零重排：自增 id 是本机代次，跨设备直插会撞主键
        assertEquals(0L, merged[1].id)
        assertEquals(90L, merged[1].timestamp)
    }

    @Test
    fun clipboard_duplicateTextInsideBackupDeduped() {
        val backup = listOf(
            ClipboardRecord(id = 2, text = "旧词", timestamp = 90, sortOrder = -90),
            ClipboardRecord(id = 1, text = "旧词", timestamp = 80, sortOrder = -80),
        )
        val merged = BackupManager.mergeClipboardForRestore(emptyList(), backup)
        assertEquals(listOf("旧词"), merged.map { it.text })
        assertEquals(0L, merged[0].id)
    }

    @Test
    fun clipboard_emptyBackupKeepsCurrentUntouched() {
        val current = listOf(
            ClipboardRecord(id = 50, text = "新词", timestamp = 300, sortOrder = -300),
        )
        assertEquals(current, BackupManager.mergeClipboardForRestore(current, emptyList()))
    }

    // —— 常用语：合并键（text, label），同文不同 label 是两条 ——

    @Test
    fun phrases_sameTextDifferentLabelBothKept() {
        val current = listOf(
            PhraseRecord(id = 7, text = "你好", label = "招呼", createdAt = 300, sortOrder = -300),
        )
        val backup = listOf(
            PhraseRecord(id = 3, text = "你好", label = "招呼", createdAt = 100, sortOrder = -100),
            PhraseRecord(id = 2, text = "你好", label = "测试", createdAt = 90, sortOrder = -90),
            PhraseRecord(id = 1, text = "再见", label = "", createdAt = 80, sortOrder = -80),
        )
        val merged = BackupManager.mergePhrasesForRestore(current, backup)
        assertEquals(
            listOf("你好/招呼", "你好/测试", "再见/"),
            merged.map { "${it.text}/${it.label}" },
        )
        // 冲突行取当前（备份后改过的排序不回退）
        assertEquals(7L, merged[0].id)
        assertEquals(-300L, merged[0].sortOrder)
        // 补入行 id 清零重排
        assertEquals(0L, merged[1].id)
        assertEquals(0L, merged[2].id)
    }

    @Test
    fun phrases_emptyCurrentTakesBackupWithFreshIds() {
        val backup = listOf(
            PhraseRecord(id = 3, text = "你好", label = "", createdAt = 100, sortOrder = -100),
        )
        val merged = BackupManager.mergePhrasesForRestore(emptyList(), backup)
        assertEquals(listOf("你好"), merged.map { it.text })
        assertEquals(0L, merged[0].id)
    }
}
