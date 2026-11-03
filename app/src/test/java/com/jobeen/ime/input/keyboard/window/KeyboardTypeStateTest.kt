package com.jobeen.ime.input.keyboard.window

import com.jobeen.ime.data.keyboard.theme.KeyboardColors
import com.jobeen.ime.input.keyboard.key.KeyDef
import com.jobeen.ime.input.keyboard.key.KeyboardAction
import com.jobeen.ime.skin.model.KeyboardTypeResolved
import com.jobeen.ime.skin.model.LayoutResolution
import com.jobeen.ime.skin.model.ResolvedLayout
import com.jobeen.ime.skin.model.SkinSnapshot
import com.jobeen.ime.skin.store.SkinGenRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 类型纯状态机与重挂决策：状态转移、主/上一跟踪、先解析再比较。 */
class KeyboardTypeStateTest {

    private val scheme = KeyboardColors.ColorScheme(
        keyBackground = 100, keyPressed = 101, keyBorderStroke = 102,
        specialKeyBackground = 200, specialKeyPressed = 201, specialKeyBorderStroke = 202,
        accentKeyBackground = 300, accentKeyPressed = 301, accentKeyBorderStroke = 302,
        keyText = 400, specialKeyText = 401, accentKeyText = 402, altText = 403,
        background = 500, cornerRadius = 5f, keyHMargin = 3f, keyVMargin = 4f,
        panel = KeyboardColors.ColorScheme.PanelColors(
            background = 600, toolbarText = 601, toolbarActived = 602, toolbarIcon = 603,
            candidateBackground = 604, candidateText = 605, candidateIndex = 606,
            candidateDivider = 607, toolbarPressed = 608,
        ),
        pinner = KeyboardColors.ColorScheme.PinnerColors(
            background = 700, textColor = 701, secondaryTextColor = 702,
        ),
        toastBackground = 999, toastText = 998,
    )

    private fun keyDef(id: String): KeyDef = KeyDef(
        appearance = KeyDef.Appearance.Text(
            displayText = id, textSize = 18f, percentWidth = 1f,
        ),
        behaviors = setOf(
            KeyDef.Behavior.Press(KeyboardAction.KeySequenceAction(id)),
        ),
        id = id,
    )

    private fun resolved(id: String) = ResolvedLayout(
        layoutId = id, generation = 1, name = id,
        baseLayout = listOf(listOf(keyDef("$id:K-1"))),
    )

    private fun snapshot(
        table: Map<String, KeyboardTypeResolved> = emptyMap(),
        legacy: ResolvedLayout? = null,
    ): SkinSnapshot = SkinSnapshot(
        ref = SkinGenRef("skin-1", "h", 1),
        name = "S",
        colorScheme = scheme,
        geometryPresence = emptySet(),
        effectiveGeometry = emptyMap(),
        background = null,
        degradations = emptySet(),
        layoutId = legacy?.layoutId,
        resolvedLayout = legacy,
        layoutResolution = if (legacy != null) {
            LayoutResolution.RESOLVED
        } else {
            LayoutResolution.NOT_SET
        },
        resolvedKeyboards = table,
    )

    private fun typeEntry(portrait: String) = KeyboardTypeResolved(
        portrait = resolved(portrait),
        portraitResolution = LayoutResolution.RESOLVED,
    )

    // ---------- 状态机 ----------

    @Test
    fun initialTypeByInputMode() {
        val state = KeyboardTypeState()
        assertEquals("pinyin", state.initialType(asciiMode = false))
        assertEquals("alphabetic", state.initialType(asciiMode = true))
    }

    @Test
    fun switchTracksLastAndPrimaryMainOnly() {
        val state = KeyboardTypeState()
        state.establish("pinyin")
        assertEquals("pinyin", state.current)
        assertEquals("pinyin", state.primary)
        assertNull(state.last)
        assertTrue(state.switchTo("emojis"))
        assertEquals("emojis", state.current)
        assertEquals("pinyin", state.last)
        // 工具类型不改 primary：returnPrimary 恒回主键盘。
        assertEquals("pinyin", state.primary)
        assertTrue(state.switchTo("alphabetic"))
        assertEquals("alphabetic", state.primary)
        assertEquals("emojis", state.last)
        // 同类型切换是空操作：last 不动。
        assertFalse(state.switchTo("alphabetic"))
        assertEquals("emojis", state.last)
    }

    @Test
    fun establishOnlyWhenUnset() {
        val state = KeyboardTypeState()
        state.establish("pinyin")
        state.establish("alphabetic")
        assertEquals("pinyin", state.current)
        assertEquals("pinyin", state.primary)
    }

    @Test
    fun toggleMainTargetRoundTrips() {
        val state = KeyboardTypeState()
        state.establish("pinyin")
        assertEquals("alphabetic", state.toggleMainTarget())
        state.switchTo("alphabetic")
        assertEquals("pinyin", state.toggleMainTarget())
        state.switchTo("emojis")
        // 工具类型下中英键回主键盘（primary），不是回上一类型。
        assertEquals("alphabetic", state.toggleMainTarget())
        val fresh = KeyboardTypeState()
        assertEquals("pinyin", fresh.toggleMainTarget())
    }

