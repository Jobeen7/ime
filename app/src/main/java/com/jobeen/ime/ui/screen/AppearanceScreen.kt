// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.ui.screen

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BrightnessMedium
import androidx.compose.material.icons.filled.Style
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.jobeen.ime.R
import com.jobeen.ime.data.keyboard.theme.BackgroundBlurMode
import com.jobeen.ime.data.keyboard.theme.FontChoice
import com.jobeen.ime.data.keyboard.theme.FontRole
import com.jobeen.ime.data.keyboard.theme.GlobalFonts
import com.jobeen.ime.data.keyboard.theme.GlobalStyle
import com.jobeen.ime.data.keyboard.theme.GlobalStyleMode
import com.jobeen.ime.data.keyboard.theme.GlobalStylePrefs
import com.jobeen.ime.data.keyboard.theme.SkinFonts
import com.jobeen.ime.data.keyboard.theme.SurfaceOverride
import com.jobeen.ime.data.keyboard.theme.VideoPrefs
import com.jobeen.ime.skin.runtime.SkinSnapshotProvider
import com.jobeen.ime.ui.screen.ScreenComponent.GroupCard
import com.jobeen.ime.ui.screen.ScreenComponent.NavRow
import com.jobeen.ime.ui.screen.ScreenComponent.SettingsGroup
import com.jobeen.ime.ui.screen.ScreenComponent.SingleChoiceDialog
import com.jobeen.ime.ui.screen.ScreenComponent.SliderRow
import com.jobeen.ime.ui.screen.ScreenComponent.SwitchRow
import com.jobeen.ime.ui.screen.SettingsPageScaffold
import com.jobeen.ime.ui.screen.SettingsSectionTitle
import com.jobeen.ime.ui.theme.AppAppearanceMode
import java.io.ByteArrayOutputStream
import kotlin.math.roundToInt
import androidx.compose.foundation.border
import androidx.compose.ui.unit.sp

/**
 * 外观页：分类列表页（与键盘设置同构）。主页行＝键盘皮肤／视频背景／
 * 全局样式／字体／应用外观；皮肤行直达皮肤库页，其余各行进各详情页。
 * 各详情页内容复用旧长页的各段，不新做设置项、不改行为。
 *
 * 简洁约束：全局样式页只有 2 行（主题／自定义），每行开对话框
 * 编辑器；颜色用预设色（含跟随），不用十六进制输入；改动 staged，
 * 点应用才写偏好，避免拖动中反复重建键盘。
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

    // ---- 视频背景 ----
    var videoEnabled by remember { mutableStateOf(VideoPrefs.isEnabled(context)) }
    val videoSnapshot = remember { SkinSnapshotProvider.peek() }
    val videoStatus = when {
        videoSnapshot == null -> stringResource(R.string.video_status_noskin)
        videoSnapshot.background?.videoSha256 != null ->
            stringResource(R.string.video_status_with)
        else -> stringResource(R.string.video_status_without)
    }

    // ---- 全局样式：2 行＋对话框编辑器（改动 staged，应用才写偏好） ----
    var globalDialogMode by remember { mutableStateOf<GlobalStyleMode?>(null) }

    // ---- 字体 ----
    var fontChoices by remember {
        mutableStateOf(FontRole.entries.associateWith { GlobalFonts.getChoice(context, it) })
    }
    var fontTarget by remember { mutableStateOf<FontRole?>(null) }
    var fontImportError by remember { mutableStateOf(false) }
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
                    title = stringResource(R.string.video_background),
                    onClick = { category = AppearanceCategory.VIDEO },
                    icon = Icons.Filled.VideoLibrary,
                )
                NavRow(
                    title = stringResource(R.string.global_style),
                    onClick = { category = AppearanceCategory.GLOBAL },
                    icon = Icons.Filled.Style,
                )
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
                AppearanceCategory.VIDEO -> {
                    SettingsGroup(title = stringResource(R.string.video_background)) {
                        SwitchRow(
                            title = stringResource(R.string.video_background),
                            checked = videoEnabled,
                            onCheckedChange = { checked ->
                                VideoPrefs.setEnabled(context, checked)
                                videoEnabled = checked
                            },
                        )
                        Text(
                            text = videoStatus,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = stringResource(R.string.video_note),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                AppearanceCategory.GLOBAL -> {
                    // 全局样式页：2 行（主题／自定义），每行开对话框编辑器；
                    // 改动 staged：对话框内只改草稿，点应用才写该模式偏好。
                    SettingsSectionTitle(stringResource(R.string.global_style))
                    Text(
                        text = stringResource(R.string.global_style_note),
                        modifier = Modifier.padding(start = 4.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    GroupCard {
                        NavRow(
                            title = stringResource(R.string.global_style_theme),
                            subtitle = stringResource(R.string.global_style_theme_desc),
                            onClick = { globalDialogMode = GlobalStyleMode.THEME },
                        )
                        NavRow(
                            title = stringResource(R.string.global_style_custom),
                            subtitle = stringResource(R.string.global_style_custom_desc),
                            onClick = { globalDialogMode = GlobalStyleMode.CUSTOM },
                        )
                    }
                    Spacer(Modifier.height(20.dp))
                }
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

    val gMode = globalDialogMode
    if (gMode != null) {
        GlobalStyleDialog(
            mode = gMode,
            initial = GlobalStylePrefs.get(context, gMode),
            onApply = { draft ->
                GlobalStylePrefs.put(context, gMode, draft)
                globalDialogMode = null
            },
            onDismiss = { globalDialogMode = null },
        )
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

/** 颜色选择目标（预设色对话框共用）。 */
private enum class ColorTarget {
    KEY_BG, KEY_BORDER, TOOLBAR_BG, TOOLBAR_BORDER, BAR_BG,
}

