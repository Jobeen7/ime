// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.ui.screen

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.jobeen.ime.R
import com.jobeen.ime.skin.symbol.SharedPrefsT9QuickSymbolsStore
import com.jobeen.ime.skin.symbol.T9QuickSymbolsConfig
import com.jobeen.ime.skin.symbol.T9QuickSymbolsOps

/**
 * T9 快捷符号设置页（独立 Screen，导航接线由上层统一做）：
 * 与 SymbolBarSettingsScreen 同款式（顶部输入框新增＋保存、
 * 说明、符号列表直接排在背景上，不包卡片），只差两点：
 * - 无拖动排序（列表只 12 项以内，上下移动按钮已够用，不复制
 *   拖拽 machinery）；
 * - 删到空即恢复默认 6 符号（与符号栏同口径）。
 * 改动下次打开键盘生效（弹窗随布局一次建成）。
 */
@Composable
fun T9QuickSymbolsSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val store = remember { SharedPrefsT9QuickSymbolsStore(context) }
    var config by remember { mutableStateOf(store.load()) }
    var draft by remember { mutableStateOf("") }

    fun persist(next: T9QuickSymbolsConfig) {
        config = next
        store.save(next)
    }

    fun saveDraft() {
        val next = T9QuickSymbolsOps.add(config, draft)
        if (next != config) {
            persist(next)
            draft = ""
        }
    }

    SettingsPageScaffold(
        title = stringResource(R.string.t9_quick_symbols_title),
        onBack = onBack,
    ) {
        OutlinedTextField(
            shape = MaterialTheme.shapes.medium,
            value = draft,
            onValueChange = { draft = it },
            singleLine = true,
            placeholder = { Text(stringResource(R.string.t9_quick_symbols_add_hint)) },
            trailingIcon = {
                TextButton(
                    onClick = { saveDraft() },
                    enabled = draft.isNotBlank(),
                ) {
                    Text(stringResource(R.string.t9_quick_symbols_save))
                }
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { saveDraft() }),
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            text = stringResource(R.string.t9_quick_symbols_list_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp, top = 12.dp),
        )
        Spacer(Modifier.height(8.dp))
        if (config.symbols.isEmpty()) {
            Text(
                text = stringResource(R.string.t9_quick_symbols_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp, top = 8.dp),
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
                        modifier = Modifier
                            .weight(1f)
                            .padding(start = 12.dp),
                    )
                    IconButton(
                        onClick = { persist(T9QuickSymbolsOps.move(config, index, index - 1)) },
                        enabled = index > 0,
                    ) {
                        Icon(
                            Icons.Filled.KeyboardArrowUp,
                            contentDescription = stringResource(R.string.toolbar_move_up),
                        )
                    }
                    IconButton(
                        onClick = { persist(T9QuickSymbolsOps.move(config, index, index + 1)) },
                        enabled = index < config.symbols.lastIndex,
                    ) {
                        Icon(
                            Icons.Filled.KeyboardArrowDown,
                            contentDescription = stringResource(R.string.toolbar_move_down),
                        )
                    }
                    IconButton(
                        onClick = { persist(T9QuickSymbolsOps.removeAtOrRestoreDefault(config, index)) },
                    ) {
                        Icon(
                            Icons.Filled.Delete,
                            contentDescription = stringResource(R.string.symbol_bar_delete),
                            tint = MaterialTheme.colorScheme.error,
                        )
                    }
                }
                if (index < config.symbols.lastIndex) {
                    HorizontalDivider()
                }
            }
        }
        Spacer(Modifier.height(20.dp))
    }
}
