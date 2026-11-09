// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.skin.symbol

import com.jobeen.ime.input.keyboard.impl.QwertyKeyboard
import com.jobeen.ime.input.keyboard.key.KeyDef
import com.jobeen.ime.input.keyboard.key.KeyboardAction
import com.jobeen.ime.input.keyboard.key.alphabetKey
import com.jobeen.ime.input.keyboard.key.commitKey
import com.jobeen.ime.input.keyboard.key.mixedAlphabetKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** 按键符号覆盖（施工单 10 H2-14）：纯函数＋内存存储＋布局套用。 */
class KeySymbolOverridesTest {

    @Test
    fun resolve_returnsFactorySymbolWithoutOverride() {
        assertEquals("1", KeySymbolOps.resolve('q', emptyMap()))
        assertEquals("~", KeySymbolOps.resolve('a', emptyMap()))
        assertEquals("(", KeySymbolOps.resolve('z', emptyMap()))
        assertEquals("/", KeySymbolOps.resolve('m', emptyMap()))
    }

    @Test
    fun resolve_overrideWinsAndUnknownLetterIsNull() {
        assertEquals("!", KeySymbolOps.resolve('q', mapOf('q' to "!")))
        // 大写字母归一到小写键。
        assertEquals("1", KeySymbolOps.resolve('Q', mapOf('1' to "!")))
        assertNull(KeySymbolOps.resolve('1', emptyMap()))
    }

    @Test
    fun set_storesSingleCharAndDropsFactoryValue() {
        val overridden = KeySymbolOps.set(emptyMap(), 'q', "!")
        assertEquals(mapOf('q' to "!"), overridden)
        // 改回出厂值即删覆盖（只存不同位置）。
        assertEquals(emptyMap<Char, String>(), KeySymbolOps.set(overridden, 'q', "1"))
        // 大写字母归一到小写键。
        assertEquals(mapOf('q' to "!"), KeySymbolOps.set(emptyMap(), 'Q', "!"))
    }

    @Test
    fun set_rejectsInvalidSymbolOrLetter() {
        val base = mapOf('q' to "!")
        assertSame(base, KeySymbolOps.set(base, 'q', ""))
        assertSame(base, KeySymbolOps.set(base, 'q', "  "))
        assertSame(base, KeySymbolOps.set(base, 'q', "ab"))
        assertSame(base, KeySymbolOps.set(base, '1', "!"))
        assertSame(base, KeySymbolOps.set(base, 'é', "!"))
    }

    @Test
    fun isValidSymbol_acceptsSingleCodePointBeyondBmp() {
        // 😀 U+1F600、𠀀 U+20000：UTF-16 代理对（length==2），码点数 1。
        assertTrue(KeySymbolOps.isValidSymbol("\uD83D\uDE00"))
        assertTrue(KeySymbolOps.isValidSymbol("\uD800\uDC00"))
        assertTrue(KeySymbolOps.isValidSymbol("!"))
        assertTrue(KeySymbolOps.isValidSymbol("。"))
        assertTrue(!KeySymbolOps.isValidSymbol(""))
        assertTrue(!KeySymbolOps.isValidSymbol("  "))
        assertTrue(!KeySymbolOps.isValidSymbol("ab"))
        // ZWJ 一家三口（4 码点）与组合附加符 e+U+0301（2 码点）仍拒绝。
        assertTrue(!KeySymbolOps.isValidSymbol("\uD83D\uDC68\u200D\uD83D\uDC69\u200D\uD83D\uDC67"))
        assertTrue(!KeySymbolOps.isValidSymbol("e\u0301"))
    }

    @Test
    fun set_storesEmojiAndSupplementaryPlaneChar() {
        assertEquals(mapOf('q' to "\uD83D\uDE00"), KeySymbolOps.set(emptyMap(), 'q', "\uD83D\uDE00"))
        assertEquals(mapOf('q' to "\uD800\uDC00"), KeySymbolOps.set(emptyMap(), 'q', "\uD800\uDC00"))
        val base = mapOf('q' to "!")
        assertSame(base, KeySymbolOps.set(base, 'q', "\uD83D\uDC68\u200D\uD83D\uDC69\u200D\uD83D\uDC67"))
        assertSame(base, KeySymbolOps.set(base, 'q', "e\u0301"))
    }

    @Test
    fun reset_clearsAllOverrides() {
        assertEquals(emptyMap<Char, String>(), KeySymbolOps.reset())
    }

    @Test
    fun serializeAndParse_roundTrip() {
        val overrides = mapOf('q' to "!", 'a' to "。")
        assertEquals(overrides, KeySymbolOps.parse(KeySymbolOps.serialize(overrides)))
    }

    @Test
    fun serializeAndParse_roundTripWithEmoji() {
        val overrides = mapOf('q' to "\uD83D\uDE00", 'a' to "\uD800\uDC00")
        assertEquals(overrides, KeySymbolOps.parse(KeySymbolOps.serialize(overrides)))
    }

