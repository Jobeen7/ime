// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.ui.screen

import android.content.Context
import android.content.res.Configuration
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.jobeen.ime.R
import com.jobeen.ime.data.keyboard.theme.KeyboardColors
import com.jobeen.ime.data.keyboard.theme.KeyboardTheme
import com.jobeen.ime.data.keyboard.theme.MonetPalette
import com.jobeen.ime.data.manager.KeyboardManager
import com.jobeen.ime.skin.diag.DiagPrefs
import com.jobeen.ime.skin.diag.DiagLog
import com.jobeen.ime.skin.library.SkinColorMode
import com.jobeen.ime.skin.library.SkinLibraryPrefs
import com.jobeen.ime.skin.model.Degradation
import com.jobeen.ime.skin.model.LayoutResolution
import com.jobeen.ime.skin.runtime.SkinSnapshotProvider
import com.jobeen.ime.ui.screen.ScreenComponent.GroupCard
import com.jobeen.ime.ui.screen.ScreenComponent.SwitchRow

/**
 * 主题诊断（2026-10-29 批量第 5 项）：只读快照检查，不写任何状态。
 * 每项检查独立容错：单项异常记一条诊断日志（操作＋阶段＋异常类名，
 * 只记元数据）并显示“检查失败”，不让一处异常拖垮整页。
 */
