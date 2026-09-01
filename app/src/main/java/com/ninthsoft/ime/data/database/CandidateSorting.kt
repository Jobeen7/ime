package com.ninthsoft.ime.data.database

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "candidate_sorting")
data class CandidateSorting(
    @PrimaryKey
    val preedit: String,
    val candidateIds: List<Int>,
)
