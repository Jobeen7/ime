// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.ui.screen

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.jobeen.ime.R
import com.jobeen.ime.data.keyboard.theme.KeyboardTheme
import com.jobeen.ime.data.keyboard.theme.KeyboardThemePresets
import com.jobeen.ime.data.keyboard.theme.MonetPalette
import com.jobeen.ime.data.manager.KeyboardManager
import com.jobeen.ime.ui.screen.ScreenComponent.NavRow
import com.jobeen.ime.ui.screen.ScreenComponent.SettingsGroup

/** 键盘皮肤页“默认”选项的选中键（非主题 id：它对应浅/深双槽位）。 */
internal const val THEME_KEY_DEFAULT = "default"

/**
 * 内置单选三槽位（主题 id／浅槽／深槽）：点一张卡即定死三槽，
 * 与颜色模式无关——正式版 v1.1.20 库页只给“默认双槽＋莫奈色”
 * 两张卡，单张暗夜/素白/落日在跟随系统下会被槽位逻辑翻回浅
 * 色；此处三槽同值，选哪张就是哪张（KeyboardColors
 * .effectiveBuiltinThemeId 在 LIGHT/DARK/跟随系统下都返回同值）。
 * 返回 null 的是只置主题 id 的选项（莫奈色、扫码导入的自定义
 * 主题）：莫奈色沿正式版口径只置主题 id，跟随系统下莫奈调色板
 * 自身按系统深浅分支；自定义主题 id 在槽位对之外，跟随系统下
 * 亦固定本身（LIGHT/DARK 模式下两者都被强制到槽位，与正式版一致）。
 */
internal fun themeTripleForChoice(key: String): Triple<String, String, String>? = when (key) {
    THEME_KEY_DEFAULT -> Triple(
        KeyboardTheme.DARK_DEFAULT_ID,
        KeyboardTheme.LIGHT_DEFAULT_ID,
        KeyboardTheme.DARK_DEFAULT_ID,
    )
    KeyboardTheme.DARK_DEFAULT_ID -> Triple(
        KeyboardTheme.DARK_DEFAULT_ID,
        KeyboardTheme.DARK_DEFAULT_ID,
        KeyboardTheme.DARK_DEFAULT_ID,
    )
    KeyboardTheme.LIGHT_DEFAULT_ID -> Triple(
        KeyboardTheme.LIGHT_DEFAULT_ID,
        KeyboardTheme.LIGHT_DEFAULT_ID,
        KeyboardTheme.LIGHT_DEFAULT_ID,
    )
    // 只用 companion 常量，不触 KeyboardThemePresets：预设初始化调
    // android.graphics.Color，主机单测会抛（见迁移测试注释）。
    KeyboardTheme.SUNSET_ID -> Triple(
        KeyboardTheme.SUNSET_ID,
        KeyboardTheme.SUNSET_ID,
        KeyboardTheme.SUNSET_ID,
    )
    else -> null
}

/**
 * 由存的三槽位反推当前选中键：默认／内置单张／莫奈色／自定义主题 id；
 * 旧版混搭槽位（不在任一口径内）返回 null，不标任何行，点任一行即归一。
 * 判定顺序：三槽口径优先于自定义 id（自定义主题 id 若撞内置 id，
 * KeyboardTheme.byId 本就先取内置，自定义副本不可达）。
 */
internal fun resolveSelectedThemeKey(
    themeId: String,
    lightId: String,
    darkId: String,
    customIds: Set<String>,
): String? {
    themeTripleForChoice(THEME_KEY_DEFAULT)?.let { triple ->
        if (themeId == triple.first && lightId == triple.second && darkId == triple.third) {
            return THEME_KEY_DEFAULT
        }
    }
    for (presetId in listOf(
        KeyboardTheme.DARK_DEFAULT_ID,
        KeyboardTheme.LIGHT_DEFAULT_ID,
        KeyboardTheme.SUNSET_ID,
    )) {
        val triple = themeTripleForChoice(presetId) ?: continue
        if (themeId == triple.first && lightId == triple.second && darkId == triple.third) {
            return presetId
        }
    }
    if (themeId == MonetPalette.ID) return MonetPalette.ID
    if (themeId in customIds) return themeId
    return null
}

