package com.jobeen.ime.ui

import androidx.compose.runtime.Composable
import com.jobeen.ime.ui.screen.ClipboardScreen

/**
 * 剪贴板设置页宿主。
 *
 * deep link 落点：`clipboard?id=<id>` 由 `jime://` 路由携 id 调起本页
 * （[EXTRA_CLIPBOARD_ID]），本页按 id 打开历史列表并定位到该条
 * （高亮＋滚动到该条）；id 查不到时给明确提示、不崩溃；不带 id
 * 时只打开设置页（与原来一致）。
 */
class ClipboardActivity : ImeActivity() {
    @Composable
    override fun Content() {
        val clipboardId = intent.getLongExtra(EXTRA_CLIPBOARD_ID, -1L)
        ClipboardScreen(
            onBack = { finish() },
            initialClipboardId = clipboardId,
        )
    }

    companion object {
        const val EXTRA_CLIPBOARD_ID = "clipboard_id"
    }
}
