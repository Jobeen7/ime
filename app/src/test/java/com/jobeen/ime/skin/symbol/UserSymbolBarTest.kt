// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.skin.symbol

import com.jobeen.ime.data.PunctuationMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/** 用户自定义常用符号栏（施工单 10 H2-5；2026-11-12 符号栏重做：删置顶、默认 16 符号）：纯函数＋内存存储。 */
class UserSymbolBarTest {

    private val config = UserSymbolBarConfig(
        symbols = listOf("。", "，", "、"),
    )

    @Test
    fun defaultSymbols_hasSixteen() {
        assertEquals(16, UserSymbolBarOps.DEFAULT_SYMBOLS.size)
        assertEquals(
            listOf("，", "。", "？", "！", "…", "～", "、", "=", "+", "-", "*", "/", "%", "#", "@", "&"),
            UserSymbolBarOps.DEFAULT_SYMBOLS,
        )
        assertEquals(
            UserSymbolBarConfig(symbols = UserSymbolBarOps.DEFAULT_SYMBOLS),
            UserSymbolBarOps.defaultConfig(),
        )
    }

    @Test
    fun normalize_trimsDropsBlanksDedupesCaps() {
        assertEquals(
            listOf("。", "，", "、"),
            UserSymbolBarOps.normalize(listOf(" 。 ", "", "，", "。", "、", "  ")),
        )
        val many = (1..25).map { "符$it" }
        assertEquals(20, UserSymbolBarOps.normalize(many).size)
        assertEquals("符20", UserSymbolBarOps.normalize(many).last())
    }

    @Test
    fun add_appendsAndIgnoresBlankOrDuplicate() {
        assertEquals(
            listOf("。", "，", "、", "！"),
            UserSymbolBarOps.add(config, "！").symbols,
        )
        assertSame(config, UserSymbolBarOps.add(config, "。"))
        assertSame(config, UserSymbolBarOps.add(config, "   "))
    }

    @Test
    fun add_atCapIsNoop() {
        val full = UserSymbolBarConfig((1..20).map { "符$it" })
        assertSame(full, UserSymbolBarOps.add(full, "新"))
    }

    @Test
    fun removeAt_removesAndIgnoresOutOfBounds() {
        assertEquals(
            listOf("。", "、"),
            UserSymbolBarOps.removeAt(config, 1).symbols,
        )
        assertSame(config, UserSymbolBarOps.removeAt(config, -1))
        assertSame(config, UserSymbolBarOps.removeAt(config, 3))
    }

    @Test
    fun move_reordersAndIgnoresOutOfBoundsOrSame() {
        assertEquals(
            listOf("，", "、", "。"),
            UserSymbolBarOps.move(config, 0, 2).symbols,
        )
        assertEquals(
            listOf("、", "。", "，"),
            UserSymbolBarOps.move(config, 2, 0).symbols,
        )
        assertSame(config, UserSymbolBarOps.move(config, 0, 0))
        assertSame(config, UserSymbolBarOps.move(config, -1, 1))
        assertSame(config, UserSymbolBarOps.move(config, 0, 3))
    }

    @Test
    fun serializeParse_roundTrip() {
        val parsed = UserSymbolBarOps.parse(UserSymbolBarOps.serialize(config))
        assertEquals(config, parsed)
    }

    @Test
    fun parse_invalidFallsBackToDefault() {
        val defaults = UserSymbolBarOps.defaultConfig()
        assertEquals(defaults, UserSymbolBarOps.parse(null))
        assertEquals(defaults, UserSymbolBarOps.parse(""))
        assertEquals(defaults, UserSymbolBarOps.parse("not json"))
        assertEquals(defaults, UserSymbolBarOps.parse("[]"))
        // 非法符号项丢弃；旧 pinned 字段直接忽略。
        assertEquals(
            UserSymbolBarConfig(symbols = listOf("。")),
            UserSymbolBarOps.parse("""{"symbols":["。", "", 1]}"""),
        )
        assertEquals(
            UserSymbolBarConfig(symbols = listOf("。")),
            UserSymbolBarOps.parse("""{"pinned":false,"symbols":["。"]}"""),
        )
        assertEquals(
            UserSymbolBarConfig(symbols = listOf("。")),
            UserSymbolBarOps.parse("""{"pinned":true,"symbols":["。"]}"""),
        )
        // WO30 §3(7)：已存空列视为删空，恢复默认，不再原样保留空列。
        assertEquals(
            defaults,
            UserSymbolBarOps.parse("""{"symbols":[]}"""),
        )
    }

