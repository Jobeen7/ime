// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.ui.screen

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.jobeen.ime.R
import com.jobeen.ime.skin.symbol.SharedPrefsUserSymbolBarStore
import com.jobeen.ime.skin.symbol.UserSymbolBarConfig
import com.jobeen.ime.skin.symbol.UserSymbolBarOps
import kotlin.math.roundToInt

/**
 * 自定义符号栏设置页（2026-11-12 符号栏重做）：
 *
 * - 无置顶：列表本身就是符号面板左侧竖栏那排符号，没东西可置顶；
 * - 顶部整行输入框（新增符号＋保存）→ 说明 → 符号列表直接排在
 *   背景上（不包卡片），每行左侧 ⋮⋮ 拖动手柄、中间符号、右侧垃圾桶；
 * - 列表默认 16 符号，不再是空表；拖动排序、删除、新增都即改即存；
 * - 改动在下次打开符号面板时生效（键盘侧每附着重建竖栏）。
 * 拖拽机制与 SchemaSettingsScreen 的 ManageEnabledList 同款
 * （长按后跟手位移、落点按行高取整、拖动时微放大 + 阴影）。
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

    fun saveDraft() {
        val next = UserSymbolBarOps.add(config, draft)
        if (next != config) {
            persist(next)
            draft = ""
        }
    }

    SettingsPageScaffold(
        title = stringResource(R.string.symbol_bar_title),
        onBack = onBack,
    ) {
        OutlinedTextField(
            value = draft,
            onValueChange = { draft = it },
            singleLine = true,
            placeholder = { Text(stringResource(R.string.symbol_bar_add_hint)) },
            trailingIcon = {
                TextButton(
                    onClick = { saveDraft() },
                    enabled = draft.isNotBlank(),
                ) {
                    Text(stringResource(R.string.symbol_bar_save))
                }
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { saveDraft() }),
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            text = stringResource(R.string.symbol_bar_list_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp, top = 12.dp),
        )
        Spacer(Modifier.height(8.dp))
        if (config.symbols.isEmpty()) {
            Text(
                text = stringResource(R.string.symbol_bar_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp, top = 8.dp),
            )
        } else {
            SymbolList(
                symbols = config.symbols,
                onMove = { from, to -> persist(UserSymbolBarOps.move(config, from, to)) },
                onDelete = { index -> persist(UserSymbolBarOps.removeAt(config, index)) },
            )
        }
        Text(
            text = stringResource(R.string.symbol_bar_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp, top = 16.dp),
        )
        Spacer(Modifier.height(20.dp))
    }
}

/** 符号列表：拖动手柄排序（长按拖动）＋垃圾桶删除。 */
@Composable
private fun SymbolList(
    symbols: List<String>,
    onMove: (fromIndex: Int, toIndex: Int) -> Unit,
    onDelete: (index: Int) -> Unit,
) {
    var dragIndex by remember { mutableIntStateOf(-1) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    var itemHeight by remember { mutableFloatStateOf(0f) }

    Column {
        symbols.forEachIndexed { index, symbol ->
            val isDragging = dragIndex == index
            val dragScale by animateFloatAsState(
                targetValue = if (isDragging) 1.03f else 1f,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioMediumBouncy,
                    stiffness = Spring.StiffnessMedium,
                ),
                label = "dragScale",
            )

            Box(
                modifier = Modifier
                    .zIndex(if (isDragging) 1f else 0f)
                    .offset { IntOffset(0, if (isDragging) dragOffset.roundToInt() else 0) }
                    .scale(dragScale)
                    .graphicsLayer {
                        this.shadowElevation = if (isDragging) 8f else 0f
                    }
                    .onGloballyPositioned {
                        if (itemHeight == 0f) itemHeight = it.size.height.toFloat()
                    }
                    .pointerInput(index) {
                        detectDragGesturesAfterLongPress(
                            onDragStart = {
                                dragIndex = index
                                dragOffset = 0f
                            },
                            onDragEnd = {
                                if (dragIndex < 0) return@detectDragGesturesAfterLongPress
                                if (itemHeight > 0f) {
                                    val target = (dragIndex + (dragOffset / itemHeight).roundToInt())
                                        .coerceIn(0, symbols.size - 1)
                                    if (target != dragIndex) {
                                        onMove(dragIndex, target)
                                    }
                                }
                                dragIndex = -1
                            },
                            onDrag = { change, amount ->
                                if (dragIndex < 0) return@detectDragGesturesAfterLongPress
                                change.consume()
                                dragOffset += amount.y
                            },
                        )
                    },
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    DragHandle()
                    Text(
                        text = symbol,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier
                            .weight(1f)
                            .padding(start = 12.dp),
                    )
                    IconButton(
                        onClick = { onDelete(index) },
                    ) {
                        Icon(
                            Icons.Filled.Delete,
                            contentDescription = stringResource(R.string.symbol_bar_delete),
                            tint = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
            if (index < symbols.lastIndex) {
                HorizontalDivider()
            }
        }
    }
}

/** 拖动手柄 ⋮⋮（两列三点），仅作拖动示意；拖动由整行接。 */
@Composable
private fun DragHandle() {
    Row(
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        modifier = Modifier.padding(start = 4.dp),
    ) {
        repeat(2) {
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                repeat(3) {
                    Box(
                        Modifier
                            .size(4.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.onSurfaceVariant),
                    )
                }
            }
        }
    }
}
