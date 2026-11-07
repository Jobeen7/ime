// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.ui.screen

import com.jobeen.ime.R
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 状态栏图标分区行清单（2026-11-07 按视频定稿重设计）：
 * 跟随开仍只留开关＋说明两行，独立行全部收起；跟随关全行按密集版出现；
 * 说明开／关同一句；标题栏芯片与摘要芯片行已删除。
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
    fun followNote_sameSentenceBothStates() {
        assertEquals(
            R.string.bar_icon_follow_note,
            iconsFollowNoteRes(true),
        )
        assertEquals(
            R.string.bar_icon_follow_note,
            iconsFollowNoteRes(false),
        )
    }
}
