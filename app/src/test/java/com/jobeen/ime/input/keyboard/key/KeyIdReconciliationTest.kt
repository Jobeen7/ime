package com.jobeen.ime.input.keyboard.key

import com.jobeen.ime.input.keyboard.impl.NumberKeyboard
import com.jobeen.ime.input.keyboard.impl.QwertyKeyboard
import com.jobeen.ime.input.keyboard.impl.T15Keyboard
import com.jobeen.ime.input.keyboard.impl.T9Keyboard
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * 稳定键 ID 对账（定稿五.1 交付物 / Batch 2 详设 §2.8）：
 * 四布局实际标注的 ID 集合必须与登记表逐项相等、无重复；
 * T9 禁用名不得出现；T15 十五键（副标, 发, 面）三元组防错位；
 * Symbol/Emoji 只登记锚点（源码守卫 + 分类表计数）。
 *
 * 期望值在本文件内以字面量独立写出（不引用 KeyIds 常量拼装），
 * 使登记表、布局标注、期望三方互相对账。
 */
class KeyIdReconciliationTest {

    private fun idsOf(layout: List<List<KeyDef>>): List<String> =
        layout.flatten().mapNotNull { it.id }

    private fun assertReconciled(
        layoutName: String,
        layout: List<List<KeyDef>>,
        expected: Set<String>,
        registered: Set<String>,
    ) {
        val ids = idsOf(layout)
        assertEquals("$layoutName 存在重复 ID", ids.size, ids.toSet().size)
        assertEquals("$layoutName 布局标注与期望不符", expected, ids.toSet())
        assertEquals("$layoutName 布局标注与登记表不符", registered, ids.toSet())
    }

    @Test
    fun qwertyIdsReconciled() {
        val expected = setOf(
            "KEY_Q", "KEY_W", "KEY_E", "KEY_R", "KEY_T",
            "KEY_Y", "KEY_U", "KEY_I", "KEY_O", "KEY_P",
            "KEY_A", "KEY_S", "KEY_D", "KEY_F", "KEY_G",
            "KEY_H", "KEY_J", "KEY_K", "KEY_L",
            "KEY_CAPS",
            "KEY_Z", "KEY_X", "KEY_C", "KEY_V", "KEY_B", "KEY_N", "KEY_M",
            "KEY_DELETE", "KEY_LAYOUT_NUMBER", "KEY_PERIOD",
            "KEY_SPACE", "KEY_SCHEMA", "KEY_ENTER",
        )
        assertEquals(33, expected.size)
        assertReconciled("Qwerty", QwertyKeyboard.buildLayout(), expected, KeyIds.Qwerty.ALL)
    }

    @Test
    fun t9IdsReconciled() {
        val expected = setOf(
            "T9_SEGMENT",
            "T9_KEY_2", "T9_KEY_3", "T9_KEY_4", "T9_KEY_5",
            "T9_KEY_6", "T9_KEY_7", "T9_KEY_8", "T9_KEY_9",
            "T9_DELETE", "T9_NEWLINE", "T9_ENTER",
            "T9_LAYOUT_NUMBER", "T9_PERIOD", "T9_SPACE", "T9_SCHEMA",
        )
        assertEquals(16, expected.size)
        assertReconciled("T9", T9Keyboard.Layout, expected, KeyIds.T9.ALL)
        // 审计事实：T9 无 0/1 数字键、无星/井键，禁用名永不出现
        val ids = idsOf(T9Keyboard.Layout).toSet()
        for (forbidden in listOf("T9_KEY_0", "T9_KEY_1", "T9_KEY_STAR", "T9_KEY_HASH")) {
            assertFalse("T9 不得出现 $forbidden", forbidden in ids)
            assertFalse("登记表不得出现 $forbidden", KeyIds.isRegistered(forbidden))
        }
        // 侧栏占位不登记
        assertNull(T9Keyboard.Layout[0][0].id)
    }

    @Test
    fun t15IdsReconciled() {
        val expected = setOf(
            "T15_KEY_1", "T15_KEY_2", "T15_KEY_3", "T15_KEY_4", "T15_KEY_5",
            "T15_KEY_6", "T15_KEY_7", "T15_KEY_8", "T15_KEY_9", "T15_KEY_10",
            "T15_KEY_11", "T15_KEY_12", "T15_KEY_13", "T15_KEY_14", "T15_KEY_15",
            "T15_CLEAR", "T15_ZERO", "T15_DELETE", "T15_ENTER", "T15_SPACE",
            "T15_PERIOD", "T15_LAYOUT_NUMBER", "T15_SCHEMA",
        )
        assertEquals(23, expected.size)
        assertReconciled("T15", T15Keyboard.Layout, expected, KeyIds.T15.ALL)
        assertNull(T15Keyboard.Layout[0][0].id)
    }

