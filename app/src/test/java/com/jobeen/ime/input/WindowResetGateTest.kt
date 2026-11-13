// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.input

import android.text.InputType
import com.jobeen.ime.base.util.InputFieldPolicy
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 新窗口复位门（WO32，LatinIME 公式 `!restarting || inputTypeChanged`）
 * 单测：门在 onStartInput 算、pending 由 onStartInputView 消费。
 * 均不依赖 Android 框架（inputType 只取整型值）。
 *
 * 微信切聊天框情形：两会话共用同一布局与字段 id，inputType 也
 * 全同——旧四项比对门恒判不变；新门只看 restarting，新会话必为
 * false，故每次切框都复位。
 */
class WindowResetGateTest {

    // 微信聊天框：TYPE_CLASS_TEXT | TYPE_TEXT_VARIATION_NORMAL
    private val chatInputType =
        InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_NORMAL
    private val numberInputType =
        InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_NORMAL
    private val emailInputType =
        InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS

    @Test
    fun wechatSwitch_restartingFalse_resets() {
        // 情形 1：微信切聊天框——restarting=false → 门真 → 复位跑。
        val gate = WindowResetGate()
        assertTrue(gate.noteStartInput(restarting = false, inputType = chatInputType))
        assertTrue(gate.consumePending())
    }

    @Test
    fun wechatSwitch_sameInputTypeStillResets() {
        // 情形 1 的关键：第二聊天框与第一框 inputType 全同，
        // 旧四项门会漏判；新门只看 restarting，照样复位。
        val gate = WindowResetGate()
        assertTrue(gate.noteStartInput(restarting = false, inputType = chatInputType))
        assertTrue(gate.consumePending())
        assertTrue(gate.noteStartInput(restarting = false, inputType = chatInputType))
        assertTrue(gate.consumePending())
    }

    @Test
    fun sameFieldRestart_noReset() {
        // 情形 2：restarting=true＋同 inputType → 不复位。
        val gate = WindowResetGate()
        assertTrue(gate.noteStartInput(restarting = false, inputType = chatInputType))
        assertTrue(gate.consumePending())
        assertFalse(gate.noteStartInput(restarting = true, inputType = chatInputType))
        assertFalse(gate.consumePending())
    }

    @Test
    fun inputTypeChangedWhileRestarting_resets() {
        // 情形 3：restarting=true 但 inputType 变了（LatinIME
        // 公式第二项，例：restart 型逻辑换框）→ 复位。
        val gate = WindowResetGate()
        assertTrue(gate.noteStartInput(restarting = false, inputType = chatInputType))
        assertTrue(gate.consumePending())
        assertTrue(gate.noteStartInput(restarting = true, inputType = numberInputType))
        assertTrue(gate.consumePending())
    }

    @Test
    fun accountField_stillExempt() {
        // 情形 4：账号框仍豁免——门真，但 KSM.startInput 的
        // !isAccountField 守门；普通文本框不豁免。
        val gate = WindowResetGate()
        assertTrue(gate.noteStartInput(restarting = false, inputType = emailInputType))
        assertTrue(InputFieldPolicy.isAccountField(emailInputType))
        assertFalse(InputFieldPolicy.isAccountField(chatInputType))
        // 门真＋账号框 → 不复位；门真＋普通框 → 复位。
        assertFalse(gate.consumePending() && !InputFieldPolicy.isAccountField(emailInputType))
        assertTrue(gate.noteStartInput(restarting = false, inputType = chatInputType))
        assertTrue(gate.consumePending() && !InputFieldPolicy.isAccountField(chatInputType))
    }

    @Test
    fun rotation_firstStartInput_noReset() {
        // 情形 5：转屏——转屏第一次 onStartInput 的 restarting=true
        // 且 inputType 与上次应用值相同 → 不误复位。
        val gate = WindowResetGate()
        assertTrue(gate.noteStartInput(restarting = false, inputType = chatInputType))
        assertTrue(gate.consumePending())
        // 转屏重投：restarting=true，同 inputType。
        assertFalse(gate.noteStartInput(restarting = true, inputType = chatInputType))
        assertFalse(gate.consumePending())
    }

    @Test
    fun rotation_doesNotClearPendingFromEarlierSwitch() {
        // 转屏不能把先前换框攒下的 pending 抹掉：切框时键盘未显示
        // （pending 待消费），随后转屏重投 restarting=true 同类型，
        // 下次弹出仍应复位。
        val gate = WindowResetGate()
        assertTrue(gate.noteStartInput(restarting = false, inputType = chatInputType))
        assertFalse(gate.noteStartInput(restarting = true, inputType = chatInputType))
        assertTrue(gate.consumePending())
    }

    @Test
    fun hideAndReopenSameField_noNewStartInput_noReset() {
        // 同框隐藏再点开：框架不产生新 startInput，pending 保持
        // false，onStartInputView 消费不到复位。
        val gate = WindowResetGate()
        assertTrue(gate.noteStartInput(restarting = false, inputType = chatInputType))
        assertTrue(gate.consumePending())
        // 无新的 noteStartInput：再次消费仍为 false。
        assertFalse(gate.consumePending())
    }

    @Test
    fun finishInput_clearsStalePending() {
        // 会话结束清零残留 pending：切框后视图未弹出即结束会话，
        // 下一会话同框重启不再误消费。
        val gate = WindowResetGate()
        assertTrue(gate.noteStartInput(restarting = false, inputType = chatInputType))
        gate.clearPending()
        assertFalse(gate.consumePending())
    }

    @Test
    fun firstStart_unknownAppliedInputType_resetsOnRestart() {
        // 进程内首次输入开始即 restarting=true 且无上次应用值时，
        // 按变化处理（LatinIME 的 mInputEditorInfo 为空口径一致）。
        val gate = WindowResetGate()
        assertTrue(gate.noteStartInput(restarting = true, inputType = chatInputType))
        assertTrue(gate.consumePending())
    }

    @Test
    fun nullInputTypeWhileRestarting_noReset() {
        // inputType 缺失时无法比对，restarting=true → 不复位；
        // restarting=false 仍复位（新会话）。
        val gate = WindowResetGate()
        assertFalse(gate.noteStartInput(restarting = true, inputType = null))
        assertFalse(gate.consumePending())
        assertTrue(gate.noteStartInput(restarting = false, inputType = null))
        assertTrue(gate.consumePending())
    }
}
