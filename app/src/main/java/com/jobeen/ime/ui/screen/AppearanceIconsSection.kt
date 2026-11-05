// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.ui.screen

import com.jobeen.ime.R

/**
 * 全局样式对话框「状态栏图标」分区行清单（纯函数，供 JVM 单测）：
 *
 * 口径（Jobeen 2026-11-04 定，设计图 settings-redesign-compact.png
 * 跟随开／settings-redesign.png 下半部分跟随关密集态）：
 * - 跟随开：只留三行（开关＋短说明），独立设置全部收起，不留
 *   「独立设置已收起」行；说明行保留但缩短为
 *   「背景、边框、圆角跟随按键。」；
 * - 跟随关：开关＋说明「关闭后用下方独立设置。」＋全部独立行
 *   （工具栏背景／自定义边框粗细／边框粗细／工具栏边框颜色／
 *   自定义圆角／工具栏圆角／动态模糊背景），按密集版渲染；
 * - 折叠行为与开关含义不变；独立行收起不丢草稿值（再开即现）。
 *
 * 行顺序即渲染顺序；[FOLLOW_SWITCH] 与 [FOLLOW_NOTE] 两态共有，
 * 其余行只在跟随关时出现。
 */
internal enum class IconsSectionRow {
    FOLLOW_SWITCH,
    FOLLOW_NOTE,
    TOOLBAR_BACKGROUND,
    BORDER_CUSTOM_SWITCH,
    BORDER_WIDTH_SLIDER,
    TOOLBAR_BORDER_COLOR,
    RADIUS_CUSTOM_SWITCH,
    TOOLBAR_RADIUS_SLIDER,
    BLUR_SWITCH,
}

internal fun iconsSectionRows(linkToolbar: Boolean): List<IconsSectionRow> =
    if (linkToolbar) {
        listOf(IconsSectionRow.FOLLOW_SWITCH, IconsSectionRow.FOLLOW_NOTE)
    } else {
        listOf(
            IconsSectionRow.FOLLOW_SWITCH,
            IconsSectionRow.FOLLOW_NOTE,
            IconsSectionRow.TOOLBAR_BACKGROUND,
            IconsSectionRow.BORDER_CUSTOM_SWITCH,
            IconsSectionRow.BORDER_WIDTH_SLIDER,
            IconsSectionRow.TOOLBAR_BORDER_COLOR,
            IconsSectionRow.RADIUS_CUSTOM_SWITCH,
            IconsSectionRow.TOOLBAR_RADIUS_SLIDER,
            IconsSectionRow.BLUR_SWITCH,
        )
    }

/** 跟随开关下方的说明行文案：开＝短说明，关＝「关闭后用下方独立设置。」。 */
internal fun iconsFollowNoteRes(linkToolbar: Boolean): Int =
    if (linkToolbar) R.string.bar_icon_follow_on_note else R.string.bar_icon_follow_off_note
