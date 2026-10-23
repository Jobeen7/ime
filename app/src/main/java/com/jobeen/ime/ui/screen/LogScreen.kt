package com.jobeen.ime.ui.screen

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PersistableBundle
import androidx.core.content.FileProvider
import java.io.File
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jobeen.ime.R
import com.jobeen.ime.base.CrashTrap
import com.jobeen.ime.base.log.AppLogBuffer
import com.jobeen.ime.base.log.AppLogEntry
import com.jobeen.ime.base.util.ToastUtil
import com.jobeen.ime.skin.diag.DiagExporter
import com.jobeen.ime.skin.diag.DiagLog
import com.jobeen.ime.skin.diag.DiagPrefs
import com.jobeen.ime.data.manager.takeCodePoints
import com.jobeen.ime.ui.screen.ScreenComponent.barFontSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val entries by AppLogBuffer.entries.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    val logText = remember(entries) {
        entries.joinToString("\n") { "${it.time} ${it.tag} ${it.message}" }
    }
    // 最近崩溃记录（CrashTrap 落盘）：有内容才显示复制入口与发生
    // 时间，纯手动取回——复制带敏感标记，不进自家剪贴板历史
    var pendingCrash by remember { mutableStateOf<CrashTrap.PendingCrash?>(null) }
    LaunchedEffect(Unit) {
        pendingCrash = withContext(Dispatchers.IO) { CrashTrap.readPendingCrashInfo(context) }
    }
    // 诊断模式开关（详设十三.6）：控制 DiagLog 的 DEBUG 级是否落盘
    var diagnosticMode by remember { mutableStateOf(DiagPrefs.isDiagnosticMode(context)) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(entries.size) {
        if (entries.isNotEmpty()) listState.animateScrollToItem(entries.lastIndex)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_logs), fontSize = barFontSize) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
                actions = {
                    val crash = pendingCrash
                    if (crash != null) {
                        val copiedLabel = stringResource(R.string.crash_copied)
                        IconButton(onClick = {
                            copySensitiveClip(
                                context,
                                // 字素口径截断：String.take 按 UTF-16
                                // 码元计数会劈开代理对/组合 emoji
                                crash.text.takeCodePoints(CrashTrap.MAX_CHARS),
                                "Jime crash log",
                            )
                            ToastUtil.showToast(context, copiedLabel)
                        }) {
                            Icon(
                                Icons.Filled.BugReport,
                                contentDescription = stringResource(R.string.copy_last_crash),
                            )
                        }
                    }
                    // 导出诊断日志（详设十三.5）：DiagLog 近期日志 +
                    // CrashTrap 崩溃文件打成单个 zip 走系统分享
                    val exportFailedLabel = stringResource(R.string.diag_export_failed)
                    IconButton(onClick = {
                        scope.launch {
                            val zip = withContext(Dispatchers.IO) {
                                runCatching {
                                    DiagLog.flush()
                                    DiagExporter.buildExportZip(
                                        logsDir = DiagLog.logsDir()
                                            ?: File(context.filesDir, "logs"),
                                        // CrashTrap 的崩溃目录（filesDir/crash）
                                        crashDir = File(context.filesDir, "crash"),
                                        outDir = File(context.cacheDir, "shared"),
                                    )
                                }.getOrNull()
                            }
                            if (zip == null) {
                                ToastUtil.showToast(context, exportFailedLabel)
                            } else {
                                val uri = FileProvider.getUriForFile(
                                    context,
                                    "${context.packageName}.fileprovider",
                                    zip,
                                )
                                val intent = Intent(Intent.ACTION_SEND).apply {
                                    type = "application/zip"
                                    putExtra(Intent.EXTRA_STREAM, uri)
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                context.startActivity(Intent.createChooser(intent, null))
                            }
                        }
                    }) {
                        Icon(
                            Icons.Filled.Share,
                            contentDescription = stringResource(R.string.export_diag_logs),
                        )
                    }
                    val logsLabel = stringResource(R.string.app_logs)
                    IconButton(onClick = { copyLogs(context, logText, logsLabel) }, enabled = entries.isNotEmpty()) {
                        Icon(Icons.Filled.ContentCopy, contentDescription = stringResource(R.string.copy_logs))
                    }
                    IconButton(
                        onClick = {
                            AppLogBuffer.clear()
                            // 崩溃记录一并清除（此前只清日志：崩溃文件
                            // 只读不删、旧崩溃入口永久留在页面上）
                            pendingCrash = null
                            scope.launch(Dispatchers.IO) {
                                CrashTrap.clearPendingCrashes(context)
                            }
                        },
                        enabled = entries.isNotEmpty() || pendingCrash != null,
                    ) {
                        Icon(Icons.Filled.DeleteSweep, contentDescription = stringResource(R.string.clear_logs))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(8.dp)
                .background(Color(0xFF101214)),
        ) {
            // 诊断模式开关行（详设十三.6）
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.diagnostic_mode),
                        color = Color(0xFFF5F5F7),
                        fontSize = 13.sp,
                    )
                    Text(
                        text = stringResource(R.string.diagnostic_mode_desc),
                        color = Color(0xFF9A9AA0),
                        fontSize = 11.sp,
                    )
                }
                Switch(
                    checked = diagnosticMode,
                    onCheckedChange = { enabled ->
                        diagnosticMode = enabled
                        DiagPrefs.setDiagnosticMode(context, enabled)
                    },
                )
            }
            // 最近崩溃的发生时间：记录只读不删，没有时间用户无从判断
            // 顶栏那个入口是几天前的旧崩溃还是刚刚发生的
            val crash = pendingCrash
            if (crash != null) {
                val crashTimeText = remember(crash.occurredAtMs) {
                    java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
                        .format(java.util.Date(crash.occurredAtMs))
                }
                Text(
                    text = stringResource(R.string.last_crash_time, crashTimeText),
                    color = Color(0xFFFF7777),
                    fontSize = 11.sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(8.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                itemsIndexed(entries, key = { index, _ -> index }) { _, entry ->
                    LogLine(entry)
                }
            }
        }
    }
}

@Composable
private fun LogLine(entry: AppLogEntry) {
    val color = when (entry.priority) {
        android.util.Log.ERROR, android.util.Log.ASSERT -> Color(0xFFFF7777)
        android.util.Log.WARN -> Color(0xFFFFD166)
        android.util.Log.INFO -> Color(0xFF8BE9FD)
        else -> Color(0xFFB7F7B7)
    }
    Text(
        text = "${entry.time} ${entry.tag}: ${entry.message}",
        color = color,
        fontFamily = FontFamily.Monospace,
        fontSize = 11.sp,
        lineHeight = 15.sp,
        modifier = Modifier.fillMaxWidth(),
    )
}

private fun copyLogs(context: Context, text: String, label: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
}

/**
 * 带敏感标记的复制：标记键与 ClipboardManager 读取侧口径一致
 * （API 33+ 用公开常量、低版本用同一字符串键），自家剪贴板监控
 * 见标记即跳过入库，系统层面也按敏感内容处理。
 */
private fun copySensitiveClip(context: Context, text: String, label: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    val description = ClipDescription(label, arrayOf(ClipDescription.MIMETYPE_TEXT_PLAIN))
    description.extras = PersistableBundle().apply {
        putBoolean(
            if (Build.VERSION.SDK_INT >= 33) ClipDescription.EXTRA_IS_SENSITIVE
            else "android.content.extra.IS_SENSITIVE",
            true,
        )
    }
    clipboard.setPrimaryClip(ClipData(description, ClipData.Item(text)))
}
