// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.input

import android.text.InputType
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * LatinIME 公式盲点（WO36 I-M2／V-中1）：restarting 型逻辑切换且
 * inputType 四项全同时，复位门（[shouldResetWindowOnStartInput]）
 * 与四项身份比对（[isInputFieldChanged]）两门均判未变；云端会话
 * 停不停的消费方并集（KeyboardWindowView.onStartInput：
 * `fieldChanged || identityChanged`）同样判不变。
 *
 * WO36 候选信号核查（无可靠新信号，盲区记残余，不硬造）：
 * - EditorInfo 扩展字段（autofillId、fieldName、hintText、label、
 *   privateImeOptions）：十六/十七批已试过、按 D6 拆除——WebView
 *   里常为 null，动态文案变会误判换框、误杀在途语音；再加是把已
 *   知回归请回来，收益未经证实。字段本身存在（本机 android-36
 *   SDK javap 核实：fieldName／hintText／privateImeOptions／
 *   getAutofillId 均在 EditorInfo 上），存在 ≠ 可区分。
 * - InputConnection 身份：每次 startInput 都重新绑定，同框重开
 *   也换新实例，不能当信号（任务给定约束）。EditorInfo 对象同一
 *   性同理（旧实现已证有同一问题）。引擎 predictionFieldGen 走
 *   同一四项比对，同盲区。
 * - initialSelStart/End、onUpdateSelection：光标态不是字段身份，
 *   同框内也会变；空框两边都是 0/0，分不出。
 * - 框架回调：restarting 型换框不经任何 FINISH 回调（doStartInput
 *   只在 !restarting 时先 doFinishInput）；onStartInputView 的
 *   restarting 在同框收起再开时为 false，不可信。
 * - 门与锁存的并集已覆盖其余全部情形：门补 !restarting、锁存补
 *   四项变；盲区是两路同假的唯一情形（本文件即钉此状态空间）。
 *
 * 云端会话隐藏到重弹存活是真机验收，不在本批代码内。
 * 微信聊天框：TYPE_CLASS_TEXT | TYPE_TEXT_VARIATION_NORMAL，
 * 各会话共用同一布局与字段 id（WO12 诊断）。
 */
class Wo36FieldGateTest {

    private val chatInputType =
        InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_NORMAL
    private val numberInputType =
        InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_NORMAL

    private fun wechatKey(
        fieldId: Int = 12345,
        inputType: Int = chatInputType,
        imeOptions: Int = 0,
        packageName: String? = "com.tencent.mm",
    ) = InputFieldKey(
        packageName = packageName,
        fieldId = fieldId,
        inputType = inputType,
        imeOptions = imeOptions,
    )

    /**
     * 消费方并集（与 KeyboardWindowView.onStartInput 同条件）：
     * 复位门值或锁存值任一为真即换框、停云端会话。
     */
    private fun unionStopsCloudVoice(gateChanged: Boolean, identityChanged: Boolean) =
        gateChanged || identityChanged

    // ---- 盲区：restarting＋四项全同，两门同假 ----

    @Test
    fun blindSpot_restartingIdenticalFour_bothGatesFalse() {
        val previous = wechatKey()
        val current = wechatKey()
        // 复位门：restarting=true＋同 inputType → 假。
        val gate = WindowResetGate()
        gate.noteStartInput(restarting = false, inputType = chatInputType)
        gate.consumePending()
        assertFalse(gate.noteStartInput(restarting = true, inputType = chatInputType))
        // 四项比对：全同 → 假。
        assertFalse(isInputFieldChanged(previous, current))
        // 锁存：记入假 → 消费假。
        val latch = FieldIdentityChangeLatch()
        latch.note(isInputFieldChanged(previous, current))
        assertFalse(latch.consume())
        // 并集：两路同假 → 不停云端会话（盲区，记残余）。
        assertFalse(unionStopsCloudVoice(false, false))
    }

    @Test
    fun blindSpot_unionTruthTable_otherCellsStop() {
        // 并集真值表的其余三格都停：盲区是两路同假的唯一情形。
        assertTrue(unionStopsCloudVoice(true, false))
        assertTrue(unionStopsCloudVoice(false, true))
        assertTrue(unionStopsCloudVoice(true, true))
    }

