package com.jobeen.ime.ui.screen

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.clickable
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.draw.scale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.jobeen.ime.R
import com.jobeen.ime.base.speech.cloud.BuiltinPlugins
import com.jobeen.ime.base.speech.cloud.KeystoreSecretStore
import com.jobeen.ime.base.speech.cloud.PluginContract
import com.jobeen.ime.base.speech.cloud.PluginImportError
import com.jobeen.ime.base.speech.cloud.PluginMeta
import com.jobeen.ime.base.speech.cloud.PluginMetaDiff
import com.jobeen.ime.base.speech.cloud.PluginSettingDecl
import com.jobeen.ime.base.speech.cloud.PluginStore
import com.jobeen.ime.base.speech.cloud.SettingType
import com.jobeen.ime.base.speech.cloud.SharedPrefsStore
import com.jobeen.ime.ui.screen.ScreenComponent.GroupCard
import com.jobeen.ime.ui.screen.ScreenComponent.NavRow
import com.jobeen.ime.ui.screen.ScreenComponent.SettingsGroup
import com.jobeen.ime.ui.screen.ScreenComponent.SwitchRow
import com.jobeen.ime.ui.screen.ScreenComponent.barFontSize
import com.jobeen.ime.ui.screen.ScreenComponent.rowSubFontSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/** 导入失败原因码 → 界面文案（只露原因，不露脚本与密钥）。 */
@Composable
private fun importErrorText(error: PluginImportError): String = stringResource(
    when (error) {
        PluginImportError.TOO_LARGE -> R.string.cloud_voice_error_too_large
        PluginImportError.LOAD_FAILED -> R.string.cloud_voice_error_load_failed
        PluginImportError.NO_PLUGIN -> R.string.cloud_voice_error_no_plugin
        PluginImportError.NO_VOICE_FEATURE -> R.string.cloud_voice_error_no_voice_feature
        PluginImportError.INVALID_ID -> R.string.cloud_voice_error_invalid_id
        PluginImportError.RESERVED_ID -> R.string.cloud_voice_error_reserved_id
        PluginImportError.NO_HOSTS -> R.string.cloud_voice_error_no_hosts
        PluginImportError.INVALID_HOSTS -> R.string.cloud_voice_error_invalid_hosts
        PluginImportError.INVALID_SETTINGS -> R.string.cloud_voice_error_invalid_settings
        PluginImportError.INVALID_AUTH -> R.string.cloud_voice_error_invalid_auth
        PluginImportError.UNSUPPORTED_SAMPLE_RATE -> R.string.cloud_voice_error_unsupported_sample_rate
    },
)

