package com.jobeen.ime.ui.screen

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
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
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Palette
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import kotlinx.coroutines.delay
import kotlin.math.roundToInt
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jobeen.ime.R
import com.jobeen.ime.ui.screen.ScreenComponent.GroupCard
import com.jobeen.ime.ui.screen.ScreenComponent.GroupDivider
import com.jobeen.ime.ui.screen.ScreenComponent.NavRow
import com.jobeen.ime.ui.screen.ScreenComponent.SectionHeader

/** 辅助触控式 FAB 深色：参考图外盘色，固定深灰，双主题同色（不跟随主题）。 */
private val FabAssistiveDark = Color(0xFF202931)

/**
 * 设置 home：顶部 hero（居中 logo + Jime）+ 一页一组卡片，
 * 行 = 彩色方块图标 + 标题 + 右箭头，无副标题。
 * 应用主题已删除（Lee 2026-10-25 定）：恒跟随系统，不再设手动项。
 * FAB 只放在本页：辅助触控式深色圆钮，可拖动、松手贴边；
 * 展开时部署、同步两动作自钮位扇出（圆形深色钮＋浅色图标）。
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

    // 辅助触控式悬浮钮（仅设置页内）：可拖动、松手左右贴边、
    // 闲置 3 秒变淡、扇出方向跟随钮的位置（上半屏向下展开）。
    val context = LocalContext.current
    val fabPrefs = remember { context.getSharedPreferences("jime_fab", Context.MODE_PRIVATE) }
    val density = LocalDensity.current
    val fabSizePx = with(density) { 56.dp.toPx() }
    val fabMarginPx = with(density) { 16.dp.toPx() }
    var containerSize by remember { mutableStateOf(IntSize.Zero) }
    var fabFrac by remember {
        mutableStateOf(
            Offset(
                fabPrefs.getFloat("x_frac", 1f),
                fabPrefs.getFloat("y_frac", 1f),
            ),
        )
    }
    var dragOffset by remember { mutableStateOf(Offset.Zero) }
    var dragging by remember { mutableStateOf(false) }
    var fabFaded by remember { mutableStateOf(false) }
    var interactSeq by remember { mutableStateOf(0) }
    fun touch() {
        interactSeq++
        fabFaded = false
    }
    // 原汁原味（视频实测）：闲置 3 秒后整钮淡到 0.4（闲置中心
    // #707A7F 对墙纸 #23363E 解出约 0.40；拖动/展开时中心皆为
    // 纯白 #FFFFFF，即不透明）；触摸/拖动/展开/运行中恒为 1。
    // 拖动中途不淡：dragging 进 effect 键与条件，松手才重计
    // 3 秒（旧实现长拖超 3 秒会在拖动中途变淡）。
    LaunchedEffect(interactSeq, fabExpanded, fabAnyRunning, dragging) {
        if (fabExpanded || fabAnyRunning || dragging) {
            fabFaded = false
        } else {
            delay(3000)
            fabFaded = true
        }
    }
    val fabAlpha by animateFloatAsState(
        targetValue = if (fabFaded) 0.4f else 1f,
        label = "fabFade",
    )
    fun fracToPx(f: Offset, size: IntSize): Offset {
        if (size == IntSize.Zero) return Offset.Zero
        val w = (size.width - fabSizePx - 2 * fabMarginPx).coerceAtLeast(0f)
        val h = (size.height - fabSizePx - 2 * fabMarginPx).coerceAtLeast(0f)
        return Offset(f.x * w + fabMarginPx, f.y * h + fabMarginPx)
    }
    // 跟手时直接显示，松手后 X 向贴边值做弹簧动画。
    val rawPos = fracToPx(fabFrac, containerSize) + dragOffset
    val boundedX = rawPos.x.coerceIn(
        fabMarginPx,
        (containerSize.width - fabSizePx - fabMarginPx).coerceAtLeast(fabMarginPx),
    )
    val boundedY = rawPos.y.coerceIn(
        fabMarginPx,
        (containerSize.height - fabSizePx - fabMarginPx).coerceAtLeast(fabMarginPx),
    )
    val snapX by animateFloatAsState(
        targetValue = boundedX,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "fabSnapX",
    )
    val displayX = if (dragging || containerSize == IntSize.Zero) boundedX else snapX
    val displayY = boundedY
    var fanoutHeightPx by remember { mutableStateOf(0) }
    val expandDown = displayY < containerSize.height / 2f
    // 松手/取消统一落位：从实时状态重算当前位置（不用组合期捕获的
    // boundedX/Y，避免旧手势闭包读到首帧零尺寸），贴最近左右边、
    // 保持高度并持久化；取消不再弹回旧位。
    fun settleFab() {
        val curRaw = fracToPx(fabFrac, containerSize) + dragOffset
        val cur = Offset(
            curRaw.x.coerceIn(
                fabMarginPx,
                (containerSize.width - fabSizePx - fabMarginPx).coerceAtLeast(fabMarginPx),
            ),
            curRaw.y.coerceIn(
                fabMarginPx,
                (containerSize.height - fabSizePx - fabMarginPx).coerceAtLeast(fabMarginPx),
            ),
        )
        dragOffset = Offset.Zero
        val h = (containerSize.height - fabSizePx - 2 * fabMarginPx).coerceAtLeast(1f)
        val snapped = Offset(
            x = if (cur.x < containerSize.width / 2f) 0f else 1f,
            y = ((cur.y - fabMarginPx) / h).coerceIn(0f, 1f),
        )
        fabFrac = snapped
        fabPrefs.edit()
            .putFloat("x_frac", snapped.x)
            .putFloat("y_frac", snapped.y)
            .apply()
    }

    Scaffold(
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .onSizeChanged { containerSize = it },
        ) {
            // 收起层在内容之下：空白处点按透过内容落到本层收起
            // （行点按则照常导航）；FAB 覆盖层在内容之后（高于
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
                NavRow(
                    title = stringResource(R.string.schema_settings),
                    onClick = onOpenSchemaSettings,
                    icon = Icons.Filled.Tune,
                )
                GroupDivider()
                NavRow(
                    title = stringResource(R.string.schema_update_title),
                    onClick = onOpenSchemeUpdate,
                    icon = Icons.Filled.SystemUpdateAlt,
                )
                GroupDivider()
                NavRow(
                    title = stringResource(R.string.rime_title),
                    onClick = onOpenRime,
                    icon = Icons.Filled.Build,
                )
                GroupDivider()
                NavRow(
                    title = stringResource(R.string.keyboard_settings),
                    onClick = onOpenKeyboardSettings,
                    icon = Icons.Filled.Keyboard,
                )
                GroupDivider()
                NavRow(
                    title = stringResource(R.string.keyboard_theme),
                    onClick = onOpenKeyboardThemeSettings,
                    icon = Icons.Filled.Palette,
                )
            }

            SectionHeader(stringResource(R.string.extended_features))
            GroupCard {
                NavRow(
                    title = stringResource(R.string.clipboard_manager),
                    onClick = onOpenClipboard,
                    icon = Icons.Filled.ContentPaste,
                )
                GroupDivider()
                NavRow(
                        title = stringResource(R.string.prediction_candidates),
                        onClick = onOpenModelSettings,
                        icon = Icons.Filled.AutoAwesome,
                    )
                GroupDivider()
                NavRow(
                    title = stringResource(R.string.voice_settings),
                    onClick = onOpenVoiceSettings,
                    icon = Icons.Filled.Mic,
                )
                GroupDivider()
                NavRow(
                    title = stringResource(R.string.schema_files),
                    onClick = onOpenFiles,
                    icon = Icons.Filled.FolderOpen,
                )
                GroupDivider()
                NavRow(
                    title = stringResource(R.string.user_dict),
                    onClick = onOpenUserDict,
                    icon = Icons.Filled.MenuBook,
                )
                GroupDivider()
                NavRow(
                    title = stringResource(R.string.backup_restore),
                    onClick = onOpenBackup,
                    icon = Icons.Filled.Backup,
                )
                // 关于我们并入组件功能组（置于备份与还原之下），不再单独立组。
                GroupDivider()
                NavRow(
                    title = stringResource(R.string.about_us),
                    onClick = onOpenAbout,
                    icon = Icons.Filled.Info,
                )
            }

            // 底部留白 96dp：悬浮 FAB 区（56dp 钮＋16dp 边距）上方
            // 不遮挡末行，滚动到底时末行仍可完整点按。
            Spacer(Modifier.height(96.dp))
            }

            // 悬浮钮覆盖层（内容之上）：扇出在钮的上方或下方，
            // 钮本身可拖动；收起层在内容之下，不影响钮的点按。
            if (containerSize != IntSize.Zero) {
                val fanoutX = displayX + (fabSizePx - with(density) { 48.dp.toPx() }) / 2
                val fanoutY = if (expandDown) {
                    displayY + fabSizePx + with(density) { 12.dp.toPx() }
                } else {
                    displayY - fanoutHeightPx - with(density) { 12.dp.toPx() }
                }
                AnimatedVisibility(
                    visible = fabExpanded,
                    enter = expandVertically(
                        expandFrom = if (expandDown) Alignment.Top else Alignment.Bottom,
                    ) + fadeIn(),
                    exit = shrinkVertically(
                        shrinkTowards = if (expandDown) Alignment.Top else Alignment.Bottom,
                    ) + fadeOut(),
                    modifier = Modifier.offset {
                        IntOffset(fanoutX.roundToInt(), fanoutY.roundToInt())
                    },
                ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.onSizeChanged { fanoutHeightPx = it.height },
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                FabAction(
                                    label = stringResource(R.string.fab_deploy),
                                    icon = painterResource(R.drawable.ic_fab_deploy),
                                    running = deployRunning,
                                    enabled = !fabAnyRunning,
                                    onClick = {
                                        touch()
                                        fabExpanded = false
                                        onDeploy()
                                    },
                                )
                                FabAction(
                                    label = stringResource(R.string.fab_sync),
                                    icon = painterResource(R.drawable.ic_fab_sync),
                                    running = syncRunning,
                                    enabled = !fabAnyRunning,
                                    onClick = {
                                        touch()
                                        fabExpanded = false
                                        onSync()
                                    },
                                )
                            }
                        }
                    }
                Box(
                    modifier = Modifier
                        .offset { IntOffset(displayX.roundToInt(), displayY.roundToInt()) }
                        .size(56.dp)
                        .alpha(fabAlpha)
                        .pointerInput(containerSize) {
                            detectDragGestures(
                                onDragStart = {
                                    touch()
                                    fabExpanded = false
                                    dragging = true
                                },
                                onDragEnd = {
                                    dragging = false
                                    settleFab()
                                },
                                onDragCancel = {
                                    dragging = false
                                    settleFab()
                                },
                                onDrag = { change, dragAmount ->
                                    dragOffset += dragAmount
                                    change.consume()
                                },
                            )
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    FloatingActionButton(
                        onClick = {
                            touch()
                            if (!fabAnyRunning) fabExpanded = !fabExpanded
                        },
                        shape = CircleShape,
                        containerColor = FabAssistiveDark,
                        contentColor = Color.White,
                    ) {
                        if (fabAnyRunning) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(24.dp),
                                color = Color.White,
                                strokeWidth = 2.dp,
                            )
                        } else {
                            // 收起与展开同一图标（iPhone 辅助触控样式）：
                            // 展开后不再显示 ×，再点主钮即收起（onClick
                            // 的 fabExpanded 取反逻辑不变）。
                            Icon(
                                painter = painterResource(R.drawable.ic_fab_assistive),
                                contentDescription = null,
                                modifier = Modifier.size(24.dp),
                            )
                        }
                    }
                }
            }
        }
    }

    BackHandler(enabled = fabExpanded) { fabExpanded = false }
}

/** FAB 栈展开后的单个动作：圆形深色钮（辅助触控式，48dp）
 * ＋小字标签在下；图标为浅色。块 48dp、图标 24dp。 */
@Composable
private fun FabAction(
    label: String,
    icon: Painter,
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
                .clip(CircleShape)
                .background(FabAssistiveDark),
            contentAlignment = Alignment.Center,
        ) {
            if (running) {
                CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    color = Color.White,
                    strokeWidth = 2.dp,
                )
            } else {
                Icon(
                    painter = icon,
                    contentDescription = label,
                    modifier = Modifier.size(24.dp),
                    tint = Color.White,
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

