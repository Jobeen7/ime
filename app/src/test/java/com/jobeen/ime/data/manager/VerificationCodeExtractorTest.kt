// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.data.manager

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 验证码抽取＋提示条状态（重点候选 8-5）：锚定优先、日期／手机号不误抽。 */
class VerificationCodeExtractorTest {

    @Test
    fun keywordAnchored() {
        assertEquals("123456", VerificationCodeExtractor.extract("您的验证码是123456，请勿泄露"))
        assertEquals("123456", VerificationCodeExtractor.extract("验证码： 123 456"))
        assertEquals("1234", VerificationCodeExtractor.extract("验证码1234，订单5678"))
        assertEquals("987654", VerificationCodeExtractor.extract("Your verification code is 987654."))
        assertEquals("112233", VerificationCodeExtractor.extract("动态码为 112233"))
    }

    @Test
    fun standalone() {
        assertEquals("123456", VerificationCodeExtractor.extract("复制这个 123456 粘贴"))
        assertEquals("1234", VerificationCodeExtractor.extract("取件码 1234"))
        assertNull(VerificationCodeExtractor.extract("会议 2026-10-29 下午三点"))
        assertNull(VerificationCodeExtractor.extract("电话 13812345678 请联系"))
        assertNull(VerificationCodeExtractor.extract("版本 1.2345 已发布"))
        assertNull(VerificationCodeExtractor.extract("只有 123 三位数"))
        assertNull(VerificationCodeExtractor.extract("九位 123456789 太长"))
        assertNull(VerificationCodeExtractor.extract(""))
    }

    @Test
    fun keywordBeatsEarlierStandalone() {
        assertEquals(
            "123456",
            VerificationCodeExtractor.extract("订单2026，验证码123456"),
        )
    }

    @Test
    fun promptState_codePreferred() {
        val state = ClipboardPromptState()
        assertNull(state.displayText)
        state.onClipboardText("您的验证码是123456，请勿泄露")
        assertEquals("123456", state.latestCode)
        assertEquals("123456", state.displayText)
        state.onClipboardText("普通文本无数字")
        assertNull(state.latestCode)
        assertEquals("普通文本无数字", state.displayText)
        state.clear()
        assertNull(state.displayText)
    }
}
