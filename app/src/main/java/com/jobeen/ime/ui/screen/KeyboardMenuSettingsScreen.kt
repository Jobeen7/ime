// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.jobeen.ime.R
import com.jobeen.ime.input.panel.menu.KeyboardMenuConfigOps
import com.jobeen.ime.input.panel.menu.KeyboardMenuEntries
import com.jobeen.ime.input.panel.menu.SharedPrefsKeyboardMenuStore

/**
 * 键盘菜单管理页（WO52 12-⑨，复用工具栏／符号栏模式，不另写一套管理 UI）：
 *
 * - 列表即菜单顺序：每行标签＋上移／下移／删除（符号栏设置页同款行）；
 * - 下方“可添加”列出未进菜单的入口，点即追加到末尾；
 * - 全部改动即改即存，下次打开键盘菜单时生效；删光后菜单为空
 *   （C-L-40，不再回落默认），底部“恢复默认”回 16 项。
 * 排布（411dp 真机宽）：标签占剩余宽，上移／下移／删除各 48dp，
 * 一行共 144dp＋标签，放得下。
 */
@Composable
fun KeyboardMenuSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val store = remember { SharedPrefsKeyboardMenuStore(context) }
    var ids by remember { mutableStateOf(store.load()) }

    fun persist(next: List<String>) {
        ids = KeyboardMenuConfigOps.resolve(next)
        store.save(ids)
    }

    SettingsPageScaffold(
        title = stringResource(R.string.keyboard_menu_title),
        onBack = onBack,
    ) {
        Text(
            text = stringResource(R.string.keyboard_menu_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp, top = 4.dp),
        )
        Spacer(Modifier.height(8.dp))
        Column {
            ids.forEachIndexed { index, id ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(KeyboardMenuEntries.labelRes(id)),
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier
                            .weight(1f)
                            .padding(start = 12.dp),
                    )
                    IconButton(
                        onClick = { persist(KeyboardMenuConfigOps.move(ids, index, index - 1)) },
                        enabled = index > 0,
                    ) {
                        Icon(
                            Icons.Filled.KeyboardArrowUp,
                            contentDescription = stringResource(R.string.toolbar_move_up),
                        )
                    }
                    IconButton(
                        onClick = { persist(KeyboardMenuConfigOps.move(ids, index, index + 1)) },
                        enabled = index < ids.lastIndex,
                    ) {
                        Icon(
                            Icons.Filled.KeyboardArrowDown,
                            contentDescription = stringResource(R.string.toolbar_move_down),
                        )
                    }
                    IconButton(
                        onClick = { persist(KeyboardMenuConfigOps.remove(ids, id)) },
                    ) {
                        Icon(
                            Icons.Filled.Delete,
                            contentDescription = stringResource(R.string.symbol_bar_delete),
                            tint = MaterialTheme.colorScheme.error,
                        )
                    }
                }
                if (index < ids.lastIndex) {
                    HorizontalDivider()
                }
            }
        }
        val addable = KeyboardMenuConfigOps.defaultIds().filter { it !in ids }
        if (addable.isNotEmpty()) {
            Text(
                text = stringResource(R.string.keyboard_menu_addable),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp, top = 16.dp),
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 4.dp, top = 8.dp),
            ) {
                addable.forEach { id ->
                    TextButton(
                        onClick = { persist(KeyboardMenuConfigOps.add(ids, id)) },
                    ) {
                        Text(stringResource(KeyboardMenuEntries.labelRes(id)))
                    }
                }
            }
        }
        Text(
            text = stringResource(R.string.keyboard_menu_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp, top = 16.dp),
        )
        // C-L-40：恢复默认入口（删光后菜单为空，点此回 16 项默认菜单）。
        TextButton(
            onClick = { persist(KeyboardMenuConfigOps.defaultIds()) },
            modifier = Modifier.padding(start = 4.dp, top = 8.dp),
        ) {
            Text(stringResource(R.string.reset_default))
        }
        Spacer(Modifier.height(20.dp))
    }
}
