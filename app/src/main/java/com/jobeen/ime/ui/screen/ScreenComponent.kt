package com.jobeen.ime.ui.screen

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import com.jobeen.ime.R
import com.jobeen.ime.data.keyboard.theme.KeyboardTheme
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
                .height(32.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(containerColor),
        ) {
            Box(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .fillMaxHeight()
                    .fillMaxWidth(fraction)
                    .background(progressColor.copy(alpha = 0.3f)),
            )
            Text(
                text = "${(fraction * 100).toInt()}%",
                color = progressColor,
                fontSize = 13.sp,
                modifier = Modifier.align(Alignment.Center),
            )
        }
    }

    @Composable
    fun SettingsGroup(
        title: String,
        icon: ImageVector? = null,
        content: @Composable () -> Unit,
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
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
                    Spacer(Modifier.width(6.dp))
                }
                Text(
                    text = title,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
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
    }

    /**
     * 分组卡片：一张卡片装多行，组内行之间只用发丝线分隔，行自身无圆角无阴影。
     * 卡片即“一页中的一组”。
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
                containerColor = MaterialTheme.colorScheme.surfaceContainer,
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                content()
            }
        }
    }

    /** 分组卡片内的发丝线：装饰性分隔，无语义。 */
    @Composable
    fun GroupDivider() {
        HorizontalDivider(
            modifier = Modifier.padding(horizontal = 16.dp),
            thickness = 0.5.dp,
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
        )
    }

    /**
     * 导航行：彩色方块图标 + 标题 + 右箭头，无副标题。
     * 方块颜色由调用方按组内顺序取 Material 容器色循环，图标本身用轮廓图标。
     */
    @Composable
    fun NavRow(
        title: String,
        onClick: () -> Unit,
        icon: ImageVector,
        iconContainer: Color,
        iconTint: Color,
        titleColor: Color = MaterialTheme.colorScheme.onSurface,
        showChevron: Boolean = true,
        subtitle: String? = null,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(iconContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = iconTint,
                    modifier = Modifier.size(20.dp),
                )
            }
            Spacer(Modifier.width(12.dp))
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
            if (showChevron) {
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f),
                    modifier = Modifier.size(20.dp),
                )
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
                .padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
                fontSize = rowSubFontSize,
                color = if (enabled) MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
            )
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                enabled = enabled,
                modifier = Modifier.scale(SWITCH_SCALE),
                colors = SwitchDefaults.colors(
                    checkedThumbColor = MaterialTheme.colorScheme.primary,
                    checkedTrackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.4f),
                    uncheckedThumbColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    uncheckedTrackColor = MaterialTheme.colorScheme.surfaceVariant,
                ),
            )
        }
        if (showDivider) {
            HorizontalDivider(
                thickness = 0.5.dp,
                color = MaterialTheme.colorScheme.outlineVariant,
            )
        }
    }

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
                .padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontSize = rowSubFontSize,
                )
                if (subtitle != null) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = rowSubFontSize,
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            trailing()
        }
        if (showDivider) {
            HorizontalDivider(
                thickness = 0.5.dp,
                color = MaterialTheme.colorScheme.outlineVariant,
            )
        }
    }

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
                .padding(vertical = 6.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f),
                    fontSize = rowSubFontSize,
                    color = if (enabled) MaterialTheme.colorScheme.onSurface
                    else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                )
                Text(
                    text = valueLabel,
                    style = MaterialTheme.typography.bodyMedium,
                    fontSize = rowSubFontSize,
                    fontWeight = FontWeight.Medium,
                    color = if (enabled) MaterialTheme.colorScheme.onSurfaceVariant
                    else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f),
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Slider(
                value = value,
                onValueChange = onValueChange,
                onValueChangeFinished = onValueChangeFinished,
                valueRange = range,
                enabled = enabled,
                modifier = Modifier.offset(x = (-6).dp),
                thumb = {
                    Box(
                        modifier = Modifier
                            .size(width = 3.dp, height = 14.dp)
                            .clip(RoundedCornerShape(1.dp))
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
        if (showDivider) {
            HorizontalDivider(
                thickness = 0.5.dp,
                color = MaterialTheme.colorScheme.outlineVariant,
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
            AlertDialog(
                onDismissRequest = { onStepChange(0) },
                title = { Text(stringResource(R.string.about_reset)) },
                text = {
                    Text(
                        stringResource(R.string.about_reset_footnote) +
                            stringResource(R.string.about_reset_confirm1_msg),
                    )
                },
                confirmButton = {
                    TextButton(onClick = { onStepChange(2) }) {
                        Text(stringResource(R.string.about_reset_continue))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { onStepChange(0) }) {
                        Text(stringResource(R.string.cancel))
                    }
                },
            )
        }
        if (step == 2) {
            AlertDialog(
                onDismissRequest = { onStepChange(0) },
                title = { Text(stringResource(R.string.about_reset_confirm2_title)) },
                text = { Text(stringResource(R.string.about_reset_confirm2_msg)) },
                confirmButton = {
                    TextButton(onClick = {
                        onStepChange(0)
                        onConfirmed()
                    }) {
                        Text(
                            stringResource(R.string.about_reset_confirm),
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                },
                dismissButton = {
                    TextButton(onClick = { onStepChange(0) }) {
                        Text(stringResource(R.string.cancel))
                    }
                },
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
        AlertDialog(
            onDismissRequest = onDismiss,
            shape = RoundedCornerShape(16.dp),
            containerColor = MaterialTheme.colorScheme.surface,
            title = {
                Text(
                    text = title,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                )
            },
            text = {
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
            confirmButton = {
                TextButton(onClick = onDismiss) {
                    Text(
                        stringResource(R.string.cancel),
                        fontWeight = FontWeight.Medium,
                    )
                }
            },
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
