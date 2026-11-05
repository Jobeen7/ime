// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.ui.screen

import com.jobeen.ime.R
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 状态栏图标分区行清单（设计图 settings-redesign.png，
 * 2026-11-06 重做版）：
 * 跟随开仍只留开关＋说明两行（摘要芯片是说明行渲染的一部分，
 * 不另占行），独立行全部收起；跟随关全行按密集版出现；
 * 说明文案开＝完整说明（逐字与设计图一致），关＝旧合并句后半句。
 */
class AppearanceIconsSectionTest {

    @Test
    fun followOn_onlySwitchAndNote() {
        assertEquals(
            listOf(
                IconsSectionRow.FOLLOW_SWITCH,
                IconsSectionRow.FOLLOW_NOTE,
            ),
            iconsSectionRows(true),
        )
    }

    @Test
    fun followOff_allNineRowsInOrder() {
        assertEquals(
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
            ),
            iconsSectionRows(false),
        )
    }

    @Test
    fun followNote_onIsFullOffIsUnchanged() {
        assertEquals(
            R.string.bar_icon_follow_on_note,
            iconsFollowNoteRes(true),
        )
        assertEquals(
            R.string.bar_icon_follow_off_note,
            iconsFollowNoteRes(false),
        )
    }

    @Test
    fun followSummaryChips_backgroundBorderRadiusInOrder() {
        assertEquals(
            listOf(
                R.string.bar_icon_summary_background,
                R.string.bar_icon_summary_border,
                R.string.bar_icon_summary_radius,
            ),
            iconsFollowSummaryChips(),
        )
    }

    @Test
    fun followStateChip_onIsFollowOnOffIsFollowOff() {
        assertEquals(
            R.string.bar_icon_follow_on_chip,
            iconsFollowStateChipRes(true),
        )
        assertEquals(
            R.string.bar_icon_follow_off_chip,
            iconsFollowStateChipRes(false),
        )
    }
}
