// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.ui.screen

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.jobeen.ime.R
import com.jobeen.ime.engine.rime.data.userdict.LevelDbBrowser
import com.jobeen.ime.engine.rime.data.userdict.LevelDbPage
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * LevelDB 查看页（WO52 12-⑭）：
 *
 * - 数据库列表（`*.userdb`）→ 点进键值浏览（只读遍历，50 条一页）；
 * - 只读，不写；列表与导出一律经 [LevelDbBrowser] 走现有 `RimeApi`
 *   路径（引擎线程串行访问），不直连 LevelDB JNI；
 * - 引擎未就绪（browser 为 null）时显示加载中，60 秒超时置失败态。
 */
@Composable
fun LevelDbScreen(
    browser: LevelDbBrowser?,
    onBack: () -> Unit,
) {
    var dicts by remember { mutableStateOf<List<String>?>(null) }
    var loadFailed by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<String?>(null) }
    var page by remember { mutableStateOf<LevelDbPage?>(null) }
    var pageFailed by remember { mutableStateOf(false) }
    var reloadSeq by remember { mutableStateOf(0) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(browser, reloadSeq) {
        val b = browser ?: return@LaunchedEffect
        loadFailed = false
        val got = withTimeoutOrNull(60_000L) {
            runCatching { b.listDatabases() }.getOrNull()
        }
        if (got == null) {
            loadFailed = true
        } else {
            dicts = got
        }
    }

    LaunchedEffect(selected) {
        val dict = selected ?: return@LaunchedEffect
        val b = browser ?: return@LaunchedEffect
        page = null
        pageFailed = false
        val got = withTimeoutOrNull(60_000L) {
            runCatching { b.browse(dict, 0) }.getOrNull()
        }
        if (got == null) {
            pageFailed = true
        } else {
            page = got
        }
    }

    suspend fun loadPage(dict: String, target: Int) {
        val b = browser ?: return
        pageFailed = false
        val got = withTimeoutOrNull(60_000L) {
            runCatching { b.browse(dict, target) }.getOrNull()
        }
        if (got == null) {
            pageFailed = true
        } else {
            page = got
        }
    }

    SettingsPageScaffold(
        title = stringResource(R.string.leveldb_title),
        onBack = {
            if (selected != null) {
                selected = null
                page = null
            } else {
                onBack()
            }
        },
    ) {
        Text(
            text = stringResource(R.string.leveldb_readonly_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp, top = 4.dp),
        )
        Spacer(Modifier.height(8.dp))
        if (browser == null) {
            Text(
                text = stringResource(R.string.leveldb_loading),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp, top = 8.dp),
            )
            return@SettingsPageScaffold
        }
        if (selected == null) {
            // 数据库列表。
            when {
                loadFailed -> {
                    Text(
                        text = stringResource(R.string.leveldb_load_failed),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(start = 4.dp, top = 8.dp),
                    )
                    TextButton(onClick = {
                        dicts = null
                        reloadSeq++
                    }) {
                        Text(stringResource(R.string.leveldb_retry))
                    }
                }
                dicts == null -> {
                    Text(
                        text = stringResource(R.string.leveldb_loading),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 4.dp, top = 8.dp),
                    )
                }
                dicts!!.isEmpty() -> {
                    Text(
                        text = stringResource(R.string.leveldb_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 4.dp, top = 8.dp),
                    )
                }
                else -> {
                    dicts!!.forEachIndexed { index, dict ->
                        TextButton(
                            onClick = { selected = dict },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                text = dict,
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                        if (index < dicts!!.lastIndex) {
                            HorizontalDivider()
                        }
                    }
                }
            }
            return@SettingsPageScaffold
        }
        // 键值浏览（分页）。
        val dict = selected!!
        Text(
            text = dict,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(start = 4.dp, top = 4.dp),
        )
        when {
            pageFailed -> {
                Text(
                    text = stringResource(R.string.leveldb_load_failed),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(start = 4.dp, top = 8.dp),
                )
                TextButton(onClick = { selected = null }) {
                    Text(stringResource(R.string.leveldb_back_to_list))
                }
            }
            page == null -> {
                Text(
                    text = stringResource(R.string.leveldb_loading),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp, top = 8.dp),
                )
            }
            else -> {
                val current = page!!
                if (current.entries.isEmpty()) {
                    Text(
                        text = stringResource(R.string.leveldb_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 4.dp, top = 8.dp),
                    )
                } else {
                    current.entries.forEachIndexed { index, entry ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 4.dp, top = 4.dp, bottom = 4.dp),
                        ) {
                            Text(
                                text = entry.key,
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                text = entry.word,
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            entry.weight?.let { weight ->
                                Text(
                                    text = weight.toString(),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        if (index < current.entries.lastIndex) {
                            HorizontalDivider()
                        }
                    }
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp),
                ) {
                    TextButton(
                        onClick = { scope.launch { loadPage(dict, current.page - 1) } },
                        enabled = current.hasPrev,
                    ) {
                        Text(stringResource(R.string.leveldb_prev_page))
                    }
                    Text(
                        text = stringResource(
                            R.string.leveldb_page_info,
                            current.page + 1,
                            current.totalPages.coerceAtLeast(1),
                            current.total,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        onClick = { scope.launch { loadPage(dict, current.page + 1) } },
                        enabled = current.hasNext,
                    ) {
                        Text(stringResource(R.string.leveldb_next_page))
                    }
                }
                TextButton(onClick = { selected = null }) {
                    Text(stringResource(R.string.leveldb_back_to_list))
                }
            }
        }
        Spacer(Modifier.height(20.dp))
    }
}
