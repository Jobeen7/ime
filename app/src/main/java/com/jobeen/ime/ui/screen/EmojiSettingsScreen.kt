// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.ui.screen

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.jobeen.ime.R
import com.jobeen.ime.skin.symbol.SharedPrefsRecentEmojiStore

/**
 * Emoji 设置页（WO52 7.1／7.4）：
 *
 * - 7.1 最近使用：说明＋“重置常用 Emoji”按钮（清最近使用）；
 * - 7.4 自定义键盘：分组编辑区（见 [EmojiCustomSection]，本页内）。
 * 改动下次打开 Emoji 面板时生效（面板每次附着重建数据源）。
 */
@Composable
fun EmojiSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val recentStore = remember { SharedPrefsRecentEmojiStore(context) }
    var resetDone by remember { mutableStateOf(false) }

    SettingsPageScaffold(
        title = stringResource(R.string.emoji_title),
        onBack = onBack,
    ) {
        Text(
            text = stringResource(R.string.emoji_recent_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp, top = 4.dp),
        )
        TextButton(
            onClick = {
                recentStore.clear()
                resetDone = true
            },
        ) {
            Text(stringResource(R.string.emoji_reset_recent))
        }
        if (resetDone) {
            Text(
                text = stringResource(R.string.emoji_reset_done),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp),
            )
        }
        Text(
            text = stringResource(R.string.emoji_reset_recent_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp, top = 4.dp),
        )
        Spacer(Modifier.height(20.dp))
        EmojiCustomSection()
    }
}
