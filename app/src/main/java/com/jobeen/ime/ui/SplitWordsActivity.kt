// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.ui

import android.content.Intent
import android.os.Bundle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import com.jobeen.ime.ui.screen.SplitWordsScreen

/**
 * 分词页（WO52 1.9，对端 split_string_panel）：独立页（非键盘内面板，
 * 与 AboutActivity 等设置页同口径——从键盘打开时键盘收起）。
 * 入口：剪贴板长按菜单 "Split words" 与行内 Split 钮（见 5.3）。
 * singleTop：同一页再次被打开时走 onNewIntent 更新文本重分词
 * （L-34），不重建页面。
 */
class SplitWordsActivity : ImeActivity() {

    companion object {
        const val EXTRA_TEXT = "text"
    }

    private val textState = mutableStateOf("")

    override fun onCreateBeforeContent(savedInstanceState: Bundle?) {
        super.onCreateBeforeContent(savedInstanceState)
        textState.value = intent.getStringExtra(EXTRA_TEXT).orEmpty()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // 文本更新走同一 LaunchedEffect(text) 通道重分词（与 SW-1
        // 的后台分词同批；Content 读 textState.value 即重组）。
        textState.value = intent.getStringExtra(EXTRA_TEXT).orEmpty()
    }

    @Composable
    override fun Content() {
        SplitWordsScreen(text = textState.value, onDone = { finish() })
    }
}
