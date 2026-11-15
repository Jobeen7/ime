// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.ui

import android.content.Intent
import androidx.compose.runtime.Composable
import com.jobeen.ime.ui.screen.SplitWordsScreen

/**
 * 分词页（WO52 1.9，对端 split_string_panel）：独立页（非键盘内面板，
 * 与 AboutActivity 等设置页同口径——从键盘打开时键盘收起）。
 * 入口：剪贴板长按菜单 "Split words" 与行内 Split 钮（见 5.3）。
 */
class SplitWordsActivity : ImeActivity() {

    companion object {
        const val EXTRA_TEXT = "text"
    }

    @Composable
    override fun Content() {
        val text = intent.getStringExtra(EXTRA_TEXT).orEmpty()
        SplitWordsScreen(text = text, onDone = { finish() })
    }
}
