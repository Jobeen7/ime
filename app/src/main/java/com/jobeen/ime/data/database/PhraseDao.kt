package com.jobeen.ime.data.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update

@Dao
interface PhraseDao {

    @Insert
    suspend fun insert(record: PhraseRecord): Long

    @Insert
    suspend fun insertAll(records: List<PhraseRecord>)

    @Update
    suspend fun update(record: PhraseRecord): Int

    @Query("SELECT * FROM phrase_records ORDER BY sortOrder ASC, createdAt DESC")
    suspend fun getAll(): List<PhraseRecord>

    @Query("SELECT MIN(sortOrder) FROM phrase_records")
    suspend fun minSortOrder(): Long?

    @Query("UPDATE phrase_records SET sortOrder = :order WHERE id = :id")
    suspend fun setSortOrderById(id: Long, order: Long)

    @Query("SELECT * FROM phrase_records WHERE id = :id")
    suspend fun getById(id: Long): PhraseRecord?

    @Query("DELETE FROM phrase_records WHERE id = :id")
    suspend fun deleteById(id: Long): Int

    @Query("DELETE FROM phrase_records")
    suspend fun deleteAll(): Int

    @Query("SELECT * FROM phrase_records WHERE label LIKE '%' || :query || '%' OR text LIKE '%' || :query || '%' ORDER BY sortOrder ASC, createdAt DESC")
    suspend fun search(query: String): List<PhraseRecord>
}
