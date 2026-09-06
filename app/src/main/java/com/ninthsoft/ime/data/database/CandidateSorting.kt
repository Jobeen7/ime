package com.ninthsoft.ime.data.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "candidate_sorting_v2")
data class CandidateSorting(
    /** 由候选集合计算出的无序唯一指纹，替代原 preedit 作为主键。 */
    @PrimaryKey
    @ColumnInfo(name = "sorting_key")
    val key: String,
    val candidateIds: List<Int>,
)