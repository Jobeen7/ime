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
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import com.jobeen.ime.R
import com.jobeen.ime.data.keyboard.theme.KeyboardTheme
import com.jobeen.ime.ui.theme.LocalMonetCardColor
import com.jobeen.ime.ui.theme.SuccessDark
import com.jobeen.ime.ui.theme.SuccessLight

object ScreenComponent {
    val barFontSize = 18.sp
    val rowFontSize = 15.sp
    val rowSubFontSize = 13.sp
    const val SWITCH_SCALE = 0.75f

    @Composable
    fun ProgressButton(progress: Float, extracting: Boolean = false, width: Dp = 72.dp) {
        val fraction = progress.coerceIn(0f, 1f)
        val buttonWidth = if (width > 0.dp) width else 72.dp
        val containerColor = if (extracting) {
            if (isSystemInDarkTheme()) SuccessDark else SuccessLight
        } else {
            MaterialTheme.colorScheme.primary
        }
        val progressColor = MaterialTheme.colorScheme.onPrimary
        Box(
            modifier = Modifier
                .width(buttonWidth)
                // 与去定高后的 Button 同高（自然高、字号再大也不裁）：
                // 最小 40dp（Material Button 自然高），内容更高时随内容长。
                .defaultMinSize(minHeight = 40.dp)
                .clip(RoundedCornerShape(8.dp))
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
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(start = 4.dp, top = 8.dp, bottom = 6.dp),
            ) {
                if (icon != null) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                }
                Text(
                    text = title,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = titleColor,
                )
            }
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
            shape = RoundedCornerShape(16.dp),
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
     * 导航行：单色图标 + 标题 + 右箭头，无副标题时只有一行。
     * 图标不带彩色底块，颜色跟随主题正文色（浅色黑、深色白）。
     */
    /**
     * 导航行：图标（可选）＋标题＋副标题，无箭头（可点性由整行点按承担）。
     * 须放在卡片内：横向留白由卡片提供，行自身只留纵向 12dp。
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
                .padding(vertical = 12.dp),
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
                    fontWeight = FontWeight.Medium,
                    color = titleColor,
                    maxLines = 1,
                )
                if (subtitle != null) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = subtitle,
                        fontSize = 14.sp,
                        lineHeight = 20.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
            }
        }
    }

    /** 琥珀色警告卡（内容流中的 Inline Alert，非 Banner）：深浅主题各一套琥珀色。 */
    @Composable
    fun WarningCard(
        modifier: Modifier = Modifier,
        content: @Composable ColumnScope.() -> Unit,
    ) {
        val dark = isSystemInDarkTheme()
        Card(
            modifier = modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = if (dark) Color(0xFF40300A) else Color(0xFFFFF0C2),
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

    /** 琥珀色警告卡内的文字色，与 WarningCard 容器配对。 */
    @Composable
    fun warningContentColor(): Color =
        if (isSystemInDarkTheme()) Color(0xFFFFD54A) else Color(0xFF6D4C00)

    /**
     * 开关行：标题 16sp，纵向 12dp；须放在卡片内，横向留白由卡片提供。
     * showDivider 保留参数以兼容调用点，按对齐要求不再绘制分隔线。
     */
    @Composable
    fun SwitchRow(
        title: String,
        checked: Boolean,
        onCheckedChange: (Boolean) -> Unit,
        showDivider: Boolean = false,
        enabled: Boolean = true,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .rowAnchor(title)
                .padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
                color = if (enabled) MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
            )
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                enabled = enabled,
                modifier = Modifier.scale(SWITCH_SCALE),
                colors = SwitchDefaults.colors(
                    checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                    checkedTrackColor = MaterialTheme.colorScheme.primary,
                    uncheckedThumbColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    uncheckedTrackColor = MaterialTheme.colorScheme.surfaceVariant,
                ),
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
     * 滑杆行：标题 16sp、值标签 14sp，纵向 12dp；须放在卡片内。
     * 横向留白全部由页边距＋卡片内边距提供，本行不再自带内缩：
     * 2026-10-29 批量轮的 inset 只是贴在三行上的补丁，页级与卡片级
     * 仍无留白才是贴边裁切的根因；补丁随统一装饰系统废除。
     * 滑块不再用 offset 补偿，拇指位置由滑块自身几何决定，落在卡内。
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
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .rowAnchor(title)
                .padding(vertical = 12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = if (enabled) MaterialTheme.colorScheme.onSurface
                    else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                )
                Text(
                    text = valueLabel,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = if (enabled) MaterialTheme.colorScheme.onSurfaceVariant
                    else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f),
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            // 圆点轨道：显式 20dp 圆点拇指（不依赖 M3 版本默认拇指样式；
            // 此前 3dp 竖条拇指即旧“蓝点”的一种形态）；轨道仍 4dp、
            // 主色激活段。滑杆在卡片 16dp 横向留白内，圆点边缘不超出卡片。
            Slider(
                value = value,
                onValueChange = onValueChange,
                onValueChangeFinished = onValueChangeFinished,
                valueRange = range,
                enabled = enabled,
                thumb = {
                    Box(
                        modifier = Modifier
                            .size(20.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary),
                    )
                },
                track = { sliderState ->
                    SliderDefaults.Track(
                        sliderState = sliderState,
                        modifier = Modifier.height(4.dp),
                        colors = SliderDefaults.colors(
                            activeTrackColor = MaterialTheme.colorScheme.primary,
                            inactiveTrackColor = MaterialTheme.colorScheme.surfaceVariant,
                        ),
                    )
                },
            )
        }
    }

