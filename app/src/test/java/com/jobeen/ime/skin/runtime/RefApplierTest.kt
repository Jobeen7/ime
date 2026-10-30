package com.jobeen.ime.skin.runtime

import com.jobeen.ime.data.keyboard.theme.KeyboardColors
import com.jobeen.ime.input.keyboard.impl.NumberKeyboard
import com.jobeen.ime.input.keyboard.impl.QwertyKeyboard
import com.jobeen.ime.input.keyboard.impl.T15Keyboard
import com.jobeen.ime.input.keyboard.impl.T9Keyboard
import com.jobeen.ime.input.keyboard.key.KeyDef
import com.jobeen.ime.input.keyboard.key.KeyDef.Appearance.Variant
import com.jobeen.ime.input.keyboard.key.KeyboardAction
import com.jobeen.ime.skin.model.KeyOverride
import com.jobeen.ime.skin.model.SkinAction
import com.jobeen.ime.skin.model.SkinSnapshot
import com.jobeen.ime.skin.model.SoundBindings
import com.jobeen.ime.skin.model.WhitelistAction
import com.jobeen.ime.skin.store.SkinGenRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 参考项的装配层：popupChars 走 KeyboardPopup、icon 写外观、
 * 自创布局命名空间键命中；覆盖九宫格 / 26 键 / 十五键 / 数字 /
 * 自创布局五类结构（imageAdjust 为键盘级，与布局无关，见快照测试）。
 */
class RefApplierTest {

    private val scheme = KeyboardColors.ColorScheme(
        keyBackground = 1, keyPressed = 2, keyBorderStroke = 3,
        specialKeyBackground = 4, specialKeyPressed = 5, specialKeyBorderStroke = 6,
        accentKeyBackground = 7, accentKeyPressed = 8, accentKeyBorderStroke = 9,
        keyText = 10, specialKeyText = 11, accentKeyText = 12, altText = 13,
        background = 14, cornerRadius = 5f, keyHMargin = 3f, keyVMargin = 4f,
        panel = KeyboardColors.ColorScheme.PanelColors(
            background = 20, toolbarText = 21, toolbarActived = 22, toolbarIcon = 23,
            candidateBackground = 24, candidateText = 25, candidateIndex = 26,
            candidateDivider = 27, toolbarPressed = 28,
        ),
        pinner = KeyboardColors.ColorScheme.PinnerColors(
            background = 30, textColor = 31, secondaryTextColor = 32,
        ),
        toastBackground = 40, toastText = 41,
    )

    private fun snapshot(overrides: Map<String, KeyOverride>) = SkinSnapshot(
        ref = SkinGenRef("skin-1", "h".repeat(64), 1),
        name = "test",
        colorScheme = scheme,
        geometryPresence = emptySet(),
        effectiveGeometry = emptyMap(),
        background = null,
        degradations = emptySet(),
        layoutId = null,
        keyOverrides = overrides,
        soundBindings = SoundBindings.EMPTY,
    )

    private fun List<List<KeyDef>>.byId(id: String): KeyDef =
        flatten().first { it.id == id }

    private fun KeyDef.keyboardPopups(): List<KeyDef.Popup.Keyboard> =
        (popups ?: emptyArray()).filterIsInstance<KeyDef.Popup.Keyboard>()

    private fun KeyDef.longPressActions(): List<KeyDef.Behavior.LongPress> =
        behaviors.filterIsInstance<KeyDef.Behavior.LongPress>()

    private fun popupSequences(def: KeyDef): List<String> =
        def.keyboardPopups().flatMap { popup ->
            popup.keys.filterIsInstance<KeyboardAction.KeySequenceAction>().map { it.sequence }
        }

    @Test
    fun popupCharsBecomeKeyboardPopupOnQwerty() {
        val out = KeyOverrideApplier.apply(
            "Qwerty",
            QwertyKeyboard.buildLayout(Variant.Accent),
            snapshot(mapOf("KEY_Q" to KeyOverride(popupChars = listOf("，", "。")))),
            emptyMap(),
        )
        assertEquals(listOf("，", "。"), popupSequences(out.byId("KEY_Q")))
    }

    @Test
    fun popupCharsOnT9T15Number() {
        val t9 = KeyOverrideApplier.apply(
            "T9", T9Keyboard.buildLayout(Variant.Accent),
            snapshot(mapOf("T9_KEY_2" to KeyOverride(popupChars = listOf("a", "b")))),
            emptyMap(),
        )
        assertEquals(listOf("a", "b"), popupSequences(t9.byId("T9_KEY_2")))

        val t15 = KeyOverrideApplier.apply(
            "T15", T15Keyboard.buildLayout(Variant.Accent),
            snapshot(mapOf("T15_KEY_2" to KeyOverride(popupChars = listOf("x")))),
            emptyMap(),
        )
        assertEquals(listOf("x"), popupSequences(t15.byId("T15_KEY_2")))

        val num = KeyOverrideApplier.apply(
            "Number", NumberKeyboard.buildLayout(Variant.Accent),
            snapshot(mapOf("NUM_KEY_1" to KeyOverride(popupChars = listOf("1", "!", "¡")))),
            emptyMap(),
        )
        assertEquals(listOf("1", "!", "¡"), popupSequences(num.byId("NUM_KEY_1")))
    }

