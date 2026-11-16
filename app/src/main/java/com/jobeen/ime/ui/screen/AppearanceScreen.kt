// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.ui.screen

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BrightnessMedium
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.jobeen.ime.R
import com.jobeen.ime.data.keyboard.theme.FontChoice
import com.jobeen.ime.data.keyboard.theme.FontRole
import com.jobeen.ime.data.keyboard.theme.GlobalFonts
import android.content.Context
import android.text.format.Formatter
import com.jobeen.ime.data.keyboard.theme.SkinFonts
import com.jobeen.ime.skin.runtime.SkinSnapshotProvider
import com.jobeen.ime.ui.screen.ScreenComponent.GroupCard
import com.jobeen.ime.ui.screen.ScreenComponent.NavRow
import com.jobeen.ime.ui.screen.ScreenComponent.SettingsGroup
import com.jobeen.ime.ui.screen.ScreenComponent.SingleChoiceDialog
import com.jobeen.ime.ui.screen.SettingsPageScaffold
import com.jobeen.ime.ui.theme.AppAppearanceMode
import java.io.ByteArrayOutputStream
import androidx.compose.ui.unit.sp

/**
 * 外观页：分类列表页（与键盘设置同构）。主页行＝字体／应用外观，
 * 各行进各详情页；各详情页内容复用旧长页的各段，不新做设置项、不改行为。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppearanceScreen(
    mode: AppAppearanceMode,
    onModeChange: (AppAppearanceMode) -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    var showModeDialog by remember { mutableStateOf(false) }
    // 2026-11-01 默认跟随系统：选项为跟随系统／莫奈色／亮色／暗色
    // （与枚举顺序一致；标签与 modeValues 按下标配对，增项须同序增标签）。
    val modeLabels = listOf(
        stringResource(R.string.app_appearance_follow_system),
        stringResource(R.string.app_appearance_monet),
        stringResource(R.string.app_appearance_light),
        stringResource(R.string.app_appearance_dark),
    )
    val modeValues = AppAppearanceMode.entries

    // ---- 字体 ----
    var fontChoices by remember {
        mutableStateOf(FontRole.entries.associateWith { GlobalFonts.getChoice(context, it) })
    }
    var fontTarget by remember { mutableStateOf<FontRole?>(null) }
    var fontImportError by remember { mutableStateOf(false) }
    // 导入字体管理（施工单 9 特征 2）：列表在点行时现取，不在组合中做文件 IO。
    var showManageFonts by remember { mutableStateOf(false) }
    var managedFonts by remember { mutableStateOf<List<GlobalFonts.ImportedFont>>(emptyList()) }
    var fontDeleteTarget by remember { mutableStateOf<GlobalFonts.ImportedFont?>(null) }
    var pendingFontRole by remember { mutableStateOf<FontRole?>(null) }
    val skinFamily = remember {
        SkinFonts.resolveFamily(SkinSnapshotProvider.peek()?.fontFamily)
    }
    val fontPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        val role = pendingFontRole
        pendingFontRole = null
        if (uri == null || role == null) return@rememberLauncherForActivityResult
        fontImportError = false
        val imported = runCatching {
            val input = context.contentResolver.openInputStream(uri) ?: return@runCatching null
            input.use { stream ->
                val out = ByteArrayOutputStream()
                val buf = ByteArray(8192)
                var total = 0L
                var overflow = false
                while (true) {
                    val n = stream.read(buf)
                    if (n < 0) break
                    total += n
                    if (total > GlobalFonts.MAX_IMPORT_BYTES) {
                        overflow = true
                        break
                    }
                    out.write(buf, 0, n)
                }
                if (overflow) return@runCatching null
                val bytes = out.toByteArray()
                val mime = context.contentResolver.getType(uri)
                val ext = when (mime) {
                    "font/ttf", "application/x-font-ttf" -> "ttf"
                    "font/otf", "application/x-font-opentype" -> "otf"
                    else -> uri.lastPathSegment
                        ?.substringAfterLast('.', "")
                        ?.lowercase()
                        .orEmpty()
                }
                GlobalFonts.importFont(context, bytes, ext)
            }
        }.getOrNull()
        when (imported) {
            is GlobalFonts.ImportResult.Ok -> {
                GlobalFonts.setChoice(context, role, FontChoice.Imported(imported.fileId))
                fontChoices = FontRole.entries.associateWith { GlobalFonts.getChoice(context, it) }
            }
            else -> fontImportError = true
        }
    }

    // 分类列表页（与键盘设置同构）：主页为分类行列表，点行进各详情页；
    // 皮肤行直达皮肤库页，不另开详情页。顶栏标题仍为外观，子页标题在组内，
    // 主页之下不再有标题为外观的长单页。
    var category by remember { mutableStateOf<AppearanceCategory?>(null) }
    BackHandler(enabled = category != null) { category = null }

    SettingsPageScaffold(
        title = stringResource(R.string.appearance_title),
        onBack = { if (category != null) category = null else onBack() },
    ) {
        val open = category
        if (open == null) {
            GroupCard {
                NavRow(
                    title = stringResource(R.string.fonts_title),
                    onClick = { category = AppearanceCategory.FONTS },
                    icon = Icons.Filled.TextFields,
                )
                NavRow(
                    title = stringResource(R.string.app_appearance),
                    onClick = { category = AppearanceCategory.APP },
                    icon = Icons.Filled.BrightnessMedium,
                )
            }
            Spacer(Modifier.height(20.dp))
        } else {
            when (open) {
                AppearanceCategory.FONTS -> {
                    SettingsGroup(title = stringResource(R.string.fonts_title)) {
                        if (skinFamily != null) {
                            Text(
                                text = stringResource(R.string.font_skin_hint),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (fontImportError) {
                            Text(
                                text = stringResource(R.string.font_import_failed),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                        for (role in FontRole.entries) {
                            NavRow(
                                title = fontRoleLabel(role),
                                subtitle = fontChoiceLabel(fontChoices[role]),
                                onClick = { fontTarget = role },
                            )
                        }
                        NavRow(
                            title = stringResource(R.string.font_manage_imported),
                            subtitle = stringResource(R.string.font_manage_imported_desc),
                            onClick = {
                                managedFonts = GlobalFonts.listImported(context)
                                showManageFonts = true
                            },
                        )
                    }
                }
                AppearanceCategory.APP -> {
                    SettingsGroup(title = stringResource(R.string.app_appearance)) {
                        NavRow(
                            title = stringResource(R.string.app_appearance),
                            subtitle = modeLabels[modeValues.indexOf(mode)],
                            onClick = { showModeDialog = true },
                        )
                    }
                    Text(
                        text = stringResource(R.string.app_appearance_monet_note),
                        modifier = Modifier.padding(start = 4.dp),
                    )
                    Spacer(Modifier.height(20.dp))
                }
            }
        }
    }

    if (showModeDialog) {
        SingleChoiceDialog(
            title = stringResource(R.string.app_appearance),
            options = modeLabels,
            selectedIndex = modeValues.indexOf(mode),
            onSelect = { index ->
                onModeChange(modeValues[index])
                showModeDialog = false
            },
            onDismiss = { showModeDialog = false },
        )
    }

    // 导入字体管理（施工单 9 特征 2）：列表＋逐个删除确认；删除后 purge
    // 由 GlobalFonts.deleteImported 一次完成（删文件＋清引用＋版本＋1＋清缓存）。
    if (showManageFonts) {
        SettingsDialog(
            onDismissRequest = { showManageFonts = false },
            title = stringResource(R.string.font_manage_imported),
            body = {
                Column {
                    if (managedFonts.isEmpty()) {
                        Text(
                            text = stringResource(R.string.font_manage_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        for (font in managedFonts) {
                            val usedByLabels = mutableListOf<String>()
                            for (role in font.usedBy) usedByLabels.add(fontRoleLabel(role))
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Column(
                                    modifier = Modifier.weight(1f),
                                ) {
                                    Text(
                                        text = font.fileId,
                                        style = MaterialTheme.typography.bodyLarge,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        text = Formatter.formatFileSize(context, font.sizeBytes) +
                                            " · " + if (usedByLabels.isEmpty()) {
                                                stringResource(R.string.font_manage_unused)
                                            } else {
                                                stringResource(
                                                    R.string.font_manage_used_by,
                                                    usedByLabels.joinToString("、"),
                                                )
                                            },
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                IconButton(onClick = { fontDeleteTarget = font }) {
                                    Icon(
                                        Icons.Filled.Delete,
                                        contentDescription = stringResource(R.string.font_delete),
                                    )
                                }
                            }
                        }
                    }
                }
            },
            actions = listOf(
                SettingsDialogAction(
                    text = stringResource(R.string.cancel),
                    onClick = { showManageFonts = false },
                ),
            ),
        )
    }

    val deleteTarget = fontDeleteTarget
    if (deleteTarget != null) {
        val usedByLabels = mutableListOf<String>()
        for (role in deleteTarget.usedBy) usedByLabels.add(fontRoleLabel(role))
        SettingsConfirmDialog(
            onDismissRequest = { fontDeleteTarget = null },
            title = deleteTarget.fileId,
            text = stringResource(R.string.font_delete_confirm, deleteTarget.fileId) +
                if (usedByLabels.isEmpty()) {
                    ""
                } else {
                    "\n" + stringResource(
                        R.string.font_delete_used_by,
                        usedByLabels.joinToString("、"),
                    )
                },
            confirmText = stringResource(R.string.font_delete),
            onConfirm = {
                GlobalFonts.deleteImported(context, deleteTarget.fileId)
                managedFonts = GlobalFonts.listImported(context)
                fontChoices = FontRole.entries.associateWith { GlobalFonts.getChoice(context, it) }
                fontDeleteTarget = null
            },
            dismissText = stringResource(R.string.cancel),
            confirmColor = MaterialTheme.colorScheme.error,
        )
    }

    val role = fontTarget
    if (role != null) {
        val fontLabels = listOf(
            stringResource(R.string.font_follow),
            stringResource(R.string.font_sans),
            stringResource(R.string.font_serif),
            stringResource(R.string.font_mono),
            stringResource(R.string.font_import),
        )
        val choice = fontChoices[role] ?: FontChoice.Follow
        val selected = when (choice) {
            FontChoice.Follow -> 0
            is FontChoice.System -> when (choice.family) {
                "sans-serif" -> 1
                "serif" -> 2
                "monospace" -> 3
                else -> 0
            }
            is FontChoice.Imported -> 4
        }
        SingleChoiceDialog(
            title = fontRoleLabel(role),
            options = fontLabels,
            selectedIndex = selected,
            onSelect = { index ->
                fontTarget = null
                when (index) {
                    0 -> GlobalFonts.setChoice(context, role, FontChoice.Follow)
                    1 -> GlobalFonts.setChoice(
                        context, role, FontChoice.System("sans-serif"),
                    )
                    2 -> GlobalFonts.setChoice(
                        context, role, FontChoice.System("serif"),
                    )
                    3 -> GlobalFonts.setChoice(
                        context, role, FontChoice.System("monospace"),
                    )
                    4 -> {
                        pendingFontRole = role
                        fontPicker.launch(arrayOf("*/*"))
                    }
                }
                fontChoices = FontRole.entries.associateWith { GlobalFonts.getChoice(context, it) }
            },
            onDismiss = { fontTarget = null },
        )
    }
}

