package com.ninthsoft.ime.data.database

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
)