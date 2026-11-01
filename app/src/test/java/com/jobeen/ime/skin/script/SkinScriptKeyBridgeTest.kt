package com.jobeen.ime.skin.script

import com.jobeen.ime.input.keyboard.key.KeyboardAction
import com.jobeen.ime.skin.model.SkinAction
import com.jobeen.ime.skin.model.WhitelistAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 按键脚本桥接：返回值转动作规则（纯函数）＋按键执行结果
 * （缺失／禁用／无处理器／失败／无可转动作一律无动作、不崩）。
 */
class SkinScriptKeyBridgeTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun runner(): Pair<SkinScriptStore, SkinScriptRunner> {
        val store = SkinScriptStore(tmp.root)
        return store to SkinScriptRunner(store)
    }

    // ---------- 返回值转动作（纯函数） ----------

    @Test
    fun resolveReturnAction_noValueActionName_becomesCommand() {
        assertEquals(
            SkinAction.Command(WhitelistAction.DELETE),
            SkinScriptKeyBridge.resolveReturnAction("delete"),
        )
        assertEquals(
            SkinAction.Command(WhitelistAction.HASH_TRADITIONAL),
            SkinScriptKeyBridge.resolveReturnAction("#简繁切换"),
        )
        assertEquals(
            SkinAction.Command(WhitelistAction.OPEN_SCRIPT),
            SkinScriptKeyBridge.resolveReturnAction("open_script"),
        )
        assertEquals(
            SkinAction.Command(WhitelistAction.HASH_TOGGLE_SCRIPT_VIEW),
            SkinScriptKeyBridge.resolveReturnAction("#toggleScriptView"),
        )
    }

    @Test
    fun resolveReturnAction_valueActionsNeverBecomeActions() {
        // 带值动作没有值通道：返回值无法补值，一律不成立；
        // run_script 不成立也断了返回值递归触发另一脚本的路。
        assertNull(SkinScriptKeyBridge.resolveReturnAction("run_script"))
        assertNull(SkinScriptKeyBridge.resolveReturnAction("switch_layout"))
        assertNull(SkinScriptKeyBridge.resolveReturnAction("open_url"))
        assertNull(SkinScriptKeyBridge.resolveReturnAction("commit_direct"))
    }

    @Test
    fun resolveReturnAction_voiceInputNeverTriggeredByReturnValue() {
        // 录音副作用只能来自显式长按，不能由脚本返回值绕过
        // 槽位规则触发。
        assertNull(SkinScriptKeyBridge.resolveReturnAction("voice_input"))
    }

    @Test
    fun resolveReturnAction_unknownOrBlank_neverBecomesAction() {
        assertNull(SkinScriptKeyBridge.resolveReturnAction("nope"))
        assertNull(SkinScriptKeyBridge.resolveReturnAction(""))
        assertNull(SkinScriptKeyBridge.resolveReturnAction(" Delete "))
    }

    // ---------- 按键执行 ----------

    @Test
    fun runForKey_returningActionName_dispatchesIt() {
        val (store, runner) = runner()
        store.add("a.one", "A", "function onEvent(ctx) { return 'delete'; }")
        val outcome = SkinScriptKeyBridge.runForKey(runner, "a.one")
        assertEquals(
            SkinScriptKeyBridge.KeyRunOutcome.Dispatch(KeyboardAction.BackspaceAction),
            outcome,
        )
    }

    @Test
    fun runForKey_missingOrDisabledOrHandlerless_neverRuns() {
        val (store, runner) = runner()
        assertEquals(
            SkinScriptKeyBridge.KeyRunOutcome.NoAction(
                SkinScriptKeyBridge.NoActionReason.NOT_FOUND,
            ),
            SkinScriptKeyBridge.runForKey(runner, "nope"),
        )
        store.add("d.one", "D", "function onEvent(ctx) { return 'delete'; }")
        assertTrue(store.setEnabled("d.one", false))
        assertEquals(
            SkinScriptKeyBridge.KeyRunOutcome.NoAction(
                SkinScriptKeyBridge.NoActionReason.DISABLED,
            ),
            SkinScriptKeyBridge.runForKey(runner, "d.one"),
        )
        store.add("h.one", "H", "var x = 1;")
        assertEquals(
            SkinScriptKeyBridge.KeyRunOutcome.NoAction(
                SkinScriptKeyBridge.NoActionReason.NO_HANDLER,
            ),
            SkinScriptKeyBridge.runForKey(runner, "h.one"),
        )
    }

    @Test
    fun runForKey_failedScript_isNoActionNotCrash() {
        val (store, runner) = runner()
        store.add("f.one", "F", "function onEvent(ctx) { throw new Error('boom'); }")
        assertEquals(
            SkinScriptKeyBridge.KeyRunOutcome.NoAction(
                SkinScriptKeyBridge.NoActionReason.FAILED,
            ),
            SkinScriptKeyBridge.runForKey(runner, "f.one"),
        )
    }

    @Test
    fun runForKey_nonActionReturnValue_isNoAction() {
        val (store, runner) = runner()
        store.add("n.one", "N", "function onEvent(ctx) { return 'hello'; }")
        assertEquals(
            SkinScriptKeyBridge.KeyRunOutcome.NoAction(
                SkinScriptKeyBridge.NoActionReason.NO_RETURN_ACTION,
            ),
            SkinScriptKeyBridge.runForKey(runner, "n.one"),
        )
        store.add("v.one", "V", "function onEvent(ctx) { return 'voice_input'; }")
        assertEquals(
            SkinScriptKeyBridge.KeyRunOutcome.NoAction(
                SkinScriptKeyBridge.NoActionReason.NO_RETURN_ACTION,
            ),
            SkinScriptKeyBridge.runForKey(runner, "v.one"),
        )
    }
}
