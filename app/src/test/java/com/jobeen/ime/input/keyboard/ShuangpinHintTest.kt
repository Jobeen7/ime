// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.input.keyboard

import com.jobeen.ime.input.keyboard.impl.QwertyKeyboard
import com.jobeen.ime.input.keyboard.key.KeyDef
import com.jobeen.ime.input.keyboard.key.KeyboardAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 双拼韵母提示（小鹤双拼键位表＋26 键键面接线）：
 * 只显示、不改动作——副标换韵母，点按／长按一字不动。
 */
class ShuangpinHintTest {

    @Test
    fun fullFinalTableMatchesXiaohe() {
        // 全部 26 键的韵母（含双韵母键的第二韵母），与小鹤标准键位表一致。
        val expected = mapOf(
            'a' to listOf("a"),
            'b' to listOf("in"),
            'c' to listOf("ao"),
            'd' to listOf("ai"),
            'e' to listOf("e"),
            'f' to listOf("en"),
            'g' to listOf("eng"),
            'h' to listOf("ang"),
            'i' to listOf("i"),
            'j' to listOf("an"),
            'k' to listOf("ing", "uai"),
            'l' to listOf("iang", "uang"),
            'm' to listOf("ian"),
            'n' to listOf("iao"),
            'o' to listOf("o", "uo"),
            'p' to listOf("ie"),
            'q' to listOf("iu"),
            'r' to listOf("uan"),
            's' to listOf("ong", "iong"),
            't' to listOf("ue", "üe"),
            'u' to listOf("u"),
            'v' to listOf("ui", "ü"),
            'w' to listOf("ei"),
            'x' to listOf("ia", "ua"),
            'y' to listOf("un"),
            'z' to listOf("ou"),
        )
        for ((key, finals) in expected) {
            assertEquals("key $key", finals, ShuangpinHint.finalsFor(key))
        }
    }

    @Test
    fun displayHintUsesPrimaryFinal() {
        // 双韵母键只显示首选韵母；O 显示 uo（o 与键同字，无信息量）。
        assertEquals("iu", ShuangpinHint.hintFor('q'))
        assertEquals("ei", ShuangpinHint.hintFor('w'))
        assertEquals("ing", ShuangpinHint.hintFor('k'))
        assertEquals("iang", ShuangpinHint.hintFor('l'))
        assertEquals("uo", ShuangpinHint.hintFor('o'))
        assertEquals("ong", ShuangpinHint.hintFor('s'))
        assertEquals("üe", ShuangpinHint.hintFor('t'))
        assertEquals("ui", ShuangpinHint.hintFor('v'))
        assertEquals("ia", ShuangpinHint.hintFor('x'))
        assertEquals("a", ShuangpinHint.hintFor('a'))
        assertEquals("un", ShuangpinHint.hintFor('y'))
        // 大写键同值；非字母无提示。
        assertEquals("iu", ShuangpinHint.hintFor('Q'))
        assertNull(ShuangpinHint.hintFor('1'))
        assertTrue(ShuangpinHint.finalsFor('1').isEmpty())
    }

    @Test
    fun qwertyLayoutWithoutHintKeepsFactorySubscripts() {
        // 默认（提示关）：副标仍是出厂标点，与 KeySymbolOps.DEFAULT_SYMBOLS 一致。
        val layout = QwertyKeyboard.buildLayout(KeyDef.Appearance.Variant.Accent)
        val altTexts = layout.flatten()
            .mapNotNull { it.appearance as? KeyDef.Appearance.AltText }
            .filter { it.displayText.length == 1 && it.displayText[0] in 'a'..'z' }
            .associate { it.displayText to it.altText }
        assertEquals("1", altTexts["q"])
        assertEquals("2", altTexts["w"])
        assertEquals("/", altTexts["m"])
    }

    @Test
    fun qwertyLayoutWithHintShowsFinalsAndKeepsActions() {
        val layout = QwertyKeyboard.buildLayout(
            KeyDef.Appearance.Variant.Accent, shuangpinHint = true,
        )
        val letterKeys = layout.flatten()
            .mapNotNull { it.appearance as? KeyDef.Appearance.AltText }
            .filter { it.displayText.length == 1 && it.displayText[0] in 'a'..'z' }
        assertEquals(26, letterKeys.size)
        val altTexts = letterKeys.associate { it.displayText to it.altText }
        assertEquals("iu", altTexts["q"])
        assertEquals("ei", altTexts["w"])
        assertEquals("uan", altTexts["r"])
        assertEquals("iao", altTexts["n"])
        assertEquals("ou", altTexts["z"])
        // 动作一字不动：点按发字母、长按提交出厂标点。
        val defs = layout.flatten().filter {
            (it.appearance as? KeyDef.Appearance.AltText)?.displayText == "q"
        }
        assertEquals(1, defs.size)
        val press = defs[0].behaviors.filterIsInstance<KeyDef.Behavior.Press>().single()
        assertEquals(KeyboardAction.KeySequenceAction("q"), press.action)
        val longPress = defs[0].behaviors.filterIsInstance<KeyDef.Behavior.LongPress>().single()
        assertEquals(KeyboardAction.CommitAction("1"), longPress.action)
    }
}
