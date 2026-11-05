package com.jobeen.ime.input.panel.toolbar

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 主页“当前”计数与键盘实际渲染列同一口径（2026-11-06 修：计数以
 * resolve 为唯一来源；2026-11-02 修只覆盖了“用户列空”一支）。
 * 旧口径在用户列／自定义栏全停用、栏 id 悬空、引用悬空时与渲染
 * 错位（主页计 0、键盘实际渲染回落默认 5 项）。
 */
class ToolbarMainCountTest {

    private fun dataWith(
        userButtons: List<ToolbarButtonEntry> = emptyList(),
        activeToolbarId: String? = null,
        customToolbars: Map<String, ToolbarCustomConfig> = emptyMap(),
        customButtons: Map<String, ToolbarCustomButton> = emptyMap(),
    ): ToolbarUserData = ToolbarUserData(
        skins = mapOf(
            "skin" to ToolbarSkinState(
                buttons = userButtons,
                activeToolbarId = activeToolbarId,
            ),
        ),
        customButtons = customButtons,
        customToolbars = customToolbars,
    )

    private fun customToolbar(
        id: String,
        buttons: List<ToolbarButtonEntry>,
    ): ToolbarCustomConfig = ToolbarCustomConfig(
        id = id,
        name = "T",
        buttons = buttons,
        background = ToolbarBackground.Transparent,
    )

    private fun customButtonDef(id: String): ToolbarCustomButton = ToolbarCustomButton(
        id = id,
        kind = CustomDisplayKind.TEXT,
        display = "X",
        imageFile = null,
        iconSizeDp = 24,
        widthDp = 48,
        backgroundArgb = null,
        bindings = emptyMap(),
    )

    @Test
    fun activeToolbarCountsEnabledOnly() {
        assertEquals(
            2,
            ToolbarUserStore.mainPageCount(
                "skin",
                dataWith(
                    activeToolbarId = "toolbar:aaaa0001",
                    customToolbars = mapOf(
                        "toolbar:aaaa0001" to customToolbar(
                            "toolbar:aaaa0001",
                            listOf(
                                ToolbarButtonEntry("emoji"),
                                ToolbarButtonEntry("copy", enabled = false),
                                ToolbarButtonEntry("paste"),
                            ),
                        ),
                    ),
                ),
                null,
            ),
        )
    }

    @Test
    fun userListCountsEnabledOnly() {
        assertEquals(
            1,
            ToolbarUserStore.mainPageCount(
                "skin",
                dataWith(
                    userButtons = listOf(
                        ToolbarButtonEntry("emoji"),
                        ToolbarButtonEntry("copy", enabled = false),
                    ),
                ),
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
                "skin",
                dataWith(),
                listOf("emoji", "unknown_id", "clipboard"),
            ),
        )
    }

    @Test
    fun emptyUserListWithoutSkinButtonsFallsBackToFactoryFive() {
        // 皮肤未定义工具栏：计出厂默认 5 项（与编辑页起步列一致）。
        assertEquals(
            5,
            ToolbarUserStore.mainPageCount("skin", dataWith(), null),
        )
    }

    @Test
    fun emptyUserListWithExplicitEmptySkinButtonsIsZero() {
        // 皮肤显式空数组：皮肤全权优先，编辑页起步列同样为空，计数一致为 0。
        assertEquals(
            0,
            ToolbarUserStore.mainPageCount("skin", dataWith(), emptyList()),
        )
    }

    @Test
    fun allDisabledUserListFallsBackToFactoryFive() {
        // 用户列非空但全停用：resolve 视为无配置、渲染回落默认 5 项，
        // 旧口径计 0（编辑页开关可逐项停用后保存，此态可经 UI 到达）。
        assertEquals(
            5,
            ToolbarUserStore.mainPageCount(
                "skin",
                dataWith(
                    userButtons = listOf(
                        ToolbarButtonEntry("emoji", enabled = false),
                        ToolbarButtonEntry("copy", enabled = false),
                    ),
                ),
                null,
            ),
        )
    }

    @Test
    fun allDisabledUserListFallsBackToSkinButtons() {
        assertEquals(
            2,
            ToolbarUserStore.mainPageCount(
                "skin",
                dataWith(
                    userButtons = listOf(
                        ToolbarButtonEntry("emoji", enabled = false),
                    ),
                ),
                listOf("emoji", "clipboard"),
            ),
        )
    }

    @Test
    fun allDisabledActiveToolbarFallsBackToFactoryFive() {
        // 自定义栏全停用：resolve 返回 null 且不看用户列（与渲染一致），
        // 不计用户列的 2 项、不计 0，直接回落默认 5 项。
        assertEquals(
            5,
            ToolbarUserStore.mainPageCount(
                "skin",
                dataWith(
                    userButtons = listOf(
                        ToolbarButtonEntry("emoji"),
                        ToolbarButtonEntry("paste"),
                    ),
                    activeToolbarId = "toolbar:aaaa0001",
                    customToolbars = mapOf(
                        "toolbar:aaaa0001" to customToolbar(
                            "toolbar:aaaa0001",
                            listOf(
                                ToolbarButtonEntry("emoji", enabled = false),
                                ToolbarButtonEntry("copy", enabled = false),
                            ),
                        ),
                    ),
                ),
                null,
            ),
        )
    }

    @Test
    fun danglingActiveToolbarIdFallsBackToFactoryFive() {
        // 生效栏 id 悬空（栏在读盘校验中被丢弃、id 未清）：resolve 直接
        // 返回 null、不看用户列，渲染回落默认 5 项。
        assertEquals(
            5,
            ToolbarUserStore.mainPageCount(
                "skin",
                dataWith(
                    userButtons = listOf(
                        ToolbarButtonEntry("emoji"),
                        ToolbarButtonEntry("paste"),
                    ),
                    activeToolbarId = "toolbar:deadbeef",
                ),
                null,
            ),
        )
    }

    @Test
    fun danglingRefsInUserListAreSkipped() {
        // 未知池 id 与已删自定义钮引用：渲染期逐项跳过，计数同样剔除。
        assertEquals(
            1,
            ToolbarUserStore.mainPageCount(
                "skin",
                dataWith(
                    userButtons = listOf(
                        ToolbarButtonEntry("emoji"),
                        ToolbarButtonEntry("unknown_id"),
                        ToolbarButtonEntry("custom:deadbeef"),
                    ),
                ),
                null,
            ),
        )
    }

    @Test
    fun validCustomRefInUserListIsCounted() {
        assertEquals(
            2,
            ToolbarUserStore.mainPageCount(
                "skin",
                dataWith(
                    userButtons = listOf(
                        ToolbarButtonEntry("emoji"),
                        ToolbarButtonEntry("custom:abcd1234"),
                    ),
                    customButtons = mapOf(
                        "custom:abcd1234" to customButtonDef("custom:abcd1234"),
                    ),
                ),
                null,
            ),
        )
    }

    @Test
    fun danglingRefInActiveToolbarCountsResolvableOnly() {
        assertEquals(
            1,
            ToolbarUserStore.mainPageCount(
                "skin",
                dataWith(
                    activeToolbarId = "toolbar:aaaa0001",
                    customToolbars = mapOf(
                        "toolbar:aaaa0001" to customToolbar(
                            "toolbar:aaaa0001",
                            listOf(
                                ToolbarButtonEntry("emoji"),
                                ToolbarButtonEntry("unknown_id"),
                            ),
                        ),
                    ),
                ),
                null,
            ),
        )
    }
}
