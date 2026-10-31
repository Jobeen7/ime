// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime

import com.jobeen.ime.ui.screen.CatalogPage
import com.jobeen.ime.ui.screen.CatalogRow
import com.jobeen.ime.ui.screen.MainSettingEntry
import com.jobeen.ime.ui.screen.MainSettingGroup
import com.jobeen.ime.ui.screen.ResolvedPage
import com.jobeen.ime.ui.screen.ResolvedRow
import com.jobeen.ime.ui.screen.buildSearchPath
import com.jobeen.ime.ui.screen.catalogPages
import com.jobeen.ime.ui.screen.catalogRows
import com.jobeen.ime.ui.screen.directPageForGroup
import com.jobeen.ime.ui.screen.filterMainSettings
import com.jobeen.ime.ui.screen.groupMainSettings
import com.jobeen.ime.ui.screen.searchCatalog
import com.jobeen.ime.ui.screen.validateCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 主设置页搜索＋分组纯逻辑：过滤只看行标题／副标题，空组自动隐藏，组顺序固定。 */
class MainSettingsCatalogTest {

    private val entries = listOf(
        MainSettingEntry("schema", "输入方案", "选择与管理输入方案", MainSettingGroup.INPUT_CANDIDATE),
        MainSettingEntry("rime", "RIME", "部署、同步与方案目录", MainSettingGroup.INPUT_CANDIDATE),
        MainSettingEntry("keyboard", "键盘布局", "按键音、振动、高度与手势", MainSettingGroup.KEYBOARD),
        MainSettingEntry("skin", "键盘皮肤", "皮肤库、导入与颜色模式", MainSettingGroup.KEYBOARD),
        MainSettingEntry("appearance", "外观", "视频背景、全局样式、字体与应用外观", MainSettingGroup.APPEARANCE),
        MainSettingEntry("about", "关于我们", "版本、诊断与开发者信息", MainSettingGroup.ADVANCED_ABOUT),
    )

    @Test
    fun blankQuery_returnsAllInOrder() {
        assertEquals(entries, filterMainSettings("", entries))
        assertEquals(entries, filterMainSettings("   ", entries))
    }

    @Test
    fun titleMatch_onlyMatchingRows() {
        val got = filterMainSettings("皮肤", entries)
        assertEquals(listOf("skin"), got.map { it.key })
    }

    @Test
    fun subtitleMatch_onlyMatchingRows() {
        val got = filterMainSettings("颜色模式", entries)
        assertEquals(listOf("skin"), got.map { it.key })
    }

    @Test
    fun queryIsTrimmedAndCaseInsensitive() {
        assertEquals(listOf("rime"), filterMainSettings("  rime  ", entries).map { it.key })
        assertEquals(listOf("rime"), filterMainSettings("RIME", entries).map { it.key })
    }

    @Test
    fun noMatch_returnsEmpty() {
        assertTrue(filterMainSettings("不存在的设置", entries).isEmpty())
        assertTrue(groupMainSettings(emptyList()).isEmpty())
    }

    @Test
    fun grouping_dropsEmptyGroupsAndKeepsFixedOrder() {
        // 输入顺序打乱，组顺序仍按枚举固定顺序；只命中键盘组时其余组全部隐藏。
        val shuffled = listOf(entries[5], entries[3], entries[0])
        val grouped = groupMainSettings(shuffled)
        assertEquals(
            listOf(MainSettingGroup.INPUT_CANDIDATE, MainSettingGroup.KEYBOARD, MainSettingGroup.ADVANCED_ABOUT),
            grouped.map { it.first },
        )
        val keyboardOnly = groupMainSettings(filterMainSettings("皮肤", entries))
        assertEquals(1, keyboardOnly.size)
        assertEquals(MainSettingGroup.KEYBOARD, keyboardOnly[0].first)
        assertEquals(listOf("skin"), keyboardOnly[0].second.map { it.key })
    }

