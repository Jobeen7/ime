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

    /**
     * 一次性清理旧格式行：现行键为 "p:<方案>:<组字哈希>" 三段式；
     * 裸十六进制指纹键与上一代 "p:<指纹>" 两段键永不再命中，只占行与备份体积。
     */
    @Query("DELETE FROM candidate_sorting_v2 WHERE sorting_key NOT LIKE 'p:%:%'")
    suspend fun deleteLegacyKeys()
}
