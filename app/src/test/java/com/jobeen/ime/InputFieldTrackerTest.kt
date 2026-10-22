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

    // WebView 形态：核心四项全同，区分只剩扩展字段
    private val webFieldA = fieldA.copy(autofillId = "1001/7", hintText = "搜索")
    private val webFieldOtherHint = webFieldA.copy(hintText = "评论")
    private val webFieldOtherAutofill = webFieldA.copy(autofillId = "1001/8")

    // 强信号全缺形态：核心扩展三项皆无，区分只剩文案类
    private val hintOnlyA = fieldA.copy(hintText = "搜索")
    private val hintOnlyOtherHint = hintOnlyA.copy(hintText = "评论")
    private val labelOnlyA = fieldA.copy(label = "搜索")
    private val labelOnlyOther = labelOnlyA.copy(label = "评论")

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
    fun hintDifferenceAdvancesWhenPrimarySignalsAbsent() {
        // §4.4 的兜底形态：同一 WebView 内换 <input>，核心四项
        // 全同、核心扩展（autofillId/fieldName/privateImeOptions）
        // 两侧全缺，区分只剩文案类——第十七批口径下 hintText 作
        // 兜底信号参与判变，仍须判成换框，否则 R3 的语音作废在
        // 这类宿主里漏过（第十六批的对应旧用例
        // restartWithOnlyHintChangedAdvances 带 autofillId 夹具，
        // 在新口径下属「核心在场」形态、改判见下一条，故本条换
        // 强信号全缺夹具续测其 §4.4 本意）
        val tracker = InputFieldTracker()
        tracker.track(hintOnlyA)
        assertTrue(tracker.track(hintOnlyOtherHint))
        // label 同口径
        val labelTracker = InputFieldTracker()
        labelTracker.track(labelOnlyA)
        assertTrue(labelTracker.track(labelOnlyOther))
    }

    @Test
    fun copyDifferencesDoNotAdvanceWhenPrimarySignalPresent() {
        // 第十七批降权：核心扩展在场（autofillId 两侧有值）时，
        // hintText/label 即使两侧有值且不同也不单独触发判变——
        // 同一框在两次喂入间改写动态提示文案不得误判换框、误杀
        // 在途语音（audit16 (g) 反向残余的收口）
        val tracker = InputFieldTracker()
        tracker.track(webFieldA)
        assertFalse(tracker.track(webFieldOtherHint))
        val labelTracker = InputFieldTracker()
        labelTracker.track(webFieldA.copy(label = "搜索"))
        assertFalse(labelTracker.track(webFieldA.copy(label = "评论")))
    }

    @Test
    fun restartWithOnlyFieldNameChangedAdvances() {
        // fieldName 属核心扩展：仅它两侧有值且不同即判换框，
        // 不受文案类降权影响
        val tracker = InputFieldTracker()
        tracker.track(webFieldA.copy(fieldName = "search"))
        assertTrue(tracker.track(webFieldA.copy(fieldName = "comment")))
    }

    @Test
    fun restartWithOnlyAutofillIdChangedAdvances() {
        // autofillId 是扩展字段里区分力最强的一项：仅它不同
        // （虚拟子 id 不同）同样判换框
        val tracker = InputFieldTracker()
        tracker.track(webFieldA)
        assertTrue(tracker.track(webFieldOtherAutofill))
    }

    @Test
    fun sameCoreAndExtendedFieldsAllEqualDoesNotAdvance() {
        // 核心四项与五项扩展字段全等（同框 hide/show 或同框
        // restarting 的两次喂入）——恒判同框，不因扩展字段在场
        // 而误判（S1 口径不回退）
        val tracker = InputFieldTracker()
        tracker.track(webFieldA)
        assertFalse(tracker.track(webFieldA.copy()))
        assertEquals(0, advancesFor(tracker, listOf(webFieldA, webFieldA)))
    }

    @Test
    fun extendedFieldPresentOnOnlyOneSideDoesNotAdvance() {
        // 扩展字段的判变口径是「两侧都有值且不同」：同框两次喂
        // 入之间宿主只在一侧给出 hint（null↔有值抖动）不算换
        // 框，避免同框重启被误判、误杀在途语音/联想
        val tracker = InputFieldTracker()
        tracker.track(fieldA) // 扩展字段全缺
        assertFalse(tracker.track(fieldA.copy(hintText = "搜索")))
        val tracker2 = InputFieldTracker()
        tracker2.track(webFieldA)
        assertFalse(tracker2.track(webFieldA.copy(hintText = null, autofillId = null)))
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
