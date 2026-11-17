// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime

import com.jobeen.ime.ui.screen.MainSettingEntry
import com.jobeen.ime.ui.screen.MainSettingGroup
import com.jobeen.ime.ui.screen.ResolvedPage
import com.jobeen.ime.ui.screen.ResolvedRow
import com.jobeen.ime.ui.screen.buildSearchPath
import com.jobeen.ime.ui.screen.FOCUS_CONSUMING_PAGE_KEYS
import com.jobeen.ime.ui.screen.catalogPages
import com.jobeen.ime.ui.screen.catalogRows
import com.jobeen.ime.ui.screen.directPageForGroup
import com.jobeen.ime.ui.screen.searchCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        // “按键圆角”与“按键音”差一字，作为容错结果排在精确结果之后。
        val got = searchCatalog("按键音", pages, rows)
        assertEquals(listOf("键盘设置", "按键音", "按键音量", "按键圆角"), got.map { it.title })
        val row = got[1]
        assertEquals("键盘 › 键盘设置 › 声音", row.path)
        assertEquals("keyboard", row.pageKey)
        // 键盘行定位到所在分类行，不是行本身。
        assertEquals("声音", row.focusTitle)
    }

    @Test
    fun searchCatalog_fuzzyTypoSubstitution_findsRow() {
        // 替错一字（健→键）仍搜到；“按键圆角”与查询差两字（健→键、圆→音），不命中。
        // 容错只看标题，页标题“键盘设置”不命中，故无页结果。
        val got = searchCatalog("按健音", pages, rows)
        assertEquals(listOf("按键音", "按键音量"), got.map { it.title })
    }

    @Test
    fun searchCatalog_fuzzyMissingChar_findsRow() {
        // 少打一字（按音）仍搜到。
        val got = searchCatalog("按音", pages, rows)
        assertEquals(listOf("按键音", "按键音量", "按键圆角"), got.map { it.title })
    }

    @Test
    fun searchCatalog_fuzzyExtraChar_findsRow() {
        // 多打一字（按键音音）仍搜到；“按键圆角”与查询差两字，不命中。
        val got = searchCatalog("按键音音", pages, rows)
        assertEquals(listOf("按键音", "按键音量"), got.map { it.title })
    }

    @Test
    fun searchCatalog_fuzzyExactResults_first() {
        // 精确结果在前、容错结果在后：按键圆角只差一字，不插队。
        val got = searchCatalog("按键音", pages, rows)
        assertEquals("按键音", got[1].title)
        assertEquals("按键音量", got[2].title)
        assertEquals("按键圆角", got[3].title)
    }

    @Test
    fun searchCatalog_fuzzySingleCharQuery_noFuzzy() {
        // 单字查询不做容错：错一字等于任意字，噪声太大。
        assertTrue(searchCatalog("健", pages, rows).isEmpty())
    }

    @Test
    fun searchCatalog_fuzzyTwoErrors_noMatch() {
        // 错两字不命中。
        assertTrue(searchCatalog("按健因", pages, rows).isEmpty())
    }

    @Test
    fun searchCatalog_traditionalQuery_matchesViaSimplify() {
        // 繁体查询经注入的 simplify 折成简体后命中；生产调用方传
        // TraditionalConverter::toSimplified，主机单测用假函数。
        // “按键圆角”与折后查询只差一字，同为容错命中。
        val got = searchCatalog("按鍵音", pages, rows) { it.replace("鍵", "键") }
        assertEquals(listOf("键盘设置", "按键音", "按键音量", "按键圆角"), got.map { it.title })
        // 不传 simplify 时繁体查询仍以“错一字”走容错命中行（无页结果，
        // 页标题不含该词）；simplify 的作用是把它升为精确命中、补上页结果。
        val fuzzyOnly = searchCatalog("按鍵音", pages, rows)
        assertEquals(listOf("按键音", "按键音量"), fuzzyOnly.map { it.title })
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
    fun catalogPages_nineteenKeysInOrder() {
        // 19 键：Emoji 设置入口、按键符号页、皮肤脚本页、皮肤编辑器页、
        // LevelDB 页、皮肤库页、脚本页、Wi-Fi 传输页已删；
        // 键盘皮肤页（skin）2026-11-18 恢复（内置选择＋扫码导入）；
        // 旧期望随单子改（测试期望与单子相背时按单子改期望）。
        val keys = catalogPages().map { it.key }
        assertEquals(
            listOf(
                "schema", "scheme_update", "rime", "candidates", "skin", "keyboard",
                "keyboardMenu", "toolbar", "symbol_bar", "appearance", "clipboard", "phrase", "ai", "voice", "stats", "userdict",
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
    fun realCatalog_appearanceRows_focusToCategoryRow() {
        // 外观页只剩应用外观一行：视频背景行、全局样式 14 行、
        // 皮肤行均已删；应用外观行本身就在主页层，
        // 对焦默认行标题即分类行标题，不设显式对焦。
        val rows = catalogRows().filter { it.pageKey == "appearance" }
        assertEquals(1, rows.size)
        val app = rows.single { it.titleRes == R.string.app_appearance }
        assertEquals(null, app.focusTitleRes)
    }

    // ── U-M3：语音行定位标题须是页内真实渲染的行 ──

    @Test
    fun realCatalog_voiceModelRow_focusesRenderedLocalModelRow() {
        // “识别模型”在语音页不存在（模型行标题是“本地语音模型”），
        // 旧值永不到达、3 秒超时后静默放弃；搜索结果标题仍为行标题本身。
        val row = catalogRows().single { it.titleRes == R.string.voice_recognition_model }
        assertEquals("voice", row.pageKey)
        assertEquals(R.string.voice_local_model, row.focusTitleRes)
    }

    // ── U-M4：定位标题只写给消费页 ──

    @Test
    fun focusConsumingPages_coverEveryRowTarget() {
        // 全部目录行的目标页都消费定位请求；否则标题残留到下一个设置页。
        val uncovered = catalogRows().map { it.pageKey }.toSet() - FOCUS_CONSUMING_PAGE_KEYS
        assertTrue(uncovered.isEmpty())
    }

    @Test
    fun focusConsumingPages_excludeNonScaffoldPages() {
        // 无脚手架的页（方案管理、系统文件、
        // 输入统计）不消费标题，写给它们即泄漏。
        for (key in listOf("schema", "files", "stats")) {
            assertFalse(FOCUS_CONSUMING_PAGE_KEYS.contains(key))
        }
    }

}
