package com.jobeen.ime.data.database

import androidx.room.Dao
import androidx.room.Query

@Dao
interface CandidatePreferDao {

    @Query("SELECT * FROM candidate_prefers WHERE text = :text LIMIT 1")
    suspend fun get(text: String): CandidatePrefer?

    @Query("SELECT * FROM candidate_prefers WHERE text IN (:texts)")
    suspend fun getAllByTextIn(texts: List<String>): List<CandidatePrefer>

    @Query("SELECT text, click_count FROM candidate_prefers")
    suspend fun getAllCounts(): List<PreferCount>

    data class PreferCount(val text: String, val click_count: Int)

    @Query(
        """
        INSERT INTO candidate_prefers (text, context, click_count, created_at, updated_at)
        VALUES (:text, :context, 1, :now, :now)
        ON CONFLICT (text) DO UPDATE SET
            click_count = click_count + 1,
            context = :context,
            updated_at = :now
        """
    )
    suspend fun upsert(text: String, context: String, now: Long = System.currentTimeMillis())

    @Query("SELECT COUNT(*) FROM candidate_prefers")
    suspend fun count(): Int

    // 容量上限：只保留点击量最高（同量取最近更新）的前 limit 条，其余删除。
    // 偏好表无上限时长年使用会无限膨胀，拖慢全量加载与重排。
    @Query(
        """
        DELETE FROM candidate_prefers WHERE text NOT IN (
            SELECT text FROM candidate_prefers
            ORDER BY click_count DESC, updated_at DESC LIMIT :limit
        )
        """
    )
    suspend fun pruneToLimit(limit: Int)
}
