package com.ninthsoft.ime.data.manager

import android.content.Context
import com.ninthsoft.ime.data.database.AppDatabase
import com.ninthsoft.ime.data.database.PhraseRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import com.ninthsoft.ime.base.util.appScope
import timber.log.Timber

object PhraseManager {

    private const val PREFS_NAME = "phrase_prefs"
    private const val KEY_SEEDED = "seeded"

    var onContentChanged: (() -> Unit)? = null

    data class Phrase(
        val id: Long,
        val text: String,
        val label: String,
        val createdAt: Long,
    )

    fun getAll(context: Context): List<Phrase> = db(context) { db ->
        ensureSeeded(context, db)
        db.phraseDao().getAll().map { Phrase(it.id, it.text, it.label, it.createdAt) }
    } ?: emptyList()

    fun getById(context: Context, id: Long): Phrase? = db(context) { db ->
        db.phraseDao().getById(id)?.let { Phrase(it.id, it.text, it.label, it.createdAt) }
    }

    fun search(context: Context, query: String): List<Phrase> = db(context) { db ->
        db.phraseDao().search(query).map { Phrase(it.id, it.text, it.label, it.createdAt) }
    } ?: emptyList()

    fun insert(context: Context, text: String, label: String): Long {
        val t = text.trim()
        if (t.isEmpty()) return -1
        val l = label.trim().ifEmpty { t.take(12) }
        val id = db(context) { db ->
            db.phraseDao().insert(PhraseRecord(text = t, label = l, createdAt = System.currentTimeMillis()))
        } ?: -1
        if (id > 0) onContentChanged?.invoke()
        return id
    }

    fun update(context: Context, phrase: Phrase) {
        val t = phrase.text.trim()
        if (t.isEmpty()) return
        val l = phrase.label.trim().ifEmpty { t.take(12) }
        db(context) { db ->
            db.phraseDao().update(
                PhraseRecord(id = phrase.id, text = t, label = l, createdAt = phrase.createdAt)
            )
        }
        onContentChanged?.invoke()
    }

    fun delete(context: Context, id: Long) {
        db(context) { db -> db.phraseDao().deleteById(id) }
        onContentChanged?.invoke()
    }

    fun deleteAll(context: Context) {
        db(context) { db -> db.phraseDao().deleteAll() }
        onContentChanged?.invoke()
    }

    private suspend fun ensureSeeded(context: Context, db: AppDatabase) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_SEEDED, false)) return
        val dao = db.phraseDao()
        if (dao.getAll().isEmpty()) {
            val now = System.currentTimeMillis()
            val defaults = listOf(
                "谢谢" to "谢谢",
                "辛苦了" to "辛苦了",
                "收到" to "收到",
                "辛苦了，注意身体" to "关心",
            )
            defaults.forEachIndexed { index, (text, label) ->
                dao.insert(PhraseRecord(text = text, label = label, createdAt = now - index * 1000))
            }
        }
        prefs.edit().putBoolean(KEY_SEEDED, true).apply()
    }

    private fun <T> db(context: Context, block: suspend (AppDatabase) -> T): T? =
        try {
            runBlocking(Dispatchers.IO) {
                val db = AppDatabase.getInstance(context)
                block(db)
            }
        } catch (e: Exception) {
            Timber.e(e, "phrase database operation failed")
            null
        }
}