    // ---- 门补 !restarting：微信切聊天框（新会话、四项全同） ----

    @Test
    fun wechatSwitch_newSession_gateCatchesDespiteIdenticalFour() {
        val previous = wechatKey()
        val current = wechatKey()
        assertFalse(isInputFieldChanged(previous, current))
        val gate = WindowResetGate()
        assertTrue(gate.noteStartInput(restarting = false, inputType = chatInputType))
        assertTrue(unionStopsCloudVoice(true, false))
    }

    // ---- 锁存补四项变：restarting 型切换但某项变了 ----

    @Test
    fun restartingWithInputTypeChanged_bothCatch() {
        val previous = wechatKey()
        val current = wechatKey(inputType = numberInputType)
        assertTrue(isInputFieldChanged(previous, current))
        val gate = WindowResetGate()
        gate.noteStartInput(restarting = false, inputType = chatInputType)
        gate.consumePending()
        assertTrue(gate.noteStartInput(restarting = true, inputType = numberInputType))
        assertTrue(unionStopsCloudVoice(true, true))
    }

    @Test
    fun restartingWithFieldIdChanged_latchCatchesGateMisses() {
        val previous = wechatKey(fieldId = 111)
        val current = wechatKey(fieldId = 222)
        assertTrue(isInputFieldChanged(previous, current))
        val gate = WindowResetGate()
        gate.noteStartInput(restarting = false, inputType = chatInputType)
        gate.consumePending()
        // 同 inputType 时门为假，锁存一路仍停得到。
        assertFalse(gate.noteStartInput(restarting = true, inputType = chatInputType))
        val latch = FieldIdentityChangeLatch()
        latch.note(isInputFieldChanged(previous, current))
        assertTrue(unionStopsCloudVoice(false, latch.consume()))
    }

    @Test
    fun restartingWithPackageChanged_latchCatches() {
        val previous = wechatKey(packageName = "com.tencent.mm")
        val current = wechatKey(packageName = "com.example.other")
        assertTrue(isInputFieldChanged(previous, current))
        val latch = FieldIdentityChangeLatch()
        latch.note(isInputFieldChanged(previous, current))
        assertTrue(unionStopsCloudVoice(false, latch.consume()))
    }

    @Test
    fun restartingWithImeOptionsChanged_latchCatches() {
        val previous = wechatKey(imeOptions = 0)
        val current = wechatKey(imeOptions = 0x00000002)
        assertTrue(isInputFieldChanged(previous, current))
        val latch = FieldIdentityChangeLatch()
        latch.note(isInputFieldChanged(previous, current))
        assertTrue(unionStopsCloudVoice(false, latch.consume()))
    }

    // ---- 不误判侧：同框重启与空快照 ----

    @Test
    fun sameFrameRestart_unionFalse_noFalsePositive() {
        // 同框重启（restarting=true、同 inputType、四项全同）：
        // 两门同假 → 云端会话不断、在途语音不误杀。盲区与同框
        // 重启在此不可区分——正是无信号可加的原因。
        val previous = wechatKey()
        val current = wechatKey()
        val gate = WindowResetGate()
        gate.noteStartInput(restarting = false, inputType = chatInputType)
        gate.consumePending()
        assertFalse(gate.noteStartInput(restarting = true, inputType = chatInputType))
        assertFalse(isInputFieldChanged(previous, current))
        assertFalse(unionStopsCloudVoice(false, false))
    }

    @Test
    fun missingSnapshotNeverJudgesChange() {
        val key = wechatKey()
        assertFalse(isInputFieldChanged(null, key))
        assertFalse(isInputFieldChanged(key, null))
        assertFalse(isInputFieldChanged(null, null))
        assertNull(InputFieldKey.of(null))
    }

    @Test
    fun firstInput_gateResetsButFourItemSilent() {
        // 进程内首次输入即 restarting=true 且无上次应用值时，门按
        // 变化处理（LatinIME 空历史口径），四项比对因缺快照沉默；
        // 并集仍为真，不属盲区。
        val gate = WindowResetGate()
        assertTrue(gate.noteStartInput(restarting = true, inputType = chatInputType))
        assertFalse(isInputFieldChanged(null, wechatKey()))
        assertTrue(unionStopsCloudVoice(true, false))
    }
}
