package com.ninthsoft.ime.data.manager

import android.content.Context
import androidx.core.content.edit

object CandidateManager {
    private const val PREFS_NAME = "candidate_settings"
    private const val KEY_PREDICTION_ENABLED = "prediction_enabled"
    private const val KEY_TRADITIONAL_ENABLED = "enableTraditionalChinese"
    private const val KEY_RERANK_ENABLED = "rerank_enabled"
    private const val KEY_SHOW_INDEX = "show_index"
    private const val KEY_SHOW_COMMENT = "show_comment"
    private const val KEY_BORDERLESS = "borderless"

    fun isPredictionEnabled(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_PREDICTION_ENABLED, true)
    }

    fun setPredictionEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
            putBoolean(KEY_PREDICTION_ENABLED, enabled)
        }
    }

    fun isTraditionalChineseEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_TRADITIONAL_ENABLED, false)

    fun setTraditionalChineseEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
            putBoolean(KEY_TRADITIONAL_ENABLED, enabled)
        }
    }

    fun isRerankEnabled(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_RERANK_ENABLED, true)
    }

    fun setRerankEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
            putBoolean(KEY_RERANK_ENABLED, enabled)
        }
    }

    fun isShowIndex(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_SHOW_INDEX, true)
    }

    fun setShowIndex(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
            putBoolean(KEY_SHOW_INDEX, enabled)
        }
    }

    fun isShowComment(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_SHOW_COMMENT, false)
    }

    fun setShowComment(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
            putBoolean(KEY_SHOW_COMMENT, enabled)
        }
    }

    fun isBorderless(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_BORDERLESS, false)
    }

    fun setBorderless(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
            putBoolean(KEY_BORDERLESS, enabled)
        }
    }
}