/** 腾讯类别→引擎标签表（仅标签，引擎 id 解析在插件脚本内；与脚本表同序）。 */
private val tencentCategoryEngines: Map<String, List<String>> = mapOf(
    "大模型 2.0 · 流式" to listOf("中英语言", "Hy-ASR-3.0-preview"),
    "大模型 1.0 · 流式" to listOf("中英语言", "多语言", "英语通用"),
    "标准版 · 流式" to listOf(
        "中文通用", "中文繁体", "中文教育", "中文医疗", "中文法庭", "粤语",
        "英语通用", "英语游戏", "英语教育", "韩语", "日语", "泰语", "印尼语",
        "越南语", "马来语", "菲律宾语", "葡萄牙语", "土耳其语", "阿拉伯语",
        "西班牙语", "印地语", "法语", "德语",
    ),
    "大模型 · 极速文件" to listOf("中英语言", "多语言"),
    "标准版 · 极速文件" to listOf(
        "中文通用", "中英粤混合", "粤语", "英语通用", "日语", "韩语",
        "越南语", "马来语", "印尼语", "菲律宾语", "泰语", "葡萄牙语",
        "土耳其语", "阿拉伯语", "西班牙语", "印地语", "法语", "德语",
    ),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CloudVoicePluginsScreen(
    onBack: () -> Unit,
    /**
     * 从服务商对话框点在线服务商带入的目标插件 id（可空＝普通打开）。
     * 种子安装完成后，若该插件任一密钥字段未填，自动打开密钥对话框；
     * 已填齐则只落在插件页，不自动开设置对话框、不自动设首选
     * （只保证入口链，不替用户做选择）。
     */
    targetPluginId: String? = null,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val prefs = remember { SharedPrefsStore(context, PluginStore.PREFS_NAME) }
    val store = remember { PluginStore(context.filesDir, prefs) }
    val secrets = remember { KeystoreSecretStore(prefs) }

    var plugins by remember { mutableStateOf(store.list()) }
    var preferred by remember { mutableStateOf(store.getPreferred()) }
    var optIn by remember { mutableStateOf(store.isOptIn()) }
    var status by remember { mutableStateOf<String?>(null) }
    var statusIsError by remember { mutableStateOf(false) }

    // 页态：详情页插件（空＝列表页）＋详情页设置快照（写后即刷新）。
    // 对话框态：待确认导入／待删插件／密钥对话框插件／行输入／行单选。
    var pendingBytes by remember { mutableStateOf<ByteArray?>(null) }
    var pendingMeta by remember { mutableStateOf<PluginMeta?>(null) }
    var pendingDiff by remember { mutableStateOf<PluginMetaDiff?>(null) }
    var deleteTarget by remember { mutableStateOf<PluginMeta?>(null) }
    var keysTarget by remember { mutableStateOf<PluginMeta?>(null) }
    var detailPlugin by remember { mutableStateOf<PluginMeta?>(null) }
    var detailSettings by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var textDecl by remember { mutableStateOf<PluginSettingDecl?>(null) }
    var selectDecl by remember { mutableStateOf<PluginSettingDecl?>(null) }
    var refreshTick by remember { mutableStateOf(0) }
    var targetConsumed by remember { mutableStateOf(false) }

    fun refresh() {
        plugins = store.list()
        preferred = store.getPreferred()
        optIn = store.isOptIn()
        refreshTick++
    }

    /** 详情页写设置：即写即生效（改动当即落盘并刷新行摘要，不经过保存／取消）。 */
    fun writeDetail(key: String, value: String) {
        val detail = detailPlugin ?: return
        store.setSetting(detail.id, key, value)
        detailSettings = store.getSettings(detail.id)
        refresh()
    }

    /** 腾讯两级联动：类别行选项固定 5 类；引擎行选项随当前类别过滤。 */
    fun tencentEnginesFor(category: String): List<String> =
        tencentCategoryEngines[category] ?: emptyList()

    /** 行单选落值：腾讯类别切换时引擎重置为该类首项；类别未定时选引擎连带定类别。 */
    fun applySelect(decl: PluginSettingDecl, option: String) {
        val detail = detailPlugin ?: return
        if (detail.id == "builtin.tencent" && decl.key == "category") {
            writeDetail("category", option)
            val first = tencentCategoryEngines[option]?.firstOrNull()
            if (first != null) writeDetail("engine", first)
        } else if (detail.id == "builtin.tencent" && decl.key == "engine") {
            val currentCat = detailSettings["category"] ?: ""
            if (tencentCategoryEngines[currentCat]?.contains(option) != true) {
                val containing = tencentCategoryEngines.entries
                    .firstOrNull { option in it.value }?.key
                if (containing != null) writeDetail("category", containing)
            }
            writeDetail("engine", option)
        } else {
            writeDetail(decl.key, option)
        }
        selectDecl = null
    }

    fun openDetail(plugin: PluginMeta) {
        detailPlugin = plugin
        detailSettings = store.getSettings(plugin.id)
    }

    // 内置插件首次打开即种子安装（IO 线程），装完刷新列表。
    LaunchedEffect(Unit) {
        val changed = withContext(Dispatchers.IO) {
            BuiltinPlugins.ensure(store) { name ->
                runCatching { context.assets.open(name).use { it.readBytes() } }.getOrNull()
            }
        }
        if (changed.isNotEmpty()) refresh()
        // 目标插件入口链：种子安装完成后落在目标插件详情页；
        // 缺密钥才自动开密钥框，已填齐不自动开框、不自动设首选。
        if (!targetConsumed && targetPluginId != null) {
            targetConsumed = true
            val target = store.list().singleOrNull { it.id == targetPluginId }
            if (target != null) {
                openDetail(target)
                val missing = target.settings.any { decl ->
                    decl.type == SettingType.SECRET && !secrets.has(target.id, decl.key)
                }
                if (missing) keysTarget = target
            }
        }
    }

    fun showStatus(message: String, error: Boolean = false) {
        status = message
        statusIsError = error
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val bytes = withContext(Dispatchers.IO) {
                runCatching {
                    val stream = context.contentResolver.openInputStream(uri) ?: return@runCatching null
                    stream.use { input ->
                        // 有界读取：超 1MB 即停，绝不把超大文件完整载入内存。
                        val out = ByteArrayOutputStream()
                        val buffer = ByteArray(8192)
                        var total = 0
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            total += read
                            if (total > PluginContract.MAX_SCRIPT_BYTES) {
                                out.write(buffer, 0, read)
                                break
                            }
                            out.write(buffer, 0, read)
                        }
                        out.toByteArray()
                    }
                }.getOrNull()
            }
            if (bytes == null) {
                showStatus(context.getString(R.string.cloud_voice_error_load_failed), error = true)
                return@launch
            }
            if (bytes.size > PluginContract.MAX_SCRIPT_BYTES) {
                showStatus(context.getString(R.string.cloud_voice_error_too_large), error = true)
                return@launch
            }
            when (val result = withContext(Dispatchers.IO) { store.importScript(bytes) }) {
                is PluginStore.ImportResult.Installed -> {
                    showStatus(
                        context.getString(
                            if (result.upgraded) {
                                R.string.cloud_voice_upgraded
                            } else {
                                R.string.cloud_voice_imported
                            },
                        ),
                    )
                    refresh()
                }
                is PluginStore.ImportResult.NeedsConfirmation -> {
                    pendingBytes = bytes
                    pendingMeta = result.meta
                    pendingDiff = result.diff
                }
                is PluginStore.ImportResult.Failed -> {
                    // 原因码转文案在组合内完成（需 stringResource）。
                    pendingBytes = null
                    showStatus("FAILED:${result.error.name}", error = true)
                }
            }
        }
    }

    // 详情页返回：先回列表，不直接退出。
    BackHandler(enabled = detailPlugin != null) {
        detailPlugin = null
    }

    Scaffold(
        topBar = {
            val detail = detailPlugin
            TopAppBar(
                title = {
                    Text(
                        text = if (detail != null) {
                            val displayNameRes = builtinDisplayName(detail.id)
                            if (displayNameRes != null) {
                                stringResource(displayNameRes)
                            } else {
                                detail.id
                            }
                        } else {
                            stringResource(R.string.cloud_voice_plugins)
                        },
                        fontSize = barFontSize,
                        fontWeight = FontWeight.Bold,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = {
                        if (detailPlugin != null) detailPlugin = null else onBack()
                    }) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back),
                            modifier = Modifier.scale(0.8f),
                            tint = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                },
            )
        },
    ) { padding ->
        val detail = detailPlugin
        if (detail != null) {
            val isPreferred = preferred == detail.id
            val budgetDisabled = store.isBudgetDisabled(detail.id)
            val authFields = detail.authHeaders.map { it.field }.toSet()
            val missingKeys = authFields.any { !secrets.has(detail.id, it) }
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 16.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.cloud_voice_hosts) + "：" +
                        detail.hosts.joinToString("、") +
                        " · " + detail.version,
                    fontSize = rowSubFontSize,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                GroupCard {
                    for (decl in detail.settings) {
                        when (decl.type) {
                            SettingType.SECRET -> {
                                val filled = secrets.has(detail.id, decl.key)
                                NavRow(
                                    title = decl.label,
                                    subtitle = stringResource(
                                        if (filled) {
                                            R.string.cloud_voice_key_filled
                                        } else {
                                            R.string.cloud_voice_key_empty
                                        },
                                    ),
                                    onClick = { keysTarget = detail },
                                )
                            }
                            SettingType.SWITCH -> {
                                SwitchRow(
                                    title = decl.label,
                                    checked = detailSettings[decl.key] == "true",
                                    onCheckedChange = {
                                        writeDetail(decl.key, it.toString())
                                    },
                                )
                            }
                            SettingType.SELECT -> {
                                val value = detailSettings[decl.key] ?: decl.defaultValue
                                NavRow(
                                    title = decl.label,
                                    subtitle = value.ifBlank {
                                        stringResource(R.string.cloud_voice_not_set)
                                    },
                                    onClick = { selectDecl = decl },
                                )
                            }
                            else -> {
                                val value = detailSettings[decl.key] ?: decl.defaultValue
                                NavRow(
                                    title = decl.label,
                                    subtitle = value.ifBlank {
                                        stringResource(R.string.cloud_voice_not_set)
                                    },
                                    onClick = { textDecl = decl },
                                )
                            }
                        }
                    }
                    if (!isPreferred) {
                        NavRow(
                            title = stringResource(R.string.cloud_voice_set_preferred),
                            onClick = {
                                if (missingKeys) {
                                    keysTarget = detail
                                    showStatus(
                                        context.getString(R.string.cloud_voice_need_keys),
                                        error = true,
                                    )
                                } else {
                                    store.setPreferred(detail.id)
                                    refresh()
                                }
                            },
                        )
                    } else {
                        NavRow(
                            title = stringResource(R.string.cloud_voice_clear_preferred),
                            subtitle = detail.id,
                            onClick = {
                                store.setPreferred(null)
                                showStatus(
                                    context.getString(R.string.cloud_voice_preferred_cleared),
                                )
                                refresh()
                            },
                        )
                    }
                    if (budgetDisabled) {
                        NavRow(
                            title = stringResource(R.string.cloud_voice_reenable),
                            onClick = {
                                store.setBudgetDisabled(detail.id, false)
                                refresh()
                            },
                        )
                    }
                    if (!BuiltinPlugins.isBuiltin(detail.id)) {
                        NavRow(
                            title = stringResource(R.string.cloud_voice_delete),
                            onClick = { deleteTarget = detail },
                        )
                    }
                }
                Spacer(Modifier.height(16.dp))
            }
        } else {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Spacer(Modifier.height(4.dp))
            // 隐私说明全文已移至“关于我们”页（本页不再重复贴出）；
            // 选服务商时的确认披露（音频发送对象）不受影响，仍在选择流程内。
            SettingsGroup(
                title = stringResource(R.string.cloud_voice_plugins),
            ) {
                SwitchRow(
                    title = stringResource(R.string.cloud_voice_opt_in),
                    checked = optIn,
                    onCheckedChange = {
                        optIn = it
                        store.setOptIn(it)
                    },
                    showDivider = true,
                )
                Text(
                    text = stringResource(R.string.cloud_voice_opt_in_desc),
                    fontSize = rowSubFontSize,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 6.dp),
                )
                NavRow(
                    title = stringResource(R.string.cloud_voice_import),
                    onClick = { importLauncher.launch("*/*") },
                )
                if (status != null) {
                    val failed = status!!.startsWith("FAILED:")
                    val text = if (failed) {
                        val code = status!!.removePrefix("FAILED:")
                        val error = runCatching {
                            PluginImportError.valueOf(code)
                        }.getOrNull()
                        if (error != null) {
                            stringResource(
                                R.string.cloud_voice_import_failed,
                                importErrorText(error),
                            )
                        } else {
                            stringResource(
                                R.string.cloud_voice_import_failed,
                                context.getString(R.string.cloud_voice_error_load_failed),
                            )
                        }
                    } else {
                        status!!
                    }
                    Text(
                        text = text,
                        fontSize = rowSubFontSize,
                        color = if (failed || statusIsError) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        modifier = Modifier.padding(vertical = 4.dp),
                    )
                }
                if (plugins.isEmpty()) {
                    Text(
                        text = stringResource(R.string.cloud_voice_no_plugins),
                        fontSize = rowSubFontSize,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 6.dp),
                    )
                }
                for (plugin in plugins) {
                    val isPreferred = preferred == plugin.id
                    val budgetDisabled = store.isBudgetDisabled(plugin.id)
                    val authFields = plugin.authHeaders.map { it.field }.toSet()
                    val missingKeys = authFields.any { !secrets.has(plugin.id, it) }
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { openDetail(plugin) }
                            .padding(vertical = 6.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            val displayNameRes = builtinDisplayName(plugin.id)
                            Text(
                                text = if (displayNameRes != null) {
                                    stringResource(displayNameRes)
                                } else {
                                    plugin.id
                                },
                                fontSize = rowSubFontSize,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.weight(1f),
                            )
                            if (BuiltinPlugins.isBuiltin(plugin.id)) {
                                Text(
                                    text = stringResource(R.string.cloud_voice_builtin),
                                    fontSize = rowSubFontSize,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(horizontal = 4.dp),
                                )
                            }
                            if (isPreferred) {
                                Text(
                                    text = stringResource(R.string.cloud_voice_preferred),
                                    fontSize = rowSubFontSize,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            } else if (budgetDisabled) {
                                Text(
                                    text = stringResource(R.string.cloud_voice_budget_disabled),
                                    fontSize = rowSubFontSize,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            } else if (missingKeys) {
                                Text(
                                    text = stringResource(R.string.cloud_voice_secret_missing),
                                    fontSize = rowSubFontSize,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                        Text(
                            text = stringResource(R.string.cloud_voice_hosts) + "：" +
                                plugin.hosts.joinToString("、") +
                                " · " + plugin.version,
                            fontSize = rowSubFontSize,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }
        }
    }

    // 导入确认对话框：逐项列出域名与能力，用户确认＝授权。
    val meta = pendingMeta
    val bytes = pendingBytes
    if (meta != null && bytes != null) {
        val diff = pendingDiff
        SettingsDialog(
            onDismissRequest = {
                pendingMeta = null
                pendingBytes = null
                pendingDiff = null
            },
            title = stringResource(
                if (diff != null) {
                    R.string.cloud_voice_confirm_upgrade_title
                } else {
                    R.string.cloud_voice_confirm_import_title
                },
            ),
            scrollable = false,
            body = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                ) {
                    Text(meta.id + " · " + meta.version)
                    Spacer(Modifier.height(8.dp))
                    Text(stringResource(R.string.cloud_voice_confirm_desc))
                    Spacer(Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.cloud_voice_hosts) + "：",
                        fontWeight = FontWeight.SemiBold,
                    )
                    for (host in meta.hosts) Text("· $host")
                    if (diff != null) {
                        if (diff.hostsAdded.isNotEmpty()) {
                            Text(
                                stringResource(R.string.cloud_voice_diff_hosts_added) + "：" +
                                    diff.hostsAdded.joinToString("、"),
                            )
                        }
                        if (diff.hostsRemoved.isNotEmpty()) {
                            Text(
                                stringResource(R.string.cloud_voice_diff_hosts_removed) + "：" +
                                    diff.hostsRemoved.joinToString("、"),
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.cloud_voice_capabilities) + "：",
                        fontWeight = FontWeight.SemiBold,
                    )
                    for (cap in meta.capabilities) Text("· $cap")
                    if (diff != null) {
                        if (diff.capsAdded.isNotEmpty()) {
                            Text(
                                stringResource(R.string.cloud_voice_diff_caps_added) + "：" +
                                    diff.capsAdded.joinToString("、"),
                            )
                        }
                        if (diff.capsRemoved.isNotEmpty()) {
                            Text(
                                stringResource(R.string.cloud_voice_diff_caps_removed) + "：" +
                                    diff.capsRemoved.joinToString("、"),
                            )
                        }
                    }
                    if (meta.settings.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            stringResource(R.string.cloud_voice_settings_list) + "：",
                            fontWeight = FontWeight.SemiBold,
                        )
                        for (decl in meta.settings) Text("· ${decl.label}（${decl.key}）")
                    }
                    if (meta.authHeaders.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            stringResource(R.string.cloud_voice_auth_list) + "：",
                            fontWeight = FontWeight.SemiBold,
                        )
                        for (auth in meta.authHeaders) Text("· ${auth.header}")
                    }
                }
            },
            actions = listOf(
                SettingsDialogAction(
                    text = stringResource(R.string.cloud_voice_cancel),
                    onClick = {
                        pendingMeta = null
                        pendingBytes = null
                        pendingDiff = null
                    },
                ),
                SettingsDialogAction(
                    text = stringResource(R.string.cloud_voice_confirm),
                    onClick = {
                        scope.launch(Dispatchers.IO) {
                            store.confirmInstall(meta, bytes)
                            withContext(Dispatchers.Main) {
                                pendingMeta = null
                                pendingBytes = null
                                pendingDiff = null
                                showStatus(context.getString(R.string.cloud_voice_imported))
                                refresh()
                            }
                        }
                    },
                ),
            ),
        )
    }

    // 删除确认对话框：清脚本＋沙箱＋设置＋密钥＋首选。
    val toDelete = deleteTarget
    if (toDelete != null) {
        SettingsConfirmDialog(
            onDismissRequest = { deleteTarget = null },
            title = stringResource(R.string.cloud_voice_delete_title),
            text = toDelete.id + "\n" +
                stringResource(R.string.cloud_voice_delete_desc),
            confirmText = stringResource(R.string.cloud_voice_delete_confirm),
            onConfirm = {
                val wasPreferred = preferred == toDelete.id
                scope.launch(Dispatchers.IO) {
                    store.uninstall(toDelete.id, secrets)
                    withContext(Dispatchers.Main) {
                        deleteTarget = null
                        if (detailPlugin?.id == toDelete.id) detailPlugin = null
                        showStatus(
                            if (wasPreferred) {
                                context.getString(R.string.cloud_voice_preferred_cleared)
                            } else {
                                context.getString(R.string.cloud_voice_deleted)
                            },
                        )
                        refresh()
                    }
                }
            },
            dismissText = stringResource(R.string.cloud_voice_cancel),
        )
    }

    // 密钥对话框：只显已填／未填，永不回显值。
    val keysPlugin = keysTarget
    if (keysPlugin != null) {
        SettingsDialog(
            onDismissRequest = { keysTarget = null },
            title = stringResource(R.string.cloud_voice_keys_title),
            scrollable = false,
            body = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                ) {
                    Text(stringResource(R.string.cloud_voice_keys_desc))
                    Spacer(Modifier.height(8.dp))
                    for (decl in keysPlugin.settings.filter { it.type == SettingType.SECRET }) {
                        SecretFieldRow(
                            pluginId = keysPlugin.id,
                            decl = decl,
                            secrets = secrets,
                            onChanged = { refreshTick++ },
                        )
                    }
                }
            },
            actions = listOf(
                SettingsDialogAction(
                    text = stringResource(R.string.cloud_voice_cancel),
                    onClick = { keysTarget = null },
                ),
                SettingsDialogAction(
                    text = stringResource(R.string.cloud_voice_save),
                    onClick = { keysTarget = null },
                ),
            ),
        )
    }

    // 行输入对话框（文本／数字行）：即写即生效，保存当即落盘并刷新行摘要。
    val detailForDialog = detailPlugin
    val inputDecl = textDecl
    if (detailForDialog != null && inputDecl != null) {
        var textValue by remember(inputDecl.key) {
            mutableStateOf(detailSettings[inputDecl.key] ?: inputDecl.defaultValue)
        }
        SettingsDialog(
            onDismissRequest = { textDecl = null },
            title = inputDecl.label,
            scrollable = false,
            body = {
                OutlinedTextField(
                    value = textValue,
                    onValueChange = { textValue = it },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = if (inputDecl.type == SettingType.NUMBER) {
                            KeyboardType.Number
                        } else {
                            KeyboardType.Text
                        },
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            actions = listOf(
                SettingsDialogAction(
                    text = stringResource(R.string.cloud_voice_cancel),
                    onClick = { textDecl = null },
                ),
                SettingsDialogAction(
                    text = stringResource(R.string.cloud_voice_save),
                    onClick = {
                        writeDetail(inputDecl.key, textValue)
                        textDecl = null
                    },
                ),
            ),
        )
    }

    // 行单选对话框：单选显现值；腾讯两级联动在 applySelect 内处理。
    val selectDeclNow = selectDecl
    if (detailForDialog != null && selectDeclNow != null) {
        val options = if (detailForDialog.id == "builtin.tencent" &&
            selectDeclNow.key == "engine"
        ) {
            tencentEnginesFor(detailSettings["category"] ?: "")
                .ifEmpty { selectDeclNow.options }
        } else {
            selectDeclNow.options
        }
        val current = detailSettings[selectDeclNow.key] ?: selectDeclNow.defaultValue
        SettingsDialog(
            onDismissRequest = { selectDecl = null },
            title = selectDeclNow.label,
            scrollable = false,
            body = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                ) {
                    for (option in options) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { applySelect(selectDeclNow, option) }
                                .padding(vertical = 12.dp),
                        ) {
                            Text(
                                text = option,
                                fontSize = rowSubFontSize,
                                color = if (option == current) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                },
                                modifier = Modifier.weight(1f),
                            )
                            if (option == current) {
                                Text(
                                    text = "✓",
                                    fontSize = rowSubFontSize,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                    }
                }
            },
            actions = listOf(
                SettingsDialogAction(
                    text = stringResource(R.string.cloud_voice_cancel),
                    onClick = { selectDecl = null },
                ),
            ),
        )
    }
}

