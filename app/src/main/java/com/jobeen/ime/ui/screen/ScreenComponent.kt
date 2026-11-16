package com.jobeen.ime.ui.screen

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import com.jobeen.ime.R
import com.jobeen.ime.data.keyboard.theme.KeyboardTheme
import com.jobeen.ime.ui.theme.LocalMonetCardColor

object ScreenComponent {
    val barFontSize = 22.sp
    // 行标题 16sp / 行副标题 14sp
    // （androidx.preference 默认 ?attr/textAppearanceListItem 为 16sp，
    //  summary 为 14sp；此前 15/13 是自有取值）。
    val rowFontSize = 16.sp
    val rowSubFontSize = 14.sp
    const val SWITCH_SCALE = 1f

    /**
     * 行间分隔：同组各行连成一整块 20dp 圆角面，视觉上就是「没有分隔线」。
     * 保留此函数只为兼容既有调用点，不绘制任何内容。
     */
    @Composable
    fun GroupDivider() {
        // 有意留空：无分隔线列表。
    }

    @Composable
    fun ProgressButton(progress: Float, extracting: Boolean = false, width: Dp = 72.dp) {
        val fraction = progress.coerceIn(0f, 1f)
        val buttonWidth = if (width > 0.dp) width else 72.dp
        val containerColor = MaterialTheme.colorScheme.primary
        val progressColor = MaterialTheme.colorScheme.onPrimary
        Box(
            modifier = Modifier
                .width(buttonWidth)
                // 与去定高后的 Button 同高（自然高、字号再大也不裁）：
                // 最小 40dp（Material Button 自然高），内容更高时随内容长。
                .defaultMinSize(minHeight = 40.dp)
                .clip(MaterialTheme.shapes.large)
                .background(containerColor)
                // 进度条按比例绘制，不参与布局：此前内层 Box 用 fillMaxHeight，
                // 在 wrap-content 父容器里取到最大约束，把整行撑到满屏高。
                .drawBehind {
                    val w = size.width * fraction
                    if (w > 0f) {
                        drawRect(
                            color = progressColor.copy(alpha = 0.3f),
                            topLeft = Offset.Zero,
                            size = Size(w, size.height),
                        )
                    }
                },
        ) {
            Text(
                text = "${(fraction * 100).toInt()}%",
                color = progressColor,
                fontSize = 13.sp,
                modifier = Modifier.align(Alignment.Center),
            )
        }
    }

