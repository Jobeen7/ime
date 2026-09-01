package com.ninthsoft.ime.data.database

import androidx.room.Dao
import androidx.room.Query

@Dao
interface CandidatePreferDao {

    @Query("SELECT * FROM candidate_prefers WHERE text = :text LIMIT 1")
    suspend fun get(text: String): CandidatePrefer?

    @Query("SELECT * FROM candidate_prefers WHERE text IN (:texts)")
    suspend fun getAllByTextIn(texts: List<String>): List<CandidatePrefer>

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
}
