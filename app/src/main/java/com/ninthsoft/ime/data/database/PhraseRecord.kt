package com.ninthsoft.ime.data.database

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "phrase_records")
data class PhraseRecord(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val text: String,
    val label: String,
    val createdAt: Long,
)
