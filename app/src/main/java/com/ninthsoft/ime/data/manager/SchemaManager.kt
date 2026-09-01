package com.ninthsoft.ime.data.manager

import android.content.Context
import androidx.core.content.edit

object SchemaManager {
    const val PREFS_NAME = "schema_settings"
    const val KEY_ENABLED_IDS = "enabled_schema_ids"
    const val KEY_GRAMMAR_MODEL = "grammar_model"

    fun isGrammarModelEnabled(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_GRAMMAR_MODEL, true)
    }

    fun setGrammarModelEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
            putBoolean(KEY_GRAMMAR_MODEL, enabled)
        }
    }
}