    @Test
    fun t15TriplesMatchTable() {
        // （副标 digit, Press 发 send, 主显 letters）——双拼错位，逐项防回归
        val expected: Map<String, Triple<String, String, String>> = mapOf(
            "T15_KEY_1" to Triple("1", "q", "b"),
            "T15_KEY_2" to Triple("2", "w", "p"),
            "T15_KEY_3" to Triple("3", "e", "m"),
            "T15_KEY_4" to Triple("4", "r", "rf"),
            "T15_KEY_5" to Triple("5", "t", "ẑz"),
            "T15_KEY_6" to Triple("6", "a", "d"),
            "T15_KEY_7" to Triple("7", "s", "t"),
            "T15_KEY_8" to Triple("8", "d", "n"),
            "T15_KEY_9" to Triple("9", "f", "l"),
            "T15_KEY_10" to Triple("0", "g", "ĉc"),
            "T15_KEY_11" to Triple("&", "z", "gj"),
            "T15_KEY_12" to Triple("*", "x", "kq"),
            "T15_KEY_13" to Triple("^", "c", "hx"),
            "T15_KEY_14" to Triple("#", "v", "yw"),
            "T15_KEY_15" to Triple(";", "b", "ŝs"),
        )
        val byId = T15Keyboard.Layout.flatten().filter { it.id != null }.associateBy { it.id }
        for ((id, triple) in expected) {
            val def = byId[id]
            if (def == null) {
                fail("T15 缺少 $id")
                error("unreachable")
            }
            val appearance = def.appearance as KeyDef.Appearance.AltText
            val press = def.behaviors.filterIsInstance<KeyDef.Behavior.Press>().single().action
            val send = (press as KeyboardAction.KeySequenceAction).sequence
            assertEquals("$id 副标", triple.first, appearance.altText)
            assertEquals("$id 发", triple.second, send)
            assertEquals("$id 面", triple.third, appearance.displayText)
            val longPress = def.behaviors.filterIsInstance<KeyDef.Behavior.LongPress>()
                .single().action
            assertEquals("$id 长按出副标", triple.first, (longPress as KeyboardAction.CommitAction).text)
        }
    }

    @Test
    fun numberIdsReconciled() {
        val expected = setOf(
            "NUM_KEY_1", "NUM_KEY_2", "NUM_KEY_3", "NUM_KEY_4", "NUM_KEY_5",
            "NUM_KEY_6", "NUM_KEY_7", "NUM_KEY_8", "NUM_KEY_9", "NUM_KEY_0",
            "NUM_DELETE", "NUM_CLEAR", "NUM_SPACE", "NUM_RESUME", "NUM_SYMBOL",
            "NUM_COMMA", "NUM_DOT", "NUM_AT", "NUM_ENTER",
        )
        assertEquals(19, expected.size)
        assertReconciled("Number", NumberKeyboard.Layout, expected, KeyIds.Number.ALL)
        assertNull(NumberKeyboard.Layout[0][0].id)
    }

    @Test
    fun registryGovernance() {
        assertEquals(91, KeyIds.OVERRIDABLE.size)
        assertEquals(18, KeyIds.ANCHORS.size)
        assertTrue(KeyIds.OVERRIDABLE.intersect(KeyIds.ANCHORS).isEmpty())
        assertTrue(KeyIds.DEPRECATED.intersect(KeyIds.OVERRIDABLE + KeyIds.ANCHORS).isEmpty())
        assertEquals(7, KeyIds.Symbol.CATS.size)
        assertEquals(9, KeyIds.Emoji.CATS.size)
        // 分类表计数与锚点序号上限同账（序号随表序，增删走五.1 治理）
        assertEquals(7, com.jobeen.ime.data.Symbol.Symbol.size)
        assertEquals(9, com.jobeen.ime.data.Symbol.Emoji.size)
    }

    @Test
    fun symbolEmojiAnchorAnnotationPoints() {
        // 面板为 View 类、JVM 单测无法实例化：以源码守卫钉住标注点
        val symbol = sourceText("app/src/main/java/com/jobeen/ime/input/keyboard/impl/SymbolKeyboard.kt")
        assertTrue(symbol.contains("id = KeyIds.Symbol.SYM_RETURN"))
        assertTrue(symbol.contains("id = KeyIds.Symbol.CATS.getOrNull(index)"))
        val emoji = sourceText("app/src/main/java/com/jobeen/ime/input/keyboard/impl/EmojiKeyboard.kt")
        assertTrue(emoji.contains("id = KeyIds.Emoji.EMOJI_RETURN"))
        assertTrue(emoji.contains("id = KeyIds.Emoji.CATS.getOrNull(index)"))
        // 网格键现场生成处不得带 id（网格键不登记）
        assertFalse(symbol.contains("private fun keyDef(text: String): KeyDef {\n            return KeyDef(\n                id"))
    }

    private fun sourceText(relative: String): String {
        var dir: File? = File(System.getProperty("user.dir"))
        repeat(6) {
            val current = dir ?: return@repeat
            val candidate = File(current, relative)
            if (candidate.isFile) return candidate.readText()
            dir = current.parentFile
        }
        fail("找不到 $relative")
        error("unreachable")
    }
}
