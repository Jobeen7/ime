package com.jobeen.ime.data.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "candidate_sorting_v2")
data class CandidateSorting(
    /** 排序记录的键：现行格式为组字串（preedit）派生键（"p:" 前缀）；历史行是候选集合指纹键，不再命中。 */
    @PrimaryKey
    @ColumnInfo(name = "sorting_key")
    val key: String,
    val candidateIds: List<Int>,
)