package com.jobeen.ime.input.keyboard.window

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 类型纯状态机与重挂决策：状态转移、主/上一跟踪、先解析再比较。 */
class KeyboardTypeStateTest {


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
    fun customSkinCollapseReopenSwitchesToSchemaDefaultType() {
        // 自定义皮肤名 custom:pin-lay 在快照恒 null 后无类型可查：
        // pinyin 类型目标即方案默认 T9，当前名不是 T9 → 按类型切。
        assertEquals(
            ReattachAction.SwitchType("pinyin"),
            decideReattach("custom:pin-lay", "pinyin", "T9"),
        )
    }

    @Test
    fun toolTypeCollapseReopenReattaches() {
        assertEquals(
            ReattachAction.Reshow,
            decideReattach("custom:emo-lay", "emojis", "T9"),
        )
        // 内置工具键盘同理：类型解析目标即当前名。
        assertEquals(
            ReattachAction.Reshow,
            decideReattach("Emoji", "emojis", "T9"),
        )
    }

    @Test
    fun nonMainTypeNeverOverridesFieldKeyboardOnReattach() {
        // 名／类型脱节：文本字段的 startInput 已把名切回 T9、类型却
        // 留在数字板上；重挂时当前名即字段键盘，不得再按陈旧板类型
        // 翻回数字键盘（重启／换框后首键盘变数字即此路径）。
        assertEquals(ReattachAction.Reshow, decideReattach("T9", "numeric", "T9"))
        assertEquals(ReattachAction.Reshow, decideReattach("T9", "symbolic", "T9"))
        assertEquals(ReattachAction.Reshow, decideReattach("T9", "emojis", "T9"))
        assertEquals(ReattachAction.Reshow, decideReattach("T9", "my-board", "T9"))
        // 数字字段同理：当前名已是数字键盘时不被符号板翻走。
        assertEquals(ReattachAction.Reshow, decideReattach("Number", "symbolic", "T9"))
        // 主类型不受此限：中英语言选择照常按类型切。
        assertEquals(
            ReattachAction.SwitchType("alphabetic"),
            decideReattach("T9", "alphabetic", "T9"),
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
            decideReattach("T9", null, "T9"),
        )
        // 自定义皮肤名在快照恒 null 后无类型可查：目标即方案默认 T9 → 按名切。
        assertEquals(
            ReattachAction.SwitchName("T9"),
            decideReattach("custom:old-lay", null, "T9"),
        )
    }

    @Test
    fun mismatchSwitchesByTypeOrByName() {
        // 快照恒 null 后 pinyin 类型目标即方案默认 Qwerty，与当前名一致 → 重显。
        assertEquals(
            ReattachAction.Reshow,
            decideReattach("Qwerty", "pinyin", "Qwerty"),
        )
        assertEquals(
            ReattachAction.SwitchName("Qwerty"),
            decideReattach("T9", null, "Qwerty"),
        )
    }
}
