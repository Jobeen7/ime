package com.jobeen.ime.base.util

import android.text.InputType
import android.view.inputmethod.EditorInfo
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 密码框不学习（施工单 9 特征 1）回归测试：
 * 禁学习判定是全部学习写路径的唯一决策点——选词偏好 recordClick、
 * 搭配学习 learnCollocation、预测／前文读取，以及 native 用户词典
 * 自学习（`__no_personalized_learning` 运行时开关，见
 * patches/librime-no-personalized-learning.patch）都以它为准。
 * 本表锁死判定口径：密码类输入框与声明
 * IME_FLAG_NO_PERSONALIZED_LEARNING 的框一律禁学习，普通框不受影响。
 *
 * 机制本身自 2026-10-01（b564aed4）已在位；本测试是防回退的
 * 回归锁，不是新行为的验收。
 */
class InputFieldPolicyTest {

    private fun text(variation: Int): Int =
        InputType.TYPE_CLASS_TEXT or variation

    private fun number(variation: Int): Int =
        InputType.TYPE_CLASS_NUMBER or variation

    // ---- isPasswordField ----

    @Test
    fun textPasswordIsPasswordField() {
        assertTrue(InputFieldPolicy.isPasswordField(text(InputType.TYPE_TEXT_VARIATION_PASSWORD)))
    }

    @Test
    fun textVisiblePasswordIsPasswordField() {
        assertTrue(InputFieldPolicy.isPasswordField(text(InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD)))
    }

    @Test
    fun textWebPasswordIsPasswordField() {
        assertTrue(InputFieldPolicy.isPasswordField(text(InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD)))
    }

    @Test
    fun numberPinIsPasswordField() {
        assertTrue(InputFieldPolicy.isPasswordField(number(InputType.TYPE_NUMBER_VARIATION_PASSWORD)))
    }

    @Test
    fun normalTextIsNotPasswordField() {
        assertFalse(InputFieldPolicy.isPasswordField(text(InputType.TYPE_TEXT_VARIATION_NORMAL)))
    }

    @Test
    fun emailAddressIsNotPasswordField() {
        assertFalse(InputFieldPolicy.isPasswordField(text(InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS)))
    }

    @Test
    fun normalNumberIsNotPasswordField() {
        assertFalse(InputFieldPolicy.isPasswordField(number(InputType.TYPE_NUMBER_VARIATION_NORMAL)))
    }

    @Test
    fun phoneClassIsNotPasswordField() {
        assertFalse(InputFieldPolicy.isPasswordField(InputType.TYPE_CLASS_PHONE))
    }

    @Test
    fun zeroInputTypeIsNotPasswordField() {
        assertFalse(InputFieldPolicy.isPasswordField(0))
    }

    // ---- isNoPersonalizedLearning ----

    @Test
    fun textPasswordDisablesLearning() {
        assertTrue(
            InputFieldPolicy.isNoPersonalizedLearning(
                text(InputType.TYPE_TEXT_VARIATION_PASSWORD),
                0,
            ),
        )
    }

    @Test
    fun visiblePasswordDisablesLearning() {
        assertTrue(
            InputFieldPolicy.isNoPersonalizedLearning(
                text(InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD),
                0,
            ),
        )
    }

    @Test
    fun webPasswordDisablesLearning() {
        assertTrue(
            InputFieldPolicy.isNoPersonalizedLearning(
                text(InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD),
                0,
            ),
        )
    }

    @Test
    fun numberPinDisablesLearning() {
        assertTrue(
            InputFieldPolicy.isNoPersonalizedLearning(
                number(InputType.TYPE_NUMBER_VARIATION_PASSWORD),
                0,
            ),
        )
    }

    @Test
    fun normalFieldWithoutFlagKeepsLearning() {
        assertFalse(
            InputFieldPolicy.isNoPersonalizedLearning(
                text(InputType.TYPE_TEXT_VARIATION_NORMAL),
                0,
            ),
        )
    }

    @Test
    fun normalFieldWithNoLearningFlagDisablesLearning() {
        assertTrue(
            InputFieldPolicy.isNoPersonalizedLearning(
                text(InputType.TYPE_TEXT_VARIATION_NORMAL),
                EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING,
            ),
        )
    }

    @Test
    fun passwordFieldWithFlagStillDisablesLearning() {
        assertTrue(
            InputFieldPolicy.isNoPersonalizedLearning(
                text(InputType.TYPE_TEXT_VARIATION_PASSWORD),
                EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING,
            ),
        )
    }

    @Test
    fun unrelatedImeOptionsDoNotDisableLearning() {
        assertFalse(
            InputFieldPolicy.isNoPersonalizedLearning(
                text(InputType.TYPE_TEXT_VARIATION_NORMAL),
                EditorInfo.IME_ACTION_DONE,
            ),
        )
    }

    // ---- EditorInfo 薄包装（空 EditorInfo 不禁学习） ----

    @Test
    fun nullEditorInfoDoesNotDisableLearning() {
        assertFalse(InputFieldPolicy.isNoPersonalizedLearning(null))
        assertFalse(InputFieldPolicy.isPasswordField(null))
    }
}
