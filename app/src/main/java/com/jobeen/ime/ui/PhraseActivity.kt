// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.ui

import androidx.compose.runtime.Composable
import com.jobeen.ime.ui.screen.PhraseSettingsScreen

/**
 * 常用语管理页宿主（WO52 6.1，设置一级入口 `phrase`）。
 *
 * deep link 落点：`phrase?id=<id>` 由第 5 组 4.2 的 `jime://` 路由
 * 携 id 调起本页（[EXTRA_PHRASE_ID]），本页按 id 打开该条的编辑页；
 * 不带 id 时打开列表。
 */
class PhraseActivity : ImeActivity() {

    @Composable
    override fun Content() {
        val phraseId = intent.getLongExtra(EXTRA_PHRASE_ID, -1L)
        PhraseSettingsScreen(
            onBack = { finish() },
            initialPhraseId = phraseId,
        )
    }

    companion object {
        const val EXTRA_PHRASE_ID = "phrase_id"
    }
}
