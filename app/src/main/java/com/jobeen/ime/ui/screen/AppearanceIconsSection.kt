// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.ui.screen

import com.jobeen.ime.R

/**
 * 全局样式对话框「状态栏图标」分区行清单（纯函数，供 JVM 单测）：
 *
 * 口径（设计图 settings-redesign.png 上半部分跟随开／下半部分跟随关
 * 密集态；2026-11-06 重做版，取代 2026-11-04 紧凑口径）：
 * - 跟随开：行清单仍只留两行（开关＋说明），独立行全部收起
 *   （草稿值保留，再关即现）；说明行改回完整文案
 *   「开启后，状态栏图标跟随按键的背景、边框与圆角。」，
 *   说明下方同行内另附摘要芯片行（背景·跟随／边框·跟随／
 *   圆角·跟随＋右端「独立设置已收起」）——摘要芯片是说明行
 *   渲染的一部分，不另占行清单名额，故行数仍为 2；
 *   分区标题栏右端另附「跟随开／关」芯片（标题栏装饰，不占行）；
 * - 跟随关：开关＋说明「关闭后用下方独立设置。」＋全部独立行
 *   （工具栏背景／自定义边框粗细／边框粗细／工具栏边框颜色／
 *   自定义圆角／工具栏圆角／动态模糊背景），按密集版渲染；
 * - 折叠行为与开关含义不变。
 *
 * 注意：紧凑图 settings-redesign-compact.png 的短说明
 * 「背景、边框、圆角跟随按键。」已被主设计图的完整说明取代，
 * 仅其紧凑形态（只留开关＋说明、约占原来四分之一高）保留。
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

/** 跟随开关下方的说明行文案：开＝完整说明，关＝「关闭后用下方独立设置。」。 */
internal fun iconsFollowNoteRes(linkToolbar: Boolean): Int =
    if (linkToolbar) R.string.bar_icon_follow_on_note else R.string.bar_icon_follow_off_note

/** 跟随开摘要芯片行：背景／边框／圆角三枚「·跟随」芯片，按渲染顺序。 */
internal fun iconsFollowSummaryChips(): List<Int> = listOf(
    R.string.bar_icon_summary_background,
    R.string.bar_icon_summary_border,
    R.string.bar_icon_summary_radius,
)

/** 分区标题栏右端芯片文案：开＝「跟随开」，关＝「跟随关」。 */
internal fun iconsFollowStateChipRes(linkToolbar: Boolean): Int =
    if (linkToolbar) R.string.bar_icon_follow_on_chip else R.string.bar_icon_follow_off_chip
