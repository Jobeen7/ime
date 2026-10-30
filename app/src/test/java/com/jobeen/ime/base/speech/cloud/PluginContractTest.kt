// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.base.speech.cloud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 插件契约纯逻辑：id／域名／能力／采样率／设置／代附头校验与差异。 */
class PluginContractTest {

    private fun validMeta(): PluginMeta = PluginMeta(
        id = "example.asr",
        version = "1.0",
        hosts = listOf("asr.example.com"),
        capabilities = listOf("voice.asr"),
        settings = listOf(
            PluginSettingDecl("api_key", SettingType.SECRET, "密钥"),
        ),
        authHeaders = listOf(
            AuthHeaderDecl("Authorization", "api_key", "Bearer "),
        ),
    )

    @Test
    fun validate_acceptsValidMeta() {
        assertNull(PluginContract.validate(validMeta()))
    }

    @Test
    fun validate_rejectsBadIds() {
        assertEquals(
            PluginImportError.INVALID_ID,
            PluginContract.validate(validMeta().copy(id = "-bad")),
        )
        assertEquals(
            PluginImportError.INVALID_ID,
            PluginContract.validate(validMeta().copy(id = "a".repeat(65))),
        )
        assertEquals(
            PluginImportError.INVALID_ID,
            PluginContract.validate(validMeta().copy(id = "has space")),
        )
    }

    @Test
    fun validate_rejectsReservedIds() {
        assertEquals(
            PluginImportError.RESERVED_ID,
            PluginContract.validate(validMeta().copy(id = "offline")),
        )
        assertEquals(
            PluginImportError.RESERVED_ID,
            PluginContract.validate(validMeta().copy(id = "builtin.demo")),
        )
    }

    @Test
    fun validate_rejectsBadHosts() {
        assertEquals(
            PluginImportError.NO_HOSTS,
            PluginContract.validate(validMeta().copy(hosts = emptyList())),
        )
        assertEquals(
            PluginImportError.INVALID_HOSTS,
            PluginContract.validate(validMeta().copy(hosts = listOf("*.example.com"))),
        )
        assertEquals(
            PluginImportError.INVALID_HOSTS,
            PluginContract.validate(validMeta().copy(hosts = listOf("asr.example.com:8443"))),
        )
        assertEquals(
            PluginImportError.INVALID_HOSTS,
            PluginContract.validate(validMeta().copy(hosts = listOf("https://asr.example.com"))),
        )
        assertEquals(
            PluginImportError.INVALID_HOSTS,
            PluginContract.validate(validMeta().copy(hosts = listOf("192.168.1.1"))),
        )
        assertEquals(
            PluginImportError.INVALID_HOSTS,
            PluginContract.validate(
                validMeta().copy(hosts = listOf("asr.example.com", "ASR.EXAMPLE.COM")),
            ),
        )
    }

    @Test
    fun validate_requiresVoiceCapability() {
        assertEquals(
            PluginImportError.NO_VOICE_FEATURE,
            PluginContract.validate(validMeta().copy(capabilities = listOf("other"))),
        )
    }

    @Test
    fun validate_rejectsUnsupportedSampleRate() {
        assertEquals(
            PluginImportError.UNSUPPORTED_SAMPLE_RATE,
            PluginContract.validate(validMeta().copy(sampleRate = 8000)),
        )
    }

    @Test
    fun validate_rejectsBadSettings() {
        assertEquals(
            PluginImportError.INVALID_SETTINGS,
            PluginContract.validate(
                validMeta().copy(
                    settings = listOf(PluginSettingDecl("Bad Key", SettingType.TEXT, "名")),
                ),
            ),
        )
        assertEquals(
            PluginImportError.INVALID_SETTINGS,
            PluginContract.validate(
                validMeta().copy(
                    settings = listOf(
                        PluginSettingDecl("k", SettingType.SELECT, "选", options = emptyList()),
                    ),
                ),
            ),
        )
        assertEquals(
            PluginImportError.INVALID_SETTINGS,
            PluginContract.validate(
                validMeta().copy(
                    settings = listOf(
                        PluginSettingDecl("dup", SettingType.TEXT, "一"),
                        PluginSettingDecl("dup", SettingType.TEXT, "二"),
                    ),
                ),
            ),
        )
    }

    @Test
    fun validate_rejectsBadAuth() {
        // 代附字段必须指向 SECRET 设置项。
        assertEquals(
            PluginImportError.INVALID_AUTH,
            PluginContract.validate(
                validMeta().copy(
                    settings = listOf(PluginSettingDecl("name", SettingType.TEXT, "名")),
                    authHeaders = listOf(AuthHeaderDecl("X-Api-Key", "name")),
                ),
            ),
        )
        // 禁转头不得声明为代附头。
        assertEquals(
            PluginImportError.INVALID_AUTH,
            PluginContract.validate(
                validMeta().copy(
                    authHeaders = listOf(AuthHeaderDecl("Cookie", "api_key")),
                ),
            ),
        )
        // 同一头不得声明两次。
        assertEquals(
            PluginImportError.INVALID_AUTH,
            PluginContract.validate(
                validMeta().copy(
                    authHeaders = listOf(
                        AuthHeaderDecl("X-Api-Key", "api_key"),
                        AuthHeaderDecl("x-api-key", "api_key"),
                    ),
                ),
            ),
        )
    }

    @Test
    fun diff_identicalIsSilent() {
        val meta = validMeta()
        assertTrue(PluginContract.diff(meta, meta).isIdentical)
    }

    @Test
    fun diff_reportsGrowth() {
        val old = validMeta()
        val new = old.copy(
            hosts = listOf("asr.example.com", "extra.example.com"),
            capabilities = listOf("voice.asr", "voice.extra"),
        )
        val diff = PluginContract.diff(old, new)
        assertFalse(diff.isIdentical)
        assertEquals(listOf("extra.example.com"), diff.hostsAdded)
        assertEquals(listOf("voice.extra"), diff.capsAdded)
        assertTrue(diff.hostsRemoved.isEmpty())
    }
}
