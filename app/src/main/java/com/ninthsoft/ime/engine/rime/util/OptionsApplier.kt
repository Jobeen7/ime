package com.ninthsoft.ime.engine.rime.util

import com.ninthsoft.ime.base.util.appContext
import com.ninthsoft.ime.data.manager.CandidateManager
import com.ninthsoft.ime.engine.rime.core.RimeApi
import com.ninthsoft.ime.engine.rime.core.RimeSchema
import timber.log.Timber

object OptionsApplier {
    val optionsMapper = mapOf(
        CandidateManager.KEY_TRADITIONAL_ENABLED to {
            CandidateManager.isTraditionalChineseEnabled(appContext)
        },
        CandidateManager.KEY_EMOJI_ENABLED to {
            CandidateManager.isEmojiEnabled(appContext)
        },
        CandidateManager.KEY_ASCII_MODE_ENABLED to {
            CandidateManager.isAsciiModeEnabled(appContext)
        },
    )

    suspend fun apply(api: RimeApi, block: RimeSchema) {
        for (option in block.options) {
            var value = optionsMapper[option.name]?.invoke() ?: option.value
            if (option.lock) {
                value = option.value
            }
            Timber.d(
                "%s apply [%s]:[%s] locked:[%s]", block.schemaId, option.key, value, option.lock
            )
            api.setRuntimeOption(option.key, value)
            for (keyName in option.keys) {
                if (keyName != option.key) {
                    Timber.d("%s apply [%s]:[%s]", block.schemaId, keyName, false)
                    api.setRuntimeOption(keyName, false)
                }
            }
        }
    }

    fun isOptionDependency(key: String?): Boolean {
        return optionsMapper.containsKey(key)
    }
}