private data class DiagRow(val title: String, val detail: String, val warn: Boolean)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SkinDiagnosticsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val rows = remember { buildDiagRows(context) }
    // 诊断模式开关（直连 DiagPrefs；此前全仓库零调用，App 内无任何
    // UI 能打开，KeyFaceDiag 的 key= 行与 PerfMonitor 的 P4 探针在
    // 真机上永远不触发——只加开关，不改诊断逻辑本身）。
    var diagnosticEnabled by remember {
        mutableStateOf(DiagPrefs.isDiagnosticMode(context))
    }

    SettingsPageScaffold(
        title = stringResource(R.string.skin_diag_title),
        onBack = onBack,
    ) {
            GroupCard {
                rows.forEach { row ->
                    Column(modifier = Modifier.padding(vertical = 10.dp)) {
                        Text(
                            text = row.title,
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium,
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = row.detail,
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (row.warn) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            GroupCard {
                SwitchRow(
                    title = stringResource(R.string.skin_diag_diagnostic_mode),
                    checked = diagnosticEnabled,
                    onCheckedChange = { enabled ->
                        DiagPrefs.setDiagnosticMode(context, enabled)
                        diagnosticEnabled = enabled
                    },
                )
                Text(
                    text = stringResource(R.string.skin_diag_diagnostic_mode_desc),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 10.dp),
                )
            }
    }
}

private fun buildDiagRows(context: Context): List<DiagRow> {
    val tActive = context.getString(R.string.skin_diag_active_skin)
    val tDual = context.getString(R.string.skin_diag_dual_mode)
    val tColor = context.getString(R.string.skin_diag_color_mode)
    val tBg = context.getString(R.string.skin_diag_background)
    val tLayout = context.getString(R.string.skin_diag_layout)
    val tDeg = context.getString(R.string.skin_diag_degradation)
    val tSugg = context.getString(R.string.skin_diag_suggestion)
    val snapshot = runCatching { SkinSnapshotProvider.peek() }.getOrNull()
    val isSystemDark = (context.resources.configuration.uiMode and
        Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    val suggestions = mutableListOf<String>()

    fun check(title: String, block: () -> DiagRow): DiagRow = runCatching(block).getOrElse { e ->
        DiagLog.w(
            "diag", "diag_check_failed",
            mapOf("check" to title, "error" to e.javaClass.simpleName),
        )
        DiagRow(title, "检查失败", warn = true)
    }

    val rows = mutableListOf<DiagRow>()
    rows += check(tActive) {
        if (snapshot != null) {
            DiagRow(tActive, "自定义皮肤：${snapshot.name}（${snapshot.ref.skinId}）", warn = false)
        } else {
            // 生效主题（非存储主题 id）：默认双槽下跟随系统取槽位，莫奈色与导入固定主题取本身。
            val effective = KeyboardColors.themeFor(context)
            DiagRow(tActive, "内置主题：${effective.name}（${effective.id}）", warn = false)
        }
    }
    rows += check(tDual) {
        if (snapshot != null) {
            if (snapshot.hasDarkPalette) {
                DiagRow(tDual, "完整（浅色＋深色）", warn = false)
            } else {
                suggestions += "请用编辑器补齐深色板后重新导入"
                DiagRow(tDual, "缺深色板：深色下沿用浅色板", warn = true)
            }
        } else {
            // 深浅唯一由颜色模式决定（见 KeyboardColors.effectiveBuiltinThemeId）：
            // 跟随系统＋默认双槽＝按系统取槽位；跟随系统＋莫奈色＝调色板自身随系统；
            // 浅色／深色＝强制对应槽位；跟随系统＋导入固定主题＝单色板保持原样。
            val mode = SkinLibraryPrefs.getColorMode(context)
            val themeId = KeyboardManager.Keyboard.getThemeId(context)
            val lightId = KeyboardManager.Keyboard.getLightThemeId(context)
            val darkId = KeyboardManager.Keyboard.getDarkThemeId(context)
            when (mode) {
                SkinColorMode.LIGHT ->
                    DiagRow(tDual, "强制浅色：${KeyboardTheme.byId(lightId).name}", warn = false)
                SkinColorMode.DARK ->
                    DiagRow(tDual, "强制深色：${KeyboardTheme.byId(darkId).name}", warn = false)
                SkinColorMode.SYSTEM -> when {
                    themeId == lightId || themeId == darkId -> {
                        val light = KeyboardTheme.byId(lightId).name
                        val dark = KeyboardTheme.byId(darkId).name
                        DiagRow(tDual, "完整（跟随系统：浅色 $light／深色 $dark）", warn = false)
                    }
                    themeId == MonetPalette.ID ->
                        DiagRow(tDual, "完整（随系统动态取色）", warn = false)
                    else ->
                        DiagRow(
                            tDual,
                            "单色板（固定主题 ${KeyboardTheme.byId(themeId).name}）",
                            warn = false,
                        )
                }
            }
        }
    }
    rows += check(tColor) {
        if (snapshot != null) {
            val mode = SkinLibraryPrefs.getColorMode(context)
            val label = when (mode) {
                SkinColorMode.SYSTEM -> "跟随系统"
                SkinColorMode.LIGHT -> "浅色"
                SkinColorMode.DARK -> "深色"
            }
            val effective = if (mode.effectiveDark(isSystemDark)) "深色" else "浅色"
            DiagRow(tColor, "$label（当前生效：$effective）", warn = false)
        } else {
            val mode = SkinLibraryPrefs.getColorMode(context)
            val themeId = KeyboardManager.Keyboard.getThemeId(context)
            val lightId = KeyboardManager.Keyboard.getLightThemeId(context)
            val darkId = KeyboardManager.Keyboard.getDarkThemeId(context)
            when (mode) {
                SkinColorMode.LIGHT -> DiagRow(tColor, "强制浅色", warn = false)
                SkinColorMode.DARK -> DiagRow(tColor, "强制深色", warn = false)
                SkinColorMode.SYSTEM -> {
                    val effective = if (isSystemDark) "深色" else "浅色"
                    if (themeId == lightId || themeId == darkId || themeId == MonetPalette.ID) {
                        DiagRow(tColor, "跟随系统（当前生效：$effective）", warn = false)
                    } else {
                        DiagRow(tColor, "固定：${KeyboardTheme.byId(themeId).name}", warn = false)
                    }
                }
            }
        }
    }
    rows += check(tBg) {
        val bg = snapshot?.background
        when {
            bg != null -> DiagRow(tBg, "背景图已接纳（资产 ${bg.assetId}）", warn = false)
            snapshot != null -> DiagRow(tBg, "无背景图（纯色）", warn = false)
            else -> DiagRow(tBg, "无自定义背景（内置配色）", warn = false)
        }
    }
    rows += check(tLayout) {
        if (snapshot == null) {
            DiagRow(tLayout, "出厂布局", warn = false)
        } else {
            val portrait = when (snapshot.layoutResolution) {
                LayoutResolution.NOT_SET -> "竖屏：未绑定（出厂布局）"
                LayoutResolution.RESOLVED ->
                    "竖屏：已解析（${snapshot.resolvedLayout?.layoutId ?: "?"}）"
                LayoutResolution.MISSING -> {
                    suggestions += "竖屏布局绑定缺失：请重新导入皮肤"
                    "竖屏：绑定缺失（已回退出厂布局）"
                }
                LayoutResolution.INVALID -> {
                    suggestions += "竖屏布局绑定无效：请重新导入皮肤"
                    "竖屏：绑定无效（已回退出厂布局）"
                }
            }
            val portraitWarn = snapshot.layoutResolution == LayoutResolution.MISSING ||
                snapshot.layoutResolution == LayoutResolution.INVALID
            val landscape = when (snapshot.landscapeLayoutResolution) {
                LayoutResolution.NOT_SET -> "横屏：未绑定（沿用竖屏）"
                LayoutResolution.RESOLVED -> "横屏：已解析"
                LayoutResolution.MISSING -> {
                    suggestions += "横屏布局绑定缺失：请重新导入皮肤"
                    "横屏：绑定缺失（已回退）"
                }
                LayoutResolution.INVALID -> {
                    suggestions += "横屏布局绑定无效：请重新导入皮肤"
                    "横屏：绑定无效（已回退）"
                }
            }
            val landscapeWarn = snapshot.landscapeLayoutResolution == LayoutResolution.MISSING ||
                snapshot.landscapeLayoutResolution == LayoutResolution.INVALID
            DiagRow(tLayout, "$portrait；$landscape", warn = portraitWarn || landscapeWarn)
        }
    }
    rows += check(tDeg) {
        val degradations = snapshot?.degradations ?: emptySet()
        if (degradations.isEmpty()) {
            DiagRow(tDeg, "无降级", warn = false)
        } else {
            DiagRow(
                tDeg,
                "降级：" + degradations.joinToString("、") { degradationLabel(it) },
                warn = false,
            )
        }
    }
    rows += DiagRow(
        tSugg,
        if (suggestions.isEmpty()) "无待处理项" else suggestions.joinToString("；"),
        warn = suggestions.isNotEmpty(),
    )
    return rows
}

private fun degradationLabel(degradation: Degradation): String = when (degradation) {
    Degradation.BLUR_DISABLED -> "模糊关闭"
    Degradation.BACKGROUND_DOWNSAMPLED -> "背景降采样"
    Degradation.ANIMATIONS_DISABLED -> "动效关闭"
}
