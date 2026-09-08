package com.ninthsoft.ime.ui.screen

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.Image
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.journeyapps.barcodescanner.BarcodeEncoder
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.ninthsoft.ime.R
import com.ninthsoft.ime.data.ThemeStore
import com.ninthsoft.ime.data.keyboard.theme.KeyboardTheme
import com.ninthsoft.ime.data.keyboard.theme.KeyboardThemePresets
import com.ninthsoft.ime.data.manager.KeyboardManager
import com.ninthsoft.ime.data.theme.CompactTheme
import kotlinx.serialization.json.Json
import com.ninthsoft.ime.ui.screen.ScreenComponent.SettingsGroup
import com.ninthsoft.ime.ui.screen.ScreenComponent.SwitchRow
import com.ninthsoft.ime.ui.screen.ScreenComponent.ThemeChip
import com.ninthsoft.ime.ui.screen.ScreenComponent.barFontSize
import com.ninthsoft.ime.ui.screen.ScreenComponent.rowSubFontSize
import java.io.File
import java.io.FileOutputStream
import java.util.EnumMap

private object ThemeCode {
    const val PREFIX = "IMEKBTHEME:"

    private val json = Json { ignoreUnknownKeys = true }

    fun encode(theme: KeyboardTheme): String =
        PREFIX + json.encodeToString(CompactTheme.from(theme))

    fun decode(raw: String): KeyboardTheme? {
        val content = raw.trim()
        if (!content.startsWith(PREFIX)) return null
        val body = content.removePrefix(PREFIX)
        runCatching {
            return json.decodeFromString<CompactTheme>(body).toKeyboardTheme()
        }
        // 兼容旧版「IMEKBTHEME:主题id」格式
        return KeyboardTheme.byId(body).takeIf { it.id == body }
    }

    fun toBitmap(text: String): Bitmap {
        val hints = EnumMap<EncodeHintType, Any>(EncodeHintType::class.java).apply {
            this[EncodeHintType.CHARACTER_SET] = "UTF-8"
            this[EncodeHintType.MARGIN] = 1
        }
        return BarcodeEncoder().encodeBitmap(text, BarcodeFormat.QR_CODE, 512, 512, hints)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KeyboardThemeSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current

    var followSystem by remember {
        mutableStateOf(KeyboardManager.Keyboard.getFollowSystem(context))
    }
    var selectedThemeId by remember {
        mutableStateOf(KeyboardManager.Keyboard.getThemeId(context))
    }
    var selectedLightThemeId by remember {
        mutableStateOf(KeyboardManager.Keyboard.getLightThemeId(context))
    }
    var selectedDarkThemeId by remember {
        mutableStateOf(KeyboardManager.Keyboard.getDarkThemeId(context))
    }

    var showQrDialog by remember { mutableStateOf(false) }
    var showThemePicker by remember { mutableStateOf(false) }
    var showOverwriteDialog by remember { mutableStateOf(false) }
    var pendingImport by remember { mutableStateOf<KeyboardTheme?>(null) }
    var qrCodeText by remember { mutableStateOf("") }
    var themeVersion by remember { mutableIntStateOf(0) }

    val customThemeIds = KeyboardThemePresets.customThemes.map { it.id }.toSet()

    val isDark = isSystemInDarkTheme()

    fun currentThemeId(): String = if (followSystem) {
        if (isDark) {
            KeyboardManager.Keyboard.getDarkThemeId(context)
        } else {
            KeyboardManager.Keyboard.getLightThemeId(context)
        }
    } else {
        KeyboardManager.Keyboard.getThemeId(context)
    }

    fun currentTheme(): KeyboardTheme = KeyboardTheme.byId(currentThemeId())

    fun sharedTheme(): KeyboardTheme? = ThemeCode.decode(qrCodeText)

    fun shareCodeText(): String {
        val theme = sharedTheme()
        return context.getString(
            R.string.keyboard_theme_share_text, theme?.name.orEmpty(), qrCodeText
        )
    }

    fun regenerateQr() {
        qrCodeText = ThemeCode.encode(currentTheme())
    }

    fun refresh() {
        ThemeStore.refresh()
        themeVersion++
        followSystem = KeyboardManager.Keyboard.getFollowSystem(context)
        selectedThemeId = KeyboardManager.Keyboard.getThemeId(context)
        selectedLightThemeId = KeyboardManager.Keyboard.getLightThemeId(context)
        selectedDarkThemeId = KeyboardManager.Keyboard.getDarkThemeId(context)
        Toast.makeText(context, R.string.keyboard_theme_refreshed, Toast.LENGTH_SHORT).show()
    }

    fun copyThemeCode() {
        val clipboard =
            context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("keyboard_theme", qrCodeText))
        Toast.makeText(context, R.string.keyboard_theme_copied, Toast.LENGTH_SHORT).show()
    }

