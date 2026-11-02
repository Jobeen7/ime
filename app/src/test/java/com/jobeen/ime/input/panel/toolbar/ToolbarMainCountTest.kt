package com.jobeen.ime.input.panel.toolbar

import org.junit.Assert.assertEquals
import org.junit.Test

/** 主页“当前”计数与编辑页生效列同一口径（2026-11-02 修：主页 0 个、编辑页 5 个之矛盾）。 */
class ToolbarMainCountTest {

    @Test
    fun activeToolbarCountsEnabledOnly() {
        assertEquals(
            2,
            ToolbarUserStore.mainPageCount(
                listOf(
                    ToolbarButtonEntry("emoji"),
                    ToolbarButtonEntry("copy", enabled = false),
                    ToolbarButtonEntry("paste"),
                ),
                emptyList(),
                null,
            ),
        )
    }

    @Test
    fun userListCountsEnabledOnly() {
        assertEquals(
            1,
            ToolbarUserStore.mainPageCount(
                null,
                listOf(ToolbarButtonEntry("emoji"), ToolbarButtonEntry("copy", enabled = false)),
                listOf("paste"),
            ),
        )
    }

    @Test
    fun emptyUserListFallsBackToSkinButtons() {
        // 皮肤栏已定义：计池内映射项（未知 id 剔除，与 seedEntries 同口径）。
        assertEquals(
            2,
            ToolbarUserStore.mainPageCount(
                null,
                emptyList(),
                listOf("emoji", "unknown_id", "clipboard"),
            ),
        )
    }

    @Test
    fun emptyUserListWithoutSkinButtonsFallsBackToFactoryFive() {
        // 皮肤未定义工具栏：计出厂默认 5 项（与编辑页起步列一致）。
        assertEquals(
            5,
            ToolbarUserStore.mainPageCount(null, emptyList(), null),
        )
    }

    @Test
    fun emptyUserListWithExplicitEmptySkinButtonsIsZero() {
        // 皮肤显式空数组：皮肤全权优先，编辑页起步列同样为空，计数一致为 0。
        assertEquals(
            0,
            ToolbarUserStore.mainPageCount(null, emptyList(), emptyList()),
        )
    }
}