    @Test
    fun removeAtOrRestoreDefault_deleteLastRestoresDefault() {
        val single = UserSymbolBarConfig(symbols = listOf("。"))
        assertEquals(
            UserSymbolBarOps.defaultConfig(),
            UserSymbolBarOps.removeAtOrRestoreDefault(single, 0),
        )
    }

    @Test
    fun removeAtOrRestoreDefault_deleteNonLastKeepsRest() {
        assertEquals(
            listOf("。", "、"),
            UserSymbolBarOps.removeAtOrRestoreDefault(config, 1).symbols,
        )
    }

    @Test
    fun removeAtOrRestoreDefault_outOfBoundsIsNoop() {
        assertSame(config, UserSymbolBarOps.removeAtOrRestoreDefault(config, 3))
        assertSame(config, UserSymbolBarOps.removeAtOrRestoreDefault(config, -1))
    }

    @Test
    fun move_byOne_isUpDownButtonSemantics() {
        // WO30 §3(6)：上下移动按钮 = move(index, index±1)。
        assertEquals(
            listOf("，", "。", "、"),
            UserSymbolBarOps.move(config, 1, 0).symbols,
        )
        assertEquals(
            listOf("，", "。", "、"),
            UserSymbolBarOps.move(config, 0, 1).symbols,
        )
        assertEquals(
            listOf("。", "、", "，"),
            UserSymbolBarOps.move(config, 1, 2).symbols,
        )
    }

    // ── 标点模式换算（WO30 §3(9)）──

    @Test
    fun displaySymbol_halfWidth_convertsDefaultPunctuation() {
        val half = UserSymbolBarOps.DEFAULT_SYMBOLS.map {
            UserSymbolBarOps.displaySymbol(it, PunctuationMode.HalfWidth)
        }
        assertEquals(
            listOf(",", ".", "?", "!", "...", "~", "､", "=", "+", "-", "*", "/", "%", "#", "@", "&"),
            half,
        )
    }

    @Test
    fun displaySymbol_fullWidth_convertsAsciiPunctuation() {
        val full = UserSymbolBarOps.DEFAULT_SYMBOLS.map {
            UserSymbolBarOps.displaySymbol(it, PunctuationMode.FullWidth)
        }
        assertEquals(
            listOf("，", "。", "？", "！", "…", "～", "、", "＝", "＋", "－", "＊", "／", "％", "＃", "＠", "＆"),
            full,
        )
    }

    @Test
    fun displaySymbol_ellipsisPairAndNonPunctuation() {
        assertEquals("...", UserSymbolBarOps.displaySymbol("…", PunctuationMode.HalfWidth))
        assertEquals("…", UserSymbolBarOps.displaySymbol("...", PunctuationMode.FullWidth))
        // WO33 核实：顿号有单字符半角形「､」(U+FF64，NFKC→U+3001)，
        // 半角出「､」、全角回「、」；非标点原样。
        assertEquals("､", UserSymbolBarOps.displaySymbol("、", PunctuationMode.HalfWidth))
        assertEquals("、", UserSymbolBarOps.displaySymbol("、", PunctuationMode.FullWidth))
        assertEquals("、", UserSymbolBarOps.displaySymbol("､", PunctuationMode.FullWidth))
        assertEquals("､", UserSymbolBarOps.displaySymbol("､", PunctuationMode.HalfWidth))
        assertEquals("★", UserSymbolBarOps.displaySymbol("★", PunctuationMode.HalfWidth))
        assertEquals("★", UserSymbolBarOps.displaySymbol("★", PunctuationMode.FullWidth))
        // 已是目标形态的符号原样；全角形在半角模式下还原。
        assertEquals("＝", UserSymbolBarOps.displaySymbol("＝", PunctuationMode.FullWidth))
        assertEquals("=", UserSymbolBarOps.displaySymbol("＝", PunctuationMode.HalfWidth))
        assertEquals(",", UserSymbolBarOps.displaySymbol("，", PunctuationMode.HalfWidth))
    }