    @Test
    fun groupNameAlone_doesNotMatch() {
        // 只匹配行标题／副标题：组名本身不是匹配源。
        // “便捷功能”四字不在任何行标题／副标题里，搜它应为空。
        assertTrue(filterMainSettings("便捷功能", entries).isEmpty())
    }

    // ---- 目录整树＋搜索页 ----

    private val pages = listOf(
        ResolvedPage("keyboard", "键盘设置", "按键音、振动、高度与手势", "键盘"),
        ResolvedPage("appearance", "外观", "视频背景、全局样式、字体与应用外观", "外观"),
    )
    private val rows = listOf(
        ResolvedRow("按键音", "keyboard", "键盘设置", "键盘", "声音", "声音"),
        ResolvedRow("按键音量", "keyboard", "键盘设置", "键盘", "声音", "声音"),
        ResolvedRow("按键圆角", "appearance", "外观", "外观", "全局样式", null),
    )

    @Test
    fun searchPath_collapsesEqualSegments() {
        assertEquals("键盘 › 键盘设置 › 声音", buildSearchPath("键盘", "键盘设置", "声音"))
        assertEquals("外观 › 全局样式", buildSearchPath("外观", "外观", "全局样式"))
        assertEquals("关于我们", buildSearchPath("关于我们", "关于我们", null))
        assertEquals("便捷功能 › 剪切板", buildSearchPath("便捷功能", "剪切板", null))
    }

    @Test
    fun searchCatalog_blankQuery_returnsEmpty() {
        assertTrue(searchCatalog("", pages, rows).isEmpty())
        assertTrue(searchCatalog("   ", pages, rows).isEmpty())
    }

    @Test
    fun searchCatalog_pageMatch_firstWithNullFocus() {
        // 页标题命中时页结果在前（无定位），同页行结果随后（页标题命中也算行命中）。
        val got = searchCatalog("键盘设置", pages, rows)
        assertEquals("键盘设置", got[0].title)
        assertEquals("keyboard", got[0].pageKey)
        assertEquals(null, got[0].focusTitle)
        assertEquals(listOf("按键音", "按键音量"), got.drop(1).map { it.title })
    }

    @Test
    fun searchCatalog_rowMatch_pathAndFocus() {
        // 页副标题含“按键音”时页结果在前，行结果随后；行结果带路径与分类定位。
        val got = searchCatalog("按键音", pages, rows)
        assertEquals(listOf("键盘设置", "按键音", "按键音量"), got.map { it.title })
        val row = got[1]
        assertEquals("键盘 › 键盘设置 › 声音", row.path)
        assertEquals("keyboard", row.pageKey)
        // 键盘行定位到所在分类行，不是行本身。
        assertEquals("声音", row.focusTitle)
    }

    @Test
    fun searchCatalog_rowFocusDefaultsToRowTitle() {
        val got = searchCatalog("按键圆角", pages, rows)
        assertEquals(1, got.size)
        assertEquals("外观 › 全局样式", got[0].path)
        assertEquals("按键圆角", got[0].focusTitle)
    }

    @Test
    fun searchCatalog_sectionMatch_andCaseInsensitive() {
        val got = searchCatalog("声音", pages, rows)
        assertEquals(listOf("按键音", "按键音量"), got.map { it.title })
        val trimmed = searchCatalog("  按键圆角  ", pages, rows)
        assertEquals(1, trimmed.size)
    }

    @Test
    fun searchCatalog_noMatch_returnsEmpty() {
        assertTrue(searchCatalog("不存在的设置", pages, rows).isEmpty())
    }

    @Test
    fun catalogPages_fifteenKeysInOrder() {
        val keys = catalogPages().map { it.key }
        assertEquals(
            listOf(
                "schema", "scheme_update", "rime", "candidates", "skin", "keyboard",
                "toolbar", "appearance", "clipboard", "voice", "stats", "userdict",
                "backup", "files", "about",
            ),
            keys,
        )
    }

