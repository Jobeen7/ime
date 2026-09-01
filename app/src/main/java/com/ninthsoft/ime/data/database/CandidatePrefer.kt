package com.ninthsoft.ime.data.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "candidate_prefers")
data class CandidatePrefer(
    @PrimaryKey
    val text: String,

    @ColumnInfo(name = "click_count")
    val count: Int = 1,

    val context: String,

    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis(),

    @ColumnInfo(name = "updated_at")
    val updatedAt: Long = System.currentTimeMillis(),
)
