package com.jobeen.ime.skin.library

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.jobeen.ime.data.keyboard.theme.KeyboardColors
import com.jobeen.ime.input.keyboard.impl.NumberKeyboard
import com.jobeen.ime.input.keyboard.impl.QwertyKeyboard
import com.jobeen.ime.input.keyboard.impl.T15Keyboard
import com.jobeen.ime.input.keyboard.impl.T9Keyboard
import com.jobeen.ime.input.keyboard.key.KeyDef
import com.jobeen.ime.input.keyboard.key.KeyDef.Appearance.Variant

/** 迷你预览的键种类：只取配色，不复用真实键盘几何（键数/宽度为示意）。 */
enum class MiniKeyKind { KEY, SPECIAL, ACCENT }

/**
 * 迷你预览行结构（纯数据，可单测）：首行是候选条，其后 3 行 × 4 键，
 * 每行含 1 个特殊键与 1 个强调键，位置轮换，避免预览看起来全同色。
 */
val MINI_PREVIEW_ROWS: List<List<MiniKeyKind>> = listOf(
    listOf(MiniKeyKind.SPECIAL, MiniKeyKind.KEY, MiniKeyKind.KEY, MiniKeyKind.ACCENT),
    listOf(MiniKeyKind.KEY, MiniKeyKind.ACCENT, MiniKeyKind.KEY, MiniKeyKind.SPECIAL),
    listOf(MiniKeyKind.KEY, MiniKeyKind.KEY, MiniKeyKind.SPECIAL, MiniKeyKind.ACCENT),
)

/** 布局感知预览的一键：行内权重（已归一化，和为 1）＋颜色种类。 */
data class MiniPreviewKey(val weight: Float, val kind: MiniKeyKind)

/** 卡片预览数据：配色＋布局行（null = 无绑定布局，用通用示意行）。 */
data class SkinCardPreview(
    val palette: MiniPreviewPalette,
    val layoutRows: List<List<MiniPreviewKey>>?,
)

/**
 * 从已解析布局（快照 resolvedLayout.baseLayout）派生迷你预览行
 * （纯函数，可单测；覆盖全部布局矩阵的自定义布局侧）：
 *
 * - 行/键结构与宽度取 KeyDef.appearance.percentWidth（自创布局构建期
 *   已按行内权重算出，含 11/14/17/18 键等任意键数与跨键宽度）；
 * - 权重非法（≤0/非有限）时该行退化为等分，绝不除零、不崩；
 * - 颜色种类只按 variant 与侧栏角色定（Accent→强调，Alternative/
 *   SidePannel→特殊，其余→普通），不猜标签含义；
 * - 跨行键（rowSpan>1，如九宫格回车）在迷你预览中只画首行：迷你图
 *   不拉通行高，属已记录的简化（见报告），不按多行重复画；
 * - 空布局/全空行 → null，调用方退化为通用示意行。
 *
 * 内置布局（九宫格/26 键/十五键/数字）不走此路：卡片与开发屏的
 * 内置预览由 [builtinPreviewRows] 直接取各键盘出厂 buildLayout
 * 的真实几何；未选内置布局且皮肤未绑自定义布局时，卡片用通用
 * 示意行只呈现配色（配色与布局无关，同一皮肤在四种内置键盘上
 * 颜色相同，故此降级不构成错绘）。
 */
fun layoutPreviewRows(baseLayout: List<List<KeyDef>>): List<List<MiniPreviewKey>>? {
    val rows = baseLayout.mapNotNull { row ->
        if (row.isEmpty()) return@mapNotNull null
        val rawWeights = row.map { def ->
            val w = def.appearance.percentWidth
            if (w.isFinite() && w > 0f) w else 0f
        }
        val total = rawWeights.sum()
        val weights = if (total > 0f) {
            rawWeights.map { it / total }
        } else {
            List(row.size) { 1f / row.size }
        }
        row.mapIndexed { index, def ->
            val kind = when {
                def.appearance.variant == KeyDef.Appearance.Variant.Accent -> MiniKeyKind.ACCENT
                def.appearance.variant == KeyDef.Appearance.Variant.Alternative -> MiniKeyKind.SPECIAL
                def.appearance is KeyDef.Appearance.SidePannel -> MiniKeyKind.SPECIAL
                else -> MiniKeyKind.KEY
            }
            MiniPreviewKey(weights[index], kind)
        }
    }
    return rows.ifEmpty { null }
}

/** 通用示意行转带权行（等分，供无绑定布局的卡片用）。 */
fun genericPreviewRows(): List<List<MiniPreviewKey>> =
    MINI_PREVIEW_ROWS.map { row ->
        row.map { kind -> MiniPreviewKey(1f / row.size, kind) }
    }

