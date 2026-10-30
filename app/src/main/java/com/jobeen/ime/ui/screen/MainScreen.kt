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
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.imePadding
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
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Style
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import com.jobeen.ime.ui.theme.LocalMonetCardColor
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.IconButton
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.util.lerp
import androidx.compose.foundation.border
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import kotlinx.coroutines.delay
import kotlin.math.roundToInt
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jobeen.ime.R
import com.jobeen.ime.ui.screen.ScreenComponent.GroupCard
import com.jobeen.ime.ui.screen.ScreenComponent.NavRow
import com.jobeen.ime.ui.screen.ScreenComponent.SettingsGroup

/** 辅助触控式 FAB 深色：参考图外盘色，固定深灰，双主题同色（不跟随主题）。 */
// 主钮 4 层实心圆颜色（原版录屏实测）：静止外盘/深环/浅环/
// 中心，拖动时外盘/深环/浅环变亮、中心恒纯白。
private val FabOuterRest = Color(0xFF182023)
private val FabOuterDrag = Color(0xFF354C56)
private val FabDeepRest = Color(0xFF4D5357)
private val FabDeepDrag = Color(0xFFA6B8BB)
private val FabLightRest = Color(0xFF787C80)
private val FabLightDrag = Color(0xFFC0D1D9)
private val FabCenterColor = Color(0xFFFFFFFF)

