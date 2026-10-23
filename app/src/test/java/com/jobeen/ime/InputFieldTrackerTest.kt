package com.jobeen.ime

import com.jobeen.ime.base.speech.EditorEpoch
import com.jobeen.ime.input.InputFieldKey
import com.jobeen.ime.input.InputFieldTracker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 字段身份跟踪（R3）的序列规格：IMS 在 onStartInput /
 * onStartInputView 两入口把每次输入开始的字段身份喂给跟踪器，
 * 关键字段变化（实为逻辑换框，无论 restarting 真假）才判变、
 * 投递语音作废推进纪元；同框 hide/show、同框 restarting 与首
 * 次快照恒不判变——S1 收窄口径（同框不误杀待结算整句）不回退。
 *
 * 事件以「喂入的字段身份」建模：onStartInput（无 View）与
 * onStartInputView 喂的是同一种身份，序列测试不区分入口，只
 * 区分身份流。
 */
class InputFieldTrackerTest {

    private val fieldA = InputFieldKey(
        packageName = "com.example.web",
        fieldId = 1001,
        inputType = 0x00000001,
        imeOptions = 0x40000000,
    )
    private val fieldB = fieldA.copy(fieldId = 1002)

    // 清点 D6 测试处置：扩展字段（autofillId/fieldName/hintText/
    // label/privateImeOptions）整层拆除后，依赖它们的夹具与用例
    // 一并删除——hintDifferenceAdvancesWhenPrimarySignalsAbsent、
    // copyDifferencesDoNotAdvanceWhenPrimarySignalPresent、
    // restartWithOnlyFieldNameChangedAdvances、
    // restartWithOnlyAutofillIdChangedAdvances、
    // extendedFieldPresentOnOnlyOneSideDoesNotAdvance 共 5 条；
    // sameCoreAndExtendedFieldsAllEqualDoesNotAdvance 改为只用核
    // 心四项夹具续测（见 sameCoreFieldsAllEqualDoesNotAdvance）。

    /** 一串喂入后判变（应推进纪元）的总次数。 */
    private fun advancesFor(tracker: InputFieldTracker, feeds: List<InputFieldKey?>): Int =
        feeds.count { tracker.track(it) }

    @Test
    fun firstSnapshotNeverAdvances() {
        // 进程内首次输入开始：没有上一个框可比，不判换框
        val tracker = InputFieldTracker()
        assertEquals(0, advancesFor(tracker, listOf(fieldA)))
    }

    @Test
    fun hideThenShowSameFieldDoesNotAdvance() {
        // 同框收起再打开（S1/N13 场景）：hide 只走
        // onFinishInputView(false)，show 的 onStartInput /
        // onStartInputView 喂入的身份与收起前全同——恒不判变
        val tracker = InputFieldTracker()
        tracker.track(fieldA) // 首次进入 A
        val feeds = listOf(fieldA, fieldA) // show 时两入口各喂一次
        assertEquals(0, advancesFor(tracker, feeds))
    }

    @Test
    fun sameFieldRestartDoesNotAdvance() {
        // 同框 restarting=true（restartInput 同一逻辑框）：身份
        // 不变，不推进
        val tracker = InputFieldTracker()
        tracker.track(fieldA)
        assertFalse(tracker.track(fieldA.copy()))
    }

    @Test
    fun restartWithChangedFieldIdAdvances() {
        // R3 本体：restarting=true 但 fieldId 已变（WebView 内换
        // <input>），无 FINISH 回调——必须判变推进
        val tracker = InputFieldTracker()
        tracker.track(fieldA)
        assertTrue(tracker.track(fieldB))
    }

    @Test
    fun fieldChangeAtStartInputWithoutViewAdvances() {
        // 键盘隐藏中换框：只有 onStartInput（无 View 回调）先到
        // ——在该入口喂入新身份即判变，不等视图弹出
        val tracker = InputFieldTracker()
        tracker.track(fieldA) // A 的 onStartInput
        tracker.track(fieldA) // A 的 onStartInputView（身份同上，不判变）
        assertTrue(tracker.track(fieldB)) // B 的 onStartInput（无 View）
    }

    @Test
    fun changeIsReportedOnceAcrossBothEntries() {
        // 同一次换框两入口各喂一次：第一次判变、第二次身份已滚
        // 动更新，不重复判变（纪元至多多推一次的口径由投递侧冪
        // 等承担，跟踪器本身只报一次）
        val tracker = InputFieldTracker()
        tracker.track(fieldA)
        val feeds = listOf(fieldB, fieldB)
        assertEquals(1, advancesFor(tracker, feeds))
    }

    @Test
    fun missingSnapshotFeedKeepsPreviousIdentity() {
        // EditorInfo 为空的一次喂入不判变、也不冲掉已有快照：
        // 其后同框身份仍判同框，其后真换框仍判变
        val tracker = InputFieldTracker()
        tracker.track(fieldA)
        assertFalse(tracker.track(null))
        assertFalse(tracker.track(fieldA))
        assertTrue(tracker.track(fieldB))
    }

    @Test
    fun sameCoreFieldsAllEqualDoesNotAdvance() {
        // 核心四项全等（同框 hide/show 或同框 restarting 的两次
        // 喂入）——恒判同框（S1 口径不回退）。原用例
        // sameCoreAndExtendedFieldsAllEqualDoesNotAdvance 以核心
        // ＋扩展全等为夹具，清点 D6 后改为核心四项夹具续测同义
        val tracker = InputFieldTracker()
        tracker.track(fieldA)
        assertFalse(tracker.track(fieldA.copy()))
        assertEquals(0, advancesFor(tracker, listOf(fieldA, fieldA)))
    }

    @Test
    fun trackerDrivenAdvanceStalesCapturedEpoch() {
        // 与语音纪元的接线形态：在途会话捕获纪元后，跟踪判变
        // 驱动一次推进，旧捕获判过期（迟到结算被挡）；身份不
        // 变时不推进、旧捕获仍现行（同框结算放行）
        val epoch = EditorEpoch()
        val tracker = InputFieldTracker()
        tracker.track(fieldA)
        val captured = epoch.current()
        if (tracker.track(fieldA.copy())) epoch.advance()
        assertTrue(epoch.isCurrent(captured))
        if (tracker.track(fieldB)) epoch.advance()
        assertFalse(epoch.isCurrent(captured))
    }
}
