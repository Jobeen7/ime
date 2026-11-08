// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.ui.screen

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Backspace
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jobeen.ime.R
import com.jobeen.ime.data.keyboard.theme.KeyboardColors
import com.jobeen.ime.data.keyboard.theme.MonetPalette
import com.jobeen.ime.skin.runtime.SkinColorMode
import com.jobeen.ime.skin.runtime.SkinSnapshotProvider

/**
 * 设置页图例（CyIME D23）：按键设置 → 按键显示页顶部的迷你键盘图例。
 *
 * 图例按真实键盘配色画：底板取键盘底色、键面取键背景色，浅色
 * 主题下白键落在键盘底色上，不再是白底白键；键面色与底色
 * 相同、配色退化时，[ensureKeyVisibleOnBackground] 把键面推开一档。
 * 边框／圆角／间距／提示字／符号跟随本页显示设置实时变。
 */
internal data class SettingsLegendStyle(
    val background: Int,
    val keyBackground: Int,
    val specialKeyBackground: Int,
    val accentKeyBackground: Int,
    val keyBorderStroke: Int,
    val keyBorderWidthDp: Float,
    val keyCornerRadiusDp: Float,
    val keyText: Int,
    val specialKeyText: Int,
    val accentKeyText: Int,
    val hintText: Int,
)

/**
 * 白底白键守卫：键面色与键盘底色 RGB 相同时，把键面往反方向
 * 推开一档（浅底压暗 12%、深底提亮 12%），保证键在底板上
 * 可见。纯 Int 运算，可主机单测。
 */
internal fun ensureKeyVisibleOnBackground(keyBackground: Int, background: Int): Int {
    if ((keyBackground and 0x00FFFFFF) != (background and 0x00FFFFFF)) return keyBackground
    val r = (background shr 16) and 0xFF
    val g = (background shr 8) and 0xFF
    val b = background and 0xFF
    val luminance = (r * 299 + g * 587 + b * 114) / 1000
    val alpha = keyBackground ushr 24
    return if (luminance >= 128) {
        // 浅底：键面压暗 12%。
        (alpha shl 24) or ((r * 88 / 100) shl 16) or ((g * 88 / 100) shl 8) or (b * 88 / 100)
    } else {
        // 深底：键面提亮 12%。
        (alpha shl 24) or ((r + (255 - r) * 12 / 100) shl 16) or
            ((g + (255 - g) * 12 / 100) shl 8) or (b + (255 - b) * 12 / 100)
    }
}

/** 从键盘配色解析图例样式：三类键面色过白底白键守卫；圆角／边框宽取显示设置值。 */
internal fun resolveSettingsLegendStyle(
    scheme: KeyboardColors.ColorScheme,
    keyCornerRadiusDp: Float,
    keyBorderWidthDp: Float,
): SettingsLegendStyle = SettingsLegendStyle(
    background = scheme.background,
    keyBackground = ensureKeyVisibleOnBackground(scheme.keyBackground, scheme.background),
    specialKeyBackground = ensureKeyVisibleOnBackground(
        scheme.specialKeyBackground,
        scheme.background,
    ),
    accentKeyBackground = ensureKeyVisibleOnBackground(
        scheme.accentKeyBackground,
        scheme.background,
    ),
    keyBorderStroke = scheme.keyBorderStroke,
    keyBorderWidthDp = keyBorderWidthDp,
    keyCornerRadiusDp = keyCornerRadiusDp,
    keyText = scheme.keyText,
    specialKeyText = scheme.specialKeyText,
    accentKeyText = scheme.accentKeyText,
    hintText = scheme.altText,
)

/**
 * 当前键盘配色：有 v3 快照取快照 effectiveColorScheme，否则取
 * 内置主题（莫奈走动态色板）。与 AppearanceScreen.previewBase 同源。
 */
