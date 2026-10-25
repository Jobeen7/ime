package com.jobeen.ime.ui.screen

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.RocketLaunch
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.SystemUpdateAlt
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jobeen.ime.R
import com.jobeen.ime.ui.screen.ScreenComponent.GroupCard
import com.jobeen.ime.ui.screen.ScreenComponent.GroupDivider
import com.jobeen.ime.ui.screen.ScreenComponent.NavRow
import com.jobeen.ime.ui.screen.ScreenComponent.SectionHeader

/**
 * 设置 home：顶部 hero（居中 logo + Jime）+ 一页一组卡片，
 * 行 = 彩色方块图标 + 标题 + 右箭头，无副标题。
 * 应用主题已删除（Lee 2026-10-25 定）：恒跟随系统，不再设手动项。
 * FAB 只放在本页：圆形主钮锚在候选词行右侧红框位，颜色跟随主题；
 * 展开时两动作以 Popup 叠于主钮上方——部署、同步。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    onOpenImeSetup: () -> Unit,
    onOpenSchemaSettings: () -> Unit,
    onOpenSchemeUpdate: () -> Unit,
    onOpenRime: () -> Unit = {},
    isImeConfigured: Boolean = true,
    onOpenImePicker: () -> Unit = {},
    onOpenKeyboardSettings: () -> Unit,
    onOpenKeyboardThemeSettings: () -> Unit,
    onOpenClipboard: () -> Unit,
    onOpenModelSettings: () -> Unit,
    onOpenVoiceSettings: () -> Unit,
    onOpenUserDict: () -> Unit,
    onOpenFiles: () -> Unit,
    onOpenBackup: () -> Unit,
    onOpenAbout: () -> Unit,
    onDeploy: () -> Unit,
    onSync: () -> Unit,
    deployRunning: Boolean = false,
    syncRunning: Boolean = false,
) {
    var fabExpanded by remember { mutableStateOf(false) }
    val fabAnyRunning = deployRunning || syncRunning

    // 方块色按组内顺序循环：主色 / 次色 / 三色 / 中性灰，均为主题容器色。
    val iconPairs = listOf(
        MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer,
        MaterialTheme.colorScheme.secondaryContainer to MaterialTheme.colorScheme.onSecondaryContainer,
        MaterialTheme.colorScheme.tertiaryContainer to MaterialTheme.colorScheme.onTertiaryContainer,
        MaterialTheme.colorScheme.surfaceVariant to MaterialTheme.colorScheme.onSurfaceVariant,
    )
    var iconIndex = 0
    fun nextPair(): Pair<Color, Color> {
        val pair = iconPairs[iconIndex % iconPairs.size]
        iconIndex++
        return pair
    }

    Scaffold { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            // 收起层必须在内容之下：FAB 现已进内容行（候选词行），
            // 若收起层在上会盖住展开动作导致点不到；空白处点按仍可
            // 透过内容落到本层收起（行点按则照常导航）。
            if (fabExpanded) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clickable { fabExpanded = false },
                )
            }
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
            Spacer(Modifier.height(4.dp))

            // 顶部 hero（居中排列）：返回键独占一行，logo 居中、
            // 下方写 Jime；不再用 TopAppBar 标题栏。
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onOpenImeSetup) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.ime_setup_title),
                        modifier = Modifier.scale(0.8f),
                        tint = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp, bottom = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Image(
                    painter = painterResource(R.drawable.ic_about_logo),
                    contentDescription = null,
                    modifier = Modifier
                        .size(72.dp)
                        .clip(RoundedCornerShape(2.dp)),
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    text = stringResource(R.string.hero_app_name),
                    style = MaterialTheme.typography.titleLarge,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                )
            }

            // 切换输入法红条：仅当 Jime 不是当前输入法时出现；已是当前时
            // 整条不存在（不是隐藏占位）。点按调出系统输入法选择器。
            if (!isImeConfigured) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                    ),
                    onClick = onOpenImePicker,
                ) {
                    Text(
                        text = stringResource(R.string.ime_not_current_switch),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                    )
                }
                Spacer(Modifier.height(12.dp))
            }

            SectionHeader(stringResource(R.string.input_settings))
            GroupCard {
                val (c1, t1) = nextPair()
                NavRow(
                    title = stringResource(R.string.schema_settings),
                    onClick = onOpenSchemaSettings,
                    icon = Icons.Filled.Tune,
                    iconContainer = c1,
                    iconTint = t1,
                )
                GroupDivider()
                val (c2, t2) = nextPair()
                NavRow(
                    title = stringResource(R.string.schema_update_title),
                    onClick = onOpenSchemeUpdate,
                    icon = Icons.Filled.SystemUpdateAlt,
                    iconContainer = c2,
                    iconTint = t2,
                )
                GroupDivider()
                val (c2b, t2b) = nextPair()
                NavRow(
                    title = stringResource(R.string.rime_title),
                    onClick = onOpenRime,
                    icon = Icons.Filled.Build,
                    iconContainer = c2b,
                    iconTint = t2b,
                )
                GroupDivider()
                val (c3, t3) = nextPair()
                NavRow(
                    title = stringResource(R.string.keyboard_settings),
                    onClick = onOpenKeyboardSettings,
                    icon = Icons.Filled.Keyboard,
                    iconContainer = c3,
                    iconTint = t3,
                )
                GroupDivider()
                val (c4, t4) = nextPair()
                NavRow(
                    title = stringResource(R.string.keyboard_theme),
                    onClick = onOpenKeyboardThemeSettings,
                    icon = Icons.Filled.Palette,
                    iconContainer = c4,
                    iconTint = t4,
                )
            }

            SectionHeader(stringResource(R.string.extended_features))
            GroupCard {
                val (c1, t1) = nextPair()
                NavRow(
                    title = stringResource(R.string.clipboard_manager),
                    onClick = onOpenClipboard,
                    icon = Icons.Filled.ContentPaste,
                    iconContainer = c1,
                    iconTint = t1,
                )
                GroupDivider()
                val (c2, t2) = nextPair()
                // FAB 锚点行：圆形主钮贴在行右侧红框位（与行垂直居中），
                // 展开动作以 Popup 叠于主钮上方，不撑开行高、不被卡片裁剪。
                BoxWithConstraints(
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    // 红框右缘在屏宽 75.9% 处；内容宽 = 屏宽 − 32dp，
                    // 距内容右缘 = 0.241 × 屏宽 − 16dp
                    //            = 0.241 × 内容宽 − 8dp（本机约 83dp）。
                    val fabEndPadding =
                        (maxWidth * 0.241f - 8.dp).coerceAtLeast(16.dp)
                    NavRow(
                        title = stringResource(R.string.prediction_candidates),
                        onClick = onOpenModelSettings,
                        icon = Icons.Filled.AutoAwesome,
                        iconContainer = c2,
                        iconTint = t2,
                    )
                    FloatingActionButton(
                        onClick = { if (!fabAnyRunning) fabExpanded = !fabExpanded },
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .padding(end = fabEndPadding),
                        shape = CircleShape,
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    ) {
                        if (fabAnyRunning) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(24.dp),
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                strokeWidth = 2.dp,
                            )
                        } else if (fabExpanded) {
                            Icon(
                                imageVector = Icons.Filled.Close,
                                contentDescription = null,
                                modifier = Modifier.size(24.dp),
                            )
                        } else {
                            // 收起态：Lee 提供的 cloud_circle，颜色随主题内容色。
                            Icon(
                                painter = painterResource(R.drawable.ic_fab_cloud_circle),
                                contentDescription = null,
                                modifier = Modifier.size(24.dp),
                            )
                        }
                    }
                    if (fabExpanded) {
                        // 动作列高 108dp（48 + 12 + 48），主钮顶距行顶 2dp
                        // （行高 60dp、钮 56dp），动作底距钮顶留 12dp，
                        // 故相对行顶上移 118dp；动作列宽 48dp、主钮 56dp，
                        // 轴线对齐需再左移 4dp。
                        val density = LocalDensity.current
                        val popupOffset = with(density) {
                            IntOffset(
                                (-(fabEndPadding + 4.dp)).roundToPx(),
                                (-118).dp.roundToPx(),
                            )
                        }
                        Popup(
                            alignment = Alignment.TopEnd,
                            offset = popupOffset,
                            onDismissRequest = { fabExpanded = false },
                            properties = PopupProperties(focusable = false),
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                FabAction(
                                    label = stringResource(R.string.fab_deploy),
                                    icon = Icons.Filled.RocketLaunch,
                                    running = deployRunning,
                                    enabled = !fabAnyRunning,
                                    onClick = {
                                        fabExpanded = false
                                        onDeploy()
                                    },
                                )
                                FabAction(
                                    label = stringResource(R.string.fab_sync),
                                    icon = Icons.Filled.Sync,
                                    running = syncRunning,
                                    enabled = !fabAnyRunning,
                                    onClick = {
                                        fabExpanded = false
                                        onSync()
                                    },
                                )
                            }
                        }
                    }
                }
                GroupDivider()
                val (c3, t3) = nextPair()
                NavRow(
                    title = stringResource(R.string.voice_settings),
                    onClick = onOpenVoiceSettings,
                    icon = Icons.Filled.Mic,
                    iconContainer = c3,
                    iconTint = t3,
                )
                GroupDivider()
                val (c4, t4) = nextPair()
                NavRow(
                    title = stringResource(R.string.schema_files),
                    onClick = onOpenFiles,
                    icon = Icons.Filled.FolderOpen,
                    iconContainer = c4,
                    iconTint = t4,
                )
                GroupDivider()
                val (c5, t5) = nextPair()
                NavRow(
                    title = stringResource(R.string.user_dict),
                    onClick = onOpenUserDict,
                    icon = Icons.Filled.MenuBook,
                    iconContainer = c5,
                    iconTint = t5,
                )
                GroupDivider()
                val (c6, t6) = nextPair()
                NavRow(
                    title = stringResource(R.string.backup_restore),
                    onClick = onOpenBackup,
                    icon = Icons.Filled.Backup,
                    iconContainer = c6,
                    iconTint = t6,
                )
                // 关于我们并入组件功能组（置于备份与还原之下），不再单独立组；
                // 行本身保留（图标色对沿用 nextPair 循环）。
                GroupDivider()
                val (c7, t7) = nextPair()
                NavRow(
                    title = stringResource(R.string.about_us),
                    onClick = onOpenAbout,
                    icon = Icons.Filled.Info,
                    iconContainer = c7,
                    iconTint = t7,
                )
            }

            Spacer(Modifier.height(32.dp))
            }
        }
    }

    BackHandler(enabled = fabExpanded) { fabExpanded = false }
}

/** FAB 栈展开后的单个动作：裸图标在上 + 小字标签在下，
 *  无 pill、无彩色 mini-FAB；整项 48dp 可点区域，图标 28dp 主色。 */
@Composable
private fun FabAction(
    label: String,
    icon: ImageVector,
    running: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .size(48.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(enabled = enabled, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        if (running) {
            CircularProgressIndicator(
                modifier = Modifier.size(28.dp),
                color = MaterialTheme.colorScheme.primary,
                strokeWidth = 2.dp,
            )
        } else {
            Icon(
                imageVector = icon,
                contentDescription = label,
                modifier = Modifier.size(28.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
        }
        Spacer(Modifier.height(2.dp))
        Text(
            text = label,
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