/**
 * 设置 home（Lee 2026-10-29 开工，分组重组）：顶栏（返回＋Jime＋
 * 键盘图标＋⋮）＋搜索框＋“设置”区 6 组卡片（图标＋标题＋副标题），
 * 点组进分组子页（顶栏返回＋组标题，行＝标题＋副标题，返回回主页）；
 * 搜索按行标题＋副标题过滤，无匹配的组自动隐藏。
 * 应用外观（Lee 2026-10-29 定）：自动／亮色／暗色／莫奈色，
 * 只影响设置页，与皮肤颜色模式独立。
 * FAB 只放在主页：辅助触控式深色圆钮，可拖动、松手贴边；
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
    onOpenSkinLibrary: () -> Unit,
    onOpenToolbar: () -> Unit = {},
    onOpenAppearance: () -> Unit = {},
    onOpenInputStats: () -> Unit = {},
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
    // 分组重组：主页只列 6 组卡片，点组进子页；搜索按行标题＋副标题
    // 过滤，无匹配的组自动隐藏；子页只列本组过滤后的行。
    var searchQuery by remember { mutableStateOf("") }
    var openGroup by remember { mutableStateOf<MainSettingGroup?>(null) }
    var searchOpen by remember { mutableStateOf(false) }
    var showTopMenu by remember { mutableStateOf(false) }
    val homeScroll = rememberScrollState()
    val subScroll = rememberScrollState()
    val searchScroll = rememberScrollState()
    val fabAnyRunning = deployRunning || syncRunning

    // 测试输入面板：只属于主页；图标点按打开覆盖式底部面板，
    // 面板自身处理返回／下滑收起，进分组子页时收起。
    val testInputState = remember { TestInputPanelState() }
    val keyboardController = LocalSoftwareKeyboardController.current

    // 独立搜索页：打开即聚焦输入框并调键盘；搜索页内不做主页过滤，
    // 主页恒列全部 6 组（过滤只发生在搜索页结果里）。
    val searchFocusRequester = remember { FocusRequester() }
    LaunchedEffect(searchOpen) {
        if (searchOpen) {
            searchFocusRequester.requestFocus()
            keyboardController?.show()
        }
    }

    // 辅助触控式悬浮钮（仅设置页内）：可拖动、松手左右贴边、
    // 闲置 3 秒变淡、扇出方向跟随钮的位置（上半屏向下展开）。
    val context = LocalContext.current
    val fabPrefs = remember { context.getSharedPreferences("jime_fab", Context.MODE_PRIVATE) }
    val density = LocalDensity.current
    val fabSizePx = with(density) { 60.dp.toPx() }
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
    // 运行开始自动收起：任一运行源起跑（fabAnyRunning 由 false
    // 变 true）时，若扇出正展开则收起；已收起时不写状态，无额外
    // 副作用；运行结束不自动再展开。fabExpanded 刻意不进键：触发
    // 点只应是运行状态变化，而非扇出自身的开关。运行指示与上
    // 述变淡 effect 均不动。
    LaunchedEffect(fabAnyRunning) {
        if (fabAnyRunning && fabExpanded) {
            fabExpanded = false
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

    // 14 行目录：标题＋副标题＋组＋点击动作；入口与目的地与改版前完全一致，
    // 只改呈现（主页组卡片＋子页行），不改偏好键、不改回调、不碰键盘服务。
    // 行表唯一来源＝目录表 catalogPages（键、标题、副标题、组与之一致）。
    val rowClicks: Map<String, () -> Unit> = mapOf(
        "schema" to onOpenSchemaSettings,
        "scheme_update" to onOpenSchemeUpdate,
        "rime" to onOpenRime,
        "candidates" to onOpenModelSettings,
        "keyboard" to onOpenKeyboardSettings,
        "skin" to onOpenSkinLibrary,
        "toolbar" to onOpenToolbar,
        "appearance" to onOpenAppearance,
        "clipboard" to onOpenClipboard,
        "voice" to onOpenVoiceSettings,
        "stats" to onOpenInputStats,
        "userdict" to onOpenUserDict,
        "backup" to onOpenBackup,
        "files" to onOpenFiles,
        "about" to onOpenAbout,
    )
    val allEntries = catalogPages().map { page ->
        MainSettingEntry(
            page.key,
            stringResource(page.titleRes),
            stringResource(page.subtitleRes),
            page.group,
        )
    }
    val visibleGroups = groupMainSettings(allEntries)
    val openRows = openGroup?.let { g -> allEntries.filter { it.group == g } }.orEmpty()

    // 搜索页解析：目录页＋行一次解析成字符串，纯搜索函数在其上跑；
    // 行定位标题：缺省行标题，键盘行定位到所在分类行。
    val resolvedPages = catalogPages().map { page ->
        ResolvedPage(
            page.key,
            stringResource(page.titleRes),
            stringResource(page.subtitleRes),
            groupTitle(page.group),
        )
    }
    val resolvedRows = catalogRows().map { row ->
        val page = resolvedPages.first { it.key == row.pageKey }
        ResolvedRow(
            title = stringResource(row.titleRes),
            pageKey = row.pageKey,
            pageTitle = page.title,
            groupTitle = page.groupTitle,
            sectionTitle = row.sectionRes?.let { stringResource(it) },
            focusTitle = row.focusTitleRes?.let { stringResource(it) },
        )
    }
    val searchResults = searchCatalog(searchQuery, resolvedPages, resolvedRows)

    Scaffold(
        topBar = {
            val open = openGroup
            if (searchOpen) {
                TopAppBar(
                    title = {
                        Text(
                            text = stringResource(R.string.main_search_hint),
                            fontSize = ScreenComponent.barFontSize,
                            fontWeight = FontWeight.Bold,
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = { searchOpen = false }) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(R.string.back),
                                modifier = Modifier.scale(0.8f),
                                tint = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    },
                )
            } else if (open == null) {
                TopAppBar(
                    title = {
                        Text(
                            text = stringResource(R.string.hero_app_name),
                            fontSize = ScreenComponent.barFontSize,
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
                    actions = {
                        // 键盘图标：打开测试输入面板（设置页内试打）。
                        // 原系统输入法选择器动作不再挂在这里，改由红条
                        // 与面板内切换入口承担。
                        IconButton(onClick = { testInputState.open() }) {
                            Icon(
                                Icons.Filled.Keyboard,
                                contentDescription = stringResource(R.string.test_input_title),
                                tint = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                        Box {
                            IconButton(onClick = { showTopMenu = true }) {
                                Icon(
                                    Icons.Filled.MoreVert,
                                    contentDescription = stringResource(R.string.schema_menu_more),
                                    tint = MaterialTheme.colorScheme.onSurface,
                                )
                            }
                            DropdownMenu(
                                expanded = showTopMenu,
                                onDismissRequest = { showTopMenu = false },
                                // 菜单卡面＝莫奈色（2026-10-30 莫奈色轮）。
                                containerColor = LocalMonetCardColor.current,
                            ) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.fab_deploy)) },
                                    enabled = !fabAnyRunning,
                                    onClick = {
                                        showTopMenu = false
                                        touch()
                                        onDeploy()
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.fab_sync)) },
                                    enabled = !fabAnyRunning,
                                    onClick = {
                                        showTopMenu = false
                                        touch()
                                        onSync()
                                    },
                                )
                            }
                        }
                    },
                )
            } else {
                TopAppBar(
                    title = {
                        Text(
                            text = groupTitle(open),
                            fontSize = ScreenComponent.barFontSize,
                            fontWeight = FontWeight.Bold,
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = { openGroup = null }) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(R.string.back),
                                modifier = Modifier.scale(0.8f),
                                tint = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    },
                )
            }
        },
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
            if (fabExpanded && openGroup == null && !searchOpen) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clickable { fabExpanded = false },
                )
            }
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(
                        if (searchOpen) searchScroll
                        else if (openGroup == null) homeScroll
                        else subScroll,
                    )
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
            Spacer(Modifier.height(4.dp))

            // 切换输入法红条：仅当 Jime 不是当前输入法时出现；已是当前时
            // 整条不存在（不是隐藏占位）。点按调出系统输入法选择器。
            // 搜索页不带红条（专注搜索）。
            if (!isImeConfigured && !searchOpen) {
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

            // 搜索页：输入框＋跨目录结果（标题＋面包屑路径），点结果
            // 打开目标页并定位到项（键盘行定位到所在分类行）。
            // 主页：搜索卡＋“设置”区一张卡含 6 组；子页：本组行。
            // FAB 只在主页出现。
            if (searchOpen) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(searchFocusRequester),
                    placeholder = { Text(stringResource(R.string.main_search_hint)) },
                    leadingIcon = {
                        Icon(Icons.Filled.Search, contentDescription = null)
                    },
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { searchQuery = "" }) {
                                Icon(
                                    Icons.Filled.Clear,
                                    contentDescription = stringResource(R.string.cancel),
                                )
                            }
                        }
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(16.dp),
                )
                Spacer(Modifier.height(12.dp))
                if (searchQuery.isNotBlank()) {
                    if (searchResults.isEmpty()) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 32.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text(
                                text = stringResource(R.string.main_search_empty),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = stringResource(R.string.main_search_empty_hint),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    } else {
                        GroupCard {
                            searchResults.forEach { result ->
                                NavRow(
                                    title = result.title,
                                    subtitle = result.path,
                                    onClick = {
                                        searchOpen = false
                                        if (result.focusTitle != null) {
                                            PendingFocus.title = result.focusTitle
                                        }
                                        rowClicks.getValue(result.pageKey)()
                                    },
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
            } else if (openGroup == null) {
                // 搜索卡：卡片行，标题＋示例副标题，无描边框、无放大镜；
                // 点按进独立搜索页（进页时收起测试面板，与进子页一致）。
                GroupCard(
                    modifier = Modifier.clickable {
                        testInputState.onNavigateAway()
                        searchOpen = true
                    },
                ) {
                    Text(
                        text = stringResource(R.string.main_search_hint),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = stringResource(R.string.main_search_example),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(12.dp))
                // 测试输入面板：覆盖式底部面板（不占主页布局），打开即聚焦并调键盘。
                if (testInputState.isOpen) {
                    TestInputPanelSheet(
                        isImeConfigured = isImeConfigured,
                        mode = testInputState.mode,
                        onModeChange = { testInputState.mode = it },
                        onSwitchIme = onOpenImePicker,
                        onClose = {
                            keyboardController?.hide()
                            testInputState.close()
                        },
                    )
                }
                // “设置”区：一张卡含全部 6 组（组行带图标、无箭头、
                // 无分隔线）；组行点按走单页组直达通用规则（见组行 onClick）。
                // 主页“设置”区头与组标题同色（onSurface），不再用主色（绿）。
            SettingsGroup(
                title = stringResource(R.string.main_settings_header),
                titleColor = MaterialTheme.colorScheme.onSurface,
            ) {
                    visibleGroups.forEach { (group, _) ->
                        NavRow(
                            title = groupTitle(group),
                            subtitle = groupSubtitle(group),
                            onClick = {
                                fabExpanded = false
                                testInputState.onNavigateAway()
                                // 单页组直达（通用规则，替代原先仅关于组的特判）：
                                // 组内仅 1 页时直接进页，不经过只剩自己一行的
                                // 组页（外观组即此 bug：组页只剩外观自己一行）；
                                // 多页组仍置 openGroup 进组页。详情页返回时
                                // openGroup 从未被单页组置位，故落回主页组列，
                                // 不再落回单行组页。测试输入面板只属于主页，
                                // 离页联动收起不变。
                                val direct = directPageForGroup(allEntries, group)
                                if (direct != null) {
                                    rowClicks.getValue(direct.key)()
                                } else {
                                    openGroup = group
                                }
                            },
                            icon = groupIcon(group),
                        )
                    }
                }

                // 底部留白 96dp：悬浮 FAB 区（60dp 钮＋16dp 边距）上方
                // 不遮挡末行，滚动到底时末行仍可完整点按。
                Spacer(Modifier.height(96.dp))
            } else {
                val group = openGroup
                if (group != null) {
                    // 组页行：标题＋副标题，无图标、无箭头、无分隔线。
                    GroupCard {
                        openRows.forEach { row ->
                            NavRow(
                                title = row.title,
                                subtitle = row.subtitle,
                                onClick = rowClicks.getValue(row.key),
                            )
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                }
            }
            }

            // 悬浮钮覆盖层（内容之上）：扇出在钮的上方或下方，
            // 钮本身可拖动；收起层在内容之下，不影响钮的点按。
            if (openGroup == null && !searchOpen && containerSize != IntSize.Zero) {
                val fanoutX = displayX + (fabSizePx - with(density) { 52.dp.toPx() }) / 2
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
                                    glyph = FabGlyph.DEPLOY,
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
                                    glyph = FabGlyph.SYNC,
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
                        .size(60.dp)
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
                        // 与静止外盘同色：Canvas 若小于钮面，露出的边环与外盘无缝；
                        // 拖动时 Canvas 放大到 75dp，盖住整个钮面。
                        containerColor = Color(0xFF182023),
                        contentColor = Color.White,
                    ) {
                        if (fabAnyRunning) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(26.dp),
                                color = Color.White,
                                strokeWidth = 2.dp,
                            )
                        } else {
                            // 收起与展开同一图标（iPhone 辅助触控样式）：
                            // 展开后不再显示 ×，再点主钮即收起（onClick
                            // 的 fabExpanded 取反逻辑不变）。图标按原版
                            // 录屏实测画 4 层实心圆（非 .12 的细描边），
                            // 拖动时变大＋各圈变亮（见 AssistiveTouchIcon）。
                            AssistiveTouchIcon(dragging = dragging)
                        }
                    }
                }
            }
        }
    }

    // 面板为底部面板时由面板自身处理返回（下滑／返回收起），主页返回链不再收面板。
    BackHandler(enabled = searchOpen || openGroup != null || fabExpanded) {
        if (searchOpen) searchOpen = false
        else if (openGroup != null) openGroup = null else fabExpanded = false
    }
}

/**
 * 测试输入面板：覆盖式底部面板，样式走 Jime 自身外观体系（MaterialTheme 配色）。
 * 打开即聚焦输入框并调起键盘；关闭按钮、下滑与返回键均收起面板（面板自身处理返回）。
 * 三 chip 切输入类型（文本／数字／密码），键盘布局跟随当前布局与输入类型；
 * 当前输入法不是 Jime 时，面板内给一行提示＋切换入口，不静默
 * 假装调起的是 Jime。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TestInputPanelSheet(
    isImeConfigured: Boolean,
    mode: TestInputMode,
    onModeChange: (TestInputMode) -> Unit,
    onSwitchIme: () -> Unit,
    onClose: () -> Unit,
) {
    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    var text by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
        keyboardController?.show()
    }
    ModalBottomSheet(
        onDismissRequest = {
            keyboardController?.hide()
            onClose()
        },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        // 底部面板卡面＝莫奈色（2026-10-30 莫奈色轮）。
        containerColor = LocalMonetCardColor.current,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .imePadding()
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.test_input_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.weight(1f))
                IconButton(onClick = {
                    keyboardController?.hide()
                    onClose()
                }) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = stringResource(R.string.test_input_close),
                        tint = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (entry in TestInputMode.entries) {
                    FilterChip(
                        selected = mode == entry,
                        onClick = { onModeChange(entry) },
                        label = {
                            Text(
                                stringResource(
                                    when (entry) {
                                        TestInputMode.TEXT -> R.string.test_input_mode_text
                                        TestInputMode.NUMBER -> R.string.test_input_mode_number
                                        TestInputMode.PASSWORD -> R.string.test_input_mode_password
                                    },
                                ),
                            )
                        },
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester),
                placeholder = { Text(stringResource(R.string.test_input_hint)) },
                singleLine = true,
                shape = RoundedCornerShape(16.dp),
                keyboardOptions = KeyboardOptions(
                    keyboardType = when (mode) {
                        TestInputMode.TEXT -> KeyboardType.Text
                        TestInputMode.NUMBER -> KeyboardType.Number
                        TestInputMode.PASSWORD -> KeyboardType.Password
                    },
                ),
                visualTransformation = if (mode == TestInputMode.PASSWORD) {
                    PasswordVisualTransformation()
                } else {
                    VisualTransformation.None
                },
            )
            if (!isImeConfigured) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.test_input_not_current),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = onSwitchIme) {
                    Text(stringResource(R.string.test_input_switch_ime))
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

/**
 * 主钮图标：4 层实心圆由外向内叠画（原版录屏实测，禁止画成细描边——
 * .12 的细线就是“只像白色中心”的根因）。
 * 静止 60dp；拖动时 0.15s 内放大到 75dp，各层半径分数与颜色同步
 * 向拖动值过渡（中心 0.226→0.282，浅环 0.298→0.317，深环
 * 0.345→0.351，外盘恒 0.50）；停稳/松手 0.15s 对称缩回。
 * 按下不动不放大（放大只跟随移动）；闲置变淡沿用外层 fabAlpha，
 * 不在这里做；松手后变淡延迟视频量不到，沿用现有 3 秒机制。
 */