@Composable
private fun SecretFieldRow(
    pluginId: String,
    decl: PluginSettingDecl,
    secrets: KeystoreSecretStore,
    onChanged: () -> Unit,
) {
    var text by remember { mutableStateOf("") }
    var filled by remember { mutableStateOf(secrets.has(pluginId, decl.key)) }
    Column(modifier = Modifier.padding(vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = decl.label,
                fontSize = rowSubFontSize,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = stringResource(
                    if (filled) {
                        R.string.cloud_voice_key_filled
                    } else {
                        R.string.cloud_voice_key_empty
                    },
                ),
                fontSize = rowSubFontSize,
                color = if (filled) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.error
                },
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            TextButton(
                onClick = {
                    if (text.isNotEmpty()) {
                        secrets.put(pluginId, decl.key, text)
                        text = ""
                        filled = true
                        onChanged()
                    }
                },
            ) {
                Text(stringResource(R.string.cloud_voice_save))
            }
            TextButton(
                onClick = {
                    secrets.clear(pluginId, decl.key)
                    text = ""
                    filled = false
                    onChanged()
                },
            ) {
                Text(stringResource(R.string.cloud_voice_clear))
            }
        }
    }
}

/** 内置插件展示名（插件身份显示）；非内置返回 null，用原始 id。 */
internal fun builtinDisplayName(id: String): Int? = when (id) {
    "builtin.doubao" -> R.string.cloud_voice_builtin_doubao
    "builtin.qwen" -> R.string.cloud_voice_builtin_qwen
    "builtin.tencent" -> R.string.cloud_voice_builtin_tencent
    else -> null
}