/**
 * 键盘皮肤页（2026-11-18 恢复内置皮肤选择入口）：
 * 正式版 v1.1.20 的内置切换在皮肤库页（主设置页键盘组 skin 行进库页，
 * 库页内置卡只默认＋莫奈色两张）；自定义皮肤随 11-16 删除令与库页
 * 一起删，入口随之消失。本页只恢复内置选择：默认／暗夜／素白／
 * 落日／莫奈色五项＋扫码导入的自定义主题行；不恢复自定义皮肤库、
 * 编辑器、快照与渲染。键盘配色只读 KeyboardColors.themeFor 的
 * 三槽位，点行即写偏好、下次开键盘生效（KeyboardWindowView
 * refreshColorsIfChanged 亦在设置页返回时重读）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KeyboardThemeScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var themeId by remember { mutableStateOf(KeyboardManager.Keyboard.getThemeId(context)) }
    var themeLightId by remember { mutableStateOf(KeyboardManager.Keyboard.getLightThemeId(context)) }
    var themeDarkId by remember { mutableStateOf(KeyboardManager.Keyboard.getDarkThemeId(context)) }
    // 自定义主题（扫码导入落盘，ThemeStore.refresh 在启动时已读盘；
    // 本页导入后由导入流程同步更新本态，不在组合中做文件 IO）。
    var customThemes by remember { mutableStateOf(KeyboardThemePresets.customThemes) }

    val selectedKey = resolveSelectedThemeKey(
        themeId,
        themeLightId,
        themeDarkId,
        customThemes.map { it.id }.toSet(),
    )

    fun applyChoice(key: String) {
        val triple = themeTripleForChoice(key)
        if (triple != null) {
            KeyboardManager.Keyboard.setThemeId(context, triple.first)
            KeyboardManager.Keyboard.setLightThemeId(context, triple.second)
            KeyboardManager.Keyboard.setDarkThemeId(context, triple.third)
            themeId = triple.first
            themeLightId = triple.second
            themeDarkId = triple.third
        } else {
            // 莫奈色／自定义主题：只置主题 id（正式版 ThemeImport.apply 同口径）。
            KeyboardManager.Keyboard.setThemeId(context, key)
            themeId = key
        }
    }

    SettingsPageScaffold(
        title = stringResource(R.string.keyboard_theme),
        onBack = onBack,
    ) {
        SettingsGroup(title = stringResource(R.string.keyboard_theme)) {
            ThemeRow(
                title = stringResource(R.string.builtin_theme_default),
                subtitle = if (selectedKey == THEME_KEY_DEFAULT) {
                    stringResource(R.string.theme_in_use)
                } else {
                    stringResource(R.string.theme_default_desc)
                },
                onClick = { applyChoice(THEME_KEY_DEFAULT) },
            )
            for (preset in KeyboardTheme.BUILTINS) {
                ThemeRow(
                    title = preset.name,
                    subtitle = if (selectedKey == preset.id) {
                        stringResource(R.string.theme_in_use)
                    } else {
                        null
                    },
                    onClick = { applyChoice(preset.id) },
                )
            }
        }
        if (customThemes.isNotEmpty()) {
            SettingsGroup(title = stringResource(R.string.keyboard_theme_custom)) {
                for (theme in customThemes) {
                    ThemeRow(
                        title = theme.name,
                        subtitle = if (selectedKey == theme.id) {
                            stringResource(R.string.theme_in_use)
                        } else {
                            null
                        },
                        onClick = { applyChoice(theme.id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun ThemeRow(title: String, subtitle: String?, onClick: () -> Unit) {
    NavRow(
        title = title,
        subtitle = subtitle,
        onClick = onClick,
    )
}
