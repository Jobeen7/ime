// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.ui.screen

import android.Manifest
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.jobeen.ime.R
import com.jobeen.ime.data.ThemeStore
import com.jobeen.ime.data.keyboard.theme.KeyboardColors
import com.jobeen.ime.data.keyboard.theme.KeyboardTheme
import com.jobeen.ime.data.keyboard.theme.KeyboardThemePresets
import com.jobeen.ime.data.keyboard.theme.MonetPalette
import com.jobeen.ime.data.keyboard.theme.ThemeCode
import com.jobeen.ime.data.keyboard.theme.ThemeImport
import com.jobeen.ime.data.manager.KeyboardManager
import com.jobeen.ime.skin.library.SkinColorMode
import com.jobeen.ime.skin.library.SkinLibraryPrefs
import com.jobeen.ime.skin.library.SkinMiniPreview
import com.jobeen.ime.skin.library.genericPreviewRows
import com.jobeen.ime.skin.library.miniPreviewPalette
import com.jobeen.ime.ui.screen.ScreenComponent.NavRow
import com.jobeen.ime.ui.screen.ScreenComponent.SettingsGroup
import com.jobeen.ime.ui.theme.LocalMonetCardColor
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import kotlinx.coroutines.delay

/**
 * 内置皮肤选择页——取回原代码（正式版 v1.1.20 皮肤库页 `SkinLibraryScreen`
 * 内置主题卡那段，`54211a5e^`；自定义皮肤随 11-16 删除令与库页一起删，
 * 本页只取内置主题卡选中态＋卡片切换＋旧主题扫码导入，不取皮肤卡、
 * 快照、⋮菜单、扫码建皮肤、多选等自定义皮肤部分）。
 *
 * 原口径（库页注释）：暗夜＋素白合并为一张“默认”卡（浅素白／深暗夜
 * 双槽）；落日已删，不再出卡；卡片只选主题家族，深浅一律由颜色模式
 * 三项决定（跟随系统／浅色／深色），点卡不再翻跟随开关。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BuiltinThemeScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    // 内置主题卡选中态（原库页 305-315：偏好在本地镜像，点卡/扫码导入
    // 旧主题时同步更新；深浅不由卡片决定，一律走颜色模式三项）。
    val colorMode = SkinLibraryPrefs.getColorMode(context)
    var themeId by remember {
        mutableStateOf(KeyboardManager.Keyboard.getThemeId(context))
    }
    var themeLightId by remember {
        mutableStateOf(KeyboardManager.Keyboard.getLightThemeId(context))
    }
    var themeDarkId by remember {
        mutableStateOf(KeyboardManager.Keyboard.getDarkThemeId(context))
    }
    var dialogText by remember { mutableStateOf<Pair<String, String>?>(null) }
    var pendingLegacyImport by remember { mutableStateOf<KeyboardTheme?>(null) }
    var showLegacyOverwriteDialog by remember { mutableStateOf(false) }
    // 切换成功绿勾（2026-11-02 用户定：切换文案改为绿勾图；非空 = 正显示的皮肤名，仅作无障碍描述）。
    var switchCheck by remember { mutableStateOf<String?>(null) }

    fun toast(message: String) {
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }

    // 切换成功绿勾 500ms 后自动消失（原库页 LaunchedEffect）。
    LaunchedEffect(switchCheck) {
        if (switchCheck != null) {
            delay(500)
            switchCheck = null
        }
    }

    val scanPromptText = stringResource(R.string.skin_lib_scan_prompt)
    val legacyQrInvalidTitleText = stringResource(R.string.skin_lib_legacy_qr_invalid_title)
    val legacyQrInvalidBodyText = stringResource(R.string.skin_lib_legacy_qr_invalid_body)
    val legacyImportedTitleText = stringResource(R.string.skin_lib_legacy_imported_title)
    val legacyImportedFormat = stringResource(R.string.skin_lib_legacy_imported)
    val cameraPermissionNeededText = stringResource(R.string.skin_lib_camera_permission_needed)
    val builtinThemeDefaultText = stringResource(R.string.builtin_theme_default)

    // 内置主题卡选中态派生（原库页 615-622）：生效主题 id 与键盘侧
    // KeyboardColors.themeFor 同口径（颜色模式唯一决定深浅）。
    val systemDark = isSystemInDarkTheme()
    val effectiveThemeId = KeyboardColors.effectiveBuiltinThemeId(
        colorMode, themeId, themeLightId, themeDarkId, systemDark,
    )

    // ---- 二维码导入旧主题（原库页 511-582：旧主题短码 IMEKBTHEME:
    // 走共享旧主题导入 ThemeImport；v3 皮肤码分流不恢复） ----
    val qrScanOptions = remember {
        ScanOptions().apply {
            setDesiredBarcodeFormats(ScanOptions.QR_CODE)
            setPrompt(scanPromptText)
            setOrientationLocked(false)
            setBeepEnabled(false)
        }
    }
    val qrScanLauncher = rememberLauncherForActivityResult(ScanContract()) { result ->
        val contents = result.contents
        if (contents.isNullOrBlank()) return@rememberLauncherForActivityResult
        if (!contents.trim().startsWith(ThemeCode.PREFIX)) {
            dialogText = legacyQrInvalidTitleText to legacyQrInvalidBodyText
            return@rememberLauncherForActivityResult
        }
        val importedTheme = ThemeCode.decode(contents.trim())
        if (importedTheme == null) {
            dialogText = legacyQrInvalidTitleText to legacyQrInvalidBodyText
            return@rememberLauncherForActivityResult
        }
        when (ThemeImport.import(importedTheme)) {
            is ThemeImport.Outcome.Applied -> {
                ThemeImport.apply(context, importedTheme)
                themeId = importedTheme.id
                dialogText = legacyImportedTitleText to
                    String.format(legacyImportedFormat, importedTheme.name)
            }
            is ThemeImport.Outcome.NeedsOverwriteSlot -> {
                pendingLegacyImport = importedTheme
                showLegacyOverwriteDialog = true
            }
        }
    }
    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            qrScanLauncher.launch(qrScanOptions)
        } else {
            toast(cameraPermissionNeededText)
        }
    }
    fun launchQrScan() {
        val granted = androidx.core.content.ContextCompat.checkSelfPermission(
            context, Manifest.permission.CAMERA,
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (granted) {
            qrScanLauncher.launch(qrScanOptions)
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    SettingsPageScaffold(
        title = stringResource(R.string.keyboard_theme),
        onBack = onBack,
    ) {
        // 内置主题卡（原库页 853-935：主题设置页脱钩后，库页为内置主题
        // 唯一切换入口）：暗夜＋素白合并为一张“默认”卡（浅素白／深暗夜
        // 双槽）；落日已删，不再出卡；卡片只选主题家族，深浅一律由颜色
        // 模式三项决定，点卡不再翻跟随开关。选中态与皮肤卡一致（绿框＋
        // 对勾）。v3 自定义皮肤已删，无生效皮肤、无多选、无忙态，故
        // revert/ busy/ multiSelect/ skinActive 四个守卫一并去掉。
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            val defaultTheme = KeyboardTheme.byId(
                when (colorMode) {
                    SkinColorMode.DARK -> KeyboardTheme.DARK_DEFAULT_ID
                    SkinColorMode.LIGHT -> KeyboardTheme.LIGHT_DEFAULT_ID
                    SkinColorMode.SYSTEM -> if (systemDark) {
                        KeyboardTheme.DARK_DEFAULT_ID
                    } else {
                        KeyboardTheme.LIGHT_DEFAULT_ID
                    }
                },
            )
            BuiltinThemeCard(
                theme = defaultTheme,
                label = stringResource(R.string.builtin_theme_default),
                isActive = effectiveThemeId in setOf(
                    KeyboardTheme.DARK_DEFAULT_ID,
                    KeyboardTheme.LIGHT_DEFAULT_ID,
                ),
                enabled = true,
                onClick = {
                    // 切默认双槽：主题 id 置入槽位对内，跟随系统下
                    // effectiveBuiltinThemeId 即按系统取槽位。
                    KeyboardManager.Keyboard.setThemeId(
                        context, KeyboardTheme.DARK_DEFAULT_ID,
                    )
                    KeyboardManager.Keyboard.setLightThemeId(
                        context, KeyboardTheme.LIGHT_DEFAULT_ID,
                    )
                    KeyboardManager.Keyboard.setDarkThemeId(
                        context, KeyboardTheme.DARK_DEFAULT_ID,
                    )
                    themeId = KeyboardTheme.DARK_DEFAULT_ID
                    themeLightId = KeyboardTheme.LIGHT_DEFAULT_ID
                    themeDarkId = KeyboardTheme.DARK_DEFAULT_ID
                    switchCheck = builtinThemeDefaultText
                },
                modifier = Modifier.weight(1f),
            )
            val monetTheme = KeyboardTheme.byId(MonetPalette.ID)
            BuiltinThemeCard(
                theme = monetTheme,
                isActive = effectiveThemeId == MonetPalette.ID,
                enabled = true,
                onClick = {
                    // 切莫奈主题：只置主题 id，深浅由颜色模式三项决定；
                    // 跟随系统下莫奈调色板自身按系统深浅分支。
                    KeyboardManager.Keyboard.setThemeId(context, monetTheme.id)
                    themeId = monetTheme.id
                    switchCheck = monetTheme.name
                },
                modifier = Modifier.weight(1f),
            )
        }
        SettingsGroup(title = stringResource(R.string.keyboard_theme_import)) {
            NavRow(
                title = stringResource(R.string.theme_scan),
                subtitle = null,
                onClick = { launchQrScan() },
            )
        }
    }

    // ---- 对话框组（原库页 1158-1172） ----
    switchCheck?.let { name ->
        Dialog(
            onDismissRequest = { switchCheck = null },
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            Image(
                painter = painterResource(R.drawable.skin_switch_check),
                contentDescription = name,
                modifier = Modifier.size(72.dp),
            )
        }
    }
    dialogText?.let { (title, body) ->
        SettingsDialog(
            onDismissRequest = { dialogText = null },
            title = title,
            body = { Text(body) },
            actions = listOf(
                SettingsDialogAction(
                    text = stringResource(R.string.cancel),
                    onClick = { dialogText = null },
                ),
            ),
        )
    }
    // 库页扫码旧主题满槽时的选槽对话框（原库页 1470-1530）：选一现有
    // 自定义主题槽位覆盖；自定义主题最多 2 个，满槽时此列表非空。
    if (showLegacyOverwriteDialog && pendingLegacyImport != null) {
        val pending = pendingLegacyImport!!
        val legacyImportedTitle = stringResource(R.string.skin_lib_legacy_imported_title)
        val legacyOverwrittenFormat = stringResource(R.string.skin_lib_legacy_overwritten)
        val existing = KeyboardThemePresets.customThemes
        SettingsDialog(
            onDismissRequest = {
                showLegacyOverwriteDialog = false
                pendingLegacyImport = null
            },
            title = stringResource(R.string.skin_lib_custom_theme_full),
            body = {
                if (existing.isEmpty()) {
                    Text(stringResource(R.string.skin_lib_no_theme_to_overwrite))
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(stringResource(R.string.skin_lib_choose_theme_to_overwrite))
                        for ((index, theme) in existing.withIndex()) {
                            TextButton(
                                onClick = {
                                    showLegacyOverwriteDialog = false
                                    pendingLegacyImport = null
                                    ThemeStore.overwrite(index, pending)
                                    ThemeImport.apply(context, pending)
                                    themeId = pending.id
                                    dialogText = legacyImportedTitle to
                                        String.format(legacyOverwrittenFormat, theme.name, pending.name)
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
            actions = listOf(
                SettingsDialogAction(
                    text = stringResource(R.string.cancel),
                    onClick = {
                        showLegacyOverwriteDialog = false
                        pendingLegacyImport = null
                    },
                ),
            ),
        )
    }
}

/**
 * 内置主题卡（原库页 1739-1801，逐字取回）：与皮肤卡同形（主题跟随卡＋
 * 迷你预览＋名称，选中态绿框＋对勾——用户 21:18 纠正，不用蓝框），无 ⋮
 * 菜单、无副标题；点按即切。预览用通用示意行只呈现配色（内置主题不
 * 绑定布局）；与同名皮肤卡的区别仅在于有无 ⋮ 菜单。
 */
@Composable
private fun BuiltinThemeCard(
    theme: KeyboardTheme,
    isActive: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
) {
    val borderColor = if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
    Box(
        modifier = modifier
            .clip(MaterialTheme.shapes.large)
            .background(LocalMonetCardColor.current)
            .border(
                width = if (isActive) 2.dp else 1.dp,
                color = borderColor,
                shape = MaterialTheme.shapes.large,
            )
            .clickable(enabled = enabled, onClick = onClick),
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            Box {
                SkinMiniPreview(
                    palette = miniPreviewPalette(theme.colors),
                    layoutRows = genericPreviewRows(),
                )
                if (isActive) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(4.dp)
                            .size(20.dp)
                            .background(MaterialTheme.colorScheme.primary, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Filled.Check,
                            contentDescription = stringResource(R.string.skin_lib_active),
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(14.dp),
                        )
                    }
                }
            }
            Text(
                label ?: theme.name,
                fontWeight = FontWeight.Medium,
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}