@Composable
private fun AssistiveTouchIcon(dragging: Boolean) {
    val dragProgress by animateFloatAsState(
        targetValue = if (dragging) 1f else 0f,
        animationSpec = tween(150),
        label = "fabDragGrow",
    )
    Canvas(modifier = Modifier.size(60.dp * (1f + 0.25f * dragProgress))) {
        val side = size.minDimension
        val c = center
        drawCircle(
            color = lerp(FabOuterRest, FabOuterDrag, dragProgress),
            radius = 0.50f * side,
            center = c,
        )
        drawCircle(
            color = lerp(FabDeepRest, FabDeepDrag, dragProgress),
            radius = lerp(0.345f, 0.351f, dragProgress) * side,
            center = c,
        )
        drawCircle(
            color = lerp(FabLightRest, FabLightDrag, dragProgress),
            radius = lerp(0.298f, 0.317f, dragProgress) * side,
            center = c,
        )
        drawCircle(
            color = FabCenterColor,
            radius = lerp(0.226f, 0.282f, dragProgress) * side,
            center = c,
        )
    }
}

/** FAB 栈展开后的单个动作：圆形玻璃钮（磨砂近似，52dp）
 * ＋小字标签在下；块 52dp、图标 32dp。玻璃是近似实现：
 * 半透明填充＋1dp 描边＋顶部高光渐变＋轻阴影，深浅按主题背景亮度判定；
 * 不是 iOS 真液态玻璃（无实时折射、无背景实时模糊）。
 * 图标与进度圈用 onSurface：浅色玻璃上白色看不见。
 * 符号不用 Icon/ImageVector：改 Canvas 手绘路径，只画符号本身、
 * 不经过矢量画笔与着色滤镜，杜绝符号外多余底形。 */
