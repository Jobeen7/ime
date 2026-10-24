package com.jobeen.ime.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jobeen.ime.input.panel.toolbar.ToolbarConfig

/**
 * 工具栏自定义（中央区按钮的增删与排序）：
 * 即改即存（ToolbarConfig 全局偏好），键盘工具栏下次重建时生效。
 * 左端菜单/返回、右端收起为固定键不在此列；剪贴板打开时的
 * 工具栏是功能态专用栏，同样不变。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ToolbarSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var selectedIds by remember { mutableStateOf(ToolbarConfig.readIds(context)) }

    fun update(newIds: List<String>) {
        selectedIds = newIds
        ToolbarConfig.writeIds(context, newIds)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("工具栏自定义") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            item {
                Text(
                    "这里改的是键盘上方工具栏中间的一排按钮：选哪些、什么顺序，" +
                        "改完即保存，下次打开键盘生效。最左的菜单键和最右的收起键固定不动；" +
                        "剪贴板打开时的工具栏不变。",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            }

            item {
                Text(
                    "已选（${selectedIds.size}/${ToolbarConfig.MAX_ITEMS}，从左到右）",
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            if (selectedIds.isEmpty()) {
                item {
                    Text(
                        "（空：工具栏中间不放任何按钮）",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(selectedIds, key = { "sel:$it" }) { id ->
                val item = ToolbarConfig.item(id)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(item?.label ?: id, fontSize = 15.sp)
                    Row {
                        TextButton(
                            onClick = {
                                val i = selectedIds.indexOf(id)
                                if (i > 0) {
                                    update(
                                        selectedIds.toMutableList().apply {
                                            removeAt(i); add(i - 1, id)
                                        },
                                    )
                                }
                            },
                            enabled = selectedIds.indexOf(id) > 0,
                        ) { Text("上移") }
                        TextButton(
                            onClick = {
                                val i = selectedIds.indexOf(id)
                                if (i in 0 until selectedIds.lastIndex) {
                                    update(
                                        selectedIds.toMutableList().apply {
                                            removeAt(i); add(i + 1, id)
                                        },
                                    )
                                }
                            },
                            enabled = selectedIds.indexOf(id) in 0 until selectedIds.lastIndex,
                        ) { Text("下移") }
                        TextButton(
                            onClick = { update(selectedIds - id) },
                        ) { Text("移除") }
                    }
                }
            }

            item {
                Text(
                    "可添加",
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
            val addable = ToolbarConfig.POOL.filter { it.id !in selectedIds }
            if (addable.isEmpty()) {
                item {
                    Text(
                        "（全部已添加）",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(addable, key = { "add:${it.id}" }) { item ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(item.label, fontSize = 15.sp)
                    TextButton(
                        onClick = { update(selectedIds + item.id) },
                        enabled = selectedIds.size < ToolbarConfig.MAX_ITEMS,
                    ) { Text("添加") }
                }
            }

            item {
                Spacer(Modifier.height(12.dp))
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { update(ToolbarConfig.DEFAULT_IDS) },
                        enabled = selectedIds != ToolbarConfig.DEFAULT_IDS,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("恢复默认（表情 / 全选 / 复制 / 粘贴 / 剪贴板）") }
                    Spacer(Modifier.height(16.dp))
                }
            }
        }
    }
}
