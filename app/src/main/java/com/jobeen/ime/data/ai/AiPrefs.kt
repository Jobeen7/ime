// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.data.ai

import android.content.Context
import androidx.core.content.edit

/**
 * AI 前缀配置（WO52 9.1）：
 * `commandViewAIPrefix`／`commandViewAIHistoryPrefix`／
 * `aiConversationHistoryLimit`。
 *
 * 默认值未在别处定过：Jime 侧取前缀空串
 * （未设＝命令面板不设 AI 前缀）、历史上限 50 条。改这里不影响
 * 已存配置——只影响从未改过的新装。
 */
object AiPrefs {
    private const val PREFS_NAME = "ai_prefs"
    private const val KEY_COMMAND_VIEW_AI_PREFIX = "commandViewAIPrefix"
    private const val KEY_COMMAND_VIEW_AI_HISTORY_PREFIX = "commandViewAIHistoryPrefix"
    private const val KEY_AI_CONVERSATION_HISTORY_LIMIT = "aiConversationHistoryLimit"

    const val DEFAULT_COMMAND_VIEW_AI_PREFIX = ""
    const val DEFAULT_COMMAND_VIEW_AI_HISTORY_PREFIX = ""
    const val DEFAULT_AI_CONVERSATION_HISTORY_LIMIT = 50

    fun getCommandViewAiPrefix(context: Context): String =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_COMMAND_VIEW_AI_PREFIX, DEFAULT_COMMAND_VIEW_AI_PREFIX).orEmpty()

    fun setCommandViewAiPrefix(context: Context, value: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
            putString(KEY_COMMAND_VIEW_AI_PREFIX, value)
        }
    }

    fun getCommandViewAiHistoryPrefix(context: Context): String =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_COMMAND_VIEW_AI_HISTORY_PREFIX, DEFAULT_COMMAND_VIEW_AI_HISTORY_PREFIX).orEmpty()

    fun setCommandViewAiHistoryPrefix(context: Context, value: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
            putString(KEY_COMMAND_VIEW_AI_HISTORY_PREFIX, value)
        }
    }

    fun getConversationHistoryLimit(context: Context): Int =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getInt(KEY_AI_CONVERSATION_HISTORY_LIMIT, DEFAULT_AI_CONVERSATION_HISTORY_LIMIT)

    fun setConversationHistoryLimit(context: Context, value: Int) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
            putInt(KEY_AI_CONVERSATION_HISTORY_LIMIT, value.coerceIn(1, 500))
        }
    }
}
