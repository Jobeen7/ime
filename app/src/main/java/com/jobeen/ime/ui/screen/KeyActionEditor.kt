// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.jobeen.ime.R
import com.jobeen.ime.skin.layout.LayoutEditorModel
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * 共享逐键编辑器（原属 LayoutEditorScreen，2026-11-16 可视化皮肤编辑器
 * 复用而抽出；行为不变）：选中键的标签／宽度／点按／长按／上划／下滑／
 * 移动／增删，外加单个动作槽编辑器（无／文字／符号／命令，命令含
 * switch_layout 目标布局＝中州鹅“目标”字段的 Jime 对应物）。
 */

/** 选中键的编辑区（标签／宽度／动作／长按／滑动／移动／增删）。 */
@Composable
fun KeyEditor(
    tree: JsonObject,
    stableId: String,
    onTreeChange: (JsonObject) -> Unit,
) {
    val key = LayoutEditorModel.keyByStableId(tree, stableId) ?: return
    val label = (key["label"] as? JsonPrimitive)?.contentOrNull ?: ""
    val weight = (key["widthWeight"] as? JsonPrimitive)?.content?.toDoubleOrNull() ?: 1.0
    val tap = key["tap"] as? JsonObject
    val longPress = (key["longPress"] as? JsonArray)
        ?.mapNotNull { it as? JsonObject } ?: emptyList()
    val swipeUp = key["swipeUp"] as? JsonObject
    val swipeDown = key["swipeDown"] as? JsonObject
    val loc = LayoutEditorModel.findKey(tree, stableId)
    val rows = LayoutEditorModel.rows(tree)

    fun update(transformed: JsonObject?) {
        if (transformed != null) onTreeChange(transformed)
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            shape = MaterialTheme.shapes.medium,
            value = label,
            onValueChange = { update(LayoutEditorModel.setLabel(tree, stableId, it)) },
            label = { Text(stringResource(R.string.layout_editor_label)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        // 宽度：滑块粗调（0.25–4，常规键全覆盖）＋数字框精调（校验域 0.05–20）。
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(stringResource(R.string.layout_editor_width))
            Slider(
                value = weight.toFloat().coerceIn(0.25f, 4f),
                onValueChange = {
                    val rounded = (it * 100).toInt() / 100.0
                    update(LayoutEditorModel.setWeight(tree, stableId, rounded))
                },
                valueRange = 0.25f..4f,
                modifier = Modifier.weight(1f),
            )
            OutlinedTextField(
                shape = MaterialTheme.shapes.medium,
                value = trimWeight(weight),
                onValueChange = { text ->
                    text.toDoubleOrNull()?.let {
                        update(LayoutEditorModel.setWeight(tree, stableId, it))
                    }
                },
                singleLine = true,
                modifier = Modifier.width(84.dp),
            )
        }
        ActionEditor(
            label = stringResource(R.string.layout_editor_tap),
            action = tap,
            onChange = { update(LayoutEditorModel.setTap(tree, stableId, it)) },
        )
        // 长按动作列。
        Text(stringResource(R.string.layout_editor_long_press))
        for ((index, action) in longPress.withIndex()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(modifier = Modifier.weight(1f)) {
                    ActionEditor(
                        label = "${index + 1}",
                        action = action,
                        onChange = { newAction ->
                            val newList = longPress.toMutableList()
                            if (newAction == null) newList.removeAt(index)
                            else newList[index] = newAction
                            update(
                                LayoutEditorModel.setLongPress(tree, stableId, newList),
                            )
                        },
                    )
                }
                TextButton(
                    onClick = {
                        val newList = longPress.toMutableList().apply { removeAt(index) }
                        update(LayoutEditorModel.setLongPress(tree, stableId, newList))
                    },
                ) {
                    Text(stringResource(R.string.delete))
                }
            }
        }
        OutlinedButton(
            onClick = {
                update(
                    LayoutEditorModel.setLongPress(
                        tree, stableId, longPress + LayoutEditorModel.actionText(""),
                    ),
                )
            },
        ) {
            Text(stringResource(R.string.layout_editor_add_long_press))
        }
        ActionEditor(
            label = stringResource(R.string.layout_editor_swipe_up),
            action = swipeUp,
            onChange = {
                update(LayoutEditorModel.setSwipe(tree, stableId, "swipeUp", it))
            },
        )
        ActionEditor(
            label = stringResource(R.string.layout_editor_swipe_down),
            action = swipeDown,
            onChange = {
                update(LayoutEditorModel.setSwipe(tree, stableId, "swipeDown", it))
            },
        )
        // 移动／增删。
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val canLeft = loc != null && loc.keyIndex > 0
            val canRight = loc != null && loc.keyIndex < rows[loc.rowIndex].size - 1
            val canUp = loc != null && loc.rowIndex > 0
            val canDown = loc != null && loc.rowIndex < rows.size - 1
            OutlinedButton(
                onClick = {
                    update(
                        LayoutEditorModel.moveKey(
                            tree, stableId, LayoutEditorModel.MoveDirection.LEFT,
                        ),
                    )
                },
                enabled = canLeft,
            ) { Text(stringResource(R.string.layout_editor_move_left)) }
            OutlinedButton(
                onClick = {
                    update(
                        LayoutEditorModel.moveKey(
                            tree, stableId, LayoutEditorModel.MoveDirection.RIGHT,
                        ),
                    )
                },
                enabled = canRight,
            ) { Text(stringResource(R.string.layout_editor_move_right)) }
            OutlinedButton(
                onClick = {
                    update(
                        LayoutEditorModel.moveKey(
                            tree, stableId, LayoutEditorModel.MoveDirection.UP,
                        ),
                    )
                },
                enabled = canUp,
            ) { Text(stringResource(R.string.layout_editor_move_up)) }
            OutlinedButton(
                onClick = {
                    update(
                        LayoutEditorModel.moveKey(
                            tree, stableId, LayoutEditorModel.MoveDirection.DOWN,
                        ),
                    )
                },
                enabled = canDown,
            ) { Text(stringResource(R.string.layout_editor_move_down)) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = {
                    val rowIndex = loc?.rowIndex ?: return@OutlinedButton
                    update(LayoutEditorModel.addKey(tree, rowIndex, "键"))
                },
            ) { Text(stringResource(R.string.layout_editor_add_key)) }
            OutlinedButton(
                onClick = { update(LayoutEditorModel.addRow(tree)) },
                enabled = rows.size < 8,
            ) { Text(stringResource(R.string.layout_editor_add_row)) }
            TextButton(
                onClick = {
                    update(LayoutEditorModel.removeKey(tree, stableId))
                },
            ) {
                Text(
                    stringResource(R.string.layout_editor_delete_key),
                    color = MaterialTheme.colorScheme.error,
                )
            }
            TextButton(
                onClick = {
                    val rowIndex = loc?.rowIndex ?: return@TextButton
                    update(LayoutEditorModel.removeRow(tree, rowIndex))
                },
                enabled = rows.size > 1,
            ) {
                Text(
                    stringResource(R.string.layout_editor_delete_row),
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

fun trimWeight(weight: Double): String {
    val rounded = (weight * 100).toInt() / 100.0
    return if (rounded == rounded.toLong().toDouble()) {
        rounded.toLong().toString()
    } else {
        rounded.toString()
    }
}

/** 单个动作槽编辑器（无／文字／符号／命令）。 */
@Composable
fun ActionEditor(
    label: String,
    action: JsonObject?,
    onChange: (JsonObject?) -> Unit,
) {
    val type = (action?.get("type") as? JsonPrimitive)?.contentOrNull
    val value = (action?.get("value") as? JsonPrimitive)?.contentOrNull ?: ""
    val commandName = (action?.get("action") as? JsonPrimitive)?.contentOrNull ?: ""
    var typeExpanded by remember { mutableStateOf(false) }
    var commandExpanded by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(label, modifier = Modifier.width(72.dp))
            Box {
                OutlinedButton(onClick = { typeExpanded = true }) {
                    Text(actionTypeLabel(type, commandName))
                }
                DropdownMenu(
                    expanded = typeExpanded,
                    onDismissRequest = { typeExpanded = false },
                ) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.layout_editor_action_none)) },
                        onClick = { typeExpanded = false; onChange(null) },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.layout_editor_action_text)) },
                        onClick = {
                            typeExpanded = false
                            onChange(LayoutEditorModel.actionText(value))
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.layout_editor_action_symbol)) },
                        onClick = {
                            typeExpanded = false
                            onChange(LayoutEditorModel.actionSymbol(value))
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.layout_editor_action_command)) },
                        onClick = {
                            typeExpanded = false
                            onChange(LayoutEditorModel.actionCommand(COMMAND_OPTIONS.first().first))
                        },
                    )
                }
            }
        }
        when (type) {
            "text", "symbol" -> {
                OutlinedTextField(
                    shape = MaterialTheme.shapes.medium,
                    value = value,
                    onValueChange = {
                        onChange(
                            if (type == "text") LayoutEditorModel.actionText(it)
                            else LayoutEditorModel.actionSymbol(it),
                        )
                    },
                    label = { Text(stringResource(R.string.layout_editor_action_value)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            "command" -> {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Box {
                        OutlinedButton(onClick = { commandExpanded = true }) {
                            Text(commandDisplayName(commandName))
                        }
                        DropdownMenu(
                            expanded = commandExpanded,
                            onDismissRequest = { commandExpanded = false },
                        ) {
                            for ((name, display) in COMMAND_OPTIONS) {
                                DropdownMenuItem(
                                    text = { Text(display) },
                                    onClick = {
                                        commandExpanded = false
                                        onChange(
                                            LayoutEditorModel.actionCommand(
                                                name,
                                                if (name in COMMANDS_REQUIRING_VALUE) value else null,
                                            ),
                                        )
                                    },
                                )
                            }
                        }
                    }
                    if (commandName in COMMANDS_REQUIRING_VALUE) {
                        OutlinedTextField(
                            shape = MaterialTheme.shapes.medium,
                            value = value,
                            onValueChange = {
                                onChange(LayoutEditorModel.actionCommand(commandName, it))
                            },
                            label = { Text(stringResource(R.string.layout_editor_action_value)) },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
            else -> Unit
        }
    }
}

@Composable
fun actionTypeLabel(type: String?, commandName: String): String = when (type) {
    "text" -> stringResource(R.string.layout_editor_action_text)
    "symbol" -> stringResource(R.string.layout_editor_action_symbol)
    "command" -> commandDisplayName(commandName)
    else -> stringResource(R.string.layout_editor_action_none)
}

fun commandDisplayName(name: String): String =
    COMMAND_OPTIONS.firstOrNull { it.first == name }?.second ?: name

/** 动作选择器常用命令（formatName → 显示名；其余命令走文本编辑器）。 */
val COMMAND_OPTIONS = listOf(
    "enter" to "回车",
    "delete" to "删除",
    "space" to "空格",
    "caps" to "大写锁定",
    "cursor_left" to "光标左移",
    "cursor_right" to "光标右移",
    "literal_newline" to "换行",
    "hide_keyboard" to "收起键盘",
    "voice_input" to "语音输入",
    "toggle_ascii_mode" to "中英切换",
    "clear" to "清空",
    "undo" to "撤销",
    "switch_layout" to "切换布局",
    "switch_schema" to "切换方案",
    "commit_direct" to "直接提交",
)

/** 需填内容的命令（ValueRule.REQUIRED）。 */
val COMMANDS_REQUIRING_VALUE = setOf("switch_layout", "switch_schema", "commit_direct")
