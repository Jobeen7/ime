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
        DiagRow(title, context.getString(R.string.skin_diag_check_failed), warn = true)
    }

    val rows = mutableListOf<DiagRow>()
    rows += check(tActive) {
        if (snapshot != null) {
            DiagRow(tActive, context.getString(R.string.skin_diag_custom_skin, snapshot.name, snapshot.ref.skinId), warn = false)
        } else {
            // 生效主题（非存储主题 id）：默认双槽下跟随系统取槽位，莫奈色与导入固定主题取本身。
            val effective = KeyboardColors.themeFor(context)
            DiagRow(tActive, context.getString(R.string.skin_diag_builtin_theme, effective.name, effective.id), warn = false)
        }
    }
    rows += check(tDual) {
        if (snapshot != null) {
            if (snapshot.hasDarkPalette) {
                DiagRow(tDual, context.getString(R.string.skin_diag_dual_full), warn = false)
            } else {
                suggestions += context.getString(R.string.skin_diag_dual_suggestion)
                DiagRow(tDual, context.getString(R.string.skin_diag_dual_missing_dark), warn = true)
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
                    DiagRow(tDual, context.getString(R.string.skin_diag_force_light, KeyboardTheme.byId(lightId).name), warn = false)
                SkinColorMode.DARK ->
                    DiagRow(tDual, context.getString(R.string.skin_diag_force_dark, KeyboardTheme.byId(darkId).name), warn = false)
                SkinColorMode.SYSTEM -> when {
                    themeId == lightId || themeId == darkId -> {
                        val light = KeyboardTheme.byId(lightId).name
                        val dark = KeyboardTheme.byId(darkId).name
                        DiagRow(tDual, context.getString(R.string.skin_diag_system_full, light, dark), warn = false)
                    }
                    themeId == MonetPalette.ID ->
                        DiagRow(tDual, context.getString(R.string.skin_diag_monet_full), warn = false)
                    else ->
                        DiagRow(
                            tDual,
                            context.getString(R.string.skin_diag_single_theme, KeyboardTheme.byId(themeId).name),
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
                SkinColorMode.SYSTEM -> context.getString(R.string.keyboard_theme_follow_system)
                SkinColorMode.LIGHT -> context.getString(R.string.color_mode_light)
                SkinColorMode.DARK -> context.getString(R.string.color_mode_dark)
            }
            val effective = if (mode.effectiveDark(isSystemDark)) context.getString(R.string.color_mode_dark) else context.getString(R.string.color_mode_light)
            DiagRow(tColor, context.getString(R.string.skin_diag_color_mode_current, label, effective), warn = false)
        } else {
            val mode = SkinLibraryPrefs.getColorMode(context)
            val themeId = KeyboardManager.Keyboard.getThemeId(context)
            val lightId = KeyboardManager.Keyboard.getLightThemeId(context)
            val darkId = KeyboardManager.Keyboard.getDarkThemeId(context)
            when (mode) {
                SkinColorMode.LIGHT -> DiagRow(tColor, context.getString(R.string.skin_diag_force_light_label), warn = false)
                SkinColorMode.DARK -> DiagRow(tColor, context.getString(R.string.skin_diag_force_dark_label), warn = false)
                SkinColorMode.SYSTEM -> {
                    val effective = if (isSystemDark) context.getString(R.string.color_mode_dark) else context.getString(R.string.color_mode_light)
                    if (themeId == lightId || themeId == darkId || themeId == MonetPalette.ID) {
                        DiagRow(tColor, context.getString(R.string.skin_diag_follow_system_current, effective), warn = false)
                    } else {
                        DiagRow(tColor, context.getString(R.string.skin_diag_fixed_theme, KeyboardTheme.byId(themeId).name), warn = false)
                    }
                }
            }
        }
    }
    rows += check(tBg) {
        val bg = snapshot?.background
        when {
            bg != null -> DiagRow(tBg, context.getString(R.string.skin_diag_bg_accepted, bg.assetId), warn = false)
            snapshot != null -> DiagRow(tBg, context.getString(R.string.skin_diag_bg_none_solid), warn = false)
            else -> DiagRow(tBg, context.getString(R.string.skin_diag_bg_none_builtin), warn = false)
        }
    }
    rows += check(tLayout) {
        if (snapshot == null) {
            DiagRow(tLayout, context.getString(R.string.skin_diag_layout_factory), warn = false)
        } else {
            val portrait = when (snapshot.layoutResolution) {
                LayoutResolution.NOT_SET -> context.getString(R.string.skin_diag_portrait_not_set)
                LayoutResolution.RESOLVED ->
                    context.getString(R.string.skin_diag_portrait_resolved, snapshot.resolvedLayout?.layoutId ?: "?")
                LayoutResolution.MISSING -> {
                    suggestions += context.getString(R.string.skin_diag_portrait_missing_sugg)
                    context.getString(R.string.skin_diag_portrait_missing)
                }
                LayoutResolution.INVALID -> {
                    suggestions += context.getString(R.string.skin_diag_portrait_invalid_sugg)
                    context.getString(R.string.skin_diag_portrait_invalid)
                }
            }
            val portraitWarn = snapshot.layoutResolution == LayoutResolution.MISSING ||
                snapshot.layoutResolution == LayoutResolution.INVALID
            val landscape = when (snapshot.landscapeLayoutResolution) {
                LayoutResolution.NOT_SET -> context.getString(R.string.skin_diag_landscape_not_set)
                LayoutResolution.RESOLVED -> context.getString(R.string.skin_diag_landscape_resolved)
                LayoutResolution.MISSING -> {
                    suggestions += context.getString(R.string.skin_diag_landscape_missing_sugg)
                    context.getString(R.string.skin_diag_landscape_missing)
                }
                LayoutResolution.INVALID -> {
                    suggestions += context.getString(R.string.skin_diag_landscape_invalid_sugg)
                    context.getString(R.string.skin_diag_landscape_invalid)
                }
            }
            val landscapeWarn = snapshot.landscapeLayoutResolution == LayoutResolution.MISSING ||
                snapshot.landscapeLayoutResolution == LayoutResolution.INVALID
            DiagRow(tLayout, context.getString(R.string.skin_diag_layout_combined, portrait, landscape), warn = portraitWarn || landscapeWarn)
        }
    }
    rows += check(tDeg) {
        val degradations = snapshot?.degradations ?: emptySet()
        if (degradations.isEmpty()) {
            DiagRow(tDeg, context.getString(R.string.skin_diag_no_degradation), warn = false)
        } else {
            DiagRow(
                tDeg,
                context.getString(R.string.skin_diag_degradations, degradations.joinToString("、") { degradationLabel(context, it) }),
                warn = false,
            )
        }
    }
    rows += DiagRow(
        tSugg,
        if (suggestions.isEmpty()) context.getString(R.string.skin_diag_no_suggestions) else suggestions.joinToString("；"),
        warn = suggestions.isNotEmpty(),
    )
    return rows
}

private fun degradationLabel(context: Context, degradation: Degradation): String = when (degradation) {
    Degradation.BLUR_DISABLED -> context.getString(R.string.skin_diag_deg_blur)
    Degradation.BACKGROUND_DOWNSAMPLED -> context.getString(R.string.skin_diag_deg_bg)
    Degradation.ANIMATIONS_DISABLED -> context.getString(R.string.skin_diag_deg_anim)
}
