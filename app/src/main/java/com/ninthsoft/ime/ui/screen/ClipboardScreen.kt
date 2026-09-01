package com.ninthsoft.ime.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.unit.dp
import com.ninthsoft.ime.R
import com.ninthsoft.ime.data.manager.ClipboardManager
import com.ninthsoft.ime.ui.screen.ScreenComponent.SettingsGroup
import com.ninthsoft.ime.ui.screen.ScreenComponent.SliderRow
import com.ninthsoft.ime.ui.screen.ScreenComponent.barFontSize

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClipboardScreen(onBack: () -> Unit) {
    val context = LocalContext.current

    var maxEntries by remember { mutableFloatStateOf(ClipboardManager.getMaxEntries(context).toFloat()) }
    var retentionDays by remember { mutableFloatStateOf(ClipboardManager.getRetentionDays(context).toFloat()) }
    var pollInterval by remember {
        mutableFloatStateOf(ClipboardManager.getPollIntervalSeconds(context).toFloat())
    }

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
                    onValueChange = {
                        maxEntries = it
                        ClipboardManager.setMaxEntries(context, it.toInt())
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
                SliderRow(
                    title = stringResource(R.string.clipboard_poll_interval),
                    value = pollInterval,
                    valueLabel = "${pollInterval.toInt()} 秒",
                    range = 1f..60f,
                    onValueChange = {
                        pollInterval = it
                        ClipboardManager.setPollIntervalSeconds(context, it.toInt())
                    },
                )
            }

            Spacer(Modifier.height(14.dp))
        }
    }
}