    @Test
    fun resetClearsAll() {
        val state = KeyboardTypeState()
        state.establish("pinyin")
        state.switchTo("emojis")
        state.reset()
        assertNull(state.current)
        assertNull(state.primary)
        assertNull(state.last)
    }

    // ---------- 重挂决策：先解析再比较（round-1 修复的类型化推广） ----------

    @Test
    fun customSkinCollapseReopenReattachesUnderTypeModel() {
        // 自定义皮肤：收起时当前名 custom:pin-lay、类型 pinyin、方案仍 T9。
        // 旧 bug 是拿方案名 T9 直接比 custom:pin-lay 恒不等；类型
        // 解析后目标仍是 custom:pin-lay → 重显，不切换、不空白。
        val snap = snapshot(table = mapOf("pinyin" to typeEntry("pin-lay")))
        assertEquals(
            ReattachAction.Reshow,
            decideReattach(snap, "custom:pin-lay", "pinyin", "T9", landscape = false),
        )
    }

    @Test
    fun toolTypeCollapseReopenReattaches() {
        val snap = snapshot(table = mapOf("emojis" to typeEntry("emo-lay")))
        assertEquals(
            ReattachAction.Reshow,
            decideReattach(snap, "custom:emo-lay", "emojis", "T9", landscape = false),
        )
        // 内置工具键盘同理：类型解析目标即当前名。
        assertEquals(
            ReattachAction.Reshow,
            decideReattach(snapshot(), "Emoji", "emojis", "T9", landscape = false),
        )
    }

    @Test
    fun nonMainTypeNeverOverridesFieldKeyboardOnReattach() {
        // 名／类型脱节：文本字段的 startInput 已把名切回 T9、类型却
        // 留在数字板上；重挂时当前名即字段键盘，不得再按陈旧板类型
        // 翻回数字键盘（重启／换框后首键盘变数字即此路径）。
        assertEquals(
            ReattachAction.Reshow,
            decideReattach(snapshot(), "T9", "numeric", "T9", landscape = false),
        )
        assertEquals(
            ReattachAction.Reshow,
            decideReattach(snapshot(), "T9", "symbolic", "T9", landscape = false),
        )
        assertEquals(
            ReattachAction.Reshow,
            decideReattach(snapshot(), "T9", "emojis", "T9", landscape = false),
        )
        assertEquals(
            ReattachAction.Reshow,
            decideReattach(snapshot(), "T9", "my-board", "T9", landscape = false),
        )
        // 数字字段同理：当前名已是数字键盘时不被符号板翻走。
        assertEquals(
            ReattachAction.Reshow,
            decideReattach(snapshot(), "Number", "symbolic", "T9", landscape = false),
        )
        // 主类型不受此限：中英语言选择照常按类型切。
        assertEquals(
            ReattachAction.SwitchType("alphabetic"),
            decideReattach(snapshot(), "T9", "alphabetic", "T9", landscape = false),
        )
    }

    @Test
    fun firstAttachRawFiltersSurvivingNonMainType() {
        // 存活主类型沿用。
        assertEquals("pinyin", resolveFirstAttachRaw("pinyin", "alphabetic", false))
        assertEquals("alphabetic", resolveFirstAttachRaw("alphabetic", null, false))
        // 存活非主类型不得成为首挂键盘：回落存过主类型／初始主类型。
        assertEquals("alphabetic", resolveFirstAttachRaw("numeric", "alphabetic", false))
        assertEquals("pinyin", resolveFirstAttachRaw("symbolic", null, false))
        assertEquals("alphabetic", resolveFirstAttachRaw("emojis", null, true))
        // 存活空时走原有存过／初始口径。
        assertEquals("alphabetic", resolveFirstAttachRaw(null, "alphabetic", false))
        assertEquals("pinyin", resolveFirstAttachRaw(null, "numeric", false))
        assertEquals("pinyin", resolveFirstAttachRaw(null, null, false))
        assertEquals("alphabetic", resolveFirstAttachRaw(null, null, true))
    }

    @Test
    fun builtinBehaviorUnchangedWithoutType() {
        // 无类型（旧路径）：内置键盘重挂仍重显。
        assertEquals(
            ReattachAction.Reshow,
            decideReattach(snapshot(), "T9", null, "T9", landscape = false),
        )
        // 旧 round-1 情形：自定义皮肤无类型时，按名解析后比较亦重显。
        val legacy = snapshot(legacy = resolved("old-lay"))
        assertEquals(
            ReattachAction.Reshow,
            decideReattach(legacy, "custom:old-lay", null, "T9", landscape = false),
        )
    }

    @Test
    fun mismatchSwitchesByTypeOrByName() {
        val snap = snapshot(table = mapOf("pinyin" to typeEntry("pin-lay")))
        // 收起期间方案布局已变（T9→Qwerty）但类型解析目标仍是
        // 表项布局：当前名是旧方案名时按类型切（目标计算与
        // switchKeyboardType 同源，不会撞去重早返）。
        assertEquals(
            ReattachAction.SwitchType("pinyin"),
            decideReattach(snap, "Qwerty", "pinyin", "Qwerty", landscape = false),
        )
        assertEquals(
            ReattachAction.SwitchName("Qwerty"),
            decideReattach(snapshot(), "T9", null, "Qwerty", landscape = false),
        )
    }
}