    fun shareTheme() {
        val file = File(File(context.cacheDir, "shared").apply { mkdirs() }, "theme_qr.png")
        FileOutputStream(file).use {
            ThemeCode.toBitmap(qrCodeText).compress(Bitmap.CompressFormat.PNG, 90, it)
        }
        val uri: Uri = FileProvider.getUriForFile(
            context, "${context.packageName}.fileprovider", file
        )
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_TEXT, shareCodeText())
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(
            Intent.createChooser(
                shareIntent, context.getString(R.string.keyboard_theme_share)
            )
        )
        showQrDialog = false
    }

    val scanOptions = remember {
        ScanOptions().apply {
            setDesiredBarcodeFormats(ScanOptions.QR_CODE)
            setPrompt(context.getString(R.string.keyboard_theme_scan_prompt))
            setOrientationLocked(false)
            setBeepEnabled(false)
        }
    }
    fun finishImport(theme: KeyboardTheme) {
        KeyboardManager.Keyboard.setFollowSystem(context, false)
        KeyboardManager.Keyboard.setThemeId(context, theme.id)
        followSystem = false
        selectedThemeId = theme.id
        themeVersion++
        Toast.makeText(
            context,
            context.getString(R.string.keyboard_theme_imported, theme.name),
            Toast.LENGTH_SHORT,
        ).show()
    }

    fun importTheme(theme: KeyboardTheme) {
        val customs = KeyboardThemePresets.customThemes
        val existingIndex = customs.indexOfFirst { it.id == theme.id }
        if (existingIndex >= 0) {
            ThemeStore.overwrite(existingIndex, theme)
            finishImport(theme)
        } else if (customs.size < ThemeStore.MAX_CUSTOM_THEMES) {
            ThemeStore.import(theme)
            finishImport(theme)
        } else {
            pendingImport = theme
            showOverwriteDialog = true
            Toast.makeText(
                context, R.string.keyboard_theme_overwrite_need, Toast.LENGTH_SHORT
            ).show()
        }
    }

    val scanLauncher = rememberLauncherForActivityResult(ScanContract()) { result ->
        val contents = result.contents ?: return@rememberLauncherForActivityResult
        val importedTheme = ThemeCode.decode(contents)
        if (importedTheme == null) {
            Toast.makeText(context, R.string.keyboard_theme_invalid_qr, Toast.LENGTH_SHORT).show()
            return@rememberLauncherForActivityResult
        }
        importTheme(importedTheme)
    }

    val qrBitmap = qrCodeText.takeIf { it.isNotEmpty() }?.let {
        remember(it) { ThemeCode.toBitmap(it) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.keyboard_theme),
                        fontSize = barFontSize,
                        fontWeight = FontWeight.Bold,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back),
                            modifier = Modifier.scale(0.8f),
                            tint = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { scanLauncher.launch(scanOptions) }) {
                        Icon(
                            Icons.Filled.QrCodeScanner,
                            contentDescription = stringResource(R.string.keyboard_theme_scan),
                            tint = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                    IconButton(onClick = { refresh() }) {
                        Icon(
                            Icons.Filled.Refresh,
                            contentDescription = stringResource(R.string.keyboard_theme_refresh),
                            tint = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                    IconButton(onClick = { showThemePicker = true }) {
                        Icon(
                            Icons.Filled.Share,
                            contentDescription = stringResource(R.string.keyboard_theme_share),
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

            key(themeVersion) {
                SettingsGroup(title = stringResource(R.string.keyboard_theme_setting)) {
                Spacer(Modifier.height(4.dp))
                SwitchRow(
                    title = stringResource(R.string.keyboard_theme_follow_system),
                    checked = followSystem,
                    onCheckedChange = {
                        followSystem = it
                        KeyboardManager.Keyboard.setFollowSystem(context, it)
                    },
                    showDivider = true,
                )
                Spacer(Modifier.height(8.dp))
                if (followSystem) {
                    Text(
                        text = stringResource(R.string.keyboard_theme_light),
                        fontSize = rowSubFontSize,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                    )
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        items(KeyboardTheme.PRESETS) { theme ->
                            val isSelected = theme.id == selectedLightThemeId
                            ThemeChip(
                                theme = theme,
                                selected = isSelected,
                                isCustom = theme.id in customThemeIds,
                                onClick = {
                                    selectedLightThemeId = theme.id
                                    KeyboardManager.Keyboard.setLightThemeId(context, theme.id)
                                },
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    HorizontalDivider(
                        thickness = 0.5.dp,
                        color = MaterialTheme.colorScheme.outlineVariant,
                    )
                    Text(
                        text = stringResource(R.string.keyboard_theme_dark),
                        fontSize = rowSubFontSize,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                    )
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        items(KeyboardTheme.PRESETS) { theme ->
                            val isSelected = theme.id == selectedDarkThemeId
                            ThemeChip(
                                theme = theme,
                                selected = isSelected,
                                isCustom = theme.id in customThemeIds,
                                onClick = {
                                    selectedDarkThemeId = theme.id
                                    KeyboardManager.Keyboard.setDarkThemeId(context, theme.id)
                                },
                            )
                        }
                    }
                } else {
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        items(KeyboardTheme.PRESETS) { theme ->
                            val isSelected = theme.id == selectedThemeId
                            ThemeChip(
                                theme = theme,
                                selected = isSelected,
                                isCustom = theme.id in customThemeIds,
                                onClick = {
                                    selectedThemeId = theme.id
                                    KeyboardManager.Keyboard.setThemeId(context, theme.id)
                                },
                            )
                        }
                    }
                }
            }
            }
            Spacer(Modifier.height(14.dp))
        }
    }

    if (showThemePicker) {
        val customThemes = KeyboardThemePresets.customThemes
        AlertDialog(
            onDismissRequest = { showThemePicker = false },
            title = { Text(stringResource(R.string.keyboard_theme_export_title)) },
            text = {
                if (customThemes.isEmpty()) {
                    Text(
                        text = stringResource(R.string.keyboard_theme_export_empty),
                        fontSize = rowSubFontSize,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        customThemes.forEach { theme ->
                            TextButton(
                                onClick = {
                                    qrCodeText = ThemeCode.encode(theme)
                                    showThemePicker = false
                                    showQrDialog = true
                                },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(
                                    text = theme.name,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showThemePicker = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    if (showOverwriteDialog && pendingImport != null) {
        val existing = KeyboardThemePresets.customThemes
        AlertDialog(
            onDismissRequest = { showOverwriteDialog = false },
            title = { Text(stringResource(R.string.keyboard_theme_overwrite_title)) },
            text = {
                if (existing.isEmpty()) {
                    Text(stringResource(R.string.keyboard_theme_overwrite_empty))
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        existing.forEachIndexed { index, theme ->
                            TextButton(
                                onClick = {
                                    pendingImport?.let {
                                        ThemeStore.overwrite(index, it)
                                        finishImport(it)
                                    }
                                    pendingImport = null
                                    showOverwriteDialog = false
                                },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(
                                    text = theme.name,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    pendingImport = null
                    showOverwriteDialog = false
                }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    if (showQrDialog && qrBitmap != null) {
        AlertDialog(
            onDismissRequest = { showQrDialog = false },
            title = { Text(stringResource(R.string.keyboard_theme_share_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        text = stringResource(R.string.keyboard_theme_share_subtitle),
                        fontSize = rowSubFontSize,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = sharedTheme()?.name.orEmpty(),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Image(
                        bitmap = qrBitmap.asImageBitmap(),
                        contentDescription = stringResource(R.string.keyboard_theme_share_title),
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(1f),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { shareTheme() }) {
                    Text(stringResource(R.string.keyboard_theme_share))
                }
            },
            dismissButton = {
                TextButton(onClick = { copyThemeCode() }) {
                    Text(stringResource(R.string.keyboard_theme_copy_code))
                }
                TextButton(onClick = { showQrDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}