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
        // WO52 5.2：无锚点（无关键词／网址）的独立数字串不再抽取——
        // 取数口径改为“先找关键词命中，再在附近取 4–6 位数字”。
        assertNull(VerificationCodeExtractor.extract("复制这个 123456 粘贴"))
        assertEquals("1234", VerificationCodeExtractor.extract("取件码 1234"))
        assertNull(VerificationCodeExtractor.extract("会议 2026-10-29 下午三点"))
        assertNull(VerificationCodeExtractor.extract("电话 13812345678 请联系"))
        assertNull(VerificationCodeExtractor.extract("版本 1.2345 已发布"))
        assertNull(VerificationCodeExtractor.extract("只有 123 三位数"))
        assertNull(VerificationCodeExtractor.extract("九位 123456789 太长"))
        assertNull(VerificationCodeExtractor.extract(""))
    }

    /** WO52 5.2：简繁关键词全表＋拉丁关键词（verification/passcode/code/otp/pin）。 */
    @Test
    fun keywordVariantsTraditionalAndLatin() {
        assertEquals("654321", VerificationCodeExtractor.extract("您的驗證碼是654321"))
        assertEquals("654321", VerificationCodeExtractor.extract("确认码：654321"))
        assertEquals("654321", VerificationCodeExtractor.extract("激活码 654321"))
        assertEquals("654321", VerificationCodeExtractor.extract("安全码 654321"))
        assertEquals("654321", VerificationCodeExtractor.extract("识别码 654321"))
        assertEquals("654321", VerificationCodeExtractor.extract("动态密码是654321"))
        assertEquals("654321", VerificationCodeExtractor.extract("验证密码 654321"))
        assertEquals("654321", VerificationCodeExtractor.extract("一次性密码 654321"))
        assertEquals("654321", VerificationCodeExtractor.extract("短信码 654321"))
        assertEquals("654321", VerificationCodeExtractor.extract("提货码 654321"))
        assertEquals("654321", VerificationCodeExtractor.extract("Your passcode is 654321"))
        assertEquals("654321", VerificationCodeExtractor.extract("Your OTP is 654321"))
        assertEquals("654321", VerificationCodeExtractor.extract("Your PIN is 654321"))
        assertEquals("654321", VerificationCodeExtractor.extract("verification 654321"))
        // 拉丁 code/otp/pin 须有词边界：barcode 内含 code 不锚定
        assertNull(VerificationCodeExtractor.extract("barcode 654321"))
    }

    /** WO52 5.2：按与网址/关键词的距离取最近者。 */
    @Test
    fun nearestToAnchorWins() {
        assertEquals(
            "123456",
            VerificationCodeExtractor.extract("订单5678，验证码123456"),
        )
        assertEquals(
            "1234",
            VerificationCodeExtractor.extract("验证码1234，订单5678"),
        )
        assertEquals(
            "123456",
            VerificationCodeExtractor.extract("详见 https://example.com/ 验证码 123456，订单 5678"),
        )
    }

    /** WO52 5.2：URL 排除——网址内的数字不是验证码。 */
    @Test
    fun urlNumbersExcluded() {
        assertNull(VerificationCodeExtractor.extract("点 https://example.com/123456 验证"))
        assertEquals(
            "123456",
            VerificationCodeExtractor.extract("验证码 123456 https://example.com/654321"),
        )
    }

    /** WO52 5.2：数字 4–8 位 → 4–6 位是收窄，7–8 位数字串不再命中。 */
    @Test
    fun fourToSixDigitsOnly() {
        assertNull(VerificationCodeExtractor.extract("验证码 1234567"))
        assertNull(VerificationCodeExtractor.extract("验证码 12345678"))
        assertNull(VerificationCodeExtractor.extract("验证码 123"))
        assertEquals("123456", VerificationCodeExtractor.extract("验证码 123456"))
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