    @Test
    fun catalogPages_toolbarPage_isPageLevelOnly() {
        // 工具栏页行全为动态列表（按钮列／自定义栏），不进静态行索引，
        // 只出页结果（与备份／方案文件等工具页同口径）。
        val page = catalogPages().single { it.key == "toolbar" }
        assertEquals(MainSettingGroup.KEYBOARD, page.group)
        assertTrue(catalogRows().none { it.pageKey == "toolbar" })
    }

    @Test
    fun directPageForGroup_singlePage_returnsEntry() {
        assertEquals("appearance", directPageForGroup(entries, MainSettingGroup.APPEARANCE)?.key)
        assertEquals("about", directPageForGroup(entries, MainSettingGroup.ADVANCED_ABOUT)?.key)
    }

    @Test
    fun directPageForGroup_multiPageOrEmpty_returnsNull() {
        assertNull(directPageForGroup(entries, MainSettingGroup.KEYBOARD))
        assertNull(directPageForGroup(entries, MainSettingGroup.INPUT_CANDIDATE))
        assertNull(directPageForGroup(entries, MainSettingGroup.DATA_BACKUP))
    }

    @Test
    fun directPageForGroup_realCatalog_onlyAppearanceAndAboutAreDirect() {
        // 真实目录：外观组与关于组各仅 1 页，其余组均为多页；
        // 单页组点按直达（不经组页），多页组仍进组页，返回落点为主页。
        val real = catalogPages().map { MainSettingEntry(it.key, "", "", it.group) }
        assertEquals("appearance", directPageForGroup(real, MainSettingGroup.APPEARANCE)?.key)
        assertEquals("about", directPageForGroup(real, MainSettingGroup.ADVANCED_ABOUT)?.key)
        assertNull(directPageForGroup(real, MainSettingGroup.INPUT_CANDIDATE))
        assertNull(directPageForGroup(real, MainSettingGroup.KEYBOARD))
        assertNull(directPageForGroup(real, MainSettingGroup.CONVENIENCE))
        assertNull(directPageForGroup(real, MainSettingGroup.DATA_BACKUP))
    }

    @Test
    fun validateCatalog_realCatalog_passes() {
        assertTrue(validateCatalog(catalogPages(), catalogRows()).isEmpty())
    }

    @Test
    fun realCatalog_appearanceRows_focusToCategoryRow() {
        // 外观分类列表化后：全局样式详情行不在主页层，搜索对焦到分类行
        // （与键盘行同口径）；视频／应用外观行本身就在主页层，
        // 对焦默认行标题即分类行标题，不设显式对焦。
        // 导航去重后外观页皮肤行已删（留键盘组入口），总数 17→16。
        val rows = catalogRows().filter { it.pageKey == "appearance" }
        assertEquals(16, rows.size)
        assertTrue(rows.none { it.titleRes == R.string.keyboard_theme })
        val global = rows.filter { it.sectionRes == R.string.global_style }
        assertEquals(14, global.size)
        assertTrue(global.all { it.focusTitleRes == R.string.global_style })
        val video = rows.single { it.titleRes == R.string.video_background }
        assertEquals(null, video.focusTitleRes)
        val app = rows.single { it.titleRes == R.string.app_appearance }
        assertEquals(null, app.focusTitleRes)
    }

    @Test
    fun validateCatalog_detectsProblems() {
        val dupPages = listOf(
            CatalogPage("a", 1, 2, MainSettingGroup.KEYBOARD),
            CatalogPage("a", 3, 4, MainSettingGroup.KEYBOARD),
        )
        assertTrue(validateCatalog(dupPages, emptyList()).isNotEmpty())
        val orphan = listOf(CatalogRow(10, "missing"))
        assertTrue(validateCatalog(dupPages, orphan).any { it.contains("missing") })
        val dupRows = listOf(
            CatalogRow(10, "a"),
            CatalogRow(10, "a"),
        )
        val singlePage = listOf(CatalogPage("a", 1, 2, MainSettingGroup.KEYBOARD))
        assertTrue(validateCatalog(singlePage, dupRows).any { it.contains("duplicate row") })
    }
}