private fun legendColorScheme(context: Context): KeyboardColors.ColorScheme {
    val snapshot = runCatching { SkinSnapshotProvider.peek() }.getOrNull()
    if (snapshot != null) return snapshot.effectiveColorScheme(SkinColorMode.isDark(context))
    val theme = KeyboardColors.themeFor(context)
    return if (theme.id == MonetPalette.ID) MonetPalette.build(context) else theme.colors
}

/**
 * 按键显示页图例：一行示例键（九宫格 2/ABC、退格图标键、回车确认键）
 * ＋一行空格键，底板为键盘底色；边框、圆角、间距、提示字、符号
 * 全部跟随本页显示设置。
 *
 * 隐藏口径与 KeyView 一致：隐藏提示 → 副文字不可见；隐藏符号 →
 * 图标键键面空白、仍可点按（文字键保持出厂文字）。
 */
@Composable
internal fun SettingsLegend(
    keyCornerRadiusDp: Float,
    keyHorizontalGapDp: Float,
    keyVerticalGapDp: Float,
    borderEnabled: Boolean,
    showSymbol: Boolean,
    showHint: Boolean,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scheme = legendColorScheme(context)
    val style = resolveSettingsLegendStyle(
        scheme,
        keyCornerRadiusDp,
        scheme.keyBorderWidthDp,
    )
    Column(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(Color(style.background))
                .padding(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(keyHorizontalGapDp.dp),
            ) {
                SettingsLegendKeyCell(
                    style = style,
                    backgroundColor = style.keyBackground,
                    textColor = style.keyText,
                    label = "2",
                    hint = "ABC",
                    showHint = showHint,
                    borderEnabled = borderEnabled,
                    modifier = Modifier.weight(1f),
                )
                SettingsLegendKeyCell(
                    style = style,
                    backgroundColor = style.specialKeyBackground,
                    textColor = style.specialKeyText,
                    icon = Icons.Filled.Backspace,
                    showSymbol = showSymbol,
                    borderEnabled = borderEnabled,
                    modifier = Modifier.weight(1f),
                )
                SettingsLegendKeyCell(
                    style = style,
                    backgroundColor = style.accentKeyBackground,
                    textColor = style.accentKeyText,
                    label = "确认",
                    borderEnabled = borderEnabled,
                    modifier = Modifier.weight(1f),
                )
            }
            Spacer(Modifier.height(keyVerticalGapDp.dp))
            SettingsLegendKeyCell(
                style = style,
                backgroundColor = style.keyBackground,
                textColor = style.keyText,
                label = "空格",
                borderEnabled = borderEnabled,
                keyHeightDp = 44f,
                labelFontSizeSp = 16f,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.settings_legend_caption),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SettingsLegendKeyCell(
    style: SettingsLegendStyle,
    backgroundColor: Int,
    textColor: Int,
    label: String? = null,
    hint: String? = null,
    icon: ImageVector? = null,
    showHint: Boolean = true,
    showSymbol: Boolean = true,
    borderEnabled: Boolean,
    keyHeightDp: Float = 52f,
    labelFontSizeSp: Float = 20f,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(style.keyCornerRadiusDp.dp)
    val borderColor = Color(style.keyBorderStroke)
    Box(
        modifier = modifier
            .height(keyHeightDp.dp)
            .clip(shape)
            .background(Color(backgroundColor))
            .then(
                if (borderEnabled && style.keyBorderWidthDp > 0f && borderColor.alpha > 0f) {
                    Modifier.border(style.keyBorderWidthDp.dp, borderColor, shape)
                } else {
                    Modifier
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (icon != null) {
            // 隐藏符号时图标不绘制，键面空白、仍可点按（与 ImageKeyView 口径一致）。
            if (showSymbol) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = Color(textColor),
                    modifier = Modifier.size(22.dp),
                )
            }
        } else if (label != null) {
            Text(
                text = label,
                fontSize = labelFontSizeSp.sp,
                color = Color(textColor),
                maxLines = 1,
            )
        }
        if (showHint && hint != null) {
            Text(
                text = hint,
                fontSize = 10.sp,
                color = Color(style.hintText),
                maxLines = 1,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 4.dp),
            )
        }
    }
}
