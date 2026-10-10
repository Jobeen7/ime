package com.jobeen.ime.data.database

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "phrase_records")
data class PhraseRecord(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val text: String,
    val label: String,
    val createdAt: Long,
    /** 手动排序序号（拖动排序写入，越小越靠前）；未手动排过时由 -createdAt 保持新建在前 */
    val sortOrder: Long = 0,
)
