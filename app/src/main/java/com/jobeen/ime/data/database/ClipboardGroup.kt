package com.jobeen.ime.data.database

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 剪贴板自定义分组（H2-4）：一条分组就是一个名字，条目经
 * [ClipboardRecord.groupId] 归属（0 = 未分组）。
 * 名称唯一索引：同名分组只留一个，还原合并按名称认同一组
 * （见 BackupManager.mergeGroupsForRestore）。
 */
@Entity(
    tableName = "clipboard_groups",
    indices = [Index(value = ["name"], unique = true)],
)
data class ClipboardGroup(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    /** 手动排序序号（越小越靠前）；新建取当前最大值加一 */
    val sortOrder: Long = 0,
    val createdAt: Long = System.currentTimeMillis(),
)
