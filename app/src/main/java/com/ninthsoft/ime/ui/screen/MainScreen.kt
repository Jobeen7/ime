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
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Tune
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ninthsoft.ime.R
import com.ninthsoft.ime.ui.screen.ScreenComponent.ClickableSettingItem
import com.ninthsoft.ime.ui.screen.ScreenComponent.SectionHeader
import com.ninthsoft.ime.ui.screen.ScreenComponent.SingleChoiceDialog
import com.ninthsoft.ime.ui.screen.ScreenComponent.barFontSize

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    currentThemeMode: Int,
    onThemeModeChanged: (Int) -> Unit,
    onOpenImeSetup: () -> Unit,
    onOpenSchemaSettings: () -> Unit,
    onOpenKeyboardSettings: () -> Unit,
    onOpenKeyboardThemeSettings: () -> Unit,
    onOpenClipboard: () -> Unit,
    onOpenModelSettings: () -> Unit,
    onOpenVoiceSettings: () -> Unit,
    onOpenFiles: () -> Unit,
    onOpenAbout: () -> Unit,
) {
    val themes = listOf(
        stringResource(R.string.theme_follow_system),
        stringResource(R.string.theme_light),
        stringResource(R.string.theme_dark),
    )

    var showThemeDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.app_name),
                        fontSize = barFontSize,
                        fontWeight = FontWeight.Bold,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onOpenImeSetup) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.ime_setup_title),
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

            SectionHeader(stringResource(R.string.basic_settings))

            ClickableSettingItem(
                title = stringResource(R.string.theme),
                subtitle = themes[currentThemeMode],
                onClick = { showThemeDialog = true },
                icon = Icons.Filled.Brush,
                showSpacer = true,
            )

            SectionHeader(stringResource(R.string.input_settings))

            ClickableSettingItem(
                title = stringResource(R.string.schema_settings),
                subtitle = stringResource(R.string.schema_settings_desc),
                onClick = onOpenSchemaSettings,
                icon = Icons.Filled.Tune,
                showSpacer = true,
            )

            ClickableSettingItem(
                title = stringResource(R.string.keyboard_settings),
                subtitle = stringResource(R.string.keyboard_settings_desc),
                onClick = onOpenKeyboardSettings,
                icon = Icons.Filled.Keyboard,
                showSpacer = true,
            )

            ClickableSettingItem(
                title = stringResource(R.string.keyboard_theme),
                subtitle = stringResource(R.string.keyboard_theme_desc),
                onClick = onOpenKeyboardThemeSettings,
                icon = Icons.Filled.Palette,
                showSpacer = true,
            )

            SectionHeader(stringResource(R.string.extended_features))

            ClickableSettingItem(
                title = stringResource(R.string.clipboard_manager),
                subtitle = stringResource(R.string.clipboard_manager_desc),
                onClick = onOpenClipboard,
                icon = Icons.Filled.ContentPaste,
                showSpacer = true,
            )

            ClickableSettingItem(
                title = stringResource(R.string.prediction_candidates),
                subtitle = stringResource(R.string.prediction_candidates_desc),
                onClick = onOpenModelSettings,
                icon = Icons.Filled.AutoAwesome,
                showSpacer = true,
            )

            ClickableSettingItem(
                title = stringResource(R.string.voice_settings),
                subtitle = stringResource(R.string.voice_settings_desc),
                onClick = onOpenVoiceSettings,
                icon = Icons.Filled.Mic,
                showSpacer = true,
            )

            ClickableSettingItem(
                title = stringResource(R.string.schema_files),
                subtitle = stringResource(R.string.schema_files_desc),
                onClick = onOpenFiles,
                icon = Icons.Filled.FolderOpen,
                showSpacer = true,
            )

            SectionHeader(stringResource(R.string.about))

            ClickableSettingItem(
                title = stringResource(R.string.about_us),
                subtitle = stringResource(R.string.about_us_desc),
                onClick = onOpenAbout,
                icon = Icons.Filled.Info,
                showSpacer = true,
            )

            Spacer(Modifier.height(32.dp))
        }
    }

    if (showThemeDialog) {
        SingleChoiceDialog(
            title = stringResource(R.string.theme),
            options = themes,
            selectedIndex = currentThemeMode,
            onSelect = { onThemeModeChanged(it); showThemeDialog = false },
            onDismiss = { showThemeDialog = false },
        )
    }
}
