package com.jobeen.ime.skin.script

import androidx.activity.compose.BackHandler
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
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import android.content.Context
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.jobeen.ime.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * App-level script management UI: list scripts with add/remove/toggle,
 * plus a test run (onEvent "test") to prove a script loads and runs.
 *
 * This screen manages scripts ONLY. It does not wire any key action to
 * any script: key bindings that run/open scripts live in the key-action
 * wiring (whitelist + runtime mapping + dispatch, see SkinScriptKeyBridge),
 * not here; test runs go through [SkinScriptRunner.runScript] on a
 * background dispatcher (never the main thread — see the runner doc).
 * The runner result value is displayed as opaque data, not interpreted
 * as an action; interpretation belongs to the key-action bridge.
 *
 * 主线程纪律：store 的 list/add/remove/setEnabled 皆为文件 I/O，
 * 一律经后台调度器执行、回主线程再刷状态；首屏列表也不在组合
 * 期间同步读盘（初始为空列表，后台载入后刷新——首帧会短暂显
 * 空态，属本改动的已知外观变化，语义与失败提示不变）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SkinScriptScreen(
    store: SkinScriptStore,
    onBack: () -> Unit,
) {
    var scripts by remember { mutableStateOf(emptyList<SkinScriptMeta>()) }
    var newId by remember { mutableStateOf("") }
    var newName by remember { mutableStateOf("") }
    var newSource by remember { mutableStateOf("") }
    var addError by remember { mutableStateOf<String?>(null) }
    var runResult by remember { mutableStateOf<Pair<String, String>?>(null) }
    var runningTestId by remember { mutableStateOf<String?>(null) }
    val runner = remember { SkinScriptRunner(store) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    fun refresh() {
        scope.launch {
            scripts = withContext(Dispatchers.IO) { store.list() }
        }
    }

    // 首屏：组合期间不读盘，后台载入后刷状态。
    LaunchedEffect(Unit) {
        scripts = withContext(Dispatchers.IO) { store.list() }
    }

    BackHandler(onBack = onBack)
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.skin_script_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
        ) {
            item {
                OutlinedTextField(
                    value = newId,
                    onValueChange = { newId = it },
                    label = { Text(stringResource(R.string.skin_script_id)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    label = { Text(stringResource(R.string.skin_script_name)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = newSource,
                    onValueChange = { newSource = it },
                    label = { Text(stringResource(R.string.skin_script_source)) },
                    modifier = Modifier.fillMaxWidth().height(160.dp),
                )
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = {
                        scope.launch {
                            val result = withContext(Dispatchers.IO) {
                                store.add(newId, newName, newSource)
                            }
                            when (result) {
                                is SkinScriptAddResult.Added -> {
                                    addError = null
                                    newId = ""
                                    newName = ""
                                    newSource = ""
                                    refresh()
                                }
                                else -> addError = addFailureText(context, result)
                            }
                        }
                    },
                ) {
                    Text(stringResource(R.string.skin_script_add))
                }
                addError?.let { error ->
                    Spacer(Modifier.height(4.dp))
                    Text(
                        stringResource(R.string.skin_script_add_failed, error),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Spacer(Modifier.height(16.dp))
            }
            if (scripts.isEmpty()) {
                item { Text(stringResource(R.string.skin_script_empty)) }
            }
            items(scripts, key = { it.id }) { meta ->
                Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            meta.name,
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.weight(1f),
                        )
                        Text(stringResource(R.string.skin_script_enabled))
                        Switch(
                            checked = meta.enabled,
                            onCheckedChange = { enabled ->
                                scope.launch {
                                    withContext(Dispatchers.IO) {
                                        store.setEnabled(meta.id, enabled)
                                    }
                                    refresh()
                                }
                            },
                        )
                    }
                    Text(meta.id, style = MaterialTheme.typography.bodySmall)
                    Row {
                        TextButton(
                            onClick = {
                                // 测试运行单飞：已有测试进行中时忽略重复
                                // 点击（按键路径的同脚本取消上一运行见
                                // KeyActionListener；管理页测试是显式点按，
                                // 忽略比取消更符合“点一次跑一次”的预期）。
                                if (runningTestId == null) {
                                    runningTestId = meta.id
                                    scope.launch {
                                        try {
                                            val result = withContext(Dispatchers.Default) {
                                                runner.runScript(meta.id, "test")
                                            }
                                            runResult = meta.id to runResultText(context, result)
                                        } finally {
                                            if (runningTestId == meta.id) runningTestId = null
                                        }
                                    }
                                }
                            },
                        ) {
                            Text(stringResource(R.string.skin_script_run_test))
                        }
                        TextButton(
                            onClick = {
                                scope.launch {
                                    withContext(Dispatchers.IO) {
                                        store.remove(meta.id)
                                    }
                                    if (runResult?.first == meta.id) runResult = null
                                    refresh()
                                }
                            },
                        ) {
                            Text(stringResource(R.string.skin_script_remove))
                        }
                    }
                    if (runResult?.first == meta.id) {
                        Text(
                            stringResource(
                                R.string.skin_script_run_result,
                                runResult?.second ?: "",
                            ),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
    }
}

private fun addFailureText(context: Context, result: SkinScriptAddResult): String = when (result) {
    is SkinScriptAddResult.Added -> ""
    SkinScriptAddResult.InvalidId -> context.getString(R.string.skin_script_invalid_id)
    SkinScriptAddResult.InvalidName -> context.getString(R.string.skin_script_invalid_name)
    SkinScriptAddResult.EmptySource -> context.getString(R.string.skin_script_empty_source)
    SkinScriptAddResult.TooLarge -> context.getString(R.string.skin_script_too_large)
    SkinScriptAddResult.AlreadyExists -> context.getString(R.string.skin_script_id_exists)
    SkinScriptAddResult.WriteFailed -> context.getString(R.string.skin_script_write_failed)
}

private fun runResultText(context: Context, result: SkinScriptResult): String = when (result) {
    is SkinScriptResult.Ok -> result.value ?: context.getString(R.string.skin_script_no_return)
    is SkinScriptResult.Skipped -> context.getString(R.string.skin_script_skipped, result.reason)
    is SkinScriptResult.Failed -> context.getString(R.string.skin_script_failed, result.failure)
}
