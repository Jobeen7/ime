package com.ninthsoft.ime.data.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update

@Dao
interface PhraseDao {

    @Insert
    suspend fun insert(record: PhraseRecord): Long

    @Update
    suspend fun update(record: PhraseRecord)

    @Query("SELECT * FROM phrase_records ORDER BY createdAt DESC")
    suspend fun getAll(): List<PhraseRecord>

    @Query("SELECT * FROM phrase_records WHERE id = :id")
    suspend fun getById(id: Long): PhraseRecord?

    @Query("DELETE FROM phrase_records WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM phrase_records")
    suspend fun deleteAll()

    @Query("SELECT * FROM phrase_records WHERE label LIKE '%' || :query || '%' OR text LIKE '%' || :query || '%' ORDER BY createdAt DESC")
    suspend fun search(query: String): List<PhraseRecord>
}
