// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.ui.screen

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitHorizontalDragOrCancellation
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BrightnessMedium
import androidx.compose.material.icons.filled.Style
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.jobeen.ime.R
import com.jobeen.ime.data.keyboard.theme.BackgroundBlurMode
import com.jobeen.ime.data.keyboard.theme.FontChoice
import com.jobeen.ime.data.keyboard.theme.FontRole
import com.jobeen.ime.data.keyboard.theme.GlobalFonts
import android.content.Context
import com.jobeen.ime.data.keyboard.theme.GlobalStyle
import com.jobeen.ime.data.keyboard.theme.GlobalStyleResolver
import com.jobeen.ime.data.keyboard.theme.KeyboardColors
import com.jobeen.ime.data.keyboard.theme.MonetPalette
import com.jobeen.ime.data.keyboard.theme.GlobalStyleMode
import com.jobeen.ime.data.keyboard.theme.GlobalStylePrefs
import com.jobeen.ime.data.keyboard.theme.SkinFonts
import com.jobeen.ime.data.keyboard.theme.SurfaceOverride
import com.jobeen.ime.data.keyboard.theme.VideoPrefs
import com.jobeen.ime.data.manager.KeyboardManager
import com.jobeen.ime.skin.model.GeometryField
import com.jobeen.ime.skin.model.KeyboardGeometryField
import com.jobeen.ime.skin.runtime.SkinColorMode
import com.jobeen.ime.skin.runtime.SkinSnapshotProvider
import com.jobeen.ime.ui.screen.ScreenComponent.GroupCard
import com.jobeen.ime.ui.screen.ScreenComponent.NavRow
import com.jobeen.ime.ui.screen.ScreenComponent.SettingsGroup
import com.jobeen.ime.ui.screen.ScreenComponent.SingleChoiceDialog
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
internal enum class ColorTarget {
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
 * 全局样式对话框编辑器（主题／自定义各一套草稿；2026-11-07 按视频定稿重设计）：
 * 顶部实时预览（迷你键盘两行，按住看按压反馈）＋三标签
 * （按键／工具栏／整体）＋五分区按标签分（按键＝按键分区；
 * 工具栏＝状态栏图标＋整条状态栏；整体＝键盘整体＋磨砂效果）；
 * 取值行＝整宽药丸（点开现有单选）；滑杆＝行内圆点滑杆
 * （标签在上，自定义开关关时置灰）；开关＝标准 M3 Switch；
 * 底部恢复默认／取消／应用 staged 不变（应用才写偏好）。
 * 入口态：标签默认按键签，分区默认全折叠，点卡展开；
 * 切标签不丢各分区展开态、不丢草稿值（状态挂在对话框级）。
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

/** 对话框三标签：按键／工具栏／整体（定稿分段）。 */
internal enum class GlobalStyleTab { KEYS, TOOLBAR, OVERALL }

/** 五分区在三标签下的归属（定稿映射；每分区恰属一标签）。 */
internal enum class GlobalStyleSectionId { KEYBOARD, KEYS, ICONS, BAR, FROST }

/** 标签 → 该标签下的分区（渲染顺序；全量五分区恰覆盖一次）。 */
internal fun sectionsForTab(tab: GlobalStyleTab): List<GlobalStyleSectionId> =
    when (tab) {
        GlobalStyleTab.KEYS -> listOf(GlobalStyleSectionId.KEYS)
        GlobalStyleTab.TOOLBAR -> listOf(
            GlobalStyleSectionId.ICONS,
            GlobalStyleSectionId.BAR,
        )
        GlobalStyleTab.OVERALL -> listOf(
            GlobalStyleSectionId.KEYBOARD,
            GlobalStyleSectionId.FROST,
        )
    }

/**
 * 全局样式对话框状态（草稿＋标签＋各分区展开态；纯状态持有，
 * 供 JVM 单测：切标签不丢草稿、不丢展开态）。
 * 草稿与展开态都挂在对话框级（标签之上），切标签只换渲染的分区。
 */
internal class GlobalStyleDialogState(initial: GlobalStyle) {
    var draft by mutableStateOf(initial)
    var tab by mutableStateOf(GlobalStyleTab.KEYS)
    private val expandedIds = mutableStateMapOf<GlobalStyleSectionId, Boolean>()

