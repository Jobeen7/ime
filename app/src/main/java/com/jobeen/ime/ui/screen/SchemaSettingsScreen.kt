package com.jobeen.ime.ui.screen

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.jobeen.ime.R
import com.jobeen.ime.base.ngram.GramModelDownloader
import com.jobeen.ime.base.update.WanxiangUpdateManager
import com.jobeen.ime.engine.rime.core.RimeConfig
import com.jobeen.ime.engine.rime.core.IRimeJob
import com.jobeen.ime.engine.rime.data.DataManager
import com.jobeen.ime.data.manager.SchemaManager
import com.jobeen.ime.engine.EngineFactory
import com.jobeen.ime.engine.rime.core.SchemaItem
import com.jobeen.ime.data.schemaLayoutTag
import com.jobeen.ime.ui.screen.ScreenComponent.SectionHeader
import com.jobeen.ime.ui.screen.ScreenComponent.ActionRow
import com.jobeen.ime.ui.screen.ScreenComponent.ProgressButton
import com.jobeen.ime.ui.screen.ScreenComponent.SettingsGroup
import com.jobeen.ime.ui.screen.ScreenComponent.barFontSize
import com.jobeen.ime.ui.screen.ScreenComponent.rowFontSize
import com.jobeen.ime.ui.screen.ScreenComponent.rowSubFontSize
import com.jobeen.ime.ui.theme.ExpressiveShapes
import kotlin.math.roundToInt
import androidx.core.content.edit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private const val KEY_APP_VERSION_CODE = "app_version_code"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SchemaSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val prefs =
        remember { context.getSharedPreferences(SchemaManager.PREFS_NAME, Context.MODE_PRIVATE) }
    val enabledSchemas = remember { mutableStateListOf<SchemaItem>() }
    val availableSchemas = remember { mutableStateListOf<SchemaItem>() }
    var loaded by remember { mutableStateOf(false) }
    var grammarLanguage by remember { mutableStateOf<String?>(null) }
    var grammarReady by remember { mutableStateOf(false) }
    var grammarDownloading by remember { mutableStateOf(false) }
    var grammarProgress by remember { mutableFloatStateOf(0f) }
    var grammarFailed by remember { mutableStateOf(false) }
    var grammarButtonWidth by remember { mutableStateOf(0.dp) }
    // 万象方案更新状态
    val updatePrefs = remember {
        context.getSharedPreferences(WanxiangUpdateManager.PREFS_NAME, Context.MODE_PRIVATE)
    }
    var updateInfo by remember { mutableStateOf<WanxiangUpdateManager.UpdateInfo?>(null) }
    var updating by remember { mutableStateOf(false) }
    var updateProgress by remember { mutableStateOf<WanxiangUpdateManager.UpdateProgress?>(null) }
    var updateStageText by remember { mutableStateOf("") }
    var updateDone by remember { mutableStateOf(false) }
    var updateFailed by remember { mutableStateOf(false) }
    var updateButtonWidth by remember { mutableStateOf(0.dp) }
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()

    // 异步加载输入方案，避免阻塞主线程（schemasList 内部走引擎线程）
    LaunchedEffect(Unit) {
        val allSchemas = EngineFactory.current()?.schemasList() ?: emptyList()
        if (allSchemas.isNotEmpty()) {
            val allItems =
                allSchemas.map { SchemaItem(it.id, it.name, it.layout, it.punctuation, it.kind) }
            val enabledIds = prefs.getString(SchemaManager.KEY_ENABLED_IDS, "")?.split(",")
                ?.filter { it.isNotBlank() } ?: emptyList()
            val byId = allItems.associateBy { it.id }
            val seen = mutableSetOf<String>()
            enabledSchemas.clear()
            enabledSchemas.addAll(enabledIds.mapNotNull { byId[it]?.also { seen.add(it.id) } })
            availableSchemas.clear()
            availableSchemas.addAll(allItems.filter { it.id !in seen })
            loaded = true
        }
    }

    fun downloadGrammar(language: String) {
        if (grammarDownloading) return
        grammarDownloading = true
        grammarFailed = false
        grammarProgress = 0f
        scope.launch {
            val success = GramModelDownloader.download(language) { downloaded, total ->
                scope.launch(Dispatchers.Main.immediate) {
                    grammarProgress = if (total > 0L) {
                        downloaded.toFloat() / total.toFloat()
                    } else {
                        0f
                    }
                }
            }
            grammarDownloading = false
            grammarReady = success && File(DataManager.sharedDataDir, "$language.gram").isFile
            grammarFailed = !success
            if (grammarReady) {
                // The grammar database is loaded during engine startup.
                EngineFactory.current()?.reload()
            }
        }
    }

    LaunchedEffect(Unit) {
        val language = withContext(Dispatchers.IO) {
            runCatching {
                (EngineFactory.current() as? IRimeJob)?.awaitJob<String?>(null) {
                    val currentSchema = currentSchema()
                    RimeConfig.openSchema(currentSchema.schemaId).use { config ->
                        config.getString("grammar/language")?.trim()?.takeIf { it.isNotEmpty() }
                    }
                }
            }.getOrNull()
        }
        grammarLanguage = language
        if (language != null) {
            grammarReady = File(DataManager.sharedDataDir, "$language.gram").isFile
        }
    }

    fun formatSize(bytes: Long): String = when {
        bytes <= 0L -> ""
        bytes >= 1024L * 1024L -> "%.1f MB".format(bytes / 1024.0 / 1024.0)
        bytes >= 1024L -> "%.0f KB".format(bytes / 1024.0)
        else -> "$bytes B"
    }

    fun currentAppVersionCode(): Long = runCatching {
        val pi = context.packageManager.getPackageInfo(context.packageName, 0)
        if (android.os.Build.VERSION.SDK_INT >= 28) pi.longVersionCode
        else @Suppress("DEPRECATION") pi.versionCode.toLong()
    }.getOrNull() ?: -1L

    fun checkWanxiangUpdate() {
        if (updating) return
        // App 升级后内置词库可能已变化：若 versionCode 变了，清除旧的更新记录，
        // 避免"已是最新"的版本号与实际被 DataManager.sync() 还原的文件不一致
        val appVersionCode = currentAppVersionCode()
        val recordedCode = updatePrefs.getLong(KEY_APP_VERSION_CODE, -1L)
        if (appVersionCode != -1L && recordedCode != -1L && recordedCode != appVersionCode) {
            updatePrefs.edit().clear().apply()
            updateInfo = null
        }
        // 固定地址版本比对，无需网络请求
        updateInfo = WanxiangUpdateManager.checkForUpdates(updatePrefs)
        updateDone = false
        updateFailed = false
    }

    fun startWanxiangUpdate(info: WanxiangUpdateManager.UpdateInfo) {
        if (updating) return
        updating = true
        updateFailed = false
        updateDone = false
        updateProgress = null
        scope.launch {
            val success = WanxiangUpdateManager.downloadAndApply(
                updatePrefs,
                info,
                context.cacheDir,
            ) { progress ->
                scope.launch(Dispatchers.Main.immediate) {
                    updateProgress = progress
                    updateStageText = when (progress.stage) {
                        WanxiangUpdateManager.UpdateProgress.Stage.SCHEMA_DOWNLOAD ->
                            context.getString(R.string.schema_update_downloading_schema)
                        WanxiangUpdateManager.UpdateProgress.Stage.DICTS_EXTRACT ->
                            context.getString(R.string.schema_update_extracting)
                        WanxiangUpdateManager.UpdateProgress.Stage.GRAM_DOWNLOAD ->
                            context.getString(R.string.schema_update_downloading_gram)
                    }
                }
            }
            updating = false
            updateProgress = null
            if (success) {
                updateDone = true
                // 记录 App 版本号，用于下次检查时判断内置词库是否被升级覆盖
                currentAppVersionCode().takeIf { it != -1L }?.let {
                    updatePrefs.edit().putLong(KEY_APP_VERSION_CODE, it).apply()
                }
                // 更新本地版本显示
                updateInfo = WanxiangUpdateManager.checkForUpdates(updatePrefs)
                // 词库与模型已替换，触发 Rime 完整重新部署
                updateStageText = context.getString(R.string.schema_update_deploying)
                EngineFactory.current()?.reload()
            } else {
                updateFailed = true
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(R.string.schema_settings),
                        fontSize = barFontSize,
                        fontWeight = FontWeight.Bold,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = { saveOrder(prefs, enabledSchemas); onBack() }) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = null,
                            modifier = Modifier.scale(0.8f),
                            tint = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
    ) { padding ->
        if (!loaded) return@Scaffold
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Spacer(Modifier.height(4.dp))

            SettingsGroup(title = stringResource(R.string.schema_model)) {
                ActionRow(
                    title = stringResource(R.string.schema_model_grammar),
                    subtitle = when {
                        grammarLanguage == null -> stringResource(R.string.schema_grammar_model_unavailable)
                        grammarReady -> stringResource(R.string.schema_grammar_model_ready)
                        grammarFailed -> stringResource(R.string.schema_grammar_model_failed)
                        else -> stringResource(R.string.schema_grammar_model_desc)
                    },
                    trailing = {
                        when {
                            grammarReady -> Icon(
                                imageVector = Icons.Default.CheckCircle,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(22.dp),
                            )

                            grammarDownloading -> ProgressButton(
                                grammarProgress, width = grammarButtonWidth
                            )

                            grammarLanguage != null -> Button(
                                onClick = { downloadGrammar(grammarLanguage!!) },
                                modifier = Modifier
                                    .height(32.dp)
                                    .onSizeChanged {
                                        grammarButtonWidth = with(density) { it.width.toDp() }
                                    },
                                shape = RoundedCornerShape(8.dp),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                            ) {
                                Text(stringResource(R.string.download), fontSize = rowSubFontSize)
                            }
                        }
                    },
                )
            }

            SettingsGroup(title = stringResource(R.string.schema_update_title)) {
                val info = updateInfo
                ActionRow(
                    title = stringResource(R.string.schema_update_title),
                    subtitle = when {
                        updating && updateProgress != null -> {
                            val p = updateProgress!!
                            val pct = if (p.total > 0L) {
                                (p.downloaded * 100 / p.total).toInt()
                            } else {
                                0
                            }
                            "$updateStageText $pct%"
                        }
                        updating -> updateStageText.ifEmpty {
                            stringResource(R.string.schema_update_deploying)
                        }
                        updateDone -> stringResource(R.string.schema_update_success)
                        updateFailed -> stringResource(R.string.schema_update_failed)
                        info == null -> stringResource(R.string.schema_update_desc)
                        info.hasUpdate ->
                            "${stringResource(R.string.schema_update_available)} " +
                                WanxiangUpdateManager.SCHEMA_VERSION
                        else -> "${stringResource(R.string.schema_update_latest)} " +
                            "(${WanxiangUpdateManager.SCHEMA_VERSION})"
                    },
                    trailing = {
                        when {
                            updateDone -> Icon(
                                imageVector = Icons.Default.CheckCircle,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(22.dp),
                            )

                            updating -> ProgressButton(
                                progress = updateProgress?.let {
                                    if (it.total > 0L) it.downloaded.toFloat() / it.total.toFloat() else 0f
                                } ?: 0f,
                                width = updateButtonWidth,
                            )

                            info?.hasUpdate == true -> Button(
                                onClick = { startWanxiangUpdate(info) },
                                modifier = Modifier
                                    .height(32.dp)
                                    .onSizeChanged {
                                        updateButtonWidth = with(density) { it.width.toDp() }
                                    },
                                shape = RoundedCornerShape(8.dp),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                            ) {
                                Text(
                                    stringResource(R.string.schema_update_start),
                                    fontSize = rowSubFontSize,
                                )
                            }

                            else -> Button(
                                onClick = { checkWanxiangUpdate() },
                                modifier = Modifier
                                    .height(32.dp)
                                    .onSizeChanged {
                                        updateButtonWidth = with(density) { it.width.toDp() }
                                    },
                                shape = RoundedCornerShape(8.dp),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                            ) {
                                Text(
                                    stringResource(R.string.schema_update_check),
                                    fontSize = rowSubFontSize,
                                    maxLines = 1,
                                )
                            }
                        }
                    },
                )
                // 本地版本信息行
                if (info != null && !updating) {
                    val builtin = stringResource(R.string.schema_update_builtin)
                    ActionRow(
                        title = "词库 ${info.schemaLocalVersion ?: builtin}",
                        subtitle = "语法模型 ${
                            if (info.gramDownloaded)
                                stringResource(R.string.schema_grammar_model_ready)
                            else builtin
                        }",
                        trailing = {},
                    )
                }
            }

            SectionHeader(stringResource(R.string.enabled_schemas))
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = ExpressiveShapes.medium,
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
            ) {
                ReorderableSchemaList(enabledSchemas) { schema ->
                    if (enabledSchemas.size > 1) {
                        IconButton(onClick = {
                            availableSchemas.add(schema)
                            enabledSchemas.remove(schema)
                            saveOrder(prefs, enabledSchemas)
                        }) {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                }
            }
            if (availableSchemas.isNotEmpty()) {
                SectionHeader(stringResource(R.string.available_schemas))
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = ExpressiveShapes.medium,
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                    ),
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                ) {
                    LazyColumn {
                        itemsIndexed(availableSchemas, key = { _, s -> s.id }) { _, schema ->
                            SchemaListItem(
                                schema = schema,
                                leadingIcon = {
                                    Icon(
                                        Icons.Default.RadioButtonUnchecked,
                                        contentDescription = null,
                                        modifier = Modifier.size(20.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                },
                                trailingIcon = {
                                    IconButton(onClick = {
                                        enabledSchemas.add(schema)
                                        availableSchemas.remove(schema)
                                        saveOrder(prefs, enabledSchemas)
                                    }) {
                                        Icon(
                                            imageVector = Icons.Default.Add,
                                            contentDescription = null,
                                            modifier = Modifier.size(20.dp),
                                            tint = MaterialTheme.colorScheme.primary,
                                        )
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ReorderableSchemaList(
    items: MutableList<SchemaItem>,
    trailingIcon: @Composable (SchemaItem) -> Unit,
) {
    val context = LocalContext.current
    val prefs =
        remember { context.getSharedPreferences(SchemaManager.PREFS_NAME, Context.MODE_PRIVATE) }
    var dragIndex by remember { mutableIntStateOf(-1) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    var itemHeight by remember { mutableFloatStateOf(0f) }

    LazyColumn {
        itemsIndexed(items, key = { _, s -> s.id }) { index, schema ->
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
                                val target =
                                    (dragIndex + (dragOffset / itemHeight).roundToInt()).coerceIn(
                                        0, items.size - 1
                                    )
                                if (target != dragIndex) {
                                    val item = items.removeAt(dragIndex)
                                    items.add(target, item)
                                    saveOrder(prefs, items)
                                }
                                dragIndex = -1
                            },
                            onDrag = { change, amount ->
                                change.consume()
                                dragOffset += amount.y
                            },
                        )
                    },
            ) {
                SchemaListItem(
                    schema = schema,
                    isDefault = index == 0,
                    leadingIcon = {
                        Box(
                            modifier = Modifier
                                .size(28.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .background(
                                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                Icons.Default.DragHandle,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                    trailingIcon = { trailingIcon(schema) },
                )
            }
        }
    }
}

@Composable
private fun SchemaListItem(
    schema: SchemaItem,
    isDefault: Boolean = false,
    leadingIcon: @Composable () -> Unit,
    trailingIcon: @Composable () -> Unit,
) {
    val context = LocalContext.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        leadingIcon()
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                schema.name.ifBlank { schema.id },
                fontSize = rowFontSize,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (schema.name.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (isDefault) {
                        TagBadge(
                            stringResource(R.string.tag_default),
                            MaterialTheme.colorScheme.secondaryContainer,
                            MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                        Spacer(Modifier.width(4.dp))
                    }
                    val tagText = schemaLayoutTag(context, schema.layout)
                    TagBadge(
                        tagText,
                        MaterialTheme.colorScheme.primaryContainer,
                        MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                    Spacer(Modifier.width(4.dp))
                    val punctText =
                        if (schema.punctuation == "FullWidth") stringResource(R.string.tag_punctuation_full)
                        else stringResource(R.string.tag_punctuation_half)
                    TagBadge(
                        punctText,
                        MaterialTheme.colorScheme.tertiaryContainer,
                        MaterialTheme.colorScheme.onTertiaryContainer,
                    )
                }
            }
        }
        trailingIcon()
    }
}

private fun saveOrder(prefs: SharedPreferences, schemas: List<SchemaItem>) {
    prefs.edit { putString(SchemaManager.KEY_ENABLED_IDS, schemas.joinToString(",") { it.id }) }
}

@Composable
private fun TagBadge(text: String, bg: Color, fg: Color) {
    Text(
        text = text,
        fontSize = rowSubFontSize * 0.9f,
        lineHeight = rowSubFontSize * 0.9f,
        color = fg,
        modifier = Modifier
            .background(bg, RoundedCornerShape(2.dp))
            .padding(horizontal = 3.dp, vertical = 1.dp),
    )
}
