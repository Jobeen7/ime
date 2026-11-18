// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.ui.screen

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
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
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.jobeen.ime.R
import com.jobeen.ime.data.ThemeStore
import com.jobeen.ime.data.keyboard.theme.KeyboardColors
import com.jobeen.ime.data.keyboard.theme.KeyboardTheme
import com.jobeen.ime.data.keyboard.theme.KeyboardThemePresets
import com.jobeen.ime.data.keyboard.theme.MonetPalette
import com.jobeen.ime.data.keyboard.theme.ThemeCode
import com.jobeen.ime.data.keyboard.theme.ThemeImport
import com.jobeen.ime.data.manager.KeyboardManager
import com.jobeen.ime.skin.diag.DiagLog
import com.jobeen.ime.skin.library.SkinColorMode
import com.jobeen.ime.skin.library.SkinLibraryPrefs
import com.jobeen.ime.skin.library.SkinMiniPreview
import com.jobeen.ime.skin.library.genericPreviewRows
import com.jobeen.ime.skin.library.miniPreviewPalette
import com.jobeen.ime.ui.theme.LocalMonetCardColor
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import kotlinx.coroutines.delay

/**
 * 内置皮肤选择页——取回原代码（正式版 v1.1.20 皮肤库页 `SkinLibraryScreen`
 * 内置主题卡那段，`54211a5e^`；自定义皮肤随 11-16 删除令与库页一起删，
 * 本页只取内置主题卡选中态＋卡片切换＋旧主题扫码导入，不取皮肤卡、
 * 快照、扫码建皮肤、多选等自定义皮肤部分）。
 * 顶栏 ⋮ 菜单 7 项（2026-11-18 Jobeen 定，顺序固定）：扫码导入／分享／
 * 导出／跟随系统／浅色／深色／重置内置。分享与导出走 ThemeCode 二维码
 * 链路（简意 shareTheme／QR 对话框原样，见下）；重置内置的确认框取原
 * 库页，动作等效于默认卡的切默认双槽（原 applier.revertToDefault 已随
 * 皮肤库删除，内置页无生效皮肤可清；颜色模式不动，与确认框文案一致）。
 *
 * 原口径（库页注释）：暗夜＋素白合并为一张“默认”卡（浅素白／深暗夜
 * 双槽）；落日已删，不再出卡；卡片只选主题家族，深浅一律由颜色模式
 * 三项决定（跟随系统／浅色／深色），点卡不再翻跟随开关。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BuiltinThemeScreen(
    onBack: () -> Unit,
    // 颜色模式点选后由宿主重建 Activity 使本页主题生效（原库页
    // SkinLibraryScreen :247，宿主传 recreate()）；缺省空操作。
    onColorModeChanged: () -> Unit = {},
) {
    val context = LocalContext.current
    // 内置主题卡选中态（原库页 305-315：偏好在本地镜像，点卡/扫码导入
    // 旧主题时同步更新；深浅不由卡片决定，一律走颜色模式三项）。
    var colorMode by remember { mutableStateOf(SkinLibraryPrefs.getColorMode(context)) }
    // 顶栏 ⋮ 菜单展开态（原库页 :296）。
    var showTopMenu by remember { mutableStateOf(false) }
    // 扫码来源选择框展开态（简意 showScanSourceDialog）。
    var showScanSourceDialog by remember { mutableStateOf(false) }
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
    // 分享／导出用主题码与对话框态（简意 :149-151 同名 state）。
    var qrCodeText by remember { mutableStateOf<String?>(null) }
    var showQrDialog by remember { mutableStateOf(false) }
    // 重置内置确认框态（原库页 showResetConfirm）。
    var showResetConfirm by remember { mutableStateOf(false) }
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
    // 分享流文案在组合期取全（回调里调 stringResource／context.getString
    // 会被 lint 拦，见原库页 :248-254 的做法）：分享文本格式串、复制
    // 成功 toast、分享面板标题（原库页 shareSkin 用 skin_lib_share_skin）。
    val shareTextFormat = stringResource(R.string.keyboard_theme_share_text)
    val themeCodeCopiedText = stringResource(R.string.keyboard_theme_copied)
    val shareChooserTitleText = stringResource(R.string.skin_lib_share_skin)

    // 内置主题卡选中态派生（原库页 615-622）：生效主题 id 与键盘侧
    // KeyboardColors.themeFor 同口径（颜色模式唯一决定深浅）。
    val systemDark = isSystemInDarkTheme()
    val effectiveThemeId = KeyboardColors.effectiveBuiltinThemeId(
        colorMode, themeId, themeLightId, themeDarkId, systemDark,
    )
    // 当前生效主题（分享／导出对象，与卡片选中态同一口径；byId 有
    // DEFAULT 兜底，恒非空——原库页“无生效皮肤”分支在此页无触发条件）。
    fun currentTheme(): KeyboardTheme = KeyboardTheme.byId(effectiveThemeId)
    // 主题码二维码位图（简意 :~285 的派生逻辑；remember 无条件调用、
    // 以 qrCodeText 为键，空码时为 null——简意把 remember 写在
    // takeIf?.let 里，条件变化会动 slot 表，改成键控写法）。
    val qrBitmap = remember(qrCodeText) {
        qrCodeText?.takeIf { it.isNotEmpty() }?.let { ThemeCode.toBitmap(it) }
    }

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
    // 扫码文本的导入处理（相机扫码与相册解码共用；原 qrScanLauncher
    // 回调本体，原样抽出）：空＝用户取消不提示；前缀不对或解码失败＝
    // 无效码对话框；否则按 ThemeImport 结果走应用或满槽选槽。
    fun handleScannedQrText(contents: String?) {
        if (contents.isNullOrBlank()) return
        if (!contents.trim().startsWith(ThemeCode.PREFIX)) {
            dialogText = legacyQrInvalidTitleText to legacyQrInvalidBodyText
            return
        }
        val importedTheme = ThemeCode.decode(contents.trim())
        if (importedTheme == null) {
            dialogText = legacyQrInvalidTitleText to legacyQrInvalidBodyText
            return
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
    val qrScanLauncher = rememberLauncherForActivityResult(ScanContract()) { result ->
        handleScannedQrText(result.contents)
    }
    // 相册扫码（简意 galleryLauncher 原样：GetContent 选图→解码图中
    // 二维码→与相机扫码同一导入处理；uri 空＝取消不提示，图中无码＝
    // 无效码对话框）。
    val galleryLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val contents = decodeQrFromImage(context, uri)
        if (contents.isNullOrBlank()) {
            dialogText = legacyQrInvalidTitleText to legacyQrInvalidBodyText
            return@rememberLauncherForActivityResult
        }
        handleScannedQrText(contents)
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

    // ---- 当前主题的 ThemeCode 二维码分享（简意 :196-223 原样） ----
    // 简意的 qrCodeText 由导出选择器先置位，本页在点按时现算——故码作参数
    // 传，不读尚未重组的 qrCodeText state（否则读到旧值）。
    fun shareCodeText(code: String): String {
        val theme = currentTheme()
        return String.format(shareTextFormat, theme.name, code)
    }

    fun shareTheme(code: String) {
        val file = File(File(context.cacheDir, "shared").apply { mkdirs() }, "theme_qr.png")
        FileOutputStream(file).use {
            ThemeCode.toBitmap(code).compress(Bitmap.CompressFormat.PNG, 90, it)
        }
        val uri = FileProvider.getUriForFile(
            context, "${context.packageName}.fileprovider", file,
        )
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_TEXT, shareCodeText(code))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(
            Intent.createChooser(shareIntent, shareChooserTitleText),
        )
        showQrDialog = false
    }

    fun copyThemeCode() {
        val clipboard =
            context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("keyboard_theme", qrCodeText.orEmpty()))
        toast(themeCodeCopiedText)
    }

    SettingsPageScaffold(
        title = stringResource(R.string.keyboard_theme),
        onBack = onBack,
        actions = {
            // 顶栏 ⋮ 菜单（原库页 :636-641 写法：Box 包 IconButton＋
            // DropdownMenu，挂在 actions 的 RowScope 内对齐右上角）。
            // 7 项固定顺序（2026-11-18 Jobeen 定，一个不多一个不少）：
            // 扫码导入／分享／导出／跟随系统／浅色／深色／重置内置。
            // 导入皮肤／创建空白皮肤／复制内置主题／批量删除／开发模式
            // 开关是已删的自定义皮肤功能，一律不加。
            Box {
                IconButton(onClick = { showTopMenu = true }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.more))
                }
                DropdownMenu(
                    expanded = showTopMenu,
                    onDismissRequest = { showTopMenu = false },
                ) {
                    // 1. 扫码导入（原库页 :699-704 原样；字串用
                    // skin_lib_scan_import“扫码导入”，不用 theme_scan）。
                    // 点按先弹来源选择（相机／相册，简意 :525-549），
                    // 不直接进相机——菜单里仍只有 7 项，不加入口。
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.skin_lib_scan_import)) },
                        onClick = {
                            showTopMenu = false
                            showScanSourceDialog = true
                        },
                    )
                    // 2. 分享（原库页顶栏版 :709-724 的作用对象是生效中
                    // 的自定义皮肤；内置主题走 ThemeCode 二维码链路，
                    // 即简意 shareTheme() :203-223：当前主题编成二维码
                    // PNG 调系统分享；无生效皮肤分支在本页无触发条件，
                    // 当前主题恒可解析，见 currentTheme 注释）。
                    // 菜单文案用短词“分享”（R.string.share）；字串
                    // skin_lib_share_skin 仍是“分享皮肤”，留给下方
                    // 系统分享面板标题（shareChooserTitleText）用。
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.share)) },
                        onClick = {
                            showTopMenu = false
                            val code = ThemeCode.encode(currentTheme())
                            qrCodeText = code
                            shareTheme(code)
                        },
                    )
                    // 3. 导出（简意导出选择器 :440-479 的作用是挑主题
                    // 生成二维码；本页当前主题已确定，直接出二维码
                    // 对话框，框内分享／复制码／关闭见下方 showQrDialog）。
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.skin_lib_export_skin)) },
                        onClick = {
                            showTopMenu = false
                            qrCodeText = ThemeCode.encode(currentTheme())
                            showQrDialog = true
                        },
                    )
                    // 4-6. 颜色模式（三项直列，当前项打勾；原库页 :759-774
                    // 照原样：持久化后本地置 mode、记一笔、调
                    // onColorModeChanged() 让 Activity 重建使本页主题生效）。
                    for (mode in SkinColorMode.entries) {
                        val label = when (mode) {
                            SkinColorMode.SYSTEM -> stringResource(R.string.keyboard_theme_follow_system)
                            SkinColorMode.LIGHT -> stringResource(R.string.color_mode_light)
                            SkinColorMode.DARK -> stringResource(R.string.color_mode_dark)
                        }
                        DropdownMenuItem(
                            text = {
                                Text(
                                    (if (mode == colorMode) "✓ " else "") + label,
                                )
                            },
                            onClick = {
                                showTopMenu = false
                                SkinLibraryPrefs.setColorMode(context, mode)
                                colorMode = mode
                                DiagLog.i(
                                    "library", "color_mode_changed",
                                    mapOf("mode" to mode.stored),
                                )
                                onColorModeChanged()
                            },
                        )
                    }
                    // 7. 重置内置（原库页 :805-813：红字项，点按只弹确认框，
                    // 确认逻辑见下方 showResetConfirm 对话框）。
                    DropdownMenuItem(
                        text = {
                            Text(
                                stringResource(R.string.skin_lib_reset_builtin),
                                color = MaterialTheme.colorScheme.error,
                            )
                        },
                        onClick = {
                            showTopMenu = false
                            showResetConfirm = true
                        },
                    )
                }
            }
        },
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
    // 扫码来源选择（简意 :525-549 原样：相机／相册两项＋取消；
    // 相机走现有 launchQrScan()，相册走图库选图＋解码）。
    if (showScanSourceDialog) {
        SettingsDialog(
            onDismissRequest = { showScanSourceDialog = false },
            title = stringResource(R.string.keyboard_theme_scan_source),
            body = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(
                        onClick = {
                            showScanSourceDialog = false
                            launchQrScan()
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = stringResource(R.string.keyboard_theme_scan_camera),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    TextButton(
                        onClick = {
                            showScanSourceDialog = false
                            galleryLauncher.launch("image/*")
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = stringResource(R.string.keyboard_theme_scan_gallery),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            },
            actions = listOf(
                SettingsDialogAction(
                    text = stringResource(R.string.cancel),
                    onClick = { showScanSourceDialog = false },
                ),
            ),
        )
    }
    // 主题二维码对话框（简意 :561-595 原样，框体用本页 SettingsDialog：
    // 副标题＋主题名＋二维码图；操作：分享／复制主题码／关闭）。
    if (showQrDialog && qrBitmap != null) {
        SettingsDialog(
            onDismissRequest = { showQrDialog = false },
            title = stringResource(R.string.keyboard_theme_share_title),
            body = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        text = stringResource(R.string.keyboard_theme_share_subtitle),
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = currentTheme().name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Image(
                        bitmap = qrBitmap!!.asImageBitmap(),
                        contentDescription = stringResource(R.string.keyboard_theme_share_title),
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(1f),
                    )
                }
            },
            actions = listOf(
                SettingsDialogAction(
                    text = stringResource(R.string.share),
                    onClick = { qrCodeText?.let { shareTheme(it) } },
                ),
                SettingsDialogAction(
                    text = stringResource(R.string.keyboard_theme_copy_code),
                    onClick = { copyThemeCode() },
                ),
                SettingsDialogAction(
                    text = stringResource(R.string.close),
                    onClick = { showQrDialog = false },
                ),
            ),
        )
    }
    // 重置内置确认框（原库页 :1390-1410 原样：标题／说明／确认字；
    // onConfirm 的动作本体原是 applier.revertToDefault() 清生效皮肤，
    // 已随皮肤库删除——内置页无生效皮肤可清，等效动作取默认卡的
    // 切默认双槽（同页已恢复的原库页代码），颜色模式不动，与框内
    // 文案“各项设置（包括颜色模式选择）不受影响”一致；DiagLog 沿用
    // 原事件名 builtin_reset，键改 previousThemeId（无皮肤可记）。
    if (showResetConfirm) {
        val resetDoneText = stringResource(R.string.skin_lib_reset_done)
        SettingsConfirmDialog(
            onDismissRequest = { showResetConfirm = false },
            title = stringResource(R.string.skin_lib_reset_builtin_title),
            text = stringResource(R.string.skin_lib_reset_builtin_message),
            confirmText = stringResource(R.string.skin_lib_reset_confirm),
            onConfirm = {
                showResetConfirm = false
                val previousThemeId = effectiveThemeId
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
                DiagLog.i(
                    "library", "builtin_reset",
                    mapOf("previousThemeId" to previousThemeId),
                )
                toast(resetDoneText)
            },
            dismissText = stringResource(R.string.cancel),
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

// 从图库图片解码二维码文本（简意 decodeQrFromImage 原样）。
private fun decodeQrFromImage(context: Context, uri: Uri): String? {
    val bitmap = runCatching {
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
    }.getOrNull() ?: return null
    val width = bitmap.width
    val height = bitmap.height
    if (width <= 0 || height <= 0) return null
    val pixels = IntArray(width * height)
    bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
    val binaryBitmap = BinaryBitmap(HybridBinarizer(RGBLuminanceSource(width, height, pixels)))
    val hints = mapOf(
        DecodeHintType.TRY_HARDER to true,
        DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
    )
    return runCatching {
        MultiFormatReader().apply { setHints(hints) }.decode(binaryBitmap).text
    }.getOrNull()
}
