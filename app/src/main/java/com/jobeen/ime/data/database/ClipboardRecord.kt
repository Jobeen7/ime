package com.jobeen.ime.data.database

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "clipboard_records")
data class ClipboardRecord(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val text: String,
    val timestamp: Long,
    val cloud: Boolean = false,
    val deleted: Boolean = false,
    val deletedAt: Long = 0,
    val pinned: Boolean = false,
    /** 手动排序序号（拖动排序写入，越小越靠前）；未手动排过时由 -timestamp 保持时间倒序 */
    val sortOrder: Long = 0,
)