    @Test
    fun dunhaoHalfWidthForm_isInCharacterSetTable() {
        // WO33：换算表 CharacterSet.half2full 补「､」→「、」一对，
        // full2half 由反推得「、」→「､」；两向都锁死。
        assertEquals("､", PunctuationMode.CharacterSet.halfWidth("、"))
        assertEquals("、", PunctuationMode.CharacterSet.fullWidth("､"))
        assertEquals("、", PunctuationMode.CharacterSet.fullWidth("、"))
        assertEquals("､", PunctuationMode.CharacterSet.halfWidth("､"))
        // 不对称口径（有意）：「。」已有 ASCII 半角等价形 "."，仍走
        // 既有映射，不补 U+FF61「｡」；「。」半角出 "." 不是 "｡"。
        assertEquals(".", PunctuationMode.CharacterSet.halfWidth("。"))
        assertEquals("。", PunctuationMode.CharacterSet.fullWidth("."))
        assertEquals("｡", PunctuationMode.CharacterSet.fullWidth("｡"))
    }

    // ── 左竖栏显示／提交列（WO30 §3(9) 续：符号面板竖栏与九宫格竖栏同口径）──

    @Test
    fun sidePanelSymbols_halfWidth_convertsDefaultList() {
        assertEquals(
            listOf(",", ".", "?", "!", "...", "~", "､", "=", "+", "-", "*", "/", "%", "#", "@", "&"),
            UserSymbolBarOps.sidePanelSymbols(
                UserSymbolBarOps.DEFAULT_SYMBOLS,
                PunctuationMode.HalfWidth,
            ),
        )
    }

    @Test
    fun sidePanelSymbols_fullWidth_convertsDefaultList() {
        assertEquals(
            listOf("，", "。", "？", "！", "…", "～", "、", "＝", "＋", "－", "＊", "／", "％", "＃", "＠", "＆"),
            UserSymbolBarOps.sidePanelSymbols(
                UserSymbolBarOps.DEFAULT_SYMBOLS,
                PunctuationMode.FullWidth,
            ),
        )
    }

    @Test
    fun sidePanelSymbols_ellipsisPairAndNonPunctuation() {
        // 省略号配对：半角出 "..."，全角回 "…"；已是目标形态的原样。
        assertEquals(
            listOf("...", "..."),
            UserSymbolBarOps.sidePanelSymbols(listOf("…", "..."), PunctuationMode.HalfWidth),
        )
        assertEquals(
            listOf("…", "…"),
            UserSymbolBarOps.sidePanelSymbols(listOf("…", "..."), PunctuationMode.FullWidth),
        )
        // 「、」↔「､」：半角出「､」、全角回「、」；非标点原样；空列仍空。
        assertEquals(
            listOf("､", "★"),
            UserSymbolBarOps.sidePanelSymbols(listOf("、", "★"), PunctuationMode.HalfWidth),
        )
        assertEquals(
            listOf("、", "★"),
            UserSymbolBarOps.sidePanelSymbols(listOf("､", "★"), PunctuationMode.FullWidth),
        )
        assertEquals(
            listOf("､", "､"),
            UserSymbolBarOps.sidePanelSymbols(listOf("、", "､"), PunctuationMode.HalfWidth),
        )
        assertEquals(
            listOf("、", "、"),
            UserSymbolBarOps.sidePanelSymbols(listOf("、", "､"), PunctuationMode.FullWidth),
        )
        assertEquals(
            emptyList<String>(),
            UserSymbolBarOps.sidePanelSymbols(emptyList(), PunctuationMode.HalfWidth),
        )
    }

    @Test
    fun inMemoryStore_roundTrip() {
        val store = InMemoryUserSymbolBarStore()
        assertEquals(UserSymbolBarOps.defaultConfig(), store.load())
        store.save(config)
        assertEquals(config, store.load())
    }
}
