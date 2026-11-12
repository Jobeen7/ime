// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.ui.screen

import com.jobeen.ime.R

/**
 * 全局样式对话框「状态栏图标」分区行清单（纯函数，供 JVM 单测）：
 *
 * 口径（2026-11-07 按视频定稿重设计，取代 2026-11-06 设计图口径；
 * 2026-11-13 方案 A：标题与说明按跟随态分支）：
 * - 分区标题随跟随态变：开「状态栏图标·跟随按键」、
 *   关「状态栏图标·独立设置」；标题行展开／折叠态均无药丸，
 *   状态只由开关＋标题文字表达一次；
 * - 跟随开：行清单仍只留两行（开关＋说明），独立行全部收起
 *   （草稿值保留，再关即现）；说明为短句
 *   「背景、边框、圆角跟随按键。」；标题栏不再附「跟随开／关」芯片，
 *   摘要芯片行（背景·跟随／边框·跟随／圆角·跟随＋
 *   「独立设置已收起」）一并删除；
 * - 跟随关：开关＋说明＋全部独立行
 *   （工具栏背景／自定义边框粗细／边框粗细／工具栏边框颜色／
 *   自定义圆角／工具栏圆角／动态模糊背景），按密集版渲染；
 *   说明沿用长句
 *   「开启后，状态栏图标共用按键的背景、边框、圆角和模糊设置；
 *   关闭后恢复独立设置。」；
 * - 折叠行为与开关含义不变。
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

/** 分区标题：随跟随态变（方案 A）。开「状态栏图标·跟随按键」，关「状态栏图标·独立设置」。 */
internal fun iconsSectionTitleRes(linkToolbar: Boolean): Int =
    if (linkToolbar) {
        R.string.global_section_icons_follow
    } else {
        R.string.global_section_icons_independent
    }

/** 跟随开关下方的说明行文案：按状态分支（方案 A）。开为短句，关沿用长句。 */
internal fun iconsFollowNoteRes(linkToolbar: Boolean): Int =
    if (linkToolbar) {
        R.string.bar_icon_follow_note_on
    } else {
        R.string.bar_icon_follow_note
    }