private enum class FabGlyph { DEPLOY, SYNC }

/** 部署符号：向下箭头＋托盘（24 视口坐标，源自 Material Download 的两段路径）。 */
private val deployGlyphPath = Path().apply {
    moveTo(5f, 20f)
    lineTo(19f, 20f)
    lineTo(19f, 18f)
    lineTo(5f, 18f)
    close()
    moveTo(19f, 9f)
    lineTo(15f, 9f)
    lineTo(15f, 3f)
    lineTo(9f, 3f)
    lineTo(9f, 9f)
    lineTo(5f, 9f)
    lineTo(12f, 16f)
    close()
}

/** 同步符号：两段带箭头的环形带（24 视口坐标，外径 8、内径 5.6、圆心 12,12）。 */
private val syncGlyphPath = Path().apply {
    val outer = Rect(4f, 4f, 20f, 20f)
    val inner = Rect(6.4f, 6.4f, 17.6f, 17.6f)
    // 上箭头：顺时针，外弧 -90° 起扫 130° 至 40°，箭头尖沿切线外伸。
    arcTo(outer, -90f, 130f, false)
    lineTo(15.666f, 18.209f)
    lineTo(16.290f, 15.599f)
    arcTo(inner, 40f, -130f, false)
    close()
    // 下箭头：顺时针，外弧 90° 起扫 130° 至 220°，箭头尖沿切线外伸。
    arcTo(outer, 90f, 130f, true)
    lineTo(8.334f, 5.792f)
    lineTo(7.710f, 8.401f)
    arcTo(inner, 220f, -130f, false)
    close()
}

