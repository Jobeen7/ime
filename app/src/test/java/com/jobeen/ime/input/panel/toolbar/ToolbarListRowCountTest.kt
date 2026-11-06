package com.jobeen.ime.input.panel.toolbar

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 工具栏列表行计数与 .19 主页计数同一 resolve() 口径（2026-11-07 修）：
 * 行显示该自定义栏可渲染项（启用且引用可解）；非使用栏全停用／
 * 全悬空时计 0；使用中栏生效列为空时渲染回落皮肤／默认，计回落
 * 列数，不再显示 0。旧口径直接计启用项：全停用的使用中栏计 0，
 * 而键盘实际渲染回落默认 5 项。
 */
class ToolbarListRowCountTest {

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

    private fun rowCount(
        data: ToolbarUserData,
        skinButtonIds: List<String>?,
        config: ToolbarCustomConfig,
        isActive: Boolean,
    ): Int = ToolbarUserStore.listRowCount("skin", data, skinButtonIds, config, isActive)

    @Test
    fun activeToolbarCountsEnabledOnly() {
        assertEquals(
            2,
            rowCount(
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
                customToolbar(
                    "toolbar:aaaa0001",
                    listOf(
                        ToolbarButtonEntry("emoji"),
                        ToolbarButtonEntry("copy", enabled = false),
                        ToolbarButtonEntry("paste"),
                    ),
                ),
                true,
            ),
        )
    }

    @Test
    fun inactiveToolbarCountsEnabledOnly() {
        assertEquals(
            2,
            rowCount(
                dataWith(
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
                customToolbar(
                    "toolbar:aaaa0001",
                    listOf(
                        ToolbarButtonEntry("emoji"),
                        ToolbarButtonEntry("copy", enabled = false),
                        ToolbarButtonEntry("paste"),
                    ),
                ),
                false,
            ),
        )
    }

    @Test
    fun inactiveToolbarWithDanglingRefsCountsResolvableOnly() {
        assertEquals(
            1,
            rowCount(
                dataWith(),
                null,
                customToolbar(
                    "toolbar:aaaa0001",
                    listOf(
                        ToolbarButtonEntry("emoji"),
                        ToolbarButtonEntry("unknown_id"),
                        ToolbarButtonEntry("custom:deadbeef"),
                    ),
                ),
                false,
            ),
        )
    }

    @Test
    fun inactiveAllDisabledShowsZero() {
        // 非使用栏全停用：无生效列、无回落，仍显示 0。
        assertEquals(
            0,
            rowCount(
                dataWith(),
                null,
                customToolbar(
                    "toolbar:aaaa0001",
                    listOf(
                        ToolbarButtonEntry("emoji", enabled = false),
                        ToolbarButtonEntry("copy", enabled = false),
                    ),
                ),
                false,
            ),
        )
    }

    @Test
    fun activeAllDisabledFallsBackToFactoryFive() {
        // 使用中栏全停用：渲染回落默认 5 项，行不再显示 0。
        assertEquals(
            5,
            rowCount(
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
                customToolbar(
                    "toolbar:aaaa0001",
                    listOf(
                        ToolbarButtonEntry("emoji", enabled = false),
                        ToolbarButtonEntry("copy", enabled = false),
                    ),
                ),
                true,
            ),
        )
    }

    @Test
    fun activeAllDisabledFallsBackToSkinButtons() {
        assertEquals(
            2,
            rowCount(
                dataWith(
                    activeToolbarId = "toolbar:aaaa0001",
                    customToolbars = mapOf(
                        "toolbar:aaaa0001" to customToolbar(
                            "toolbar:aaaa0001",
                            listOf(ToolbarButtonEntry("emoji", enabled = false)),
                        ),
                    ),
                ),
                listOf("emoji", "clipboard"),
                customToolbar(
                    "toolbar:aaaa0001",
                    listOf(ToolbarButtonEntry("emoji", enabled = false)),
                ),
                true,
            ),
        )
    }

    @Test
    fun activeAllDanglingFallsBackToFactoryFive() {
        // 使用中栏引用全悬空：可渲染项为 0，同样回落默认 5 项。
        assertEquals(
            5,
            rowCount(
                dataWith(
                    activeToolbarId = "toolbar:aaaa0001",
                    customToolbars = mapOf(
                        "toolbar:aaaa0001" to customToolbar(
                            "toolbar:aaaa0001",
                            listOf(
                                ToolbarButtonEntry("unknown_id"),
                                ToolbarButtonEntry("custom:deadbeef"),
                            ),
                        ),
                    ),
                ),
                null,
                customToolbar(
                    "toolbar:aaaa0001",
                    listOf(
                        ToolbarButtonEntry("unknown_id"),
                        ToolbarButtonEntry("custom:deadbeef"),
                    ),
                ),
                true,
            ),
        )
    }

    @Test
    fun activeValidCustomRefIsCounted() {
        assertEquals(
            2,
            rowCount(
                dataWith(
                    activeToolbarId = "toolbar:aaaa0001",
                    customButtons = mapOf(
                        "custom:abcd1234" to customButtonDef("custom:abcd1234"),
                    ),
                ),
                null,
                customToolbar(
                    "toolbar:aaaa0001",
                    listOf(
                        ToolbarButtonEntry("emoji"),
                        ToolbarButtonEntry("custom:abcd1234"),
                    ),
                ),
                true,
            ),
        )
    }

    @Test
    fun activeEffectiveIgnoresUserList() {
        // 使用中栏有生效项时计其可渲染项，不看用户列、不回落。
        assertEquals(
            2,
            rowCount(
                dataWith(
                    userButtons = listOf(
                        ToolbarButtonEntry("emoji"),
                        ToolbarButtonEntry("paste"),
                    ),
                    activeToolbarId = "toolbar:aaaa0001",
                ),
                listOf("emoji", "clipboard"),
                customToolbar(
                    "toolbar:aaaa0001",
                    listOf(
                        ToolbarButtonEntry("emoji"),
                        ToolbarButtonEntry("copy", enabled = false),
                        ToolbarButtonEntry("paste"),
                    ),
                ),
                true,
            ),
        )
    }

    @Test
    fun activeAllDisabledWithExplicitEmptySkinButtonsIsZero() {
        // 皮肤显式空数组：回落口径与主页计数一致为 0。
        assertEquals(
            0,
            rowCount(
                dataWith(
                    activeToolbarId = "toolbar:aaaa0001",
                    customToolbars = mapOf(
                        "toolbar:aaaa0001" to customToolbar(
                            "toolbar:aaaa0001",
                            listOf(ToolbarButtonEntry("emoji", enabled = false)),
                        ),
                    ),
                ),
                emptyList(),
                customToolbar(
                    "toolbar:aaaa0001",
                    listOf(ToolbarButtonEntry("emoji", enabled = false)),
                ),
                true,
            ),
        )
    }
}