/** 字体角色名。 */
@Composable
private fun fontRoleLabel(role: FontRole): String = stringResource(
    when (role) {
        FontRole.KEY -> R.string.font_role_key
        FontRole.CANDIDATE -> R.string.font_role_candidate
        FontRole.COMMENT -> R.string.font_role_comment
        FontRole.INDEX -> R.string.font_role_index
        FontRole.POPUP -> R.string.font_role_popup
        FontRole.PREEDIT -> R.string.font_role_preedit
        FontRole.TOOLBAR -> R.string.font_role_toolbar
        FontRole.SIDEPANEL -> R.string.font_role_sidepanel
    },
)

/** 字体选择副标题（导入文件不显示无意义的内部 id）。 */
@Composable
private fun fontChoiceLabel(choice: FontChoice?): String = when (choice) {
    null, FontChoice.Follow -> stringResource(R.string.font_follow)
    is FontChoice.System -> when (choice.family) {
        "sans-serif" -> stringResource(R.string.font_sans)
        "serif" -> stringResource(R.string.font_serif)
        "monospace" -> stringResource(R.string.font_mono)
        else -> stringResource(R.string.font_follow)
    }
    is FontChoice.Imported -> stringResource(R.string.font_imported)
}

private enum class AppearanceCategory { FONTS, APP }