/** 预设色：跟随＋常用色（不用十六进制输入，一目了然）。 */
private val COLOR_PRESETS: List<Pair<Int, Int?>> = listOf(
    R.string.follow to null,
    R.string.color_white to 0xFFFFFFFF.toInt(),
    R.string.color_light_gray to 0xFFEEEEEE.toInt(),
    R.string.color_dark_gray to 0xFF424242.toInt(),
    R.string.color_black to 0xFF000000.toInt(),
    R.string.color_red to 0xFFE53935.toInt(),
    R.string.color_green to 0xFF43A047.toInt(),
    R.string.color_blue to 0xFF1E88E5.toInt(),
    R.string.color_orange to 0xFFFB8C00.toInt(),
)

/** 预设色名（行副标题用；跟随＝未设置，非预设值＝自定义颜色）。 */
@Composable
private fun colorName(value: Int?): String {
    if (value == null) return stringResource(R.string.follow)
    val res = COLOR_PRESETS.firstOrNull { it.second == value }?.first
    return if (res != null) stringResource(res) else stringResource(R.string.color_custom)
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

private enum class AppearanceCategory { VIDEO, GLOBAL, FONTS, APP }

/**
 * 全局样式对话框编辑器（主题／自定义各一套草稿）：
 * 分段可折叠（入口默认全折叠，点卡展开；值标签一位小数，与图示一致）；
 * 分段自定义开关＝字段有值（关＝写回跟随）；
 * 背景／边框颜色走预设色（含跟随）；改动 staged：恢复默认＝草稿复位
 * （默认即跟随，唯磨砂叠加强度默认 0.25），取消＝丢弃草稿，
 * 应用＝整套写该模式偏好。
 *
 * 口径映射（Jime 模型 → 参考分段）：
 * - 键盘整体：自定义键盘四角开关＋顶角滑杆（关＝顶角跟随）。
 * - 按键：背景（预设色，含跟随）／自定义边框粗细＋滑杆＋边框颜色／
 *   自定义圆角＋滑杆／动态模糊背景三态（跟随／开／关，关＝强制关）
 *   ＋模糊半径滑杆（仅开时可用）。
 * - 状态栏图标：跟随按键外观（＝旧工具栏跟随按键开关）＋说明／
 *   自定义边框粗细＋滑杆／自定义圆角＋滑杆／工具栏背景＋工具栏边框颜色
 *   （旧工具栏组控制项，保留）／动态模糊背景（＝顶部栏模糊同一字段，
 *   与状态栏段的开关同步）。
 * - 整条状态栏：共用背景开关＋说明／顶部栏背景／动态模糊背景（同字段）。
 * - 磨砂效果：模糊强度滑杆（＝模糊半径同一字段，仅模糊开时可用）／
 *   背景叠加强度滑杆＋说明（新字段，窗口背景叠加层）。
 */
@Composable
private fun GlobalStyleDialog(
    mode: GlobalStyleMode,
    initial: GlobalStyle,
    onApply: (GlobalStyle) -> Unit,
    onDismiss: () -> Unit,
) {
    var draft by remember { mutableStateOf(initial) }
    var colorTarget by remember { mutableStateOf<ColorTarget?>(null) }
    var blurChoiceOpen by remember { mutableStateOf(false) }
    // 入口态按 22:00 图示为折叠（5 分区子卡），点卡展开后为 22:02／22:03
    // 展开态；此前默认全展开与入口图示不符，故改回折叠。
    var keyboardExpanded by remember { mutableStateOf(false) }
    var keysExpanded by remember { mutableStateOf(false) }
    var iconsExpanded by remember { mutableStateOf(false) }
    var barExpanded by remember { mutableStateOf(false) }
    var frostExpanded by remember { mutableStateOf(false) }

    SettingsDialog(
        onDismissRequest = onDismiss,
        title = stringResource(
            if (mode == GlobalStyleMode.THEME) {
                R.string.global_style_theme
            } else {
                R.string.global_style_custom
            },
        ),
        description = stringResource(
            if (mode == GlobalStyleMode.THEME) {
                R.string.global_style_dialog_theme_desc
            } else {
                R.string.global_style_dialog_custom_desc
            },
        ),
        // body 内边距：左右 12dp，上 4dp，下 12dp
        bodyPadding = PaddingValues(start = 12.dp, end = 12.dp),
        body = {
            // 分区之间不再另加间距：每个分区自带上下 4dp，
            // 相邻两区之间正好 8dp。
            Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
                GlobalStyleSection(
                    title = stringResource(R.string.global_section_keyboard_full),
                    expanded = keyboardExpanded,
                    onToggle = { keyboardExpanded = !keyboardExpanded },
                ) {
                    val cornerCustom = draft.keyboardTopCornerDp != null
                    SwitchRow(
                        title = stringResource(R.string.global_corner_custom),
                        checked = cornerCustom,
                        onCheckedChange = { checked ->
                            draft = draft.copy(
                                keyboardTopCornerDp = if (checked) {
                                    draft.keyboardTopCornerDp ?: 16f
                                } else {
                                    null
                                },
                            )
                        },
                    )
                    if (cornerCustom) {
                        SliderRow(
                            title = stringResource(R.string.top_corner),
                            value = draft.keyboardTopCornerDp ?: 16f,
                            valueLabel = "%.1f dp".format(draft.keyboardTopCornerDp ?: 16f),
                            range = 0f..32f,
                            steps = 31,
                            onValueChange = { draft = draft.copy(keyboardTopCornerDp = it) },
                        )
                    }
                }
                GlobalStyleSection(
                    title = stringResource(R.string.global_section_keys_full),
                    expanded = keysExpanded,
                    onToggle = { keysExpanded = !keysExpanded },
                ) {
                    GlobalStyleColorPill(
                        field = stringResource(R.string.key_background),
                        colorName = colorName(draft.key.background),
                        onClick = { colorTarget = ColorTarget.KEY_BG },
                    )
                    val borderCustom = draft.key.borderWidthDp != null
                    SwitchRow(
                        title = stringResource(R.string.global_border_custom),
                        checked = borderCustom,
                        onCheckedChange = { checked ->
                            draft = draft.copy(
                                key = draft.key.copy(
                                    borderWidthDp = if (checked) {
                                        draft.key.borderWidthDp ?: 1f
                                    } else {
                                        null
                                    },
                                ),
                            )
                        },
                    )
                    SliderRow(
                        title = stringResource(R.string.key_border_width),
                        value = draft.key.borderWidthDp ?: 1f,
                        valueLabel = "%.1f dp".format(draft.key.borderWidthDp ?: 1f),
                        range = 0f..4f,
                        steps = 7,
                        enabled = borderCustom,
                        onValueChange = {
                            draft = draft.copy(key = draft.key.copy(borderWidthDp = it))
                        },
                    )
                    GlobalStyleColorPill(
                        field = stringResource(R.string.key_border_color),
                        colorName = colorName(draft.key.borderColor),
                        onClick = { colorTarget = ColorTarget.KEY_BORDER },
                    )
                    val radiusCustom = draft.key.cornerRadiusDp != null
                    SwitchRow(
                        title = stringResource(R.string.global_radius_custom),
                        checked = radiusCustom,
                        onCheckedChange = { checked ->
                            draft = draft.copy(
                                key = draft.key.copy(
                                    cornerRadiusDp = if (checked) {
                                        draft.key.cornerRadiusDp ?: 12f
                                    } else {
                                        null
                                    },
                                ),
                            )
                        },
                    )
                    SliderRow(
                        title = stringResource(R.string.key_radius),
                        value = draft.key.cornerRadiusDp ?: 12f,
                        valueLabel = "%.1f dp".format(draft.key.cornerRadiusDp ?: 12f),
                        range = 0f..32f,
                        steps = 31,
                        enabled = radiusCustom,
                        onValueChange = {
                            draft = draft.copy(key = draft.key.copy(cornerRadiusDp = it))
                        },
                    )
                    // 背景模糊三态（跟随／开／关）：关＝强制关（OFF，
                    // 渲染链 effectiveBlur 已覆盖），不丢旧能力；半径
                    // 在磨砂分区调（本区不再重复放半径滑杆）。
                    // 模式切换保留已存半径（开时空值补 16）。
                    NavRow(
                        title = stringResource(R.string.global_blur_switch),
                        subtitle = stringResource(
                            when (draft.backgroundBlur.mode) {
                                BackgroundBlurMode.FOLLOW -> R.string.global_blur_follow
                                BackgroundBlurMode.ON -> R.string.global_blur_on
                                BackgroundBlurMode.OFF -> R.string.global_blur_off
                            },
                        ),
                        onClick = { blurChoiceOpen = true },
                    )
                }
                GlobalStyleSection(
                    title = stringResource(R.string.global_section_icons_full),
                    expanded = iconsExpanded,
                    onToggle = { iconsExpanded = !iconsExpanded },
                ) {
                    SwitchRow(
                        title = stringResource(R.string.bar_icon_follow),
                        checked = draft.linkToolbar,
                        onCheckedChange = { draft = draft.copy(linkToolbar = it) },
                    )
                    Text(
                        text = stringResource(R.string.bar_icon_follow_note),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    GlobalStyleColorPill(
                        field = stringResource(R.string.toolbar_background),
                        colorName = colorName(draft.toolbar.background),
                        onClick = { colorTarget = ColorTarget.TOOLBAR_BG },
                    )
                    val toolbarBorderCustom = draft.toolbar.borderWidthDp != null
                    SwitchRow(
                        title = stringResource(R.string.global_border_custom),
                        checked = toolbarBorderCustom,
                        onCheckedChange = { checked ->
                            draft = draft.copy(
                                toolbar = draft.toolbar.copy(
                                    borderWidthDp = if (checked) {
                                        draft.toolbar.borderWidthDp ?: 1f
                                    } else {
                                        null
                                    },
                                ),
                            )
                        },
                    )
                    SliderRow(
                        title = stringResource(R.string.toolbar_border_width),
                        value = draft.toolbar.borderWidthDp ?: 1f,
                        valueLabel = "%.1f dp".format(draft.toolbar.borderWidthDp ?: 1f),
                        range = 0f..4f,
                        steps = 7,
                        enabled = toolbarBorderCustom,
                        onValueChange = {
                            draft = draft.copy(
                                toolbar = draft.toolbar.copy(borderWidthDp = it),
                            )
                        },
                    )
                    GlobalStyleColorPill(
                        field = stringResource(R.string.toolbar_border_color),
                        colorName = colorName(draft.toolbar.borderColor),
                        onClick = { colorTarget = ColorTarget.TOOLBAR_BORDER },
                    )
                    val toolbarRadiusCustom = draft.toolbar.cornerRadiusDp != null
                    SwitchRow(
                        title = stringResource(R.string.global_radius_custom),
                        checked = toolbarRadiusCustom,
                        onCheckedChange = { checked ->
                            draft = draft.copy(
                                toolbar = draft.toolbar.copy(
                                    cornerRadiusDp = if (checked) {
                                        draft.toolbar.cornerRadiusDp ?: 12f
                                    } else {
                                        null
                                    },
                                ),
                            )
                        },
                    )
                    SliderRow(
                        title = stringResource(R.string.global_toolbar_radius),
                        value = draft.toolbar.cornerRadiusDp ?: 12f,
                        valueLabel = "%.1f dp".format(draft.toolbar.cornerRadiusDp ?: 12f),
                        range = 0f..32f,
                        steps = 31,
                        enabled = toolbarRadiusCustom,
                        onValueChange = {
                            draft = draft.copy(
                                toolbar = draft.toolbar.copy(cornerRadiusDp = it),
                            )
                        },
                    )
                    SwitchRow(
                        title = stringResource(R.string.global_blur_switch),
                        checked = draft.barBlur,
                        onCheckedChange = { draft = draft.copy(barBlur = it) },
                    )
                }
                GlobalStyleSection(
                    title = stringResource(R.string.global_section_bar_full),
                    expanded = barExpanded,
                    onToggle = { barExpanded = !barExpanded },
                ) {
                    SwitchRow(
                        title = stringResource(R.string.shared_background),
                        checked = draft.sharedBackground,
                        onCheckedChange = { draft = draft.copy(sharedBackground = it) },
                    )
                    GlobalStyleColorPill(
                        field = stringResource(R.string.bar_background),
                        colorName = colorName(draft.barBackground),
                        onClick = { colorTarget = ColorTarget.BAR_BG },
                    )
                    SwitchRow(
                        title = stringResource(R.string.global_blur_switch),
                        checked = draft.barBlur,
                        onCheckedChange = { draft = draft.copy(barBlur = it) },
                    )
                    Text(
                        text = stringResource(R.string.shared_background_note),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                GlobalStyleSection(
                    title = stringResource(R.string.global_section_frost_full),
                    expanded = frostExpanded,
                    onToggle = { frostExpanded = !frostExpanded },
                ) {
                    val frostBlurOn = draft.backgroundBlur.mode == BackgroundBlurMode.ON
                    SliderRow(
                        title = stringResource(R.string.blur_radius),
                        value = draft.backgroundBlur.radiusDp ?: 16f,
                        valueLabel = "%.1f dp".format(draft.backgroundBlur.radiusDp ?: 16f),
                        range = 0f..32f,
                        steps = 31,
                        enabled = frostBlurOn,
                        onValueChange = {
                            draft = draft.copy(
                                backgroundBlur = draft.backgroundBlur.copy(radiusDp = it),
                            )
                        },
                    )
                    SliderRow(
                        title = stringResource(R.string.frost_overlay),
                        value = draft.frostOverlayAlpha ?: 0.25f,
                        valueLabel = "%.1f%%".format((draft.frostOverlayAlpha ?: 0.25f) * 100),
                        range = 0f..1f,
                        steps = 99,
                        onValueChange = { draft = draft.copy(frostOverlayAlpha = it) },
                    )
                    Text(
                        text = stringResource(R.string.frost_overlay_note),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        actions = listOf(
            SettingsDialogAction(
                text = stringResource(R.string.reset_default),
                onClick = { draft = GlobalStyle() },
            ),
            SettingsDialogAction(
                text = stringResource(R.string.cancel),
                onClick = onDismiss,
            ),
            SettingsDialogAction(
                text = stringResource(R.string.global_apply),
                onClick = { onApply(draft) },
            ),
        ),
    )

    // 颜色选择（写草稿，不写偏好；预设含跟随＝null）。
    val target = colorTarget
    if (target != null) {
        val current = when (target) {
            ColorTarget.KEY_BG -> draft.key.background
            ColorTarget.KEY_BORDER -> draft.key.borderColor
            ColorTarget.TOOLBAR_BG -> draft.toolbar.background
            ColorTarget.TOOLBAR_BORDER -> draft.toolbar.borderColor
            ColorTarget.BAR_BG -> draft.barBackground
        }
        val title = when (target) {
            ColorTarget.KEY_BG -> stringResource(R.string.key_background)
            ColorTarget.KEY_BORDER -> stringResource(R.string.key_border_color)
            ColorTarget.TOOLBAR_BG -> stringResource(R.string.toolbar_background)
            ColorTarget.TOOLBAR_BORDER -> stringResource(R.string.toolbar_border_color)
            ColorTarget.BAR_BG -> stringResource(R.string.bar_background)
        }
        val presetNames = COLOR_PRESETS.map { stringResource(it.first) }
        // 当前值不在预设内时末尾补一项“自定义颜色”（选中它＝保持现值）。
        val customShown = current != null && COLOR_PRESETS.none { it.second == current }
        val options = if (customShown) {
            presetNames + stringResource(R.string.color_custom)
        } else {
            presetNames
        }
        SingleChoiceDialog(
            title = title,
            options = options,
            selectedIndex = COLOR_PRESETS.indexOfFirst { it.second == current }
                .takeIf { it >= 0 } ?: if (customShown) options.size - 1 else 0,
            onSelect = { index ->
                if (customShown && index == options.size - 1) {
                    colorTarget = null
                    return@SingleChoiceDialog
                }
                val value = COLOR_PRESETS[index].second
                when (target) {
                    ColorTarget.KEY_BG -> draft = draft.copy(
                        key = draft.key.copy(background = value),
                    )
                    ColorTarget.KEY_BORDER -> draft = draft.copy(
                        key = draft.key.copy(borderColor = value),
                    )
                    ColorTarget.TOOLBAR_BG -> draft = draft.copy(
                        toolbar = draft.toolbar.copy(background = value),
                    )
                    ColorTarget.TOOLBAR_BORDER -> draft = draft.copy(
                        toolbar = draft.toolbar.copy(borderColor = value),
                    )
                    ColorTarget.BAR_BG -> draft = draft.copy(barBackground = value)
                }
                colorTarget = null
            },
            onDismiss = { colorTarget = null },
        )
    }
    if (blurChoiceOpen) {
        SingleChoiceDialog(
            title = stringResource(R.string.global_blur_switch),
            options = listOf(
                stringResource(R.string.global_blur_follow),
                stringResource(R.string.global_blur_on),
                stringResource(R.string.global_blur_off),
            ),
            selectedIndex = BackgroundBlurMode.entries.indexOf(draft.backgroundBlur.mode),
            onSelect = { index ->
                val mode = BackgroundBlurMode.entries[index]
                draft = draft.copy(
                    backgroundBlur = draft.backgroundBlur.copy(
                        mode = mode,
                        radiusDp = if (mode == BackgroundBlurMode.ON) {
                            draft.backgroundBlur.radiusDp ?: 16f
                        } else {
                            draft.backgroundBlur.radiusDp
                        },
                    ),
                )
                blurChoiceOpen = false
            },
            onDismiss = { blurChoiceOpen = false },
        )
    }
}

/**
 * 全局样式对话框分区，
 * 18dp 圆角 + surface 底色（与对话框同色，看不见卡片边界）、
 * 标题 16sp 常规字重、minHeight 52dp、padding 16dp、▾/▸ 在文字左侧。
 */
@Composable
private fun GlobalStyleSection(
    title: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    content: @Composable () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.surface),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 52.dp)
                .clickable { onToggle() }
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "${if (expanded) "▾" else "▸"} $title",
                fontSize = 16.sp,
                lineHeight = 22.sp,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        if (expanded) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
            ) {
                content()
            }
        }
    }
}

/**
 * 全局样式对话框颜色行：描边药丸
 * ——透明底 + 1dp 描边 + 主色文字，整宽。
 */
@Composable
private fun GlobalStyleColorPill(
    field: String,
    colorName: String,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            // MaterialButton 自带 6dp 上下 inset，视觉高 40dp；用 6dp 间距等效。
            .padding(vertical = 6.dp)
            .height(40.dp)
            .clip(RoundedCornerShape(50))
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outline,
                shape = RoundedCornerShape(50),
            )
            .clickable { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            // text = "$title · $value"（中点两侧各一个空格）
            text = "$field · $colorName",
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            letterSpacing = 0.1.sp,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