    @Test
    fun parse_dropsInvalidEntriesAndFallsBackOnCorrupt() {
        assertEquals(emptyMap<Char, String>(), KeySymbolOps.parse(null))
        assertEquals(emptyMap<Char, String>(), KeySymbolOps.parse(""))
        assertEquals(emptyMap<Char, String>(), KeySymbolOps.parse("{not json"))
        assertEquals(emptyMap<Char, String>(), KeySymbolOps.parse("[1,2]"))
        // 非 a–z 键、多字值、非字符串值逐项丢弃，只留合法项。
        assertEquals(
            mapOf('q' to "!"),
            KeySymbolOps.parse("""{"q":"!","1":"x","a":"ab","z":5}"""),
        )
        // emoji 留下，ZWJ 序列与组合附加符逐项丢弃。
        assertEquals(
            mapOf('q' to "\uD83D\uDE00"),
            KeySymbolOps.parse(
                """{"q":"\uD83D\uDE00","a":"\uD83D\uDC68\u200D\uD83D\uDC69\u200D\uD83D\uDC67","z":"e\u0301"}""",
            ),
        )
    }

    @Test
    fun rows_coverAll26LettersInFactoryOrder() {
        assertEquals(3, KeySymbolOps.ROWS.size)
        assertEquals("qwertyuiop", KeySymbolOps.ROWS[0].joinToString(""))
        assertEquals("asdfghjkl", KeySymbolOps.ROWS[1].joinToString(""))
        assertEquals("zxcvbnm", KeySymbolOps.ROWS[2].joinToString(""))
    }

    @Test
    fun applyToLayout_emptyOverridesReturnsSameLayout() {
        val layout = QwertyKeyboard.buildLayout(KeyDef.Appearance.Variant.Accent)
        assertSame(layout, KeySymbolOps.applyToLayout(layout, emptyMap()))
    }

    @Test
    fun applyToLayout_rewritesAltTextAndLongPressOnly() {
        val layout = QwertyKeyboard.buildLayout(KeyDef.Appearance.Variant.Accent)
        val next = KeySymbolOps.applyToLayout(layout, mapOf('q' to "!", 'm' to "？"))
        val flat = next.flatten()
        val q = flat.single { (it.appearance as? KeyDef.Appearance.AltText)?.displayText == "q" }
        val qAlt = q.appearance as KeyDef.Appearance.AltText
        assertEquals("!", qAlt.altText)
        assertEquals("q", qAlt.displayText)
        // 长按提交动作跟符号走，点按仍是字母序列。
        val longPress = q.behaviors.filterIsInstance<KeyDef.Behavior.LongPress>().single()
        assertEquals(KeyboardAction.CommitAction("!"), longPress.action)
        val press = q.behaviors.filterIsInstance<KeyDef.Behavior.Press>().single()
        assertEquals(KeyboardAction.KeySequenceAction("q"), press.action)
        // 未改的键原样：w 仍是出厂角标与长按。
        val w = flat.single { (it.appearance as? KeyDef.Appearance.AltText)?.displayText == "w" }
        val wAlt = w.appearance as KeyDef.Appearance.AltText
        assertEquals("2", wAlt.altText)
        val wLongPress = w.behaviors.filterIsInstance<KeyDef.Behavior.LongPress>().single()
        assertEquals(KeyboardAction.CommitAction("2"), wLongPress.action)
        // m 也改了。
        val m = flat.single { (it.appearance as? KeyDef.Appearance.AltText)?.displayText == "m" }
        assertEquals("？", (m.appearance as KeyDef.Appearance.AltText).altText)
    }

    @Test
    fun applyToLayout_appliesEmojiOverride() {
        val layout = QwertyKeyboard.buildLayout(KeyDef.Appearance.Variant.Accent)
        val next = KeySymbolOps.applyToLayout(layout, mapOf('q' to "\uD83D\uDE00"))
        val q = next.flatten().single { (it.appearance as? KeyDef.Appearance.AltText)?.displayText == "q" }
        assertEquals("\uD83D\uDE00", (q.appearance as KeyDef.Appearance.AltText).altText)
        val longPress = q.behaviors.filterIsInstance<KeyDef.Behavior.LongPress>().single()
        assertEquals(KeyboardAction.CommitAction("\uD83D\uDE00"), longPress.action)
    }

    @Test
    fun applyToLayout_leavesNonLetterKeysAlone() {
        val layout = listOf(
            listOf(
                alphabetKey("q", "1"),
                mixedAlphabetKey("2", "abc"),
                commitKey("，"),
            ),
        )
        val next = KeySymbolOps.applyToLayout(layout, mapOf('q' to "!", 'a' to "？"))
        val flat = next.flatten()
        // 九宫格混合键主显多字，不匹配单字母规则，原样保留。
        val mixed = flat[1]
        assertEquals("abc", (mixed.appearance as KeyDef.Appearance.AltText).displayText)
        assertEquals("2", (mixed.appearance as KeyDef.Appearance.AltText).altText)
        assertTrue(mixed.behaviors.filterIsInstance<KeyDef.Behavior.LongPress>().single().action ==
            KeyboardAction.CommitAction("2"))
        // 纯文本键无角标槽，原样保留。
        assertEquals("，", (flat[2].appearance as KeyDef.Appearance.Text).displayText)
    }

    @Test
    fun store_memoryRoundTrip() {
        val store = InMemoryKeySymbolStore()
        assertEquals(emptyMap<Char, String>(), store.load())
        store.save(mapOf('q' to "!"))
        assertEquals(mapOf('q' to "!"), store.load())
        store.save(KeySymbolOps.reset())
        assertEquals(emptyMap<Char, String>(), store.load())
    }
}
