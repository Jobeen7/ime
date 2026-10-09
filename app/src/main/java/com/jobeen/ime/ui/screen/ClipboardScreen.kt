package com.jobeen.ime.ui.screen

import android.content.ClipData
import android.content.ClipDescription
import android.content.Context
import android.content.ClipboardManager as AndroidClipboardManager
import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.jobeen.ime.R
import com.jobeen.ime.data.manager.ClipboardManager
import com.jobeen.ime.data.manager.ClipboardCloudSync
import com.jobeen.ime.data.manager.ClipboardSyncPrefs
import com.jobeen.ime.base.util.appScope
import com.jobeen.ime.ui.screen.ScreenComponent.SettingsGroup
import com.jobeen.ime.ui.screen.ScreenComponent.SliderRow
import com.jobeen.ime.ui.screen.ScreenComponent.SwitchRow
import com.jobeen.ime.ui.screen.ScreenComponent.barFontSize

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClipboardScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    // 事件回调里要用的字符串先在组合期取好：回调内经 LocalContext 查
    // 资源会被 lint 判 Error（LocalContextGetResourceValueCall）
    val keySaveFailedText = stringResource(R.string.clipboard_cloud_sync_key_save_failed)
    val keyCopiedText = stringResource(R.string.clipboard_cloud_sync_key_copied)

    var maxEntries by remember { mutableFloatStateOf(ClipboardManager.getMaxEntries(context).toFloat()) }
    var retentionDays by remember { mutableFloatStateOf(ClipboardManager.getRetentionDays(context).toFloat()) }
    var syncEnabled by remember { mutableStateOf(ClipboardSyncPrefs.isEnabled(context)) }
    var syncSecret by remember { mutableStateOf(ClipboardSyncPrefs.secret(context)) }
    var syncHost by remember { mutableStateOf(ClipboardSyncPrefs.host(context)) }
    var syncPort by remember { mutableStateOf(ClipboardSyncPrefs.port(context).toString()) }
    var syncMessage by remember { mutableStateOf("") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(R.string.clipboard_manager),
                        fontSize = barFontSize,
                        fontWeight = FontWeight.Bold,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
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
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Spacer(Modifier.height(4.dp))

            SettingsGroup(title = stringResource(R.string.clipboard_history)) {
                Spacer(Modifier.height(4.dp))
                SliderRow(
                    title = stringResource(R.string.clipboard_max_entries),
                    value = maxEntries,
                    valueLabel = "${maxEntries.toInt()} 条",
                    range = 20f..100f,
                    // 拖动只更新本地显示：setMaxEntries 会立即按新上限裁剪历史，
                    // 逐帧调用会把途经低点的裁剪全打出去（拖回高位也恢复不了）
                    onValueChange = { maxEntries = it },
                    onValueChangeFinished = {
                        ClipboardManager.setMaxEntries(context, maxEntries.toInt())
                    },
                )
                SliderRow(
                    title = stringResource(R.string.clipboard_retention_days),
                    value = retentionDays,
                    valueLabel = "${retentionDays.toInt()} 天",
                    range = 1f..365f,
                    onValueChange = {
                        retentionDays = it
                        ClipboardManager.setRetentionDays(context, it.toInt())
                    },
                )
            }

            Spacer(Modifier.height(14.dp))

            SettingsGroup(title = stringResource(R.string.clipboard_cloud_sync)) {
                SwitchRow(
                    title = stringResource(R.string.clipboard_cloud_sync_enable),
                    checked = syncEnabled,
                    onCheckedChange = {
                        syncEnabled = it
                        ClipboardSyncPrefs.setEnabled(context, it)
                        if (it) ClipboardCloudSync.start(context, appScope) else ClipboardCloudSync.stop()
                    },
                )
                Text(
                    text = stringResource(R.string.clipboard_cloud_sync_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.clipboard_cloud_sync_lan_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = syncHost,
                    onValueChange = { syncHost = it; ClipboardSyncPrefs.setHost(context, it); if (syncEnabled) ClipboardCloudSync.start(context, appScope) },
                    label = { Text(stringResource(R.string.clipboard_cloud_sync_host)) },
                    supportingText = { Text(stringResource(R.string.clipboard_cloud_sync_host_desc)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = syncPort,
                    onValueChange = { value ->
                        syncPort = value.filter { it.isDigit() }.take(5)
                        syncPort.toIntOrNull()?.takeIf { it in 1..65535 }?.let {
                            ClipboardSyncPrefs.setPort(context, it)
                            if (syncEnabled) ClipboardCloudSync.start(context, appScope)
                        }
                    },
                    label = { Text(stringResource(R.string.clipboard_cloud_sync_port)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = syncSecret,
                    onValueChange = {
                        syncSecret = it
                        syncMessage = if (ClipboardSyncPrefs.setSecret(context, it)) ""
                        else keySaveFailedText
                        if (syncEnabled) ClipboardCloudSync.start(context, appScope)
                    },
                    label = { Text(stringResource(R.string.clipboard_cloud_sync_key)) },
                    supportingText = { Text(stringResource(R.string.clipboard_cloud_sync_key_desc)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(onClick = {
                        syncSecret = ClipboardSyncPrefs.generateSecret()
                        syncMessage = if (ClipboardSyncPrefs.setSecret(context, syncSecret)) ""
                        else keySaveFailedText
                        if (syncEnabled) ClipboardCloudSync.start(context, appScope)
                    }) {
                        Text(stringResource(R.string.clipboard_cloud_sync_generate_key))
                    }
                    Button(onClick = {
                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as AndroidClipboardManager
                        val clip = ClipData.newPlainText("Jime clipboard sync key", syncSecret)
                        if (Build.VERSION.SDK_INT >= 33) {
                            clip.description.extras?.putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
                        }
                        cm.setPrimaryClip(clip)
                        syncMessage = keyCopiedText
                    }, enabled = syncSecret.isNotBlank()) {
                        Text(stringResource(R.string.clipboard_cloud_sync_copy_key))
                    }
                }
                if (syncMessage.isNotBlank()) {
                    Text(
                        text = syncMessage,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }

            Spacer(Modifier.height(14.dp))
        }
    }
}
