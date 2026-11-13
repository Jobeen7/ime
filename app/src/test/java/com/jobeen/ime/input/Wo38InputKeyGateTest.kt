// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.input

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WO38 E1-9 会话门控单测：门控本身是纯纪元比对（纪元源默认走
 * SherpaSpeechClient 的 EditorEpoch；本文件一律注入可控纪元，
 * 不碰语音与框架）。
 *
 * 覆盖（与任务一一对应）：
 * - 同 epoch 放行；
 * - 旧 epoch 拒绝并记原因（原因＋新旧纪元进拒绝回调）；
 * - epoch 推进后旧键失效；
 * - 合法键行为不变（高频放行路径零误拒、零拒绝日志）。
 *
 * 不覆盖：KeyActionListener 的接线（需 InputMethodService，
 * 本仓单测无 Robolectric，见 InputKeyGate KDoc）；长按连发的
 * 按下戳（接线点在禁碰文件，见 InputKeyGate KDoc 的已知缺口）。
 */
class Wo38InputKeyGateTest {

    /** 可控纪元源＋拒绝记录器。 */
    private class Harness(var epoch: Long = 0L) {
        val rejections = mutableListOf<Pair<InputKeyGateResult.Deny, String>>()
        val gate = InputKeyGate(
            epochProvider = { epoch },
            onReject = { deny, label -> rejections += deny to label },
        )
    }

    @Test
    fun sameEpoch_allows() {
        val h = Harness(epoch = 5L)
        val captured = h.gate.capture()
        assertEquals(5L, captured)
        assertEquals(InputKeyGateResult.Allow, h.gate.check(captured, "key"))
        assertTrue(h.gate.isCurrent(captured))
        assertTrue(h.rejections.isEmpty())
    }

    @Test
    fun staleEpoch_deniesWithReasonAndLogsOnce() {
        val h = Harness(epoch = 7L)
        val captured = h.gate.capture()
        h.epoch = 8L // 换框／输入结束推进纪元。
        val result = h.gate.check(captured, "run_script:test")
        val deny = result as InputKeyGateResult.Deny
        assertEquals(InputKeyRejectReason.STALE_EPOCH, deny.reason)
        assertEquals(7L, deny.capturedEpoch)
        assertEquals(8L, deny.currentEpoch)
        // 拒绝原因进日志（注入的记录器即默认 DiagLog 槽的替身）：
        // 恰一次、原因与新旧纪元齐全、带动作标签。
        assertEquals(1, h.rejections.size)
        assertEquals(deny, h.rejections.single().first)
        assertEquals("run_script:test", h.rejections.single().second)
        assertFalse(h.gate.isCurrent(captured))
    }

    @Test
    fun epochAdvancesAfterCapture_oldKeyInvalid() {
        val h = Harness(epoch = 0L)
        val oldKey = h.gate.capture()
        h.epoch = 1L
        // 旧键失效；同纪元新键放行——推进只杀旧键，不杀新键。
        assertTrue(h.gate.check(oldKey, "key") is InputKeyGateResult.Deny)
        val newKey = h.gate.capture()
        assertEquals(InputKeyGateResult.Allow, h.gate.check(newKey, "key"))
        assertEquals(1, h.rejections.size)
    }

    @Test
    fun multipleEpochAdvances_onlyLatestCaptureSurvives() {
        val h = Harness(epoch = 3L)
        val first = h.gate.capture()
        h.epoch = 4L
        val second = h.gate.capture()
        h.epoch = 5L
        assertTrue(h.gate.check(first, "key") is InputKeyGateResult.Deny)
        assertTrue(h.gate.check(second, "key") is InputKeyGateResult.Deny)
        assertEquals(InputKeyGateResult.Allow, h.gate.check(h.gate.capture(), "key"))
        assertEquals(2, h.rejections.size)
    }

    @Test
    fun legalKeys_highFrequency_allAllow_noRejectionLog() {
        // 热路径：合法键（同纪元）高频复检必须全部放行、零拒绝日志。
        val h = Harness(epoch = 42L)
        repeat(10_000) {
            assertEquals(InputKeyGateResult.Allow, h.gate.check(h.gate.capture(), "key"))
        }
        assertTrue(h.rejections.isEmpty())
    }

    @Test
    fun isCurrent_pureCheck_doesNotLog() {
        // 不记日志的纯复检：旧纪元返回 false，但不产生拒绝日志——
        // 调用方只想要布尔值时不污染日志。
        val h = Harness(epoch = 9L)
        val captured = h.gate.capture()
        h.epoch = 10L
        assertFalse(h.gate.isCurrent(captured))
        assertTrue(h.rejections.isEmpty())
    }

    @Test
    fun captureAndCheck_doNotAdvanceEpoch() {
        // 门控只读纪元、永不推进：推进权只在 IMS 的输入结束／换框投递点。
        val h = Harness(epoch = 2L)
        val captured = h.gate.capture()
        h.gate.check(captured, "key")
        h.gate.isCurrent(captured)
        assertEquals(2L, h.epoch)
        assertEquals(2L, h.gate.capture())
    }

    @Test
    fun defaultRejectSink_staleCheck_returnsDenyWithoutCrash() {
        // 生产默认拒绝槽（DiagLog，未 init 时 no-op）：JVM 单测里
        // 调用 check 拒绝路径不崩溃、仍返回 Deny。
        val epoch = longArrayOf(1L)
        val gate = InputKeyGate(epochProvider = { epoch[0] })
        val captured = gate.capture()
        epoch[0] = 2L
        val result = gate.check(captured, "key")
        assertTrue(result is InputKeyGateResult.Deny)
        assertEquals(InputKeyRejectReason.STALE_EPOCH, (result as InputKeyGateResult.Deny).reason)
    }
}
