// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import com.jobeen.ime.ui.theme.LocalMonetCardColor
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 行定位注册表：通用渲染器的搜索定位用。
 * 行组件在布局时把标题→根坐标 Y 登记进来；页脚手架按标题滚动到该行。
 * 标题重复时后登记的覆盖先登记的；标题不在本页时定位静默放弃。
 */
class RowAnchorRegistry {
    val offsets = mutableStateMapOf<String, Float>()
    var columnY by mutableStateOf(0f)
}

val LocalRowAnchors = compositionLocalOf<RowAnchorRegistry?> { null }

/** 行定位锚点：无脚手架提供注册表时为空操作，可安全用在任何行上。 */
fun Modifier.rowAnchor(title: String): Modifier = composed {
    val registry = LocalRowAnchors.current
    if (registry == null) {
        this
    } else {
        onGloballyPositioned { coords: LayoutCoordinates ->
            registry.offsets[title] = coords.positionInRoot().y
        }
    }
}

/**
 * 跨页定位请求：搜索页设置后打开目标页，目标页脚手架首次组合时消费一次。
 * 尽力而为：进程重建、标题不在本页时静默放弃，不报错、不阻塞。
 */
object PendingFocus {
    var title: String? = null
}

/**
 * 通用设置页脚手架：所有设置页共用同一结构，不再各写 Scaffold。
 * 顶栏＝返回＋标题＋可选操作；内容＝纵向滚动列，左右 16dp 页边距；
 * focusTitle（或 PendingFocus）命中时滚动定位到该标题行。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsPageScaffold(
    title: String,
    onBack: () -> Unit,
    actions: @Composable RowScope.() -> Unit = {},
    focusTitle: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val scrollState = rememberScrollState()
    val registry = remember { RowAnchorRegistry() }
    val pendingTitle = remember {
        PendingFocus.title.also { PendingFocus.title = null }
    }
    val targetTitle = focusTitle ?: pendingTitle
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = title,
                        fontSize = ScreenComponent.barFontSize,
                        fontWeight = FontWeight.Bold,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = null,
                        )
                    }
                },
                actions = actions,
            )
        },
    ) { padding ->
        CompositionLocalProvider(LocalRowAnchors provides registry) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(scrollState)
                    .padding(horizontal = 16.dp)
                    .onGloballyPositioned { coords: LayoutCoordinates ->
                        registry.columnY = coords.positionInRoot().y
                    },
                content = content,
            )
        }
        LaunchedEffect(targetTitle) {
            val wanted = targetTitle ?: return@LaunchedEffect
            val absoluteY = withTimeoutOrNull(3000) {
                snapshotFlow { registry.offsets[wanted] }.filterNotNull().first()
            } ?: return@LaunchedEffect
            val top = (absoluteY - registry.columnY).toInt().coerceAtLeast(0)
            scrollState.animateScrollTo(top)
        }
    }
}

/**
 * 懒列设置页脚手架：与通用脚手架同一顶栏与页边距，但内容自身滚动
 * （LazyColumn 等懒列不能嵌进纵向滚动列）。搜索定位不适用此类页
 * （仅页级结果，无行级定位），故不提供注册表与定位逻辑。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsPageLazyScaffold(
    title: String,
    onBack: () -> Unit,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = title,
                        fontSize = ScreenComponent.barFontSize,
                        fontWeight = FontWeight.Bold,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = null,
                        )
                    }
                },
                actions = actions,
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            content = { content() },
        )
    }
}

/**
 * 节标题：一段内再分段时的段上标题（比组标题大一级：16sp 正文色）。
 * 组标题（14sp 强调色）与节标题分层，不混用。
 */
@Composable
fun SettingsSectionTitle(title: String) {
    Text(
        text = title,
        fontSize = 16.sp,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.padding(start = 4.dp, top = 8.dp, bottom = 6.dp),
    )
}

/** 对话框操作字：默认主题强调色；传 color 可覆盖（如错误色用于破坏性确认）。 */
data class SettingsDialogAction(
    val text: String,
    val onClick: () -> Unit,
    val color: Color? = null,
    val enabled: Boolean = true,
)

/**
 * 通用设置对话框：对话框＋子卡分段＋操作字（参考对话框结构，颜色用 Jime 主题）。
 * 对话框底＝ surfaceContainerHigh 圆角 28dp；分段子卡＝ surface 圆角 16dp；
 * 操作字默认主题强调色；3 个及以上操作字左右铺开，2 个及以下靠右。
 * scrollable 为 false 时内容自身滚动（如单选列表自带滚动），默认整框滚动。
 */
@Composable
fun SettingsDialog(
    onDismissRequest: () -> Unit,
    title: String,
    description: String? = null,
    sections: List<Pair<String, @Composable () -> Unit>> = emptyList(),
    body: (@Composable () -> Unit)? = null,
    actions: List<SettingsDialogAction>,
    scrollable: Boolean = true,
) {
    Dialog(onDismissRequest = onDismissRequest) {
        Card(
            shape = RoundedCornerShape(28.dp),
            colors = CardDefaults.cardColors(
                // 弹出卡片面＝莫奈色（2026-10-30 莫奈色轮，取代旧 surfaceContainerHigh
                // 层级；内层分区卡仍用 surface，保持对话框内层级）。
                containerColor = LocalMonetCardColor.current,
            ),
        ) {
            val scroll = rememberScrollState()
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(if (scrollable) Modifier.verticalScroll(scroll) else Modifier)
                    .padding(horizontal = 24.dp, vertical = 20.dp),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                if (description != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = description,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (sections.isNotEmpty() || body != null) {
                    Spacer(Modifier.height(16.dp))
                }
                sections.forEach { (sectionTitle, sectionContent) ->
                    Card(
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surface,
                        ),
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                        ) {
                            Text(
                                text = sectionTitle,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Spacer(Modifier.height(8.dp))
                            sectionContent()
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                }
                if (body != null) {
                    body()
                    Spacer(Modifier.height(16.dp))
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement =
                        if (actions.size >= 3) Arrangement.SpaceBetween
                        else Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    actions.forEach { action ->
                        TextButton(onClick = action.onClick, enabled = action.enabled) {
                            Text(
                                text = action.text,
                                color = action.color
                                    ?: MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 二选确认对话框：标题＋说明文字＋取消／确认操作字（确认可传错误色）。 */
@Composable
fun SettingsConfirmDialog(
    onDismissRequest: () -> Unit,
    title: String,
    text: String,
    confirmText: String,
    onConfirm: () -> Unit,
    dismissText: String? = null,
    onDismiss: (() -> Unit)? = null,
    confirmColor: Color? = null,
) {
    val actions = buildList {
        if (dismissText != null) {
            add(SettingsDialogAction(dismissText, onDismiss ?: onDismissRequest))
        }
        add(SettingsDialogAction(confirmText, onConfirm, confirmColor))
    }
    SettingsDialog(
        onDismissRequest = onDismissRequest,
        title = title,
        body = {
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        actions = actions,
    )
}