    fun isExpanded(id: GlobalStyleSectionId): Boolean = expandedIds[id] == true
    fun toggleSection(id: GlobalStyleSectionId) {
        expandedIds[id] = !(expandedIds[id] == true)
    }
    fun selectTab(newTab: GlobalStyleTab) {
        tab = newTab
    }
    fun updateDraft(transform: (GlobalStyle) -> GlobalStyle) {
        draft = transform(draft)
    }
    fun resetDraft() {
        draft = GlobalStyle()
    }
}

/**
 * 实时预览的键面样式（纯解析，供 JVM 单测）：
 * 草稿值优先（背景／边框色／圆角／边框宽），未设（跟随）时取
 * 当前键盘主题的底色（base：内置主题／快照／莫奈动态色板），
 * 与渲染链 KeyboardColors.applyGlobalStyle 同一口径。
 * 预览区颜色跟键盘主题走，不跟 App 主题混用。
 */
internal data class GlobalStylePreviewStyle(
    val background: Int,
    val keyBackground: Int,
    val keyPressed: Int,
    val keyBorderStroke: Int,
    val keyBorderWidthDp: Float,
    val keyCornerRadiusDp: Float,
    val keyText: Int,
)

internal fun resolveGlobalStylePreviewStyle(
    base: KeyboardColors.ColorScheme,
    draft: GlobalStyle,
    fallbackRadiusDp: Float,
): GlobalStylePreviewStyle = GlobalStylePreviewStyle(
    background = base.background,
    keyBackground = GlobalStyleResolver.keyBackground(draft, emptySet())
        ?: base.keyBackground,
    keyPressed = base.keyPressed,
    keyBorderStroke = GlobalStyleResolver.keyBorderStroke(draft, emptySet())
        ?: base.keyBorderStroke,
    keyBorderWidthDp = GlobalStyleResolver.clampBorderWidth(draft.key.borderWidthDp),
    keyCornerRadiusDp = GlobalStyleResolver.keyCornerRadius(draft, false)
        ?: fallbackRadiusDp,
    keyText = base.keyText,
)

/** 预览键按压态：按住取键盘按压色（keyPressed），松开回键面色。 */
internal fun previewKeyCellColor(pressed: Boolean, style: GlobalStylePreviewStyle): Int =
    if (pressed) style.keyPressed else style.keyBackground

/** 滑杆位置（0..1；与 M3 Slider 的 steps 口径一致：steps＋1 等分）。 */
internal fun sliderFraction(value: Float, range: ClosedFloatingPointRange<Float>): Float =
    ((value - range.start) / (range.endInclusive - range.start)).coerceIn(0f, 1f)

internal fun sliderValueAtFraction(
    fraction: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
): Float {
    val f = fraction.coerceIn(0f, 1f)
    if (steps <= 0) return range.start + f * (range.endInclusive - range.start)
    val step = (range.endInclusive - range.start) / (steps + 1)
    val snapped = range.start + (f * (steps + 1)).roundToInt() * step
    return snapped.coerceIn(range.start, range.endInclusive)
}

/** 滑杆标签：标题与数值同一行（「边框粗细 · 1.0 dp」式）。 */
internal fun sliderLabel(title: String, valueLabel: String): String = "$title · $valueLabel"

/** 取值药丸文案：字段与取值同一行（「按键背景 · 跟随」式）。 */
internal fun valuePillText(field: String, value: String): String = "$field · $value"

/** 颜色药丸提交：选项下标 → 预设值（0＝跟随 null；其余＝预设 ARGB）。 */
internal fun colorPresetValueAt(index: Int): Int? = COLOR_PRESETS[index].second

/** 三态药丸提交：选项下标 → 模糊模式（跟随／开／关）。 */
internal fun blurModeForOptionIndex(index: Int): BackgroundBlurMode =
    BackgroundBlurMode.entries[index]

/** 颜色药丸当前值：按选择目标取草稿对应字段。 */
internal fun currentColorForTarget(draft: GlobalStyle, target: ColorTarget): Int? =
    when (target) {
        ColorTarget.KEY_BG -> draft.key.background
        ColorTarget.KEY_BORDER -> draft.key.borderColor
        ColorTarget.TOOLBAR_BG -> draft.toolbar.background
        ColorTarget.TOOLBAR_BORDER -> draft.toolbar.borderColor
        ColorTarget.BAR_BG -> draft.barBackground
    }

/**
 * 预览底色板：THEME 模式取内置主题（莫奈走动态色板），
 * CUSTOM 模式取当前快照（无快照回落内置主题）。
 */
private data class PreviewBase(
    val scheme: KeyboardColors.ColorScheme,
    val fallbackRadiusDp: Float,
)

private fun previewBase(context: Context, mode: GlobalStyleMode): PreviewBase {
    val snapshot = if (mode == GlobalStyleMode.CUSTOM) {
        SkinSnapshotProvider.current(context)
    } else {
        null
    }
    if (snapshot != null) {
        val scheme = snapshot.effectiveColorScheme(SkinColorMode.isDark(context))
        val skinHasRadius = GeometryField.CORNER_RADIUS in snapshot.geometryPresence ||
            KeyboardGeometryField.KEY_RADIUS in snapshot.keyboardGeometry.presence
        return PreviewBase(
            scheme = scheme,
            fallbackRadiusDp = if (skinHasRadius) {
                scheme.cornerRadius
            } else {
                KeyboardManager.Keyboard.KeyRadius.getDp(context).toFloat()
            },
        )
    }
    val theme = KeyboardColors.themeFor(context)
    val scheme = if (theme.id == MonetPalette.ID) {
        MonetPalette.build(context)
    } else {
        theme.colors
    }
    return PreviewBase(
        scheme = scheme,
        fallbackRadiusDp = KeyboardManager.Keyboard.KeyRadius.getDp(context).toFloat(),
    )
}

@Composable
private fun GlobalStyleDialog(
    mode: GlobalStyleMode,
    initial: GlobalStyle,
    onApply: (GlobalStyle) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val state = remember { GlobalStyleDialogState(initial) }
    var colorTarget by remember { mutableStateOf<ColorTarget?>(null) }
    var blurChoiceOpen by remember { mutableStateOf(false) }
    // 预览底色：对话框打开时取一次当前键盘主题（模式决定取主题
    // 还是快照）；草稿只覆盖其上的键面字段，预览随草稿实时变。
    val previewBase = remember { previewBase(context, mode) }

    SettingsDialog(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.global_style_dialog_title),
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
                GlobalStylePreview(
                    style = resolveGlobalStylePreviewStyle(
                        previewBase.scheme,
                        state.draft,
                        previewBase.fallbackRadiusDp,
                    ),
                )
                Text(
                    text = stringResource(R.string.global_style_preview_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
                Spacer(Modifier.height(16.dp))
                GlobalStyleTabBar(
                    selected = state.tab,
                    onSelect = { state.selectTab(it) },
                )
                Spacer(Modifier.height(16.dp))
                for (id in sectionsForTab(state.tab)) {
                    when (id) {
                        GlobalStyleSectionId.KEYBOARD -> GlobalStyleSection(
                            title = stringResource(R.string.global_section_keyboard_full),
                            expanded = state.isExpanded(GlobalStyleSectionId.KEYBOARD),
                            onToggle = {
                                state.toggleSection(GlobalStyleSectionId.KEYBOARD)
                            },
                        ) {
                            val cornerCustom = state.draft.keyboardTopCornerDp != null
                            DenseSwitchRow(
                                title = stringResource(R.string.global_corner_custom),
                                checked = cornerCustom,
                                onCheckedChange = { checked ->
                                    state.updateDraft {
                                        it.copy(
                                            keyboardTopCornerDp = if (checked) {
                                                it.keyboardTopCornerDp ?: 16f
                                            } else {
                                                null
                                            },
                                        )
                                    }
                                },
                            )
                            if (cornerCustom) {
                                DottedSliderRow(
                                    title = stringResource(R.string.top_corner),
                                    value = state.draft.keyboardTopCornerDp ?: 16f,
                                    valueLabel = "%.1f dp".format(
                                        state.draft.keyboardTopCornerDp ?: 16f,
                                    ),
                                    range = 0f..32f,
                                    steps = 31,
                                    onValueChange = { v ->
                                        state.updateDraft { it.copy(keyboardTopCornerDp = v) }
                                    },
                                )
                            }
                        }
                        GlobalStyleSectionId.KEYS -> GlobalStyleSection(
                            title = stringResource(R.string.global_section_keys_full),
                            expanded = state.isExpanded(GlobalStyleSectionId.KEYS),
                            onToggle = {
                                state.toggleSection(GlobalStyleSectionId.KEYS)
                            },
                        ) {
                            GlobalStyleValuePill(
                                text = valuePillText(
                                    stringResource(R.string.key_background),
                                    colorName(state.draft.key.background),
                                ),
                                onClick = { colorTarget = ColorTarget.KEY_BG },
                            )
                            val borderCustom = state.draft.key.borderWidthDp != null
                            DenseSwitchRow(
                                title = stringResource(R.string.global_border_custom),
                                checked = borderCustom,
                                onCheckedChange = { checked ->
                                    state.updateDraft {
                                        it.copy(
                                            key = it.key.copy(
                                                borderWidthDp = if (checked) {
                                                    it.key.borderWidthDp ?: 1f
                                                } else {
                                                    null
                                                },
                                            ),
                                        )
                                    }
                                },
                            )
                            DottedSliderRow(
                                title = stringResource(R.string.key_border_width),
                                value = state.draft.key.borderWidthDp ?: 1f,
                                valueLabel = "%.1f dp".format(state.draft.key.borderWidthDp ?: 1f),
                                range = 0f..4f,
                                steps = 7,
                                enabled = borderCustom,
                                onValueChange = { v ->
                                    state.updateDraft {
                                        it.copy(key = it.key.copy(borderWidthDp = v))
                                    }
                                },
                            )
                            GlobalStyleValuePill(
                                text = valuePillText(
                                    stringResource(R.string.key_border_color),
                                    colorName(state.draft.key.borderColor),
                                ),
                                onClick = { colorTarget = ColorTarget.KEY_BORDER },
                            )
                            val radiusCustom = state.draft.key.cornerRadiusDp != null
                            DenseSwitchRow(
                                title = stringResource(R.string.global_radius_custom),
                                checked = radiusCustom,
                                onCheckedChange = { checked ->
                                    state.updateDraft {
                                        it.copy(
                                            key = it.key.copy(
                                                cornerRadiusDp = if (checked) {
                                                    it.key.cornerRadiusDp ?: 12f
                                                } else {
                                                    null
                                                },
                                            ),
                                        )
                                    }
                                },
                            )
                            DottedSliderRow(
                                title = stringResource(R.string.key_radius),
                                value = state.draft.key.cornerRadiusDp ?: 12f,
                                valueLabel = "%.1f dp".format(state.draft.key.cornerRadiusDp ?: 12f),
                                range = 0f..32f,
                                steps = 31,
                                enabled = radiusCustom,
                                onValueChange = { v ->
                                    state.updateDraft {
                                        it.copy(key = it.key.copy(cornerRadiusDp = v))
                                    }
                                },
                            )
                            // 背景模糊三态（跟随／开／关）：关＝强制关（OFF，
                            // 渲染链 effectiveBlur 已覆盖），不丢旧能力；半径
                            // 在磨砂分区调（本区不再重复放半径滑杆）。
                            // 模式切换保留已存半径（开时空值补 16）。
                            GlobalStyleValuePill(
                                text = valuePillText(
                                    stringResource(R.string.global_blur_switch),
                                    stringResource(
                                        when (state.draft.backgroundBlur.mode) {
                                            BackgroundBlurMode.FOLLOW -> R.string.global_blur_follow
                                            BackgroundBlurMode.ON -> R.string.global_blur_on
                                            BackgroundBlurMode.OFF -> R.string.global_blur_off
                                        },
                                    ),
                                ),
                                onClick = { blurChoiceOpen = true },
                            )
                        }
                        GlobalStyleSectionId.ICONS -> GlobalStyleSection(
                            title = stringResource(R.string.global_section_icons_full),
                            expanded = state.isExpanded(GlobalStyleSectionId.ICONS),
                            onToggle = {
                                state.toggleSection(GlobalStyleSectionId.ICONS)
                            },
                        ) {
                            for (row in iconsSectionRows(state.draft.linkToolbar)) {
                                when (row) {
                                    IconsSectionRow.FOLLOW_SWITCH -> DenseSwitchRow(
                                        title = stringResource(R.string.bar_icon_follow),
                                        checked = state.draft.linkToolbar,
                                        onCheckedChange = { checked ->
                                            state.updateDraft { it.copy(linkToolbar = checked) }
                                        },
                                    )
                                    IconsSectionRow.FOLLOW_NOTE -> Text(
                                        text = stringResource(
                                            iconsFollowNoteRes(state.draft.linkToolbar),
                                        ),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    IconsSectionRow.TOOLBAR_BACKGROUND ->
                                        GlobalStyleValuePill(
                                            text = valuePillText(
                                                stringResource(R.string.toolbar_background),
                                                colorName(state.draft.toolbar.background),
                                            ),
                                            onClick = { colorTarget = ColorTarget.TOOLBAR_BG },
                                        )
                                    IconsSectionRow.BORDER_CUSTOM_SWITCH -> {
                                        val toolbarBorderCustom =
                                            state.draft.toolbar.borderWidthDp != null
                                        DenseSwitchRow(
                                            title = stringResource(R.string.global_border_custom),
                                            checked = toolbarBorderCustom,
                                            onCheckedChange = { checked ->
                                                state.updateDraft {
                                                    it.copy(
                                                        toolbar = it.toolbar.copy(
                                                            borderWidthDp = if (checked) {
                                                                it.toolbar.borderWidthDp ?: 1f
                                                            } else {
                                                                null
                                                            },
                                                        ),
                                                    )
                                                }
                                            },
                                        )
                                    }
                                    IconsSectionRow.BORDER_WIDTH_SLIDER -> {
                                        val toolbarBorderCustom =
                                            state.draft.toolbar.borderWidthDp != null
                                        DottedSliderRow(
                                            title = stringResource(R.string.toolbar_border_width),
                                            value = state.draft.toolbar.borderWidthDp ?: 1f,
                                            valueLabel = "%.1f dp".format(
                                                state.draft.toolbar.borderWidthDp ?: 1f,
                                            ),
                                            range = 0f..4f,
                                            steps = 7,
                                            enabled = toolbarBorderCustom,
                                            onValueChange = { v ->
                                                state.updateDraft {
                                                    it.copy(
                                                        toolbar = it.toolbar.copy(borderWidthDp = v),
                                                    )
                                                }
                                            },
                                        )
                                    }
                                    IconsSectionRow.TOOLBAR_BORDER_COLOR ->
                                        GlobalStyleValuePill(
                                            text = valuePillText(
                                                stringResource(R.string.toolbar_border_color),
                                                colorName(state.draft.toolbar.borderColor),
                                            ),
                                            onClick = { colorTarget = ColorTarget.TOOLBAR_BORDER },
                                        )
                                    IconsSectionRow.RADIUS_CUSTOM_SWITCH -> {
                                        val toolbarRadiusCustom =
                                            state.draft.toolbar.cornerRadiusDp != null
                                        DenseSwitchRow(
                                            title = stringResource(R.string.global_radius_custom),
                                            checked = toolbarRadiusCustom,
                                            onCheckedChange = { checked ->
                                                state.updateDraft {
                                                    it.copy(
                                                        toolbar = it.toolbar.copy(
                                                            cornerRadiusDp = if (checked) {
                                                                it.toolbar.cornerRadiusDp ?: 12f
                                                            } else {
                                                                null
                                                            },
                                                        ),
                                                    )
                                                }
                                            },
                                        )
                                    }
                                    IconsSectionRow.TOOLBAR_RADIUS_SLIDER -> {
                                        val toolbarRadiusCustom =
                                            state.draft.toolbar.cornerRadiusDp != null
                                        DottedSliderRow(
                                            title = stringResource(R.string.global_toolbar_radius),
                                            value = state.draft.toolbar.cornerRadiusDp ?: 12f,
                                            valueLabel = "%.1f dp".format(
                                                state.draft.toolbar.cornerRadiusDp ?: 12f,
                                            ),
                                            range = 0f..32f,
                                            steps = 31,
                                            enabled = toolbarRadiusCustom,
                                            onValueChange = { v ->
                                                state.updateDraft {
                                                    it.copy(
                                                        toolbar = it.toolbar.copy(
                                                            cornerRadiusDp = v,
                                                        ),
                                                    )
                                                }
                                            },
                                        )
                                    }
                                    IconsSectionRow.BLUR_SWITCH -> DenseSwitchRow(
                                        title = stringResource(R.string.global_blur_switch),
                                        checked = state.draft.barBlur,
                                        onCheckedChange = { checked ->
                                            state.updateDraft { it.copy(barBlur = checked) }
                                        },
                                    )
                                }
                            }
                        }
                        GlobalStyleSectionId.BAR -> GlobalStyleSection(
                            title = stringResource(R.string.global_section_bar_full),
                            expanded = state.isExpanded(GlobalStyleSectionId.BAR),
                            onToggle = {
                                state.toggleSection(GlobalStyleSectionId.BAR)
                            },
                        ) {
                            DenseSwitchRow(
                                title = stringResource(R.string.shared_background),
                                checked = state.draft.sharedBackground,
                                onCheckedChange = { checked ->
                                    state.updateDraft { it.copy(sharedBackground = checked) }
                                },
                            )
                            GlobalStyleValuePill(
                                text = valuePillText(
                                    stringResource(R.string.bar_background),
                                    colorName(state.draft.barBackground),
                                ),
                                onClick = { colorTarget = ColorTarget.BAR_BG },
                            )
                            DenseSwitchRow(
                                title = stringResource(R.string.global_blur_switch),
                                checked = state.draft.barBlur,
                                onCheckedChange = { checked ->
                                    state.updateDraft { it.copy(barBlur = checked) }
                                },
                            )
                            Text(
                                text = stringResource(R.string.shared_background_note),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        GlobalStyleSectionId.FROST -> GlobalStyleSection(
                            title = stringResource(R.string.global_section_frost_full),
                            expanded = state.isExpanded(GlobalStyleSectionId.FROST),
                            onToggle = {
                                state.toggleSection(GlobalStyleSectionId.FROST)
                            },
                        ) {
                            val frostBlurOn =
                                state.draft.backgroundBlur.mode == BackgroundBlurMode.ON
                            DottedSliderRow(
                                title = stringResource(R.string.blur_radius),
                                value = state.draft.backgroundBlur.radiusDp ?: 16f,
                                valueLabel = "%.1f dp".format(
                                    state.draft.backgroundBlur.radiusDp ?: 16f,
                                ),
                                range = 0f..32f,
                                steps = 31,
                                enabled = frostBlurOn,
                                onValueChange = { v ->
                                    state.updateDraft {
                                        it.copy(
                                            backgroundBlur = it.backgroundBlur.copy(radiusDp = v),
                                        )
                                    }
                                },
                            )
                            DottedSliderRow(
                                title = stringResource(R.string.frost_overlay),
                                value = state.draft.frostOverlayAlpha ?: 0.25f,
                                valueLabel = "%.1f%%".format(
                                    (state.draft.frostOverlayAlpha ?: 0.25f) * 100,
                                ),
                                range = 0f..1f,
                                steps = 99,
                                onValueChange = { v ->
                                    state.updateDraft { it.copy(frostOverlayAlpha = v) }
                                },
                            )
                            Text(
                                text = stringResource(R.string.frost_overlay_note),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        },
        actions = listOf(
            SettingsDialogAction(
                text = stringResource(R.string.reset_default),
                onClick = { state.resetDraft() },
            ),
            SettingsDialogAction(
                text = stringResource(R.string.cancel),
                onClick = onDismiss,
            ),
            SettingsDialogAction(
                text = stringResource(R.string.global_apply),
                onClick = { onApply(state.draft) },
            ),
        ),
    )

    // 颜色选择（写草稿，不写偏好；预设含跟随＝null）。
    val target = colorTarget
    if (target != null) {
        val current = currentColorForTarget(state.draft, target)
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
                val value = colorPresetValueAt(index)
                when (target) {
                    ColorTarget.KEY_BG -> state.updateDraft {
                        it.copy(key = it.key.copy(background = value))
                    }
                    ColorTarget.KEY_BORDER -> state.updateDraft {
                        it.copy(key = it.key.copy(borderColor = value))
                    }
                    ColorTarget.TOOLBAR_BG -> state.updateDraft {
                        it.copy(toolbar = it.toolbar.copy(background = value))
                    }
                    ColorTarget.TOOLBAR_BORDER -> state.updateDraft {
                        it.copy(toolbar = it.toolbar.copy(borderColor = value))
                    }
                    ColorTarget.BAR_BG -> state.updateDraft { it.copy(barBackground = value) }
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
            selectedIndex = BackgroundBlurMode.entries.indexOf(state.draft.backgroundBlur.mode),
            onSelect = { index ->
                val mode = blurModeForOptionIndex(index)
                state.updateDraft {
                    it.copy(
                        backgroundBlur = it.backgroundBlur.copy(
                            mode = mode,
                            radiusDp = if (mode == BackgroundBlurMode.ON) {
                                it.backgroundBlur.radiusDp ?: 16f
                            } else {
                                it.backgroundBlur.radiusDp
                            },
                        ),
                    )
                }
                blurChoiceOpen = false
            },
            onDismiss = { blurChoiceOpen = false },
        )
    }
}

/**
 * 全局样式对话框分区（2026-11-07 按视频定稿重设计）：
 * 18dp 圆角 + surface 底色、标题 16sp 常规字重、minHeight 52dp、
 * padding 16dp、▾/▸ 在文字左侧；标题全称（含限定词），标题栏
 * 不带任何状态芯片（跟随状态就是分区内的开关行本身）。
 *
 * 排布预算（S24 Ultra 真机：屏宽 1440px ÷ 3.75＝384dp；Dialog
 * 默认左右各留 24dp，对话框卡宽 336dp；body 左右 12dp、分
 * 区左右 16dp 后标题栏可用 280dp；字号按代码值 16sp，
 * fontScale＝1.0，CJK 字宽按 1em 计）：
 * - 最长标题「状态栏图标·独立或跟随按键」13 字×16dp＝208dp＋
 *   「▾ 」约 16dp ≈224dp ≤280dp，余 56dp；字号放大到约
 *   1.25 倍才到临界（三星最大字号约 1.3 倍时标题折两行显示，
 *   不截断）。
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
                modifier = Modifier.weight(1f),
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
 * 三标签分段药丸（按键／工具栏／整体）：
 * 容器描边 outline；选中段底 primaryContainer、文字 onPrimaryContainer；
 * 未选中段文字 onSurface；段间分隔线 outline。
 */
@Composable
private fun GlobalStyleTabBar(
    selected: GlobalStyleTab,
    onSelect: (GlobalStyleTab) -> Unit,
) {
    val tabs = listOf(
        GlobalStyleTab.KEYS to stringResource(R.string.global_section_keys),
        GlobalStyleTab.TOOLBAR to stringResource(R.string.global_section_toolbar),
        GlobalStyleTab.OVERALL to stringResource(R.string.global_style_tab_overall),
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(50))
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(50)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        tabs.forEachIndexed { index, (tab, label) ->
            if (index > 0) {
                Box(
                    modifier = Modifier
                        .width(1.dp)
                        .height(24.dp)
                        .background(MaterialTheme.colorScheme.outline),
                )
            }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(50))
                    .background(
                        if (tab == selected) MaterialTheme.colorScheme.primaryContainer
                        else Color.Transparent,
                    )
                    .clickable { onSelect(tab) }
                    .padding(vertical = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = label,
                    fontSize = 16.sp,
                    color = if (tab == selected) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                    maxLines = 1,
                )
            }
        }
    }
}

/**
 * 取值整宽药丸（颜色／三态取值共用）：outline 描边、居中 primary 文字；
 * 点药丸开现有 SingleChoiceDialog（取值逻辑不动）。
 */
@Composable
private fun GlobalStyleValuePill(
    text: String,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .clip(RoundedCornerShape(50))
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(50))
            .clickable { onClick() }
            .padding(vertical = 12.dp, horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            fontSize = 16.sp,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * 开关行（标准 M3 Switch）：开态 primary 轨＋onPrimary 钮、
 * 关态 surfaceVariant 轨均为 M3 缺省；关态钮按定稿为白钮
 * （三方案同色，见施工单 7 第 7 条）。
 */
@Composable
private fun DenseSwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
            color = MaterialTheme.colorScheme.onSurface,
        )
        androidx.compose.material3.Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                uncheckedThumbColor = Color.White,
            ),
        )
    }
}

/**
 * 行内圆点滑杆（2026-11-07 按视频定稿重设计）：
 * 标签在上（「标题 · 值」单行）；圆点轨道＋已填段＋竖线旋钮；
 * 点按跳值、横向拖动改值；步进与 M3 Slider 的 steps 口径一致；
 * enabled＝false 时置灰不可调（值保留）。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DottedSliderRow(
    title: String,
    value: Float,
    valueLabel: String,
    range: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
    steps: Int = 0,
    enabled: Boolean = true,
) {
    val scheme = MaterialTheme.colorScheme
    val label = sliderLabel(title, valueLabel)
    val fraction = sliderFraction(value, range)
    val labelColor = if (enabled) scheme.onSurface else scheme.onSurface.copy(alpha = 0.38f)
    val fillColor = if (enabled) scheme.primary else scheme.onSurface.copy(alpha = 0.38f)
    val dotColor = if (enabled) {
        scheme.onSurfaceVariant
    } else {
        scheme.onSurfaceVariant.copy(alpha = 0.38f)
    }
    var trackWidthPx by remember { mutableStateOf(0) }
    val density = LocalDensity.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
    ) {
        Text(
            text = label,
            fontSize = 14.sp,
            lineHeight = 20.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = labelColor,
        )
        Spacer(modifier = Modifier.height(6.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .onSizeChanged { trackWidthPx = it.width }
                .semantics {
                    progressBarRangeInfo = ProgressBarRangeInfo(value, range, steps)
                    contentDescription = label
                    if (!enabled) disabled()
                }
                .pointerInput(enabled, trackWidthPx) {
                    if (!enabled) return@pointerInput
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        if (trackWidthPx > 0) {
                            onValueChange(
                                sliderValueAtFraction(
                                    down.position.x / trackWidthPx,
                                    range,
                                    steps,
                                ),
                            )
                        }
                        var drag = awaitHorizontalDragOrCancellation(down.id)
                        while (drag != null) {
                            drag.consume()
                            if (trackWidthPx > 0) {
                                onValueChange(
                                    sliderValueAtFraction(
                                        drag.position.x / trackWidthPx,
                                        range,
                                        steps,
                                    ),
                                )
                            }
                            drag = awaitHorizontalDragOrCancellation(down.id)
                        }
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(28.dp),
            ) {
                val w = size.width
                val h = size.height
                val cy = h / 2f
                val spacingPx = with(density) { 20.dp.toPx() }
                val dotRadius = with(density) { 3.dp.toPx() }
                val barHalf = with(density) { 4.dp.toPx() }
                val knobHalf = with(density) { 2.dp.toPx() }
                // 圆点轨道：每 20dp 一点（先画，已填段盖住左侧的点）。
                var x = 0f
                while (x <= w) {
                    drawCircle(color = dotColor, radius = dotRadius, center = Offset(x, cy))
                    x += spacingPx
                }
                // 已填段：起点到旋钮。
                val knobX = fraction * w
                if (knobX > 0f) {
                    drawRoundRect(
                        color = fillColor,
                        topLeft = Offset(0f, cy - barHalf),
                        size = Size(knobX, barHalf * 2f),
                        cornerRadius = CornerRadius(barHalf),
                    )
                }
                // 竖线旋钮。
                val knobLeft = (knobX - knobHalf).coerceIn(0f, (w - knobHalf * 2f).coerceAtLeast(0f))
                drawRoundRect(
                    color = fillColor,
                    topLeft = Offset(knobLeft, 0f),
                    size = Size(knobHalf * 2f, h),
                    cornerRadius = CornerRadius(knobHalf),
                )
            }
        }
    }
}

/**
 * 顶部实时预览（2026-11-07 按视频定稿重设计）：
 * 迷你键盘两行（上行 ··· ／ 中/EN ／ ∨；下行 Q／W／E），
 * 颜色取草稿键盘样式（背景、键背景、边框、圆角跟草稿值走，
 * 未设时取当前键盘主题底色）；按住任一格查看按压反馈
 * （取键盘按压色）；预览随草稿值实时变。
 * 预览背景取键盘底色实色（定稿图的渐变仅为示意，提示文案亦言明
 * 「预览背景仅为示例」）。
 */
@Composable
private fun GlobalStylePreview(style: GlobalStylePreviewStyle) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(Color(style.background))
            .padding(16.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            GlobalStylePreviewCell(
                style = style,
                label = "···",
                isKey = false,
                modifier = Modifier.weight(1f),
            )
            GlobalStylePreviewCell(
                style = style,
                label = "中 / EN",
                isKey = false,
                modifier = Modifier.weight(1f),
            )
            GlobalStylePreviewCell(
                style = style,
                label = "∨",
                isKey = false,
                modifier = Modifier.weight(1f),
            )
        }
        Spacer(Modifier.height(12.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            GlobalStylePreviewCell(
                style = style,
                label = "Q",
                isKey = true,
                modifier = Modifier.weight(1f),
            )
            GlobalStylePreviewCell(
                style = style,
                label = "W",
                isKey = true,
                modifier = Modifier.weight(1f),
            )
            GlobalStylePreviewCell(
                style = style,
                label = "E",
                isKey = true,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun GlobalStylePreviewCell(
    style: GlobalStylePreviewStyle,
    label: String,
    isKey: Boolean,
    modifier: Modifier = Modifier,
) {
    var pressed by remember { mutableStateOf(false) }
    val cellColor = Color(previewKeyCellColor(pressed, style))
    val shape = if (isKey) {
        RoundedCornerShape(style.keyCornerRadiusDp.dp)
    } else {
        RoundedCornerShape(50)
    }
    val borderWidth = style.keyBorderWidthDp.dp
    val borderColor = Color(style.keyBorderStroke)
    Box(
        modifier = modifier
            .height(if (isKey) 56.dp else 44.dp)
            .clip(shape)
            .background(cellColor)
            .then(
                if (borderWidth > 0.dp && borderColor.alpha > 0f) {
                    Modifier.border(borderWidth, borderColor, shape)
                } else {
                    Modifier
                },
            )
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        pressed = true
                        tryAwaitRelease()
                        pressed = false
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            fontSize = if (isKey) 22.sp else 16.sp,
            color = Color(style.keyText),
            maxLines = 1,
        )
    }
}
