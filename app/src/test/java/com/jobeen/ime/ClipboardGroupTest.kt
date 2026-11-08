// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime

import com.jobeen.ime.data.backup.BackupManager
import com.jobeen.ime.data.database.ClipboardGroup
import com.jobeen.ime.data.database.ClipboardRecord
import com.jobeen.ime.data.manager.ClipboardManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 剪贴板自定义分组（H2-4）：纯函数一侧。
 * DB 写路径（建组／改名／删组／移组／迁移 SQL）走 Room 与服务弹窗，
 * 本地 JVM 单测跑不通，待真机验收（见施工报告已知差距）。
 */
class ClipboardGroupTest {

    private fun entry(text: String, groupId: Long = 0L) =
        ClipboardManager.Entry(text = text, groupId = groupId)

    private fun group(id: Long, name: String, sortOrder: Long = 0L) =
        ClipboardGroup(id = id, name = name, sortOrder = sortOrder, createdAt = 100L)

    private fun record(text: String, groupId: Long = 0L, id: Long = 0L) =
        ClipboardRecord(id = id, text = text, timestamp = 100L, groupId = groupId)

    // —— 分组名规整 ——

    @Test
    fun normalizeGroupName_blankIsInvalid() {
        assertNull(ClipboardManager.normalizeGroupName(""))
        assertNull(ClipboardManager.normalizeGroupName("   "))
    }

    @Test
    fun normalizeGroupName_trimsWhitespace() {
        assertEquals("工作", ClipboardManager.normalizeGroupName("  工作  "))
    }

    @Test
    fun normalizeGroupName_truncatesOverlongName() {
        val long = "一二三四五六七八九十一二三四五六七八九十一二三"
        assertEquals(20, ClipboardManager.normalizeGroupName(long)?.length)
        assertEquals(long.take(20), ClipboardManager.normalizeGroupName(long))
    }

    @Test
    fun normalizeGroupName_exactLimitKept() {
        val exact = "一二三四五六七八九十一二三四五六七八九十"
        assertEquals(20, exact.length)
        assertEquals(exact, ClipboardManager.normalizeGroupName(exact))
    }

    // —— 按分组过滤 ——

    @Test
    fun filterByGroup_allKeepsEverything() {
        val entries = listOf(entry("a"), entry("b", 2L), entry("c", 3L))
        assertEquals(entries, ClipboardManager.filterByGroup(entries, null))
    }

    @Test
    fun filterByGroup_ungroupedOnly() {
        val entries = listOf(entry("a"), entry("b", 2L), entry("c", 3L), entry("d"))
        assertEquals(
            listOf(entry("a"), entry("d")),
            ClipboardManager.filterByGroup(entries, 0L),
        )
    }

    @Test
    fun filterByGroup_specificGroup() {
        val entries = listOf(entry("a"), entry("b", 2L), entry("c", 3L), entry("d", 2L))
        assertEquals(
            listOf(entry("b", 2L), entry("d", 2L)),
            ClipboardManager.filterByGroup(entries, 2L),
        )
    }

    @Test
    fun filterByGroup_unknownGroupIsEmpty() {
        val entries = listOf(entry("a"), entry("b", 2L))
        assertTrue(ClipboardManager.filterByGroup(entries, 99L).isEmpty())
    }

    // —— 分组合并（备份还原） ——

    @Test
    fun mergeGroups_emptyBackupKeepsCurrentUntouched() {
        val current = listOf(group(1, "工作"), group(2, "生活"))
        val (merged, remap) = BackupManager.mergeGroupsForRestore(current, emptyList())
        assertEquals(current, merged)
        assertTrue(remap.isEmpty())
    }

    @Test
    fun mergeGroups_emptyCurrentBackupGetsFreshIds() {
        val backup = listOf(group(5, "工作"), group(6, "生活"))
        val (merged, remap) = BackupManager.mergeGroupsForRestore(emptyList(), backup)
        assertEquals(listOf(1L, 2L), merged.map { it.id })
        assertEquals(listOf("工作", "生活"), merged.map { it.name })
        assertEquals(mapOf(5L to 1L, 6L to 2L), remap)
    }

