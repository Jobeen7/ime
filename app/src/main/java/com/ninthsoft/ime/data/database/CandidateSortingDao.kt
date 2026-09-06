package com.ninthsoft.ime.data.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface CandidateSortingDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveSorting(sorting: CandidateSorting)

    @Query("SELECT * FROM candidate_sorting_v2 WHERE sorting_key = :key")
    suspend fun loadSorting(key: String): CandidateSorting?
}