    /**
     * 分组：组标题＋卡片一体（统一装饰系统）。
     * 组标题与组行标题同色（onSurface，深色白、浅色黑）、14sp；
     * 标题与卡间距 6dp；卡后留 20dp 组末间距。
     * 卡内横向留白由卡片提供，行自身不再加横向边距。
     */
    @Composable
    fun SettingsGroup(
        title: String,
        icon: ImageVector? = null,
        titleColor: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurface,
        content: @Composable () -> Unit,
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // 分组标题
            // 13sp / Bold / colorPrimary / minHeight 40dp / padding 16-12-16-8
            Text(
                text = title,
                fontSize = 13.sp,
                lineHeight = 18.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 40.dp)
                    .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 8.dp),
            )
            // 分类标题与首行之间 6dp
            // outRect.bottom = dp(6)。
            Spacer(Modifier.height(6.dp))
            GroupCard {
                content()
            }
            Spacer(Modifier.height(20.dp))
        }
    }

    /**
     * 分组卡片：一张卡片装多行，行之间无分隔线、靠行内纵向留白区分。
     * 卡片即“一页中的一组”；卡内横向 16dp、纵向 8dp 留白由卡片统一提供，
     * 行自身不再加横向边距（此前各行各自加边距、有的加有的不加，是外观页
     * 内容贴屏边的根因之一）。
     */
    @Composable
    fun GroupCard(
        modifier: Modifier = Modifier,
        content: @Composable ColumnScope.() -> Unit,
    ) {
        Card(
            modifier = modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.large,
            colors = CardDefaults.cardColors(
                // 分组卡片面＝莫奈色（2026-10-30 莫奈色轮，浅／深两模式皆然）。
                containerColor = LocalMonetCardColor.current,
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                content()
            }
        }
    }

    /**
     * 导航行：图标（可选）＋标题＋副标题，无箭头（可点性由整行点按承担）。
     * 须放在卡片内：横向留白由卡片提供，行自身只留纵向 14dp。
     * 图标只用于主页组行；组页与详情页行不传图标（标题＋副标题）。
     */
    @Composable
    fun NavRow(
        title: String,
        onClick: () -> Unit,
        icon: ImageVector? = null,
        titleColor: Color = MaterialTheme.colorScheme.onSurface,
        subtitle: String? = null,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .rowAnchor(title)
                .padding(vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(12.dp))
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    fontSize = 16.sp,
                    lineHeight = 21.sp,
                    fontWeight = FontWeight.Bold,
                    color = titleColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (subtitle != null) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = subtitle,
                        fontSize = 13.sp,
                        lineHeight = 18.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }

    /** 琥珀色警告卡（内容流中的 Inline Alert，非 Banner）：容器取 tertiaryContainer。 */
    @Composable
    fun WarningCard(
        modifier: Modifier = Modifier,
        content: @Composable ColumnScope.() -> Unit,
    ) {
        Card(
            modifier = modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.large,
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.tertiaryContainer,
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            ) {
                content()
            }
        }
    }

    /**
     * 开关行：标题 16sp＋一行说明（14sp 灰色，有则显示）＋右侧 M3 Switch；
     * 须放在卡片内，横向留白由卡片提供。整行可点即切换（点 Switch 本身亦然，
     * 内层点按优先消费，不会双重切换）；enabled＝false 时整行置灰不可点。
     * showDivider 保留参数以兼容调用点，按对齐要求不再绘制分隔线。
     */
    @Composable
    fun SwitchRow(
        title: String,
        checked: Boolean,
        onCheckedChange: (Boolean) -> Unit,
        showDivider: Boolean = false,
        enabled: Boolean = true,
        subtitle: String? = null,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .rowAnchor(title)
                .then(
                    if (enabled) {
                        Modifier.clickable(role = Role.Switch) { onCheckedChange(!checked) }
                    } else {
                        Modifier
                    },
                )
                .padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (enabled) MaterialTheme.colorScheme.onSurface
                    else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                )
                if (subtitle != null) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                enabled = enabled,
                modifier = Modifier.scale(SWITCH_SCALE),
            )
        }
    }

    /**
     * 操作行：标题 16sp、副标题 14sp，纵向 12dp；须放在卡片内。
     * showDivider 保留参数以兼容调用点，按对齐要求不再绘制分隔线。
     */
    @Composable
    fun ActionRow(
        title: String,
        subtitle: String? = null,
        trailing: @Composable () -> Unit,
        showDivider: Boolean = false,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .rowAnchor(title)
                .padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                )
                if (subtitle != null) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            trailing()
        }
    }

    /**
     * 滑块行（弹窗形态，2026-11-16 定）：行内只显示标题 16sp＋当前值 14sp 灰色＋›，
     * 点行弹 Dialog 调值（当前值大字＋一条滑块，三钮 默认值／取消／确定）。
     * 拖动即写回 onValueChange（与旧行内滑块一致，行内值实时刷新）；
     * 取消把打开时的原值写回，确定才调 onValueChangeFinished 落盘
     * （如剪贴板上限：拖动只改本地显示，确定才按新上限裁剪历史）。
     * defaultValue＝null 时不显示「默认值」钮（默认值未核实的项不猜）。
     * showDivider 保留参数以兼容调用点，按对齐要求不再绘制分隔线。
     */
    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun SliderRow(
        title: String,
        value: Float,
        valueLabel: String,
        range: ClosedFloatingPointRange<Float>,
        onValueChange: (Float) -> Unit,
        showDivider: Boolean = false,
        enabled: Boolean = true,
        onValueChangeFinished: (() -> Unit)? = null,
        // 滑块调用都传了 stepSize（0.5 / 1 / 0.1），轨道上因此有一排刻度点。
        // Compose 里对应 steps = (max-min)/step - 1；steps = 0 时只有两端 stop 点、没有刻度点。
        steps: Int = 0,
        defaultValue: Float? = null,
    ) {
        var showDialog by remember { mutableStateOf(false) }
        var dialogDraft by remember { mutableStateOf(value) }
        var dialogOriginal by remember { mutableStateOf(value) }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .rowAnchor(title)
                .then(
                    if (enabled) {
                        Modifier.clickable {
                            dialogDraft = value
                            dialogOriginal = value
                            showDialog = true
                        }
                    } else {
                        Modifier
                    },
                )
                .padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = title,
                fontSize = 16.sp,
                lineHeight = 21.sp,
                color = if (enabled) MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = valueLabel,
                fontSize = 14.sp,
                lineHeight = 20.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
            Spacer(modifier = Modifier.width(8.dp))
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        }
        if (showDialog) {
            SettingsDialog(
                onDismissRequest = {
                    showDialog = false
                    onValueChange(dialogOriginal)
                },
                title = title,
                body = {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            text = valueLabel,
                            fontSize = 28.sp,
                            lineHeight = 36.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                            textAlign = TextAlign.Center,
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        // 弹窗内的滑块不接 onValueChangeFinished：
                        // 松手落盘只在点「确定」时做一次，否则取消恢复不了已落盘的中间值。
                        Slider(
                            value = dialogDraft,
                            onValueChange = {
                                dialogDraft = it
                                onValueChange(it)
                            },
                            valueRange = range,
                            steps = steps,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp),
                        )
                    }
                },
                actions = buildList {
                    if (defaultValue != null) {
                        add(
                            SettingsDialogAction(
                                text = stringResource(R.string.settings_slider_default),
                                onClick = {
                                    dialogDraft = defaultValue
                                    onValueChange(defaultValue)
                                },
                            ),
                        )
                    }
                    add(
                        SettingsDialogAction(
                            text = stringResource(R.string.cancel),
                            onClick = {
                                showDialog = false
                                onValueChange(dialogOriginal)
                            },
                        ),
                    )
                    add(
                        SettingsDialogAction(
                            text = stringResource(R.string.settings_slider_confirm),
                            onClick = {
                                showDialog = false
                                onValueChange(dialogDraft)
                                onValueChangeFinished?.invoke()
                            },
                        ),
                    )
                },
            )
        }
    }

    /**
     * 重置应用设置二次确认（关于页与 RIME 页共用，同一动作、同一文案）：
     * step 1 = 首确认（范围说明 + 不可撤销），step 2 = 再次确认；
     * 两次都只有取消 / 继续，绝不加“不再询问”。
     */
    @Composable
    fun ResetConfirmDialogs(
        step: Int,
        onStepChange: (Int) -> Unit,
        onConfirmed: () -> Unit,
    ) {
        if (step == 1) {
            SettingsConfirmDialog(
                onDismissRequest = { onStepChange(0) },
                title = stringResource(R.string.about_reset),
                text = stringResource(R.string.about_reset_footnote) +
                    stringResource(R.string.about_reset_confirm1_msg),
                confirmText = stringResource(R.string.about_reset_continue),
                onConfirm = { onStepChange(2) },
                dismissText = stringResource(R.string.cancel),
            )
        }
        if (step == 2) {
            SettingsConfirmDialog(
                onDismissRequest = { onStepChange(0) },
                title = stringResource(R.string.about_reset_confirm2_title),
                text = stringResource(R.string.about_reset_confirm2_msg),
                confirmText = stringResource(R.string.about_reset_confirm),
                onConfirm = {
                    onStepChange(0)
                    onConfirmed()
                },
                dismissText = stringResource(R.string.cancel),
                confirmColor = MaterialTheme.colorScheme.error,
            )
        }
    }

    @Composable
    fun SingleChoiceDialog(
        title: String,
        options: List<String>,
        selectedIndex: Int,
        onSelect: (Int) -> Unit,
        onDismiss: () -> Unit,
    ) {
        SettingsDialog(
            onDismissRequest = onDismiss,
            title = title,
            scrollable = false,
            body = {
                Column(
                    modifier = Modifier
                        .heightIn(max = 320.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    options.forEachIndexed { index, option ->
                        val isSelected = index == selectedIndex
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(MaterialTheme.shapes.small)
                                .clickable { onSelect(index) }
                                .background(
                                    if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                                    else Color.Transparent
                                )
                                .padding(vertical = 10.dp, horizontal = 4.dp),
                        ) {
                            RadioButton(
                                selected = isSelected,
                                onClick = { onSelect(index) },
                                colors = RadioButtonDefaults.colors(
                                    selectedColor = MaterialTheme.colorScheme.primary,
                                    unselectedColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                ),
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = option,
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = if (isSelected) FontWeight.Medium else FontWeight.Normal,
                                color = if (isSelected) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                }
            },
            actions = listOf(
                SettingsDialogAction(
                    text = stringResource(R.string.cancel),
                    onClick = onDismiss,
                ),
            ),
        )
    }

}
