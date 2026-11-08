// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.ui.screen

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jobeen.ime.R
import com.jobeen.ime.skin.symbol.KeySymbolOps
import com.jobeen.ime.skin.symbol.SharedPrefsKeySymbolStore
import com.jobeen.ime.ui.screen.ScreenComponent.SettingsGroup

/**
 * 按键符号设置页（施工单 10 H2-14，CyIME D25/H3）：
 *
 * - 迷你键盘预览（与出厂 26 键三行一致）：点按一键弹框改它的角标符号，
 *   改完即写偏好，无保存按钮；已改的键描边标出。
 * - 角标只容一字（多字会溢出裁切，见 KeySymbolOps.isValidSymbol）。
 * - 恢复默认：弹框内恢复本键、页内全部恢复默认；只清覆盖，不动出厂表。
 * - 生效：下次打开 26 键键盘（服务侧偏好监听走重建）。
 */
@Composable
fun KeySymbolSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val store = remember { SharedPrefsKeySymbolStore(context) }
    var overrides by remember { mutableStateOf(store.load()) }
    var editing by remember { mutableStateOf<Char?>(null) }

    fun persist(next: Map<Char, String>) {
        overrides = next
        store.save(next)
    }

    SettingsPageScaffold(
        title = stringResource(R.string.key_symbol_title),
        onBack = onBack,
    ) {
        SettingsGroup(title = stringResource(R.string.key_symbol_group)) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                KeySymbolOps.ROWS.forEachIndexed { rowIndex, row ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        // 第二行缩进半键、第三行缩进一键半（与出厂 26 键排版一致）。
                        if (rowIndex == 1) Spacer(Modifier.weight(0.5f))
                        if (rowIndex == 2) Spacer(Modifier.weight(1.5f))
                        row.forEach { letter ->
                            KeySymbolKey(
                                letter = letter,
                                symbol = KeySymbolOps.resolve(letter, overrides).orEmpty(),
                                customized = letter in overrides,
                                onClick = { editing = letter },
                                modifier = Modifier.weight(1f),
                            )
                        }
                        if (rowIndex == 1) Spacer(Modifier.weight(0.5f))
                        if (rowIndex == 2) Spacer(Modifier.weight(1.5f))
                    }
                }
            }
            if (overrides.isNotEmpty()) {
                Text(
                    text = stringResource(R.string.key_symbol_modified, overrides.size),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp, top = 4.dp),
                )
            }
        }
        Button(
            onClick = { persist(KeySymbolOps.reset()) },
            enabled = overrides.isNotEmpty(),
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
        ) {
            Text(stringResource(R.string.key_symbol_reset_all))
        }
        Text(
            text = stringResource(R.string.key_symbol_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp, top = 4.dp),
        )
        Spacer(Modifier.height(20.dp))
    }

    val letter = editing
    if (letter != null) {
        KeySymbolEditDialog(
            letter = letter,
            current = KeySymbolOps.resolve(letter, overrides).orEmpty(),
            customized = letter in overrides,
            onReset = {
                persist(KeySymbolOps.set(overrides, letter, KeySymbolOps.DEFAULT_SYMBOLS.getValue(letter)))
                editing = null
            },
            onSave = { symbol ->
                persist(KeySymbolOps.set(overrides, letter, symbol))
                editing = null
            },
            onDismiss = { editing = null },
        )
    }
}

/** 迷你键盘一键：主显字母居中、角标符号右上角小字；已改的键描边标出。 */
@Composable
private fun KeySymbolKey(
    letter: Char,
    symbol: String,
    customized: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .height(52.dp)
            .border(
                width = 1.dp,
                color = if (customized) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.outline
                },
                shape = RoundedCornerShape(8.dp),
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = letter.toString(),
            style = MaterialTheme.typography.bodyLarge,
        )
        Text(
            text = symbol,
            fontSize = 10.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 4.dp, end = 6.dp),
        )
    }
}

/** 改键弹框：只容一字输入；非法（空／多字）时保存不可点。 */
@Composable
private fun KeySymbolEditDialog(
    letter: Char,
    current: String,
    customized: Boolean,
    onReset: () -> Unit,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var draft by remember { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.key_symbol_edit_title, letter.toString())) },
        text = {
            Column {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { if (it.length <= 1) draft = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.key_symbol_edit_hint)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                if (customized) {
                    TextButton(
                        onClick = onReset,
                        modifier = Modifier.padding(top = 8.dp),
                    ) {
                        Text(stringResource(R.string.reset_default))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(draft) },
                enabled = KeySymbolOps.isValidSymbol(draft),
            ) {
                Text(stringResource(R.string.key_symbol_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}
