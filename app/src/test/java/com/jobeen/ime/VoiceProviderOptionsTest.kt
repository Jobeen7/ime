// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime

import com.jobeen.ime.base.speech.cloud.AuthHeaderDecl
import com.jobeen.ime.base.speech.cloud.PluginMeta
import com.jobeen.ime.ui.screen.VoiceProviderHint
import com.jobeen.ime.ui.screen.VoiceProviderStatus
import com.jobeen.ime.ui.screen.VoiceProviderTag
import com.jobeen.ime.ui.screen.partitionProviderDialogRows
import com.jobeen.ime.ui.screen.pluginSecretsMissingIds
import com.jobeen.ime.ui.screen.voiceProviderHint
import com.jobeen.ime.ui.screen.voiceProviderRows
import com.jobeen.ime.ui.screen.voiceProviderTags
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 语音服务商选项纯逻辑：行构成、密钥缺失判定、提示优先级。 */
class VoiceProviderOptionsTest {

    private fun plugin(id: String, authFields: List<String> = emptyList()): PluginMeta {
        return PluginMeta(
            id = id,
            version = "1",
            hosts = listOf("example.com"),
            capabilities = listOf("voice.asr"),
            authHeaders = authFields.map { AuthHeaderDecl(header = "X-$it", field = it) },
        )
    }

    @Test
    fun rows_localOnly_selectedAndReady() {
        val rows = voiceProviderRows(
            modelReady = true,
            plugins = emptyList(),
            preferredId = null,
            secretsMissingIds = emptySet(),
        )
        assertEquals(1, rows.size)
        assertEquals("local", rows[0].key)
        assertTrue(rows[0].isLocal)
        assertTrue(rows[0].selected)
        assertEquals(VoiceProviderStatus.LOCAL_READY, rows[0].status)
    }

    @Test
    fun rows_modelNotReady_localNotDownloaded() {
        val rows = voiceProviderRows(
            modelReady = false,
            plugins = emptyList(),
            preferredId = null,
            secretsMissingIds = emptySet(),
        )
        assertEquals(VoiceProviderStatus.LOCAL_NOT_DOWNLOADED, rows[0].status)
    }

    @Test
    fun rows_withPlugins_localFirstThenPluginsInOrder() {
        val rows = voiceProviderRows(
            modelReady = true,
            plugins = listOf(plugin("builtin.qwen"), plugin("user.custom")),
            preferredId = "user.custom",
            secretsMissingIds = setOf("builtin.qwen"),
        )
        assertEquals(listOf("local", "builtin.qwen", "user.custom"), rows.map { it.key })
        assertTrue(!rows[0].selected)
        assertTrue(!rows[1].selected)
        assertEquals(VoiceProviderStatus.PLUGIN_SECRETS_MISSING, rows[1].status)
        assertTrue(rows[2].selected)
        assertEquals(VoiceProviderStatus.PLUGIN_READY, rows[2].status)
    }

    @Test
    fun dialogPartition_onlineFirstLocalSecondOrderPreserved() {
        val rows = voiceProviderRows(
            modelReady = true,
            plugins = listOf(plugin("builtin.qwen"), plugin("user.custom")),
            preferredId = null,
            secretsMissingIds = emptySet(),
        )
        val (online, local) = partitionProviderDialogRows(rows)
        assertEquals(listOf("builtin.qwen", "user.custom"), online.map { it.key })
        assertEquals(listOf("local"), local.map { it.key })
    }

    @Test
    fun dialogPartition_localOnly_emptyOnline() {
        val rows = voiceProviderRows(
            modelReady = false,
            plugins = emptyList(),
            preferredId = null,
            secretsMissingIds = emptySet(),
        )
        val (online, local) = partitionProviderDialogRows(rows)
        assertTrue(online.isEmpty())
        assertEquals(listOf("local"), local.map { it.key })
    }