/**
 * 内置布局的迷你预览行（纯函数，可单测；覆盖九宫格/26 键/十五键/
 * 数字键盘——直接复用各键盘出厂 buildLayout 的真实行/键结构与宽度，
 * 不是手抄几何：buildLayout 只取回车变体一个参数，与主题颜色无关，
 * 此处传 Normal（回车在预览中按普通键着色，不影响行/键结构）。
 *
 * 简化（与自定义布局侧一致）：跨行键只画首行；行内无宽度信息
 * 的键（如 26 键字母键 percentWidth 缺省）由 layoutPreviewRows
 * 归一化为等分，不崩；侧栏键按 SidePannel 计特殊色。
 */
enum class BuiltinPreviewLayout(val label: String) {
    T9("九宫格"),
    QWERTY("26键"),
    T15("十五键"),
    NUMBER("数字"),
}

fun builtinPreviewRows(layout: BuiltinPreviewLayout): List<List<MiniPreviewKey>>? {
    val base = when (layout) {
        BuiltinPreviewLayout.T9 -> T9Keyboard.buildLayout(Variant.Normal)
        BuiltinPreviewLayout.QWERTY -> QwertyKeyboard.buildLayout(Variant.Normal)
        BuiltinPreviewLayout.T15 -> T15Keyboard.buildLayout(Variant.Normal)
        BuiltinPreviewLayout.NUMBER -> NumberKeyboard.buildLayout(Variant.Normal)
    }
    return layoutPreviewRows(base)
}

/** 从已解析快照配色取迷你预览调色（纯函数，可单测）。 */
data class MiniPreviewPalette(
    val background: Color,
    val candidateBackground: Color,
    val candidateText: Color,
    val key: Color,
    val keyText: Color,
    val special: Color,
    val specialText: Color,
    val accent: Color,
    val accentText: Color,
)

fun miniPreviewPalette(scheme: KeyboardColors.ColorScheme): MiniPreviewPalette =
    MiniPreviewPalette(
        background = Color(scheme.background),
        candidateBackground = Color(scheme.panel.candidateBackground),
        candidateText = Color(scheme.panel.candidateText),
        key = Color(scheme.keyBackground),
        keyText = Color(scheme.keyText),
        special = Color(scheme.specialKeyBackground),
        specialText = Color(scheme.specialKeyText),
        accent = Color(scheme.accentKeyBackground),
        accentText = Color(scheme.accentKeyText),
    )

/**
 * 基于皮肤快照的迷你预览渲染（第三组预览图方案）：
 *
 * 库里此前没有快照预览机制（只有配色二维码用的快照构建），故本实现
 * 不复用旧机制，而是直接以快照配色画 Canvas 示意键盘：候选条＋键行。
 * 绑定了自定义布局的皮肤画其真实行/键结构与宽度（[layoutRows]）；
 * 未绑定布局的皮肤画通用示意行（只呈现配色，不伪造内置布局几何）。
 * 刻意不画背景图——快照只记背景预处理产物引用、不持位图，卡片内
 * 解码背景图会打破“当前皮肤 1 份位图”上限；有背景图的皮肤在卡片
 * 上只呈现其底色，此限制如实记入报告与真机验证项。快照构建失败
 * 时调用方退化为纯色占位卡（不崩）。
 */
@Composable
fun SkinMiniPreview(
    palette: MiniPreviewPalette,
    layoutRows: List<List<MiniPreviewKey>>? = null,
    modifier: Modifier = Modifier,
) {
    val rows = layoutRows ?: genericPreviewRows()
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(72.dp),
    ) {
        val w = size.width
        val h = size.height
        drawRect(color = palette.background, size = size)
        val gap = 4.dp.toPx()
        val stripH = h * 0.22f
        drawRect(
            color = palette.candidateBackground,
            topLeft = Offset(gap, gap),
            size = Size(w - gap * 2, stripH - gap),
        )
        val rowsTop = stripH + gap
        val rowH = (h - rowsTop - gap) / rows.size
        for ((rowIndex, row) in rows.withIndex()) {
            val top = rowsTop + rowIndex * rowH + gap / 2
            val keyH = rowH - gap
            val usableW = w - gap * (row.size + 1)
            var left = gap
            for (key in row) {
                val keyW = (usableW * key.weight).coerceAtLeast(1f)
                val fill = when (key.kind) {
                    MiniKeyKind.KEY -> palette.key
                    MiniKeyKind.SPECIAL -> palette.special
                    MiniKeyKind.ACCENT -> palette.accent
                }
                drawRoundRect(
                    color = fill,
                    topLeft = Offset(left, top),
                    size = Size(keyW, keyH),
                    cornerRadius = CornerRadius(4.dp.toPx(), 4.dp.toPx()),
                )
                left += keyW + gap
            }
        }
    }
}
