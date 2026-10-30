// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.base.speech.cloud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** 内存偏好（单测替身，生产用 SharedPrefsStore）。 */
class MapPrefsStore : PrefsStore {
    private val strings = HashMap<String, String>()
    private val booleans = HashMap<String, Boolean>()

    override fun getString(key: String, default: String?): String? = strings[key] ?: default
    override fun putString(key: String, value: String?) {
        if (value == null) strings.remove(key) else strings[key] = value
    }

    override fun getBoolean(key: String, default: Boolean): Boolean = booleans[key] ?: default
    override fun putBoolean(key: String, value: Boolean) {
        booleans[key] = value
    }

    override fun remove(key: String) {
        strings.remove(key)
        booleans.remove(key)
    }

    override fun keys(): Set<String> = strings.keys + booleans.keys
}

/** 内存密钥（单测替身；生产用 KeystoreSecretStore，真机验往返）。 */
class MapSecretStore : SecretStore {
    private val values = HashMap<String, String>()
    override fun put(pluginId: String, field: String, value: String) {
        values["$pluginId/$field"] = value
    }

    override fun get(pluginId: String, field: String): String? = values["$pluginId/$field"]

    override fun clear(pluginId: String, field: String) {
        values.remove("$pluginId/$field")
    }
    override fun deleteForPlugin(pluginId: String) {
        values.keys.removeAll { it.startsWith("$pluginId/") }
    }

    override fun has(pluginId: String, field: String): Boolean =
        values.containsKey("$pluginId/$field")
}

/** 插件库：导入确认门、静默升级、差异、卸载。 */
class PluginStoreTest {

    private fun tempDir(): File = Files.createTempDirectory("plugin-store-test").toFile()

    private fun script(
        id: String = "example.asr",
        hosts: String = "'asr.example.com'",
        rate: Int = 16000,
    ): ByteArray = """
        register({
          id: '$id',
          version: '1.0',
          hosts: [$hosts],
          features: [{ type: 'voice.asr', sampleRate: $rate }],
          settings: [{ key: 'api_key', type: 'secret', label: '密钥' }],
          auth: [{ header: 'Authorization', field: 'api_key', prefix: 'Bearer ' }]
        });
        function start(ctx) {}
        function audio(ctx) { cb.partial('hi'); }
        function stop(ctx) {}
    """.trimIndent().toByteArray(Charsets.UTF_8)

    @Test
    fun import_newPluginNeedsConfirmation_thenInstalls() {
        val store = PluginStore(tempDir(), MapPrefsStore())
        val first = store.importScript(script())
        val pending = first as? PluginStore.ImportResult.NeedsConfirmation
            ?: error("expected NeedsConfirmation, got $first")
        assertNull(pending.diff)
        val installed = store.confirmInstall(pending.meta, script())
        assertEquals("example.asr", installed.meta.id)
        assertEquals(listOf("example.asr"), store.list().map { it.id })
        assertNotNull(store.get("example.asr"))
    }

    @Test
    fun import_identicalReimportUpgradesSilently() {
        val store = PluginStore(tempDir(), MapPrefsStore())
        val pending = store.importScript(script()) as PluginStore.ImportResult.NeedsConfirmation
        store.confirmInstall(pending.meta, script())
        val second = store.importScript(script())
        val installed = second as? PluginStore.ImportResult.Installed
            ?: error("expected silent upgrade, got $second")
        assertTrue(installed.upgraded)
    }

    @Test
    fun import_hostGrowthNeedsConfirmationWithDiff() {
        val store = PluginStore(tempDir(), MapPrefsStore())
        val pending = store.importScript(script()) as PluginStore.ImportResult.NeedsConfirmation
        store.confirmInstall(pending.meta, script())
        val grown = store.importScript(script(hosts = "'asr.example.com', 'extra.example.com'"))
        val needs = grown as? PluginStore.ImportResult.NeedsConfirmation
            ?: error("expected NeedsConfirmation, got $grown")
        assertEquals(listOf("extra.example.com"), needs.diff?.hostsAdded)
    }

    @Test
    fun import_rejectsOversize_noRegister_badId_badRate() {
        val store = PluginStore(tempDir(), MapPrefsStore())
        assertEquals(
            PluginStore.ImportResult.Failed(PluginImportError.TOO_LARGE),
            store.importScript(ByteArray(PluginContract.MAX_SCRIPT_BYTES + 1)),
        )
        assertEquals(
            PluginStore.ImportResult.Failed(PluginImportError.NO_PLUGIN),
            store.importScript("var x = 1;".toByteArray()),
        )
        assertEquals(
            PluginStore.ImportResult.Failed(PluginImportError.INVALID_ID),
            store.importScript(script(id = "-bad")),
        )
        assertEquals(
            PluginStore.ImportResult.Failed(PluginImportError.UNSUPPORTED_SAMPLE_RATE),
            store.importScript(script(rate = 8000)),
        )
    }

    @Test
    fun secretClear_removesOnlyThatField() {
        val secrets = MapSecretStore()
        secrets.put("example.asr", "api_key", "fixture-secret")
        secrets.put("example.asr", "other_key", "fixture-other")
        secrets.clear("example.asr", "api_key")
        assertFalse(secrets.has("example.asr", "api_key"))
        assertTrue(secrets.has("example.asr", "other_key"))
    }

    @Test
    fun uninstall_clearsDir_preferred_settings_secrets() {
        val dir = tempDir()
        val prefs = MapPrefsStore()
        val secrets = MapSecretStore()
        val store = PluginStore(dir, prefs)
        val pending = store.importScript(script()) as PluginStore.ImportResult.NeedsConfirmation
        store.confirmInstall(pending.meta, script())
        store.setPreferred("example.asr")
        store.setSetting("example.asr", "model", "a")
        secrets.put("example.asr", "api_key", "fixture-secret")
        assertTrue(File(dir, "cloud_plugins/example.asr").exists())
        store.uninstall("example.asr", secrets)
        assertFalse(File(dir, "cloud_plugins/example.asr").exists())
        assertNull(store.getPreferred())
        assertFalse(secrets.has("example.asr", "api_key"))
        assertTrue(store.getSettings("example.asr").isEmpty())
    }
}
