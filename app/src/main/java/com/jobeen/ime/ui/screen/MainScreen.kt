package com.jobeen.ime.ui.screen

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.Build
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
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.jobeen.ime.R
import com.jobeen.ime.ui.screen.ScreenComponent.GroupCard
import com.jobeen.ime.ui.screen.ScreenComponent.GroupDivider
import com.jobeen.ime.ui.screen.ScreenComponent.NavRow
import com.jobeen.ime.ui.screen.ScreenComponent.SectionHeader
import com.jobeen.ime.ui.screen.ScreenComponent.SingleChoiceDialog
import com.jobeen.ime.ui.screen.ScreenComponent.barFontSize

/**
 * 设置 home：一页一组卡片，行 = 彩色方块图标 + 标题 + 右箭头，无副标题。
 * FAB 栈只放在本页：平时一个悬浮圆钮，点开展开两个圆钮——部署、同步。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    currentThemeMode: Int,
    onThemeModeChanged: (Int) -> Unit,
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
    val themes = listOf(
        stringResource(R.string.theme_follow_system),
        stringResource(R.string.theme_light),
        stringResource(R.string.theme_dark),
    )

    var showThemeDialog by remember { mutableStateOf(false) }
    var fabExpanded by remember { mutableStateOf(false) }
    val fabAnyRunning = deployRunning || syncRunning
    val fabRotation by animateFloatAsState(
        targetValue = if (fabExpanded) 45f else 0f,
        label = "fabRotation",
    )

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
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.app_name),
                        fontSize = barFontSize,
                        fontWeight = FontWeight.Bold,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onOpenImeSetup) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.ime_setup_title),
                            modifier = Modifier.scale(0.8f),
                            tint = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
        floatingActionButton = {
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (fabExpanded) {
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
                FloatingActionButton(
                    onClick = { if (!fabAnyRunning) fabExpanded = !fabExpanded },
                ) {
                    if (fabAnyRunning) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            strokeWidth = 2.dp,
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Filled.Add,
                            contentDescription = null,
                            modifier = Modifier.rotate(fabRotation),
                        )
                    }
                }
            }
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
            Spacer(Modifier.height(4.dp))

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

            SectionHeader(stringResource(R.string.basic_settings))
            GroupCard {
                val (c1, t1) = nextPair()
                NavRow(
                    title = stringResource(R.string.theme),
                    onClick = { showThemeDialog = true },
                    icon = Icons.Filled.Brush,
                    iconContainer = c1,
                    iconTint = t1,
                )
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
            }

            SectionHeader(stringResource(R.string.about))
            GroupCard {
                val (c1, t1) = nextPair()
                NavRow(
                    title = stringResource(R.string.about_us),
                    onClick = onOpenAbout,
                    icon = Icons.Filled.Info,
                    iconContainer = c1,
                    iconTint = t1,
                )
            }

            Spacer(Modifier.height(32.dp))
            }
            if (fabExpanded) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.32f))
                        .clickable { fabExpanded = false },
                )
            }
        }
    }

    BackHandler(enabled = fabExpanded) { fabExpanded = false }

    if (showThemeDialog) {
        SingleChoiceDialog(
            title = stringResource(R.string.theme),
            options = themes,
            selectedIndex = currentThemeMode,
            onSelect = { onThemeModeChanged(it); showThemeDialog = false },
            onDismiss = { showThemeDialog = false },
        )
    }
}

/** FAB 栈展开后的单个动作：文字标签 + 小圆钮；进行中转圈并禁用。 */
@Composable
private fun FabAction(
    label: String,
    icon: ImageVector,
    running: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.inverseSurface)
                .clickable(enabled = enabled, onClick = onClick)
                .padding(horizontal = 12.dp, vertical = 6.dp),
        ) {
            Text(
                text = label,
                color = MaterialTheme.colorScheme.inverseOnSurface,
                fontSize = barFontSize * 0.75f,
                fontWeight = FontWeight.Medium,
            )
        }
        SmallFloatingActionButton(
            onClick = { if (enabled) onClick() },
            modifier = Modifier.size(48.dp),
            shape = CircleShape,
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        ) {
            if (running) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                )
            } else {
                Icon(imageVector = icon, contentDescription = label)
            }
        }
    }
}
