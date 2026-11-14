// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.ui

import androidx.compose.runtime.Composable
import com.jobeen.ime.ui.screen.EmojiSettingsScreen

/** Emoji 设置页宿主（WO52 7.1 最近使用＋重置／7.4 自定义键盘）。 */
class EmojiSettingsActivity : ImeActivity() {
    override val useEdgeToEdge = false

    @Composable
    override fun Content() {
        EmojiSettingsScreen(onBack = { finish() })
    }
}
