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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
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
import com.jobeen.ime.skin.symbol.EmojiCustomConfig
import com.jobeen.ime.skin.symbol.EmojiCustomOps
import com.jobeen.ime.skin.symbol.SharedPrefsEmojiCustomStore

/**
 * 自定义 Emoji 键盘编辑区（WO52 7.4，Emoji 设置页内）：
 *
 * - 未启用时只有说明＋“使用自定义分组”按钮（首次启用即以内置 9 组为起点）；
 * - 启用后每组一节：分组名输入框（改名即存）＋上移／下移／删除，
 *   其下 FlowRow 列符号（点 × 删除），再一行新增符号输入框；
 * - 底部新建分组＋恢复默认；全部改动即改即存，下次打开面板生效。
 * 排布（411dp 真机宽）：分组名输入框占剩余宽，上移／下移／删除
 * 各 48dp，一行共 144dp＋输入框最小约 200dp，放得下。
 */
@Composable
fun EmojiCustomSection() {
    val context = LocalContext.current
    val store = remember { SharedPrefsEmojiCustomStore(context) }
    var config by remember { mutableStateOf(store.load()) }
    var newGroupName by remember { mutableStateOf("") }

    fun persist(next: EmojiCustomConfig?) {
        config = next
        store.save(next)
    }

    Text(
        text = stringResource(R.string.emoji_custom_title),
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(start = 4.dp, top = 4.dp),
    )
    Text(
        text = stringResource(R.string.emoji_custom_desc),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 4.dp, top = 4.dp),
    )
    if (config == null) {
        TextButton(
            onClick = { persist(EmojiCustomOps.defaultConfig()) },
        ) {
            Text(stringResource(R.string.emoji_custom_enable))
        }
        return
    }
    val current = config!!
    Spacer(Modifier.height(8.dp))
    current.groups.forEachIndexed { groupIndex, group ->
        EmojiCustomGroupEditor(
            label = group.label,
            symbols = group.symbols,
            onRename = { name ->
                persist(EmojiCustomOps.renameGroup(current, groupIndex, name))
            },
            onMoveUp = { persist(EmojiCustomOps.moveGroup(current, groupIndex, groupIndex - 1)) },
            onMoveDown = { persist(EmojiCustomOps.moveGroup(current, groupIndex, groupIndex + 1)) },
            onDeleteGroup = { persist(EmojiCustomOps.deleteGroup(current, groupIndex)) },
            onAddSymbol = { symbol ->
                persist(EmojiCustomOps.addSymbol(current, groupIndex, symbol))
            },
            onDeleteSymbol = { symbolIndex ->
                persist(EmojiCustomOps.removeSymbol(current, groupIndex, symbolIndex))
            },
            isFirst = groupIndex == 0,
            isLast = groupIndex == current.groups.lastIndex,
        )
        Spacer(Modifier.height(12.dp))
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(),
    ) {
        OutlinedTextField(
            value = newGroupName,
            onValueChange = { newGroupName = it },
            singleLine = true,
            placeholder = { Text(stringResource(R.string.emoji_custom_group_name_hint)) },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = {
                val next = EmojiCustomOps.addGroup(current, newGroupName)
                if (next != current) {
                    persist(next)
                    newGroupName = ""
                }
            }),
            modifier = Modifier.weight(1f),
        )
        TextButton(
            onClick = {
                val next = EmojiCustomOps.addGroup(current, newGroupName)
                if (next != current) {
                    persist(next)
                    newGroupName = ""
                }
            },
            enabled = newGroupName.isNotBlank(),
        ) {
            Text(stringResource(R.string.emoji_custom_add_group))
        }
    }
    TextButton(
        onClick = { persist(null) },
    ) {
        Text(stringResource(R.string.emoji_custom_restore))
    }
}

/** 一组编辑器：改名框＋上下移／删除＋符号 FlowRow＋新增符号框。 */
@Composable
private fun EmojiCustomGroupEditor(
    label: String,
    symbols: List<String>,
    onRename: (String) -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onDeleteGroup: () -> Unit,
    onAddSymbol: (String) -> Unit,
    onDeleteSymbol: (Int) -> Unit,
    isFirst: Boolean,
    isLast: Boolean,
) {
    var draft by remember(label) { mutableStateOf("") }
    Column {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            OutlinedTextField(
                value = label,
                onValueChange = onRename,
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onMoveUp, enabled = !isFirst) {
                Icon(
                    Icons.Filled.KeyboardArrowUp,
                    contentDescription = stringResource(R.string.toolbar_move_up),
                )
            }
            IconButton(onClick = onMoveDown, enabled = !isLast) {
                Icon(
                    Icons.Filled.KeyboardArrowDown,
                    contentDescription = stringResource(R.string.toolbar_move_down),
                )
            }
            IconButton(onClick = onDeleteGroup) {
                Icon(
                    Icons.Filled.Delete,
                    contentDescription = stringResource(R.string.emoji_custom_delete_group),
                    tint = MaterialTheme.colorScheme.error,
                )
            }
        }
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 4.dp, top = 8.dp),
        ) {
            symbols.forEachIndexed { index, symbol ->
                TextButton(
                    onClick = { onDeleteSymbol(index) },
                    modifier = Modifier.padding(0.dp),
                ) {
                    Text("$symbol ×", style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                singleLine = true,
                placeholder = { Text(stringResource(R.string.emoji_custom_add_symbol_hint)) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = {
                    if (draft.isNotBlank()) {
                        onAddSymbol(draft)
                        draft = ""
                    }
                }),
                modifier = Modifier.weight(1f),
            )
            TextButton(
                onClick = {
                    if (draft.isNotBlank()) {
                        onAddSymbol(draft)
                        draft = ""
                    }
                },
                enabled = draft.isNotBlank(),
            ) {
                Text(stringResource(R.string.emoji_custom_add))
            }
        }
    }
}
