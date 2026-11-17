// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.data.ai

import com.jobeen.ime.base.util.SecretStore

/**
 * AI 服务商 token 的落盘形态（2.4）。
 *
 * `AIProvider.apiToken` 存的是**落盘形态**：空串，或
 * [SecretStore]（Android Keystore，AES-GCM，单别名）加密后的
 * `enc:v1:…` 密文。内存形态（明文）只在 [AiRepository] 的读写边界
 * 与 [AiChatClient] 的请求构造里出现，绝不进备份文件（导出侧仍由
 * [AiBackupCodec] 清空）、日志与诊断导出。
 *
 * 选用 `base.util.SecretStore` 而不用云语音的 `KeystoreSecretStore`：
 * 后者是每插件每字段独立别名、需 `PrefsStore`、put 失败抛异常，是给
 * 云语音插件的形状；前者正是 `UserDictPrefs` 存 WebDAV 密码的先例，
 * 且其 KDoc 明写调用方用 [SecretStore.isEncrypted] 区分存量明文做
 * 一次性迁移——本文件的两个函数就是该迁移的落盘/内存换算。
 */
object AiTokenCodec {

    /**
     * 内存形态（明文）→ 落盘形态：空 stays 空；已是密文原样返回
     * （幂等，不二次加密）；Keystore 不可用时返回 null——调用方
     * 不得回退明文落盘（见 [SecretStore] 的 KDoc）。
     */
    fun encryptForStorage(plain: String): String? {
        if (plain.isEmpty()) return ""
        if (SecretStore.isEncrypted(plain)) return plain
        return SecretStore.encrypt(plain)
    }

    /**
     * 落盘形态 → 内存形态（明文）：空→空；旧明文（无 `enc:v1:` 前缀）
     * 原样返回（v20 前的存量，调用方负责加密回写）；密文解密失败
     * （密钥丢失/损坏）返回 ""——绝不把密文当 token 发出去。
     */
    fun decryptFromStorage(stored: String): String {
        if (stored.isEmpty()) return ""
        if (!SecretStore.isEncrypted(stored)) return stored
        return SecretStore.decrypt(stored).orEmpty()
    }
}
