package com.jobeen.ime

import com.jobeen.ime.input.InputFieldKey
import com.jobeen.ime.input.isInputFieldChanged
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * restarting 重启时的换框判定规格：restarting=true 只说明宿主在
 * 同一 View 上重启输入，关键字段（核心四项：包名/fieldId/输入
 * 类型/imeOptions，口径见 InputFieldKey；第十六批的扩展字段已
 * 于清点 D6 整层拆除）任一不同即实为换框，必须推进预测代次作
 * 废旧框联想；字段全同才是同框重启，不推进（在途联想仍属本框）。
 */
class PredictionFieldKeyTest {

    private val base = InputFieldKey(
        packageName = "com.example.app",
        fieldId = 1001,
        inputType = 0x00000001,
        imeOptions = 0x40000000,
    )

    @Test
    fun identicalFieldsAreSameFieldRestart() {
        assertFalse(isInputFieldChanged(base, base.copy()))
    }

    @Test
    fun webViewFieldSwitchIsFieldChange() {
        // WebView 内不同 <input> 切换焦点：同一宿主包、同一 View，
        // 只有 fieldId 变——必须判换框
        assertTrue(isInputFieldChanged(base, base.copy(fieldId = 1002)))
    }

    @Test
    fun differentPackageIsFieldChange() {
        assertTrue(isInputFieldChanged(base, base.copy(packageName = "com.other.app")))
    }

    @Test
    fun differentInputTypeIsFieldChange() {
        // 如普通文本框切到密码变体（inputType 的 variation 位变化）
        assertTrue(isInputFieldChanged(base, base.copy(inputType = 0x00000081)))
    }

    @Test
    fun differentImeOptionsIsFieldChange() {
        assertTrue(isInputFieldChanged(base, base.copy(imeOptions = 0x00000004)))
    }

    @Test
    fun missingPreviousSnapshotIsNotFieldChange() {
        // 首次启动还没有上一个框的快照：无从比对，不判换框
        assertFalse(isInputFieldChanged(null, base))
    }

    @Test
    fun missingCurrentSnapshotIsNotFieldChange() {
        assertFalse(isInputFieldChanged(base, null))
    }
}
