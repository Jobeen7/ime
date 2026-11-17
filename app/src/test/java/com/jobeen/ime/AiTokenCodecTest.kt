// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime

import com.jobeen.ime.data.ai.AiTokenCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AI token 落盘形态换算单测（2.4）：
 * - 读侧：空→空；旧明文直通（v20 前存量可读、不丢配置）；密文解密
 *   失败→空（绝不把密文当 token 发出去）。
 * - 写侧：空→空；已是密文幂等；主机上 Keystore 是桩、加密返回
 *   null（调用方不得回退明文，见 AiRepository.encryptForWrite）。
 * 真机往返（加密→落盘→解密→原值）见仪器测试 DatabaseMigrationTest
 * 的 19→20 一例（模拟器 Keystore 可用）。
 */
class AiTokenCodecTest {

    @Test
    fun decrypt_empty_isEmpty() {
        assertEquals("", AiTokenCodec.decryptFromStorage(""))
    }

    @Test
    fun decrypt_legacyPlaintext_passesThrough() {
        // v20 前的存量明文：读侧必须原样可读，丢了就是丢配置
        assertEquals("sk-legacy-token", AiTokenCodec.decryptFromStorage("sk-legacy-token"))
    }

    @Test
    fun decrypt_corruptCiphertext_isEmpty_notCiphertext() {
        // 前缀是密文但 Base64／解密失败：返回空，绝不把密文当 token 用
        assertEquals("", AiTokenCodec.decryptFromStorage("enc:v1:!!!not-base64!!!"))
        assertEquals("", AiTokenCodec.decryptFromStorage("enc:v1:AAAA"))
    }

    @Test
    fun encrypt_empty_isEmpty() {
        assertEquals("", AiTokenCodec.encryptForStorage(""))
    }

    @Test
    fun encrypt_alreadyEncrypted_isIdempotent() {
        val stored = "enc:v1:AAAAAAAAAAAAAAAAAAAAAAAAAAA="
        assertEquals(stored, AiTokenCodec.encryptForStorage(stored))
        assertTrue(stored.startsWith("enc:v1:"))
    }

    @Test
    fun encrypt_plaintext_returnsNullOnHost_keystoreUnavailable() {
        // 主机单测无 AndroidKeyStore：加密返回 null，调用方据此不落盘
        // （不回退明文）；真机／模拟器上 Keystore 可用，返回密文。
        assertNull(AiTokenCodec.encryptForStorage("sk-live-secret"))
    }
}