@Composable
private fun FabAction(
    label: String,
    glyph: FabGlyph,
    running: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    // 深浅按主题背景亮度判定，不跟随系统深浅色（自定义主题背景与系统可能不一致）。
    val lightGlass = MaterialTheme.colorScheme.background.luminance() > 0.5f
    val glassFill = if (lightGlass) Color.White.copy(alpha = 0.55f) else Color.White.copy(alpha = 0.18f)
    val glassStroke = if (lightGlass) Color.White.copy(alpha = 0.35f) else Color.White.copy(alpha = 0.25f)
    val glassHighlight = if (lightGlass) Color.White.copy(alpha = 0.35f) else Color.White.copy(alpha = 0.25f)
    Column(
        modifier = Modifier
            .clickable(enabled = enabled, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(52.dp)
                .shadow(4.dp, CircleShape)
                .clip(CircleShape)
                .background(glassFill)
                .drawBehind {
                    drawRect(
                        brush = Brush.verticalGradient(
                            colors = listOf(glassHighlight, Color.Transparent),
                            startY = 0f,
                            endY = size.height * 0.5f,
                        ),
                        size = size,
                    )
                }
                .border(1.dp, glassStroke, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (running) {
                CircularProgressIndicator(
                    modifier = Modifier.size(26.dp),
                    color = MaterialTheme.colorScheme.onSurface,
                    strokeWidth = 2.dp,
                )
            } else {
                val glyphPath = if (glyph == FabGlyph.DEPLOY) deployGlyphPath else syncGlyphPath
                val glyphColor = MaterialTheme.colorScheme.onSurface
                Canvas(
                    modifier = Modifier.size(32.dp),
                ) {
                    val scale = size.width / 24f
                    withTransform({ scale(scale, scale) }) {
                        drawPath(glyphPath, color = glyphColor)
                    }
                }
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

/** 主页组卡片标题（与子页顶栏标题同一文案）。 */
@Composable
private fun groupTitle(group: MainSettingGroup): String = when (group) {
    MainSettingGroup.INPUT_CANDIDATE -> stringResource(R.string.main_group_input_candidate)
    MainSettingGroup.KEYBOARD -> stringResource(R.string.main_group_keyboard)
    MainSettingGroup.APPEARANCE -> stringResource(R.string.main_group_appearance)
    MainSettingGroup.CONVENIENCE -> stringResource(R.string.main_group_convenience)
    MainSettingGroup.DATA_BACKUP -> stringResource(R.string.main_group_data_backup)
    MainSettingGroup.ADVANCED_ABOUT -> stringResource(R.string.main_group_advanced_about)
}

/** 主页组卡片副标题：一目了然写清该组管什么。 */
@Composable
private fun groupSubtitle(group: MainSettingGroup): String = when (group) {
    MainSettingGroup.INPUT_CANDIDATE -> stringResource(R.string.main_group_input_candidate_desc)
    MainSettingGroup.KEYBOARD -> stringResource(R.string.main_group_keyboard_desc)
    MainSettingGroup.APPEARANCE -> stringResource(R.string.main_group_appearance_desc)
    MainSettingGroup.CONVENIENCE -> stringResource(R.string.main_group_convenience_desc)
    MainSettingGroup.DATA_BACKUP -> stringResource(R.string.main_group_data_backup_desc)
    MainSettingGroup.ADVANCED_ABOUT -> stringResource(R.string.main_group_advanced_about_desc)
}

/** 主页组卡片图标：Jime 自身图标体系，不引新图标。 */
private fun groupIcon(group: MainSettingGroup): ImageVector = when (group) {
    MainSettingGroup.INPUT_CANDIDATE -> Icons.Filled.Tune
    MainSettingGroup.KEYBOARD -> Icons.Filled.Keyboard
    MainSettingGroup.APPEARANCE -> Icons.Filled.Style
    MainSettingGroup.CONVENIENCE -> Icons.Filled.AutoAwesome
    MainSettingGroup.DATA_BACKUP -> Icons.Filled.Backup
    MainSettingGroup.ADVANCED_ABOUT -> Icons.Filled.Info
}