    @Test
    fun rows_stalePreferred_noRowSelected() {
        // 首选指向已不在列表的插件（被删／过期）：不把本地行误标为已选，
        // 提示侧由 PLUGIN_UNAVAILABLE 覆盖。
        val rows = voiceProviderRows(
            modelReady = true,
            plugins = listOf(plugin("builtin.qwen")),
            preferredId = "builtin.gone",
            secretsMissingIds = emptySet(),
        )
        assertTrue(rows.none { it.selected })
    }

    @Test
    fun tags_localBuiltinAndUser() {
        // 本地：流式离线模型；内置插件：在线流式；用户插件：在线自定义
        // （传输方式未在元数据声明，故不标流式）；方言／准确率等标签无数据来源，不出。
        assertEquals(
            listOf(VoiceProviderTag.LOCAL, VoiceProviderTag.STREAMING),
            voiceProviderTags(isLocal = true, isBuiltin = false),
        )
        assertEquals(
            listOf(VoiceProviderTag.ONLINE, VoiceProviderTag.STREAMING, VoiceProviderTag.BUILTIN),
            voiceProviderTags(isLocal = false, isBuiltin = true),
        )
        assertEquals(
            listOf(VoiceProviderTag.ONLINE, VoiceProviderTag.CUSTOM),
            voiceProviderTags(isLocal = false, isBuiltin = false),
        )
    }

    @Test
    fun secretsMissingIds_onlyPluginsWithMissingField() {
        val plugins = listOf(
            plugin("p.full", authFields = listOf("k1")),
            plugin("p.partial", authFields = listOf("k1", "k2")),
            plugin("p.noauth"),
        )
        val missing = pluginSecretsMissingIds(plugins) { _, field -> field == "k1" }
        assertEquals(setOf("p.partial"), missing)
    }

    @Test
    fun hint_noUsableEngine_whenLocalUnselectedAndModelMissing() {
        assertEquals(
            VoiceProviderHint.NO_USABLE_ENGINE,
            voiceProviderHint(
                preferredId = null,
                modelReady = false,
                optIn = false,
                secretsMissingIds = emptySet(),
                preferredInstalled = true,
            ),
        )
    }

    @Test
    fun hint_stalePreferred_beatsOptInOff() {
        assertEquals(
            VoiceProviderHint.PLUGIN_UNAVAILABLE,
            voiceProviderHint(
                preferredId = "builtin.gone",
                modelReady = true,
                optIn = false,
                secretsMissingIds = emptySet(),
                preferredInstalled = false,
            ),
        )
    }

    @Test
    fun hint_optInOff_beatsSecretsMissing() {
        assertEquals(
            VoiceProviderHint.CLOUD_NOT_ENABLED,
            voiceProviderHint(
                preferredId = "builtin.qwen",
                modelReady = true,
                optIn = false,
                secretsMissingIds = setOf("builtin.qwen"),
                preferredInstalled = true,
            ),
        )
    }

    @Test
    fun hint_secretsMissing_whenSelectedPluginLacksKeys() {
        assertEquals(
            VoiceProviderHint.PLUGIN_SECRETS_MISSING,
            voiceProviderHint(
                preferredId = "builtin.qwen",
                modelReady = true,
                optIn = true,
                secretsMissingIds = setOf("builtin.qwen"),
                preferredInstalled = true,
            ),
        )
    }

    @Test
    fun hint_none_whenLocalReadyOrCloudFullyUsable() {
        assertEquals(
            VoiceProviderHint.NONE,
            voiceProviderHint(
                preferredId = null,
                modelReady = true,
                optIn = false,
                secretsMissingIds = emptySet(),
                preferredInstalled = true,
            ),
        )
        assertEquals(
            VoiceProviderHint.NONE,
            voiceProviderHint(
                preferredId = "builtin.qwen",
                modelReady = false,
                optIn = true,
                secretsMissingIds = emptySet(),
                preferredInstalled = true,
            ),
        )
    }
}
