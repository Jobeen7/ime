// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.ui.screen

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
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
import com.jobeen.ime.skin.symbol.SharedPrefsUserSymbolBarStore
import com.jobeen.ime.skin.symbol.UserSymbolBarConfig
import com.jobeen.ime.skin.symbol.UserSymbolBarOps
import com.jobeen.ime.ui.screen.ScreenComponent.ActionRow
import com.jobeen.ime.ui.screen.ScreenComponent.SettingsGroup

/**
 * 自定义符号栏设置页（施工单 10 H2-5，CyIME R11）：
 *
 * - 置顶开关：标题＋一行说明＋右侧 Switch（设置开关家规）；
 * - 自定义符号列：输入添加、逐项上移／下移／删除；
 * - 改动即写偏好，符号面板下次打开即生效（键盘侧每附着重建条）。
 */
@Composable
fun SymbolBarSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val store = remember { SharedPrefsUserSymbolBarStore(context) }
    var config by remember { mutableStateOf(store.load()) }
    var draft by remember { mutableStateOf("") }

    fun persist(next: UserSymbolBarConfig) {
        config = next
        store.save(next)
    }

    SettingsPageScaffold(
        title = stringResource(R.string.symbol_bar_title),
        onBack = onBack,
    ) {
        SettingsGroup(title = stringResource(R.string.symbol_bar_pin_group)) {
            ActionRow(
                title = stringResource(R.string.symbol_bar_pin),
                subtitle = stringResource(R.string.symbol_bar_pin_desc),
                trailing = {
                    Switch(
                        checked = config.pinned,
                        onCheckedChange = { persist(UserSymbolBarOps.setPinned(config, it)) },
                    )
                },
            )
        }
        SettingsGroup(title = stringResource(R.string.symbol_bar_custom)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.symbol_bar_add_hint)) },
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = {
                        val next = UserSymbolBarOps.add(config, draft)
                        if (next != config) {
                            persist(next)
                            draft = ""
                        }
                    },
                    enabled = draft.isNotBlank(),
                ) {
                    Text(stringResource(R.string.symbol_bar_add))
                }
            }
            if (config.symbols.isEmpty()) {
                Text(
                    text = stringResource(R.string.symbol_bar_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            } else {
                config.symbols.forEachIndexed { index, symbol ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = symbol,
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(
                            onClick = {
                                persist(UserSymbolBarOps.move(config, index, index - 1))
                            },
                            enabled = index > 0,
                        ) {
                            Icon(
                                Icons.Filled.ArrowUpward,
                                contentDescription = stringResource(R.string.symbol_bar_move_up),
                            )
                        }
                        IconButton(
                            onClick = {
                                persist(UserSymbolBarOps.move(config, index, index + 1))
                            },
                            enabled = index < config.symbols.lastIndex,
                        ) {
                            Icon(
                                Icons.Filled.ArrowDownward,
                                contentDescription = stringResource(R.string.symbol_bar_move_down),
                            )
                        }
                        IconButton(
                            onClick = { persist(UserSymbolBarOps.removeAt(config, index)) },
                        ) {
                            Icon(
                                Icons.Filled.Delete,
                                contentDescription = stringResource(R.string.symbol_bar_delete),
                            )
                        }
                    }
                }
            }
        }
        Text(
            text = stringResource(R.string.symbol_bar_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp, top = 4.dp),
        )
        Spacer(Modifier.height(20.dp))
    }
}