    @Composable
    fun SectionHeader(title: String, icon: ImageVector? = null) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 4.dp, top = 8.dp, bottom = 8.dp),
        ) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(4.dp))
            }
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                                .clip(RoundedCornerShape(8.dp))
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

    @Composable
    fun ThemeChip(
        theme: KeyboardTheme,
        selected: Boolean,
        isCustom: Boolean = false,
        onClick: () -> Unit,
    ) {
        val colors = theme.colors

        val borderColor by animateColorAsState(
            targetValue = if (selected) MaterialTheme.colorScheme.primary
            else Color.Transparent,
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioMediumBouncy,
                stiffness = Spring.StiffnessMedium,
            ),
            label = "borderColor",
        )
        val bgColor by animateColorAsState(
            targetValue = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)
            else Color.Transparent,
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioMediumBouncy,
                stiffness = Spring.StiffnessMedium,
            ),
            label = "bgColor",
        )

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .clip(RoundedCornerShape(12.dp))
                .background(bgColor)
                .clickable(onClick = onClick)
                .padding(8.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(width = 48.dp, height = 40.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color(colors.background))
                    .padding(2.dp),
            ) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        repeat(5) {
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxSize()
                                    .clip(RoundedCornerShape(2.dp))
                                    .background(Color(colors.keyBackground)),
                            )
                        }
                    }
                    Row(
                        modifier = Modifier.weight(1f),
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Spacer(Modifier.weight(0.4f))
                        repeat(4) {
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxSize()
                                    .clip(RoundedCornerShape(2.dp))
                                    .background(Color(colors.keyBackground)),
                            )
                        }
                        Spacer(Modifier.weight(0.4f))
                    }
                    Row(
                        modifier = Modifier.weight(1f),
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxSize()
                                .clip(RoundedCornerShape(2.dp))
                                .background(Color(colors.specialKeyBackground)),
                        )
                        repeat(3) {
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxSize()
                                    .clip(RoundedCornerShape(2.dp))
                                    .background(Color(colors.specialKeyBackground)),
                            )
                        }
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxSize()
                                .clip(RoundedCornerShape(2.dp))
                                .background(Color(colors.keyBackground)),
                        )
                    }
                }
                if (isCustom) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(1.dp)
                            .size(16.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.primary),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_keyboard_user),
                            contentDescription = stringResource(R.string.custom_theme_marker),
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(10.dp),
                        )
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                text = theme.name,
                style = MaterialTheme.typography.labelSmall,
                fontSize = 13.sp,
                textAlign = TextAlign.Center,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                color = if (selected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
