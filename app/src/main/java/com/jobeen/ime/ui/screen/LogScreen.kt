package com.jobeen.ime.ui.screen

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.PersistableBundle
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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import com.jobeen.ime.ui.screen.ScreenComponent.barFontSize
import kotlinx.coroutines.Dispatchers
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
    // 最近崩溃记录（CrashTrap 落盘）：有内容才显示复制入口，纯手动
    // 取回——复制带敏感标记，不进自家剪贴板历史
    var pendingCrash by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        pendingCrash = withContext(Dispatchers.IO) { CrashTrap.readPendingCrash(context) }
    }

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
                    val crashText = pendingCrash
                    if (crashText != null) {
                        val copiedLabel = stringResource(R.string.crash_copied)
                        IconButton(onClick = {
                            copySensitiveClip(
                                context,
                                crashText.take(CrashTrap.MAX_CHARS),
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
                    val logsLabel = stringResource(R.string.app_logs)
                    IconButton(onClick = { copyLogs(context, logText, logsLabel) }, enabled = entries.isNotEmpty()) {
                        Icon(Icons.Filled.ContentCopy, contentDescription = stringResource(R.string.copy_logs))
                    }
                    IconButton(onClick = AppLogBuffer::clear, enabled = entries.isNotEmpty()) {
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