    @Test
    fun popupCharsOnCustomLayoutNamespacedKey() {
        val custom = listOf(
            listOf(
                KeyDef(
                    appearance = KeyDef.Appearance.Text("a", 20f),
                    behaviors = setOf(
                        KeyDef.Behavior.Press(KeyboardAction.KeySequenceAction("a")),
                    ),
                    id = "demo_layout:K-r0c1",
                ),
            ),
        )
        val out = KeyOverrideApplier.apply(
            "custom:demo_layout", custom,
            snapshot(
                mapOf(
                    "demo_layout:K-r0c1" to KeyOverride(
                        popupChars = listOf("à", "á"),
                        icon = "mic",
                    ),
                ),
            ),
            emptyMap(),
        )
        val key = out.byId("demo_layout:K-r0c1")
        assertEquals(listOf("à", "á"), popupSequences(key))
        assertEquals("mic", key.appearance.skinIconSymbol)
    }

    @Test
    fun popupCharsDisplacesSingleLongPress() {
        val out = KeyOverrideApplier.apply(
            "Qwerty",
            QwertyKeyboard.buildLayout(Variant.Accent),
            snapshot(
                mapOf(
                    "KEY_Q" to KeyOverride(
                        longPress = listOf(SkinAction.Text("z")),
                        popupChars = listOf("，"),
                    ),
                ),
            ),
            emptyMap(),
        )
        val key = out.byId("KEY_Q")
        assertTrue(key.longPressActions().isEmpty())
        assertEquals(listOf("，"), popupSequences(key))
    }

    @Test
    fun multiLongPressPopupWinsOverPopupChars() {
        val out = KeyOverrideApplier.apply(
            "Qwerty",
            QwertyKeyboard.buildLayout(Variant.Accent),
            snapshot(
                mapOf(
                    "KEY_Q" to KeyOverride(
                        longPress = listOf(
                            SkinAction.Command(WhitelistAction.COMMIT_DIRECT, "x"),
                            SkinAction.Command(WhitelistAction.COMMIT_DIRECT, "y"),
                        ),
                        popupChars = listOf("，"),
                    ),
                ),
            ),
            emptyMap(),
        )
        val key = out.byId("KEY_Q")
        // 多项长按候选条生效（commit 动作，非弹出字符文本）
        assertEquals(1, key.keyboardPopups().size)
        assertTrue(key.keyboardPopups().single().keys.all { it is KeyboardAction.LiteralCommitAction })
        assertFalse(popupSequences(key).contains("，"))
    }

    @Test
    fun popupCharsAndIconOnCustomLayouts11_14_17_18() {
        // 自定义布局只差键数与命名空间：11/14/17/18 键各建一布局，
        // 同一命名空间键须同时命中 popupChars 与 icon。
        for (count in listOf(11, 14, 17, 18)) {
            val layoutId = "custom$count"
            val layout = listOf(
                (0 until count).map { i ->
                    KeyDef(
                        appearance = KeyDef.Appearance.Text("k$i", 20f),
                        behaviors = setOf(
                            KeyDef.Behavior.Press(
                                KeyboardAction.KeySequenceAction("k$i"),
                            ),
                        ),
                        id = "$layoutId:K-r0c$i",
                    )
                },
            )
            val out = KeyOverrideApplier.apply(
                "custom:$layoutId", layout,
                snapshot(
                    mapOf(
                        "$layoutId:K-r0c1" to KeyOverride(
                            popupChars = listOf("à"), icon = "mic",
                        ),
                    ),
                ),
                emptyMap(),
            )
            val key = out.flatten().first { it.id == "$layoutId:K-r0c1" }
            assertEquals(listOf("à"), popupSequences(key))
            assertEquals("mic", key.appearance.skinIconSymbol)
        }
    }

    @Test
    fun knownIconWrittenUnknownIconFallsBack() {
        val out = KeyOverrideApplier.apply(
            "Qwerty",
            QwertyKeyboard.buildLayout(Variant.Accent),
            snapshot(
                mapOf(
                    "KEY_Q" to KeyOverride(icon = "mic"),
                    "KEY_W" to KeyOverride(icon = "not_a_symbol_xyz"),
                ),
            ),
            emptyMap(),
        )
        assertEquals("mic", out.byId("KEY_Q").appearance.skinIconSymbol)
        assertNull(out.byId("KEY_W").appearance.skinIconSymbol)
    }
}
