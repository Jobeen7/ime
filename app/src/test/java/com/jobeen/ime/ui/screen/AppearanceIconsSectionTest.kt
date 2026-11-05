// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.ui.screen

import com.jobeen.ime.R
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 状态栏图标分区行清单（Jobeen 2026-11-04 精简口径）：
 * 跟随开只留开关＋短说明两行，独立行全部收起；跟随关全行
 * 按密集版出现；说明文案开／关各一，关的文案与旧合并句
 * 的后半句逐字一致。
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
    fun followNote_onIsShortOffIsUnchanged() {
        assertEquals(
            R.string.bar_icon_follow_on_note,
            iconsFollowNoteRes(true),
        )
        assertEquals(
            R.string.bar_icon_follow_off_note,
            iconsFollowNoteRes(false),
        )
    }
}
