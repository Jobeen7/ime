package com.jobeen.ime.ui.screen

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import com.jobeen.ime.ui.theme.LocalMonetCardColor
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.jobeen.ime.input.stats.InputStats
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 输入统计仪表盘（2026-10-29 22:04 Lee 口述结构，逐项执行）：
 * 顶栏（分享＋⋮）＋渐变 hero 卡＋方案行＋今日／本周／本月／今年
 * ＋分段四项（上屏次数／输入字数／击键次数／误触率）＋输入速度
 * （极速／均速）＋词长分布（三档）＋码长分布（空态）＋键盘热力
 * ＋本地统计脚注。
 *
 * 口径说明（数据层与存储语义不动，除新增采集外）：
 * - 段位为五阶（2026-10-29 22:09 Lee 决定，取代图示凡键境／初识
 *   键盘与旧七段位）：炼气期／筑基期／金丹期／元婴期／化神期，
 *   阈值 0／2000／8000／20000／150000（旧七段位表合并边界，
 *   交回等否决）；Lv＝段位序＋1；EXP＝段内已获字数 ／ 到下一
 *   段位差距（新鲜用户 0 / 2000，不是图示 0 / 100，等否决）。
 * - 迅速评级←全局最快速度，精准评级←本年准确率推断值；阈值与
 *   零数据默认值（速度无数据→C、准确率无数据→S+，与图示零数
 *   据态一致）均为提议口径，等否决。
 * - 词长显示三档：单字／双字词／多字词（多字词＝三字＋四字以上
 *   求和），存储四计数不动；总量 0 时行显 0% 并追加“暂无数据”。
 * - 码长分布：提交路径只有提交文本，引擎不回传码长／预编辑，
 *   无干净采集源，故只留“暂无数据”空态，不建脏近似采集。
 * - 键盘热力：新增按键身份采集（InputStats.heatKeyOf，向前采
 *   集，历史为 0%）；热力为全局量，不受方案过滤影响；T9 数字
 *   键与符号键无唯一动作身份，不映射（对应格恒 0%，已报告）。
 * - 方案行：图示为纯文本行，本实现为单行＋点按开方案选择对话
 *   框（功能保留、呈现偏离，等否决）；仅 period＝DAY 且当日
 *   字数为 0 时追加“ · 🐢 今天刚起步～”。
 * - 速度卡第二行“—”在图示中无法识别为何值，故只渲染值＋单位。
 * - 打字时长与按键类别分布不再上屏（数据仍采集）；底部按钮行
 *   移除，功能迁入：导出／导入→⋮菜单的备份与恢复，分享为图→
 *   分享对话框的图片项，清统计→⋮菜单的清空项。
 * - WEEK 聚合仍是滚动 7 天，仅标签按参考写作“本周”。
 * - 误触率＝误触修正数 ÷ 按键总数（删除后 5000ms 内再输入记 1 次，
 *   一次删除事件只记 1 次；0 按键显“—”）；误触新定义本身仍等
 *   Lee 一句话（2026-10-29 19:36 起未答复），不催。
 * - 准确率是推断值（提交字数 ÷（提交字数＋删除数）），界面注明。
 * - 数据仅存本机，不上传；清统计只清除周期数据，段位与累计保留。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InputStatsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var period by remember { mutableStateOf(InputStats.Period.DAY) }
    var scheme by remember { mutableStateOf<String?>(null) }
    var refreshTick by remember { mutableStateOf(0) }
    var showClearConfirm by remember { mutableStateOf(false) }
    var showClearFastestConfirm by remember { mutableStateOf(false) }
    var showShareDialog by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    var showSchemeDialog by remember { mutableStateOf(false) }
    var showBackupDialog by remember { mutableStateOf(false) }
    var shareOption by remember { mutableStateOf(ShareOption.SHORT) }

    // refreshTick 变化时重读快照（Compose 内读纯函数，无 IO）。
    @Suppress("UNUSED_VARIABLE")
    val tick = refreshTick
    val stats = InputStats.snapshot(period, scheme)
    val heroStats = InputStats.snapshot(period, null)
    val yearStats = InputStats.snapshot(InputStats.Period.YEAR, null)
    val rank = InputStats.rank()
    val fastest = InputStats.fastestDaySpeed()
    val schemes = InputStats.schemeIds(period)

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    val text = context.contentResolver.openInputStream(uri)
                        ?.bufferedReader()?.readText() ?: return@runCatching false
                    InputStats.importJson(text)
                }.getOrDefault(false)
            }
            Toast.makeText(
                context,
                if (ok) "导入成功" else "导入失败：文件格式不正确，现有数据未改动",
                Toast.LENGTH_SHORT,
            ).show()
            refreshTick++
        }
    }

    fun toast(message: String) {
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }

    fun shareFile(file: File, type: String, title: String) {
        val uri = FileProvider.getUriForFile(
            context, "${context.packageName}.fileprovider", file,
        )
        val intent = Intent(Intent.ACTION_SEND).apply {
            this.type = type
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(Intent.createChooser(intent, title))
    }

    fun shareJson() {
        scope.launch {
            val file = withContext(Dispatchers.IO) {
                val dir = File(context.cacheDir, "shared").apply { mkdirs() }
                File(dir, "input-stats.json").apply {
                    writeText(InputStats.exportJson(), Charsets.UTF_8)
                }
            }
            shareFile(file, "application/json", "导出输入统计")
        }
    }

    fun shareImage() {
        scope.launch {
            val file = withContext(Dispatchers.IO) {
                InputStatsImage.renderToFile(context, period, heroStats, rank)
            }
            if (file == null) {
                toast("分享图生成失败")
            } else {
                shareFile(file, "image/png", "分享输入统计")
            }
        }
    }

    fun shareText(text: String) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
            if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(Intent.createChooser(intent, "分享输入统计"))
    }

    fun copyText(text: String) {
        val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        if (manager == null) {
            toast("复制失败：剪贴板不可用")
        } else {
            manager.setPrimaryClip(ClipData.newPlainText("输入统计", text))
            toast("已复制")
        }
    }

    SettingsPageLazyScaffold(
        title = "输入统计",
        onBack = onBack,
        actions = {
            IconButton(onClick = { showShareDialog = true }) {
                Icon(Icons.Filled.Share, contentDescription = "分享")
            }
            IconButton(onClick = { showMenu = true }) {
                Icon(Icons.Filled.MoreVert, contentDescription = "更多")
            }
            DropdownMenu(
                expanded = showMenu,
                onDismissRequest = { showMenu = false },
                // 菜单卡面＝莫奈色（2026-10-30 莫奈色轮）。
                containerColor = LocalMonetCardColor.current,
            ) {
                DropdownMenuItem(
                    text = { Text("清空统计数据") },
                    onClick = {
                        showMenu = false
                        showClearConfirm = true
                    },
                )
                DropdownMenuItem(
                    text = { Text("清除极速记录") },
                    onClick = {
                        showMenu = false
                        showClearFastestConfirm = true
                    },
                )
                DropdownMenuItem(
                    text = { Text("备份与恢复") },
                    onClick = {
                        showMenu = false
                        showBackupDialog = true
                    },
                )
            }
        },
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {

            item {
                // Hero 卡：五阶段位 pill＋Lv＋EXP 条＋累计／迅速／精准；
                // 渐变用 Jime 主题色，不用图示暗红。
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            Brush.horizontalGradient(
                                listOf(
                                    MaterialTheme.colorScheme.primaryContainer,
                                    MaterialTheme.colorScheme.secondaryContainer,
                                ),
                            ),
                            RoundedCornerShape(16.dp),
                        )
                        .padding(16.dp),
                ) {
                    val onHero = MaterialTheme.colorScheme.onPrimaryContainer
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Box(
                                modifier = Modifier
                                    .background(
                                        onHero.copy(alpha = 0.2f),
                                        RoundedCornerShape(12.dp),
                                    )
                                    .padding(horizontal = 10.dp, vertical = 4.dp),
                            ) {
                                Text(
                                    rank.name,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = onHero,
                                )
                            }
                            Text(
                                "Lv.${rank.level + 1}",
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold,
                                color = onHero,
                            )
                        }
                        LinearProgressIndicator(
                            progress = rank.progress.toFloat(),
                            modifier = Modifier.fillMaxWidth(),
                            color = onHero,
                            trackColor = onHero.copy(alpha = 0.25f),
                        )
                        Text(
                            stageExpText(rank),
                            fontSize = 13.sp,
                            color = onHero,
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    "${rank.xp} 字",
                                    fontWeight = FontWeight.Bold,
                                    color = onHero,
                                )
                                Text("累计输入", fontSize = 12.sp, color = onHero)
                            }
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    "${speedRating(fastest)} · 迅速",
                                    fontWeight = FontWeight.Bold,
                                    color = onHero,
                                )
                                Text(
                                    formatSpeed(fastest),
                                    fontSize = 12.sp,
                                    color = onHero,
                                )
                            }
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    "${accuracyRating(yearStats.accuracy)} · 精准",
                                    fontWeight = FontWeight.Bold,
                                    color = onHero,
                                )
                                Text(
                                    formatAccuracy(yearStats.accuracy),
                                    fontSize = 12.sp,
                                    color = onHero,
                                )
                            }
                        }
                        Text(
                            "注：准确率＝提交字数 ÷（提交字数＋删除数），删除不一定都是纠错，仅供参考。",
                            fontSize = 12.sp,
                            color = onHero.copy(alpha = 0.8f),
                        )
                    }
                }
            }
            item {
                // 方案行：单行＋点按开方案选择对话框（图示为纯文本行，
                // 此处为功能保留的呈现偏离，等否决）。
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showSchemeDialog = true }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "方案：" + (scheme?.ifBlank { "未知方案" } ?: "全部方案") +
                            if (period == InputStats.Period.DAY &&
                                stats.total.chars == 0L
                            ) {
                                " · 🐢 今天刚起步～"
                            } else {
                                ""
                            },
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (p in InputStats.Period.entries) {
                        val label = segmentLabel(p)
                        if (p == period) {
                            Button(onClick = {}) { Text(label) }
                        } else {
                            OutlinedButton(
                                onClick = { period = p; scheme = null },
                            ) { Text(label) }
                        }
                    }
                }
            }
            item {
                // 分段四项：上屏次数／输入字数／击键次数／误触率；
                // 0 值显“—”（与图示零数据态一致）。
                val t = stats.total
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = LocalMonetCardColor.current,
                    ),
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text(segmentLabel(period), fontWeight = FontWeight.Bold)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            StatMetric(
                                "上屏次数",
                                if (t.commits == 0L) "—" else "${t.commits}",
                                Modifier.weight(1f),
                            )
                            StatMetric(
                                "输入字数",
                                if (t.chars == 0L) "—" else "${t.chars}",
                                Modifier.weight(1f),
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            StatMetric(
                                "击键次数",
                                if (t.keypresses == 0L) "—" else "${t.keypresses}",
                                Modifier.weight(1f),
                            )
                            StatMetric(
                                "误触率",
                                formatMistouchRate(t.corrections, t.keypresses),
                                Modifier.weight(1f),
                            )
                        }
                    }
                }
            }
            item {
                // 速度卡：极速（全局最快单日）／均速（本期全局均速，
                // 不受方案过滤影响，与 hero 同口径）。
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = LocalMonetCardColor.current,
                    ),
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text("输入速度", fontWeight = FontWeight.Bold)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            StatMetric(
                                "极速",
                                formatSpeed(fastest),
                                Modifier.weight(1f),
                            )
                            StatMetric(
                                "均速",
                                formatSpeed(heroStats.avgSpeed),
                                Modifier.weight(1f),
                            )
                        }
                    }
                }
            }
            item {
                // 词长分布卡：三档显示（多字词＝三字＋四字以上求和）；
                // 总量 0 时行显 0% 并追加“暂无数据”。
                val wl = stats.total
                val single = wl.wordLen1
                val double = wl.wordLen2
                val multi = wl.wordLen3 + wl.wordLen4plus
                val wlTotal = single + double + multi
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = LocalMonetCardColor.current,
                    ),
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text("词长分布", fontWeight = FontWeight.Bold)
                        for ((label, count) in listOf(
                            "单字" to single,
                            "双字词" to double,
                            "多字词" to multi,
                        )) {
                            val fraction =
                                if (wlTotal > 0L) count.toFloat() / wlTotal else 0f
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Text(label, fontSize = 13.sp, modifier = Modifier.width(76.dp))
                                DistributionBar(fraction, Modifier.weight(1f))
                                Text(
                                    if (wlTotal > 0L) {
                                        "%.0f%%".format(fraction * 100)
                                    } else {
                                        "0%"
                                    },
                                    fontSize = 13.sp,
                                    modifier = Modifier.width(40.dp),
                                    textAlign = TextAlign.End,
                                )
                            }
                        }
                        if (wlTotal <= 0L) {
                            Text(
                                "暂无数据",
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
            item {
                // 码长分布卡：无干净采集源，只留空态（不建脏近似采集）。
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = LocalMonetCardColor.current,
                    ),
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text("码长分布", fontWeight = FontWeight.Bold)
                        Text(
                            "暂无数据",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            item {
                // 键盘热力卡：QWERTY 网格＋每键占比（全局量，不受方案
                // 过滤影响；历史为 0%，向前采集）。
                val heat = heroStats.heat
                val heatTotal = heat.values.sum()
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = LocalMonetCardColor.current,
                    ),
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text("键盘热力", fontWeight = FontWeight.Bold)
                        for (rowKeys in InputStats.HEAT_GRID) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(2.dp),
                            ) {
                                for (key in rowKeys) {
                                    val n = heat[key] ?: 0L
                                    val pct = if (heatTotal > 0L) {
                                        "%.0f%%".format(n * 100.0 / heatTotal)
                                    } else {
                                        "0%"
                                    }
                                    Column(
                                        modifier = Modifier.weight(1f),
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                    ) {
                                        Text(key, fontSize = 13.sp)
                                        Text(
                                            pct,
                                            fontSize = 11.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            }
                        }
                        if (heatTotal <= 0L) {
                            Text(
                                "暂无数据",
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
            item {
                Text(
                    "所有统计均在本地计算与存储，不会上传任何数据。",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    if (showClearConfirm) {
        SettingsConfirmDialog(
            onDismissRequest = { showClearConfirm = false },
            title = "清统计",
            text = "清除全部周期统计数据吗？生涯段位与累计字数保留，此操作不可撤销。",
            confirmText = "清除",
            onConfirm = {
                InputStats.clearPeriods()
                showClearConfirm = false
                refreshTick++
                toast("已清统计，段位保留")
            },
            dismissText = "取消",
        )
    }

    if (showClearFastestConfirm) {
        SettingsConfirmDialog(
            onDismissRequest = { showClearFastestConfirm = false },
            title = "清除极速记录",
            text = "仅清除最快速度记录，周期数据与段位不受影响。清除后需出现新的合格单日（≥50 字）才重新显示最快速度。",
            confirmText = "清除",
            onConfirm = {
                InputStats.clearFastest()
                showClearFastestConfirm = false
                refreshTick++
                toast("已清除极速记录")
            },
            dismissText = "取消",
        )
    }

    if (showSchemeDialog) {
        SettingsDialog(
            onDismissRequest = { showSchemeDialog = false },
            title = "选择方案",
            body = {
                Column {
                    SchemeRow(
                        label = "全部方案",
                        selected = scheme == null,
                        onClick = {
                            scheme = null
                            showSchemeDialog = false
                        },
                    )
                    for (id in schemes) {
                        SchemeRow(
                            label = id.ifBlank { "未知方案" },
                            selected = scheme == id,
                            onClick = {
                                scheme = id
                                showSchemeDialog = false
                            },
                        )
                    }
                }
            },
            actions = listOf(
                SettingsDialogAction(
                    text = "取消",
                    onClick = { showSchemeDialog = false },
                ),
            ),
        )
    }

    if (showBackupDialog) {
        SettingsDialog(
            onDismissRequest = { showBackupDialog = false },
            title = "备份与恢复",
            body = {
                Text(
                    "导出整份统计状态为 JSON 文件，或从 JSON 文件恢复；" +
                        "恢复会替换现有统计数据，格式非法时拒绝且不改动现有数据。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            actions = listOf(
                SettingsDialogAction(
                    text = "导出 JSON",
                    onClick = {
                        showBackupDialog = false
                        shareJson()
                    },
                ),
                SettingsDialogAction(
                    text = "导入 JSON",
                    onClick = {
                        showBackupDialog = false
                        importLauncher.launch("*/*")
                    },
                ),
                SettingsDialogAction(
                    text = "取消",
                    onClick = { showBackupDialog = false },
                ),
            ),
        )
    }

    if (showShareDialog) {
        val shortText = shortStatsReport(rank, stats, fastest)
        val longText = longStatsReport(segmentLabel(period), rank, stats, fastest)
        SettingsDialog(
            onDismissRequest = { showShareDialog = false },
            title = "分享统计",
            body = {
                Column {
                    ShareOptionRow(
                        label = "短版报告",
                        selected = shareOption == ShareOption.SHORT,
                        onClick = { shareOption = ShareOption.SHORT },
                    )
                    ShareOptionRow(
                        label = "长版报告",
                        selected = shareOption == ShareOption.LONG,
                        onClick = { shareOption = ShareOption.LONG },
                    )
                    ShareOptionRow(
                        label = "分享统计图片（含热力图）",
                        selected = shareOption == ShareOption.IMAGE,
                        onClick = { shareOption = ShareOption.IMAGE },
                    )
                }
            },
            actions = listOf(
                SettingsDialogAction(
                    text = "复制",
                    onClick = {
                        when (shareOption) {
                            ShareOption.SHORT -> {
                                copyText(shortText)
                                showShareDialog = false
                            }
                            ShareOption.LONG -> {
                                copyText(longText)
                                showShareDialog = false
                            }
                            ShareOption.IMAGE -> {
                                toast("统计图片请用分享发送")
                            }
                        }
                    },
                ),
                SettingsDialogAction(
                    text = "分享",
                    onClick = {
                        when (shareOption) {
                            ShareOption.SHORT -> {
                                shareText(shortText)
                                showShareDialog = false
                            }
                            ShareOption.LONG -> {
                                shareText(longText)
                                showShareDialog = false
                            }
                            ShareOption.IMAGE -> {
                                shareImage()
                                showShareDialog = false
                            }
                        }
                    },
                ),
            ),
        )
    }
}

private enum class ShareOption { SHORT, LONG, IMAGE }

@Composable
private fun SchemeRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(vertical = 12.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
        )
        if (selected) {
            Icon(
                imageVector = Icons.Filled.Check,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun ShareOptionRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(vertical = 12.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
        )
        if (selected) {
            Icon(
                imageVector = Icons.Filled.Check,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun StatMetric(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(value, fontWeight = FontWeight.Bold, fontSize = 16.sp)
        Text(
            label,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun DistributionBar(fraction: Float, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .height(8.dp)
            .background(
                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.2f),
                RoundedCornerShape(4.dp),
            ),
    ) {
        if (fraction > 0f) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(fraction.coerceIn(0f, 1f))
                    .height(8.dp)
                    .background(
                        MaterialTheme.colorScheme.primary,
                        RoundedCornerShape(4.dp),
                    ),
            )
        }
    }
}
