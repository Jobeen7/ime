package com.jobeen.ime.ui.screen

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
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
import androidx.compose.material3.FabPosition
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
 * FAB 只放在本页：圆形主钮右下角悬浮，颜色跟随主题；
 * 展开时三动作自钮位向上扇出——部署、同步、×（× 只收起）。
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

    Scaffold(
        floatingActionButton = {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(
                    bottom = WindowInsets.navigationBars.asPaddingValues()
                        .calculateBottomPadding(),
                ),
            ) {
                AnimatedVisibility(
                    visible = fabExpanded,
                    enter = expandVertically(expandFrom = Alignment.Bottom) + fadeIn(),
                    exit = shrinkVertically(shrinkTowards = Alignment.Bottom) + fadeOut(),
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
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
                            // × 项在栈底、贴主钮：只收起，任何时候都可点
                            // （进行中也不拦截收起，收起本身不取消任务）。
                            FabAction(
                                label = stringResource(R.string.fab_close),
                                icon = Icons.Filled.Close,
                                running = false,
                                enabled = true,
                                onClick = { fabExpanded = false },
                            )
                        }
                        Spacer(Modifier.height(12.dp))
                    }
                }
                FloatingActionButton(
                    onClick = { if (!fabAnyRunning) fabExpanded = !fabExpanded },
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
            }
        },
        floatingActionButtonPosition = FabPosition.End,
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            // 收起层在内容之下：空白处点按透过内容落到本层收起
            // （行点按则照常导航）；FAB 在 Scaffold 悬浮槽（高于
            // 内容），不受本层影响，展开动作点按正常。
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
                NavRow(
                        title = stringResource(R.string.prediction_candidates),
                        onClick = onOpenModelSettings,
                        icon = Icons.Filled.AutoAwesome,
                        iconContainer = c2,
                        iconTint = t2,
                    )
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

            // 底部留白 96dp：悬浮 FAB 区（56dp 钮＋16dp 边距）上方
            // 不遮挡末行，滚动到底时末行仍可完整点按。
            Spacer(Modifier.height(96.dp))
            }
        }
    }

    BackHandler(enabled = fabExpanded) { fabExpanded = false }
}

/** FAB 栈展开后的单个动作：圆角方块钮在上（主题主容器色底、
 *  内容色图标）＋小字标签在下；× 项同样式。块 48dp、图标 24dp。 */
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
            .clickable(enabled = enabled, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            if (running) {
                CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    strokeWidth = 2.dp,
                )
            } else {
                Icon(
                    imageVector = icon,
                    contentDescription = label,
                    modifier = Modifier.size(24.dp),
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }
        Spacer(Modifier.height(2.dp))
        Text(
            text = label,
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