    @Test
    fun mergeGroups_sameNameCurrentWins() {
        val current = listOf(group(10, "工作"))
        val backup = listOf(group(3, "工作"), group(4, "生活"))
        val (merged, remap) = BackupManager.mergeGroupsForRestore(current, backup)
        assertEquals(listOf("工作", "生活"), merged.map { it.name })
        // 同名组取当前 id；补入组 id 接在当前最大 id 之后
        assertEquals(listOf(10L, 11L), merged.map { it.id })
        assertEquals(mapOf(3L to 10L, 4L to 11L), remap)
    }

    @Test
    fun mergeGroups_duplicateNamesInsideBackupCollapse() {
        val backup = listOf(group(3, "工作"), group(4, "工作"))
        val (merged, remap) = BackupManager.mergeGroupsForRestore(emptyList(), backup)
        assertEquals(listOf("工作"), merged.map { it.name })
        // 后一个同名备份组映射到先补入的组；先补入的组 id 也重排过
        assertEquals(mapOf(3L to 1L, 4L to 1L), remap)
    }

    @Test
    fun mergeGroups_sortOrderAndCreatedAtPreserved() {
        val backup = listOf(group(7, "工作", sortOrder = 9L))
        val (merged, _) = BackupManager.mergeGroupsForRestore(emptyList(), backup)
        assertEquals(9L, merged[0].sortOrder)
        assertEquals(100L, merged[0].createdAt)
    }

    // —— 补入行的分组重映射 ——

    @Test
    fun remapBackupRecordGroups_currentRowsUntouched() {
        val records = listOf(record("a", groupId = 10L), record("b", groupId = 3L))
        val remapped = BackupManager.remapBackupRecordGroups(
            records, setOf("a", "b"), mapOf(3L to 11L)
        )
        assertEquals(records, remapped)
    }

    @Test
    fun remapBackupRecordGroups_backupRowsRemapped() {
        val records = listOf(record("a", groupId = 10L), record("b", groupId = 3L))
        val remapped = BackupManager.remapBackupRecordGroups(
            records, setOf("a"), mapOf(3L to 11L)
        )
        assertEquals(10L, remapped[0].groupId)
        assertEquals(11L, remapped[1].groupId)
    }

    @Test
    fun remapBackupRecordGroups_unknownGroupFallsBackToUngrouped() {
        val records = listOf(record("b", groupId = 77L))
        val remapped = BackupManager.remapBackupRecordGroups(
            records, emptySet(), mapOf(3L to 11L)
        )
        assertEquals(0L, remapped[0].groupId)
    }

    @Test
    fun remapBackupRecordGroups_emptyRemapKeepsRecords() {
        val records = listOf(record("b", groupId = 3L))
        assertEquals(
            records,
            BackupManager.remapBackupRecordGroups(records, emptySet(), emptyMap()),
        )
    }

    @Test
    fun mergeAndRemap_composeEndToEnd() {
        // 当前有一组“工作”(id=10)；备份有一组同名“工作”(id=3)与新组“生活”(id=4)，
        // 备份独有的一条记录在“生活”组里：合并后它应落在新 id=11 的组
        val currentGroups = listOf(group(10, "工作"))
        val backupGroups = listOf(group(3, "工作"), group(4, "生活"))
        val (mergedGroups, remap) =
            BackupManager.mergeGroupsForRestore(currentGroups, backupGroups)
        val records = listOf(
            record("当前词", groupId = 10L),
            record("备份词", groupId = 4L),
        )
        val remapped = BackupManager.remapBackupRecordGroups(
            records, setOf("当前词"), remap
        )
        assertEquals(listOf(10L, 11L), mergedGroups.map { it.id })
        assertEquals(10L, remapped[0].groupId)
        assertEquals(11L, remapped[1].groupId)
    }
}
