package com.jobeen.ime.data.database

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

    @Query("SELECT COUNT(*) FROM candidate_sorting_v2")
    suspend fun count(): Int

    // 备份/还原专用：全量读取、整表替换
    @Query("SELECT * FROM candidate_sorting_v2")
    suspend fun getAllFull(): List<CandidateSorting>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<CandidateSorting>)

    @Query("DELETE FROM candidate_sorting_v2")
    suspend fun deleteAll()
}
