// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.base.speech.cloud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * 内置云端语音插件：契约测试。脚本以 shipped assets 原文为准（单测
 * 直接读源码树 assets，不另抄一份，防漂移）；会话以内存传输验证，
 * 不触真实厂商端点（真实端到端进最终真机清单）。
 */
class BuiltinPluginsTest {

    private class RecordingListener : PluginHost.Listener {
        val partials = mutableListOf<String>()
        val finals = mutableListOf<String>()
        var ready = CountDownLatch(1)
        var partialLatch = CountDownLatch(1)
        var finalLatch = CountDownLatch(1)
        var errorLatch = CountDownLatch(1)
        var endedLatch = CountDownLatch(1)
        var budgetLatch = CountDownLatch(1)

        override fun onReady() {
            ready.countDown()
        }

        override fun onPartial(text: String) {
            partials.add(text)
            partialLatch.countDown()
        }

        override fun onFinal(text: String) {
            finals.add(text)
            finalLatch.countDown()
        }

        override fun onError() {
            errorLatch.countDown()
        }

        override fun onEnded() {
            endedLatch.countDown()
        }

        override fun onBudgetDisabled() {
            budgetLatch.countDown()
        }
    }

    private class RecordingTransport : NetworkTransport {
        data class WsCall(val url: String, val headers: Map<String, String>)
        data class HttpCall(val url: String, val headers: Map<String, String>, val body: ByteArray)

        val wsCalls = mutableListOf<WsCall>()
        val httpCalls = mutableListOf<HttpCall>()
        var httpResponseBody: ByteArray = "ok".toByteArray()
        val sentTexts = mutableListOf<String>()
        val sentBytes = mutableListOf<ByteArray>()
        private var listener: NetworkTransport.WsListener? = null

        override fun httpPost(
            url: String,
            headers: Map<String, String>,
            body: ByteArray,
        ): HttpResponse {
            httpCalls.add(HttpCall(url, headers, body))
            return HttpResponse(200, httpResponseBody)
        }

        override fun websocket(
            url: String,
            headers: Map<String, String>,
            listener: NetworkTransport.WsListener,
        ): NetworkTransport.WsHandle {
            wsCalls.add(WsCall(url, headers))
            this.listener = listener
            return object : NetworkTransport.WsHandle {
                override fun send(text: String): Boolean {
                    sentTexts.add(text)
                    return true
                }

                override fun sendBytes(bytes: ByteArray): Boolean {
                    sentBytes.add(bytes)
                    return true
                }

                override fun close() = Unit
            }
        }

        fun injectText(text: String) {
            listener?.onMessage(text, null)
        }

        fun injectBytes(bytes: ByteArray) {
            listener?.onMessage(null, bytes)
        }
    }

    private fun assetBytes(id: String): ByteArray {
        val candidates = listOf(
            File("src/main/assets/cloud_plugins/$id/plugin.js"),
            File("app/src/main/assets/cloud_plugins/$id/plugin.js"),
        )
        val file = candidates.firstOrNull { it.isFile }
            ?: throw AssertionError("builtin asset missing for $id (tried $candidates)")
        return file.readBytes()
    }

    private fun tempDir(): File = Files.createTempDirectory("builtin-test").toFile()

    private fun installedFromAsset(
        store: PluginStore,
        id: String,
    ): PluginStore.InstalledPlugin {
        // 内置脚本不走用户导入口（导入口拒绝保留 id），走种子安装。
        val result = store.seedBuiltin(assetBytes(id))
        if (result !is PluginStore.SeedResult.Seeded) {
            throw AssertionError("expected Seeded for $id, got $result")
        }
        return store.get(id) ?: throw AssertionError("seed did not persist $id")
    }

    private fun awaitCondition(timeoutMs: Long = 5000, check: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (check()) return
            Thread.sleep(10)
        }
        assertTrue("condition not met within ${timeoutMs}ms", check())
    }

    /** 断言在窗口内始终未发送（缓冲行为证明；窗口到期仍为空即通过）。 */
    private fun assertStaysEmpty(timeoutMs: Long = 1500, get: () -> Int) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            assertEquals(0, get())
            Thread.sleep(10)
        }
        assertEquals(0, get())
    }

    private fun hmacSha1Base64(key: String, message: String): String {
        val mac = Mac.getInstance("HmacSHA1")
        mac.init(SecretKeySpec(key.toByteArray(Charsets.UTF_8), "HmacSHA1"))
        // 与宿主桥接用同一编解码（单测替身去填充），签名比对才同口径。
        return JdkBase64Codec.encodeToString(mac.doFinal(message.toByteArray(Charsets.UTF_8)))
    }

    private fun percentEncode(value: String): String {
        val sb = StringBuilder()
        for (b in value.toByteArray(Charsets.UTF_8)) {
            val c = b.toInt() and 0xFF
            val ch = c.toChar()
            if (ch.isLetterOrDigit() || ch in "-_.!~*'()") {
                sb.append(ch)
            } else {
                sb.append('%').append("%02X".format(c))
            }
        }
        return sb.toString()
    }

    @Test
    fun builtinScripts_passContractValidation() {
        val expectations = mapOf(
            "builtin.doubao" to Triple(
                listOf("openspeech.bytedance.com"),
                listOf("api_key" to SettingType.SECRET, "model" to SettingType.SELECT),
                listOf(AuthHeaderDecl("X-Api-Key", "api_key", "")),
            ),
            "builtin.nls" to Triple(
                listOf("nls-meta.cn-shanghai.aliyuncs.com", "nls-gateway-cn-shanghai.aliyuncs.com"),
                listOf(
                    "appkey" to SettingType.TEXT,
                    "access_key_id" to SettingType.TEXT,
                    "access_key_secret" to SettingType.SECRET,
                ),
                emptyList(),
            ),
            "builtin.tencent" to Triple(
                listOf("asr.cloud.tencent.com"),
                listOf(
                    "appid" to SettingType.TEXT,
                    "secretid" to SettingType.TEXT,
                    "secretkey" to SettingType.SECRET,
                    "category" to SettingType.SELECT,
                    "engine" to SettingType.SELECT,
                ),
                emptyList(),
            ),
        )
        for ((id, expected) in expectations) {
            val store = PluginStore(tempDir(), MapPrefsStore())
            val seeded = store.seedBuiltin(assetBytes(id))
            if (seeded !is PluginStore.SeedResult.Seeded) {
                throw AssertionError("expected Seeded for $id, got $seeded")
            }
            val meta = store.get(id)?.meta
                ?: throw AssertionError("seed did not persist $id")
            assertEquals(id, meta.id)
            assertEquals(expected.first, meta.hosts)
            assertTrue(meta.capabilities.contains(PluginContract.CAPABILITY_VOICE_ASR))
            assertEquals(SUPPORTED_SAMPLE_RATE, meta.sampleRate)
            assertEquals(
                expected.second,
                meta.settings.map { it.key to it.type },
            )
            assertEquals(expected.third, meta.authHeaders)
        }
    }

    @Test
    fun builtinScripts_newSettingDeclarations() {
        val store = PluginStore(tempDir(), MapPrefsStore())
        val doubao = installedFromAsset(store, "builtin.doubao").meta
        val modelDecl = doubao.settings.single { it.key == "model" }
        assertEquals(
            listOf("豆包大模型流式 v2（推荐）", "豆包大模型流式 v1"),
            modelDecl.options,
        )
        assertEquals("", modelDecl.defaultValue)
        val tencent = installedFromAsset(store, "builtin.tencent").meta
        val categoryDecl = tencent.settings.single { it.key == "category" }
        assertEquals(
            listOf("大模型 2.0 · 流式", "大模型 1.0 · 流式", "标准版 · 流式", "大模型 · 极速文件", "标准版 · 极速文件"),
            categoryDecl.options,
        )
        val engineDecl = tencent.settings.single { it.key == "engine" }
        assertEquals(27, engineDecl.options.size)
        assertEquals(27, engineDecl.options.toSet().size)
    }

    @Test
    fun doubaoSession_modelSelectsResourceId() {
        val store = PluginStore(tempDir(), MapPrefsStore())
        val plugin = installedFromAsset(store, "builtin.doubao")
        val secrets = MapSecretStore()
        secrets.put("builtin.doubao", "api_key", "test-api-key")
        fun runWithModel(model: String?): String {
            val transport = RecordingTransport()
            val listener = RecordingListener()
            val settings = if (model == null) emptyMap() else mapOf("model" to model)
            val host = PluginHost(
                plugin,
                settings,
                secrets,
                transport,
                listener,
                JdkBase64Codec,
            )
            try {
                host.start()
                assertTrue(listener.ready.await(10, TimeUnit.SECONDS))
                val call = transport.wsCalls.single()
                assertEquals(
                    "wss://openspeech.bytedance.com/api/v3/sauc/bigmodel_async",
                    call.url,
                )
                assertTrue(call.headers.containsKey("X-Api-Key"))
                return call.headers["X-Api-Resource-Id"] ?: ""
            } finally {
                host.kill()
            }
        }
        assertEquals("volc.seedasr.sauc.duration", runWithModel("豆包大模型流式 v2（推荐）"))
        assertEquals("volc.bigasr.sauc.duration", runWithModel("豆包大模型流式 v1"))
        assertEquals("volc.bigasr.sauc.duration", runWithModel(null))
    }

    @Test
    fun tencentSession_categoryEngineMapping() {
        val store = PluginStore(tempDir(), MapPrefsStore())
        val plugin = installedFromAsset(store, "builtin.tencent")
        val secrets = MapSecretStore()
        secrets.put("builtin.tencent", "secretkey", "test-secret-key")
        fun runWith(category: String, engine: String): String {
            val transport = RecordingTransport()
            val listener = RecordingListener()
            val host = PluginHost(
                plugin,
                mapOf(
                    "appid" to "123456",
                    "secretid" to "AKIDTEST",
                    "category" to category,
                    "engine" to engine,
                ),
                secrets,
                transport,
                listener,
                JdkBase64Codec,
            )
            try {
                host.start()
                assertTrue(listener.ready.await(10, TimeUnit.SECONDS))
                val url = transport.wsCalls.single().url
                val query = url.substringAfter("?")
                val params = query.split("&").associate {
                    val idx = it.indexOf("=")
                    it.substring(0, idx) to it.substring(idx + 1)
                }
                return params["engine_model_type"] ?: ""
            } finally {
                host.kill()
            }
        }
        // 同一引擎标签在不同类别下映射到不同 id（类别优先消歧）。
        assertEquals("16k_yue", runWith("标准版 · 流式", "粤语"))
        assertEquals("16k_zh_en_2.0", runWith("大模型 2.0 · 流式", "中英语言"))
        assertEquals("16k_zh_en", runWith("大模型 1.0 · 流式", "中英语言"))
        assertEquals("Hy-ASR-3.0-preview", runWith("大模型 2.0 · 流式", "Hy-ASR-3.0-preview"))
        // 类别内无此引擎标签时取该类别首项。
        assertEquals("16k_zh", runWith("标准版 · 流式", "Hy-ASR-3.0-preview"))
    }

    /** 阿里云 POP 编码（与测试文件内腾讯用 percentEncode 不同：! ' ( ) * 亦编码）。 */
    private fun popPercentEncode(value: String): String {
        val sb = StringBuilder()
        for (b in value.toByteArray(Charsets.UTF_8)) {
            val c = b.toInt() and 0xFF
            val ch = c.toChar()
            if (ch.isLetterOrDigit() || ch in "-_.~") {
                sb.append(ch)
            } else {
                sb.append('%').append("%02X".format(c))
            }
        }
        return sb.toString()
    }

    private fun popDecode(value: String): String {
        val bytes = ArrayList<Byte>()
        var i = 0
        while (i < value.length) {
            val c = value[i]
            if (c == '%' && i + 2 < value.length) {
                val b = value.substring(i + 1, i + 3).toIntOrNull(16)
                if (b != null) {
                    bytes.add(b.toByte())
                    i += 3
                    continue
                }
            }
            bytes.add(c.code.toByte())
            i++
        }
        return String(bytes.toByteArray(), Charsets.UTF_8)
    }

    @Test
    fun nlsPopSignature_matchesOfflineFixture() {
        // 官方文档固定参数（虚拟密钥、已过期时间戳，仅离线核对算法）。
        val params = sortedMapOf(
            "AccessKeyId" to "my_access_key_id",
            "Action" to "CreateToken",
            "Format" to "JSON",
            "RegionId" to "cn-shanghai",
            "SignatureMethod" to "HMAC-SHA1",
            "SignatureNonce" to "b924c8c3-6d03-4c5d-ad36-d984d3116788",
            "SignatureVersion" to "1.0",
            "Timestamp" to "2019-04-18T08:32:31Z",
            "Version" to "2019-02-28",
        )
        val canonical = params.entries.joinToString("&") {
            popPercentEncode(it.key) + "=" + popPercentEncode(it.value)
        }
        assertEquals(
            "AccessKeyId=my_access_key_id&Action=CreateToken&Format=JSON&RegionId=cn-shanghai" +
                "&SignatureMethod=HMAC-SHA1&SignatureNonce=b924c8c3-6d03-4c5d-ad36-d984d3116788" +
                "&SignatureVersion=1.0&Timestamp=2019-04-18T08%3A32%3A31Z&Version=2019-02-28",
            canonical,
        )
        val stringToSign = "GET&" + popPercentEncode("/") + "&" + popPercentEncode(canonical)
        val mac = Mac.getInstance("HmacSHA1")
        mac.init(SecretKeySpec("my_access_key_secret&".toByteArray(Charsets.UTF_8), "HmacSHA1"))
        val sig = java.util.Base64.getEncoder()
            .encodeToString(mac.doFinal(stringToSign.toByteArray(Charsets.UTF_8)))
        assertEquals("hHq4yNsPitlfDJ2L0nQPdugdEzM=", sig)
    }

    private fun nlsHost(
        store: PluginStore,
        transport: RecordingTransport,
        secrets: MapSecretStore,
        listener: RecordingListener,
    ): PluginHost {
        val plugin = installedFromAsset(store, "builtin.nls")
        transport.httpResponseBody =
            """{"Token":{"Id":"test-token-123","ExpireTime":1999999999}}"""
                .toByteArray(Charsets.UTF_8)
        secrets.put("builtin.nls", "access_key_secret", "test-ak-secret")
        return PluginHost(
            plugin,
            mapOf("appkey" to "test-appkey", "access_key_id" to "test-ak-id"),
            secrets,
            transport,
            listener,
            JdkBase64Codec,
        )
    }

    @Test
    fun nlsSession_tokenAndTranscriptionFlow() {
        val store = PluginStore(tempDir(), MapPrefsStore())
        val transport = RecordingTransport()
        val secrets = MapSecretStore()
        val listener = RecordingListener()
        val host = nlsHost(store, transport, secrets, listener)
        try {
            host.start()
            assertTrue(listener.ready.await(10, TimeUnit.SECONDS))
            // Token 请求：POST 上海元数据端点，表单体含 POP 签名。
            val http = transport.httpCalls.single()
            assertEquals("https://nls-meta.cn-shanghai.aliyuncs.com/", http.url)
            assertEquals("application/x-www-form-urlencoded", http.headers["Content-Type"])
            val form = String(http.body, Charsets.UTF_8)
            val sigEncoded = form.substringAfter("&Signature=", "")
            assertTrue(sigEncoded.isNotEmpty())
            val rawParams = form.substringBefore("&Signature=").split("&").associate {
                val kv = it.split("=", limit = 2)
                popDecode(kv[0]) to popDecode(kv[1])
            }
            assertEquals("CreateToken", rawParams["Action"])
            assertEquals("2019-02-28", rawParams["Version"])
            assertEquals("cn-shanghai", rawParams["RegionId"])
            assertEquals("test-ak-id", rawParams["AccessKeyId"])
            // 签名验算：重算必须与表单签名一致（单测编解码去填充，与桥接同口径）。
            val canonical = rawParams.entries.sortedBy { it.key }.joinToString("&") {
                popPercentEncode(it.key) + "=" + popPercentEncode(it.value)
            }
            val stringToSign = "POST&" + popPercentEncode("/") + "&" + popPercentEncode(canonical)
            assertEquals(
                popPercentEncode(hmacSha1Base64("test-ak-secret&", stringToSign)),
                sigEncoded,
            )
            // WS 连接带 token，首条为 StartTranscription（appkey 在 header 内）。
            assertEquals(
                "wss://nls-gateway-cn-shanghai.aliyuncs.com/ws/v1?token=test-token-123",
                transport.wsCalls.single().url,
            )
            val startJson = transport.sentTexts.single()
            assertTrue(startJson.contains("\"name\":\"StartTranscription\""))
            assertTrue(startJson.contains("\"namespace\":\"SpeechTranscriber\""))
            assertTrue(startJson.contains("\"appkey\":\"test-appkey\""))
            assertTrue(startJson.contains("\"format\":\"pcm\""))
            assertTrue(startJson.contains("\"sample_rate\":16000"))
            assertTrue(startJson.contains("\"enable_intermediate_result\":true"))
            assertTrue(startJson.contains("\"enable_punctuation_prediction\":true"))
            assertTrue(Regex("\"task_id\":\"[0-9a-f]{32}\"").containsMatchIn(startJson))
            assertTrue(Regex("\"message_id\":\"[0-9a-f]{32}\"").containsMatchIn(startJson))
            // 任务确认后音频才发出；中间结果预览、句末提交、停止后任务完成结束。
            transport.injectText(
                """{"header":{"namespace":"SpeechTranscriber","name":"TranscriptionStarted","status":20000000}}""",
            )
            host.audio("AAAA")
            awaitCondition { transport.sentBytes.isNotEmpty() }
            transport.injectText(
                """{"header":{"namespace":"SpeechTranscriber","name":"TranscriptionResultChanged","status":20000000},"payload":{"index":1,"time":1000,"result":"今天天气"}}""",
            )
            assertTrue(listener.partialLatch.await(10, TimeUnit.SECONDS))
            assertEquals(listOf("今天天气"), listener.partials)
            transport.injectText(
                """{"header":{"namespace":"SpeechTranscriber","name":"SentenceEnd","status":20000000},"payload":{"index":1,"time":1500,"begin_time":0,"result":"今天天气很好。"}}""",
            )
            assertTrue(listener.finalLatch.await(10, TimeUnit.SECONDS))
            assertEquals(listOf("今天天气很好。"), listener.finals)
            // 注意：宿主 stop() 发完 StopTranscription 即杀会话（见 PluginHost.stop），
            // 故完成事件须在 stop 之前注入；stop 本身的发包由下个单测覆盖。
            transport.injectText(
                """{"header":{"namespace":"SpeechTranscriber","name":"TranscriptionCompleted","status":20000000}}""",
            )
            assertTrue(listener.endedLatch.await(10, TimeUnit.SECONDS))
            assertEquals(listOf("今天天气很好。"), listener.finals)
        } finally {
            host.kill()
        }
    }

    @Test
    fun nlsSession_stopSendsStopTranscription() {
        val store = PluginStore(tempDir(), MapPrefsStore())
        val transport = RecordingTransport()
        val secrets = MapSecretStore()
        val listener = RecordingListener()
        val host = nlsHost(store, transport, secrets, listener)
        try {
            host.start()
            assertTrue(listener.ready.await(10, TimeUnit.SECONDS))
            assertEquals(1, transport.sentTexts.size)
            host.stop()
            awaitCondition { transport.sentTexts.size == 2 }
            val stopJson = transport.sentTexts[1]
            assertTrue(stopJson.contains("\"name\":\"StopTranscription\""))
            assertTrue(stopJson.contains("\"namespace\":\"SpeechTranscriber\""))
            val startTask = Regex("\"task_id\":\"([0-9a-f]{32})\"")
                .find(transport.sentTexts[0])!!.groupValues[1]
            assertTrue(stopJson.contains("\"task_id\":\"$startTask\""))
        } finally {
            host.kill()
        }
    }

    @Test
    fun nlsSession_tailPartialCommittedOnCompleted() {
        val store = PluginStore(tempDir(), MapPrefsStore())
        val transport = RecordingTransport()
        val secrets = MapSecretStore()
        val listener = RecordingListener()
        val host = nlsHost(store, transport, secrets, listener)
        try {
            host.start()
            assertTrue(listener.ready.await(10, TimeUnit.SECONDS))
            transport.injectText(
                """{"header":{"namespace":"SpeechTranscriber","name":"TranscriptionStarted","status":20000000}}""",
            )
            transport.injectText(
                """{"header":{"namespace":"SpeechTranscriber","name":"TranscriptionResultChanged","status":20000000},"payload":{"index":1,"time":1000,"result":"尾巴"}}""",
            )
            assertTrue(listener.partialLatch.await(10, TimeUnit.SECONDS))
            // 无 SentenceEnd 直接完成：未提交的尾部须先提交再结束，不丢字。
            transport.injectText(
                """{"header":{"namespace":"SpeechTranscriber","name":"TranscriptionCompleted","status":20000000}}""",
            )
            assertTrue(listener.endedLatch.await(10, TimeUnit.SECONDS))
            assertEquals(listOf("尾巴"), listener.finals)
        } finally {
            host.kill()
        }
    }

    @Test
    fun ensure_prunesRemovedQwen() {
        val store = PluginStore(tempDir(), MapPrefsStore())
        val secrets = MapSecretStore()
        // 旧版千问残留（内联最小脚本，不依赖已删资产）。
        val qwenScript = """
            register({
              id: 'builtin.qwen', version: '1.2', hosts: ['dashscope.aliyuncs.com'],
              features: [{ type: 'voice.asr', sampleRate: 16000 }],
              settings: [{ key: 'api_key', type: 'secret', label: 'API 密钥' }],
              auth: [{ header: 'Authorization', field: 'api_key', prefix: 'bearer ' }]
            });
            function start(ctx) {}
            function audio(ctx) {}
            function onMessage(ctx) {}
            function stop(ctx) {}
            function onSocketClosed() {}
        """.trimIndent()
        assertTrue(store.seedBuiltin(qwenScript.toByteArray(Charsets.UTF_8)) is PluginStore.SeedResult.Seeded)
        store.setPreferred("builtin.qwen")
        secrets.put("builtin.qwen", "api_key", "old-key")
        val assets = mapOf(
            "cloud_plugins/builtin.doubao/plugin.js" to assetBytes("builtin.doubao"),
            "cloud_plugins/builtin.nls/plugin.js" to assetBytes("builtin.nls"),
            "cloud_plugins/builtin.tencent/plugin.js" to assetBytes("builtin.tencent"),
        )
        val results = BuiltinPlugins.ensure(store, { assets[it] }, secrets)
        assertEquals(3, results.size)
        assertTrue(results.all { it is PluginStore.SeedResult.Seeded })
        assertNull(store.get("builtin.qwen"))
        assertNull(store.getPreferred())
        assertFalse(secrets.has("builtin.qwen", "api_key"))
        assertEquals(
            listOf("builtin.doubao", "builtin.nls", "builtin.tencent"),
            store.list().map { it.id },
        )
    }

    @Test
    fun userImport_rejectsBuiltinIds() {
        for (id in BuiltinPlugins.SPECS.map { it.id }) {
            val store = PluginStore(tempDir(), MapPrefsStore())
            val result = store.importScript(assetBytes(id))
            assertTrue(
                "user import of $id must be rejected, got $result",
                result is PluginStore.ImportResult.Failed &&
                    result.error == PluginImportError.RESERVED_ID,
            )
        }
    }

    @Test
    fun ensure_seedsAndIsIdempotent() {
        val store = PluginStore(tempDir(), MapPrefsStore())
        val assets = mapOf(
            "cloud_plugins/builtin.doubao/plugin.js" to assetBytes("builtin.doubao"),
            "cloud_plugins/builtin.nls/plugin.js" to assetBytes("builtin.nls"),
            "cloud_plugins/builtin.tencent/plugin.js" to assetBytes("builtin.tencent"),
        )
        val first = BuiltinPlugins.ensure(store, { assets[it] })
        assertEquals(3, first.size)
        assertTrue(first.all { it is PluginStore.SeedResult.Seeded })
        assertEquals(
            listOf("builtin.doubao", "builtin.nls", "builtin.tencent"),
            store.list().map { it.id },
        )
        val second = BuiltinPlugins.ensure(store, { assets[it] })
        assertEquals(3, second.size)
        assertTrue(second.all { it is PluginStore.SeedResult.Unchanged })
    }

    @Test
    fun ensure_upgradeClearsPreferredOnlyOnMetaChange() {
        val store = PluginStore(tempDir(), MapPrefsStore())
        val nls = assetBytes("builtin.nls")
        val assets = mutableMapOf(
            "cloud_plugins/builtin.doubao/plugin.js" to assetBytes("builtin.doubao"),
            "cloud_plugins/builtin.nls/plugin.js" to nls,
            "cloud_plugins/builtin.tencent/plugin.js" to assetBytes("builtin.tencent"),
        )
        BuiltinPlugins.ensure(store, { assets[it] })
        store.setPreferred("builtin.nls")
        // 仅版本号变化：升级但不清首选（NLS 脚本现为 1.0，故 1.0→1.1）。
        assets["cloud_plugins/builtin.nls/plugin.js"] =
            String(nls, Charsets.UTF_8).replace("version: '1.0'", "version: '1.1'")
                .toByteArray(Charsets.UTF_8)
        val versionOnly = BuiltinPlugins.ensure(store, { assets[it] })
            .filterIsInstance<PluginStore.SeedResult.Updated>()
            .singleOrNull()
        assertNotNull(versionOnly)
        assertEquals(false, versionOnly!!.reselectNeeded)
        assertEquals("builtin.nls", store.getPreferred())
        // 域名变化：升级且清首选。
        assets["cloud_plugins/builtin.nls/plugin.js"] =
            String(nls, Charsets.UTF_8)
                .replace("nls-gateway-cn-shanghai.aliyuncs.com", "example.invalid")
                .toByteArray(Charsets.UTF_8)
        val metaChanged = BuiltinPlugins.ensure(store, { assets[it] })
            .filterIsInstance<PluginStore.SeedResult.Updated>()
            .singleOrNull()
        assertNotNull(metaChanged)
        assertEquals(true, metaChanged!!.reselectNeeded)
        assertNull(store.getPreferred())
    }

    @Test
    fun seedBuiltin_rejectsNonBuiltinId() {
        val store = PluginStore(tempDir(), MapPrefsStore())
        val script = """
            register({
              id: 'user.x', version: '1.0', hosts: ['asr.example.com'],
              features: [{ type: 'voice.asr', sampleRate: 16000 }]
            });
            function start(ctx) {}
            function audio(ctx) {}
            function stop(ctx) {}
        """.trimIndent()
        val result = store.seedBuiltin(script.toByteArray(Charsets.UTF_8))
        assertTrue(result is PluginStore.SeedResult.Failed)
    }

    @Test
    fun seedBuiltin_update_clearsBudgetDisabled() {
        // 2026-10-30 音频预算轮：旧脚本留下的停用标记，随脚本升级清一次；
        // 新脚本再失败会重新标记（预算测试 budgetExceeded_disablesPlugin 覆盖）。
        val store = PluginStore(tempDir(), MapPrefsStore())
        fun script(comment: String) = """
            register({
              id: 'builtin.test', version: '1.0', hosts: ['asr.example.com'],
              features: [{ type: 'voice.asr', sampleRate: 16000 }]
            });
            function start(ctx) {}
            function audio(ctx) {}
            function stop(ctx) {}
            $comment
        """.trimIndent()
        assertTrue(store.seedBuiltin(script("").toByteArray(Charsets.UTF_8)) is PluginStore.SeedResult.Seeded)
        store.setBudgetDisabled("builtin.test", true)
        assertTrue(store.isBudgetDisabled("builtin.test"))
        val updated = store.seedBuiltin(script("// updated").toByteArray(Charsets.UTF_8))
        assertTrue(updated is PluginStore.SeedResult.Updated)
        assertFalse(store.isBudgetDisabled("builtin.test"))
    }

    @Test
    fun seedBuiltin_unchanged_keepsBudgetDisabled() {
        // 脚本未变的种子不碰停用标记：用户仍需手动重新启用。
        val store = PluginStore(tempDir(), MapPrefsStore())
        val script = """
            register({
              id: 'builtin.test', version: '1.0', hosts: ['asr.example.com'],
              features: [{ type: 'voice.asr', sampleRate: 16000 }]
            });
            function start(ctx) {}
            function audio(ctx) {}
            function stop(ctx) {}
        """.trimIndent()
        assertTrue(store.seedBuiltin(script.toByteArray(Charsets.UTF_8)) is PluginStore.SeedResult.Seeded)
        store.setBudgetDisabled("builtin.test", true)
        assertTrue(
            store.seedBuiltin(script.toByteArray(Charsets.UTF_8)) is PluginStore.SeedResult.Unchanged,
        )
        assertTrue(store.isBudgetDisabled("builtin.test"))
    }

    @Test
    fun cryptoBridge_hmacSha1KnownVector() {
        val script = """
            register({
              id: 'p1', version: '1.0', hosts: ['asr.example.com'],
              features: [{ type: 'voice.asr', sampleRate: 16000 }],
              settings: [{ key: 'api_key', type: 'secret', label: '密钥' }],
              auth: []
            });
            function start(ctx) {}
            function audio(ctx) {
              var s = crypto.hmacSha1Base64('api_key', 'The quick brown fox jumps over the lazy dog');
              // 摘要真值 hex de7c9b85b8b78aa6bc8a7a36f70a90701c9db4d9；单测 Base64
              // 替身去填充（生产 Android 编解码带填充），故此处期望无填充形式。
              if (s === '3nybhbi3iqa8ino29wqQcBydtNk') { cb.partial('sig-ok'); } else { cb.partial('sig-bad'); }
            }
            function stop(ctx) {}
        """.trimIndent()
        val dir = tempDir()
        val pluginDir = File(dir, "p1").apply { mkdirs() }
        val scriptFile = File(pluginDir, "plugin.js").apply { writeText(script) }
        val sandbox = File(pluginDir, "files").apply { mkdirs() }
        val meta = PluginMeta(
            id = "p1",
            version = "1.0",
            hosts = listOf("asr.example.com"),
            capabilities = listOf("voice.asr"),
            settings = listOf(PluginSettingDecl("api_key", SettingType.SECRET, "密钥")),
        )
        val secrets = MapSecretStore()
        secrets.put("p1", "api_key", "key")
        val listener = RecordingListener()
        val host = PluginHost(
            PluginStore.InstalledPlugin(meta, scriptFile, sandbox),
            emptyMap(),
            secrets,
            RecordingTransport(),
            listener,
            JdkBase64Codec,
        )
        try {
            host.start()
            assertTrue(listener.ready.await(10, TimeUnit.SECONDS))
            host.audio("AAAA")
            assertTrue(listener.partialLatch.await(10, TimeUnit.SECONDS))
            assertEquals(listOf("sig-ok"), listener.partials)
        } finally {
            host.kill()
        }
    }

    @Test
    fun cryptoBridge_restrictions() {
        val script = """
            register({
              id: 'p1', version: '1.0', hosts: ['asr.example.com'],
              features: [{ type: 'voice.asr', sampleRate: 16000 }],
              settings: [
                { key: 'api_key', type: 'secret', label: '密钥' },
                { key: 'model', type: 'text', label: '模型' }
              ],
              auth: []
            });
            function start(ctx) {}
            function audio(ctx) {
              var big = new Array(4098).join('x');
              var a = crypto.hmacSha1Base64('nope', 'm');
              var b = crypto.hmacSha1Base64('model', 'm');
              var c = crypto.hmacSha1Base64('api_key', big);
              if (a === null && b === null && c === null) { cb.partial('restricted-ok'); } else { cb.partial('restricted-bad'); }
            }
            function stop(ctx) {}
        """.trimIndent()
        val dir = tempDir()
        val pluginDir = File(dir, "p1").apply { mkdirs() }
        val scriptFile = File(pluginDir, "plugin.js").apply { writeText(script) }
        val sandbox = File(pluginDir, "files").apply { mkdirs() }
        val meta = PluginMeta(
            id = "p1",
            version = "1.0",
            hosts = listOf("asr.example.com"),
            capabilities = listOf("voice.asr"),
            settings = listOf(
                PluginSettingDecl("api_key", SettingType.SECRET, "密钥"),
                PluginSettingDecl("model", SettingType.TEXT, "模型"),
            ),
        )
        val secrets = MapSecretStore()
        secrets.put("p1", "api_key", "key")
        val listener = RecordingListener()
        val host = PluginHost(
            PluginStore.InstalledPlugin(meta, scriptFile, sandbox),
            mapOf("model" to "m"),
            secrets,
            RecordingTransport(),
            listener,
            JdkBase64Codec,
        )
        try {
            host.start()
            assertTrue(listener.ready.await(10, TimeUnit.SECONDS))
            host.audio("AAAA")
            assertTrue(listener.partialLatch.await(10, TimeUnit.SECONDS))
            assertEquals(listOf("restricted-ok"), listener.partials)
        } finally {
            host.kill()
        }
    }

    @Test
    fun shimDirect_doubaoAuthHeaderReachesTransport() {
        val store = PluginStore(tempDir(), MapPrefsStore())
        val doubao = installedFromAsset(store, "builtin.doubao")
        val secrets = MapSecretStore()
        secrets.put("builtin.doubao", "api_key", "test-api-key")
        val transport = RecordingTransport()
        val shim = NetworkShim(
            allowedHosts = doubao.meta.hosts.map { it.lowercase() }.toSet(),
            authHeaders = doubao.meta.authHeaders,
            secretResolver = { field -> secrets.get(doubao.meta.id, field) },
            transport = transport,
        )
        shim.websocket(
            "wss://openspeech.bytedance.com/test",
            emptyMap(),
            object : NetworkTransport.WsListener {
                override fun onMessage(text: String?, bytes: ByteArray?) {}
                override fun onClosed() {}
                override fun onFailure() {}
            },
        )
        assertEquals(1, transport.wsCalls.size)
        assertEquals("test-api-key", transport.wsCalls.single().headers["X-Api-Key"])
    }

    @Test
    fun tencentSession_contractAndSignature() {
        val store = PluginStore(tempDir(), MapPrefsStore())
        val plugin = installedFromAsset(store, "builtin.tencent")
        val transport = RecordingTransport()
        val secrets = MapSecretStore()
        secrets.put("builtin.tencent", "secretkey", "test-secret-key")
        val listener = RecordingListener()
        val host = PluginHost(
            plugin,
            mapOf("appid" to "123456", "secretid" to "AKIDTEST"),
            secrets,
            transport,
            listener,
            JdkBase64Codec,
        )
        try {
            host.start()
            assertTrue(listener.ready.await(10, TimeUnit.SECONDS))
            val url = transport.wsCalls.single().url
            assertTrue(url.startsWith("wss://asr.cloud.tencent.com/asr/v2/123456?"))
            val query = url.substringAfter("?")
            val params = query.split("&").associate {
                val idx = it.indexOf("=")
                it.substring(0, idx) to it.substring(idx + 1)
            }
            assertEquals("AKIDTEST", params["secretid"])
            assertEquals("16k_zh_large", params["engine_model_type"])
            assertEquals("1", params["voice_format"])
            assertEquals("1", params["needvad"])
            assertEquals(16, params["voice_id"]?.length)
            val signature = params["signature"]
            assertNotNull(signature)
            val plainParams = params.filterKeys { it != "signature" }
                .toSortedMap()
                .entries.joinToString("&") { "${it.key}=${it.value}" }
            val plain = "asr.cloud.tencent.com/asr/v2/123456?$plainParams"
            val expected = percentEncode(hmacSha1Base64("test-secret-key", plain))
            assertEquals(expected, signature)
            val pcm = byteArrayOf(5, 6, 7, 8)
            host.audio(JdkBase64Codec.encodeToString(pcm))
            assertStaysEmpty { transport.sentBytes.size }
            transport.injectText("{\"code\":0,\"message\":\"success\",\"voice_id\":\"testvoiceid123456\"}")
            awaitCondition { transport.sentBytes.size == 1 }
            assertTrue(transport.sentBytes.single().contentEquals(pcm))
            transport.injectText(
                "{\"code\":0,\"result\":{\"slice_type\":1,\"voice_text_str\":\"你好\"}}",
            )
            assertTrue(listener.partialLatch.await(10, TimeUnit.SECONDS))
            assertEquals(listOf("你好"), listener.partials)
            transport.injectText(
                "{\"code\":0,\"result\":{\"slice_type\":2,\"voice_text_str\":\"你好世界\"}}",
            )
            assertTrue(listener.finalLatch.await(10, TimeUnit.SECONDS))
            assertEquals(listOf("你好世界"), listener.finals)
            transport.injectText("{\"code\":0,\"final\":1}")
            assertTrue(listener.endedLatch.await(10, TimeUnit.SECONDS))
        } finally {
            host.kill()
        }
    }

    private fun frameBytes(
        type: Int,
        flag: Int,
        serial: Int,
        payload: ByteArray,
        sequence: Int? = null,
    ): ByteArray {
        val out = ArrayList<Byte>()
        out.add(0x11.toByte())
        out.add(((type shl 4) or flag).toByte())
        out.add(((serial shl 4) or 0).toByte())
        out.add(0.toByte())
        if (sequence != null) {
            out.add((sequence shr 24).toByte())
            out.add((sequence shr 16).toByte())
            out.add((sequence shr 8).toByte())
            out.add(sequence.toByte())
        }
        out.add((payload.size shr 24).toByte())
        out.add((payload.size shr 16).toByte())
        out.add((payload.size shr 8).toByte())
        out.add(payload.size.toByte())
        out.addAll(payload.toList())
        return out.toByteArray()
    }

    private fun int32BE(bytes: ByteArray, pos: Int): Int {
        return (bytes[pos].toInt() shl 24) or
            ((bytes[pos + 1].toInt() and 0xFF) shl 16) or
            ((bytes[pos + 2].toInt() and 0xFF) shl 8) or
            (bytes[pos + 3].toInt() and 0xFF)
    }

    @Test
    fun doubaoSession_contract() {
        val store = PluginStore(tempDir(), MapPrefsStore())
        val plugin = installedFromAsset(store, "builtin.doubao")
        val transport = RecordingTransport()
        val secrets = MapSecretStore()
        secrets.put("builtin.doubao", "api_key", "test-api-key")
        val listener = RecordingListener()
        val host = PluginHost(
            plugin,
            mapOf("model" to "豆包大模型流式 v2（推荐）"),
            secrets,
            transport,
            listener,
            JdkBase64Codec,
        )
        try {
            host.start()
            assertTrue(listener.ready.await(10, TimeUnit.SECONDS))
            val call = transport.wsCalls.single()
            assertEquals(
                "wss://openspeech.bytedance.com/api/v3/sauc/bigmodel_async",
                call.url,
            )
            // X-Api-Key 由宿主按声明代附（值不进入断言，只验存在，避免与密钥脱敏口径耦合）。
            assertTrue(call.headers.containsKey("X-Api-Key"))
            assertEquals("volc.seedasr.sauc.duration", call.headers["X-Api-Resource-Id"])
            assertTrue(!call.headers["X-Api-Connect-Id"].isNullOrBlank())
            awaitCondition { transport.sentBytes.size == 1 }
            val config = transport.sentBytes.single()
            assertEquals(0x11.toByte(), config[0])
            assertEquals(0x10.toByte(), config[1])
            // 配置帧：JSON 序列化、gzip 压缩，载荷须先解压再读。
            assertEquals(0x11.toByte(), config[2])
            val configLen = int32BE(config, 4)
            val configJson = String(
                java.util.zip.GZIPInputStream(
                    config.copyOfRange(8, 8 + configLen).inputStream(),
                ).use { it.readBytes() },
                Charsets.UTF_8,
            )
            assertTrue(configJson.contains("\"model_name\":\"bigmodel\""))
            assertTrue(configJson.contains("\"format\":\"pcm\""))
            assertTrue(configJson.contains("\"rate\":16000"))
            val pcm = byteArrayOf(1, 2, 3, 4)
            host.audio(JdkBase64Codec.encodeToString(pcm))
            awaitCondition { transport.sentBytes.size == 2 }
            val audioFrame = transport.sentBytes[1]
            assertEquals(0x11.toByte(), audioFrame[0])
            assertEquals(0x20.toByte(), audioFrame[1])
            assertEquals(0x01.toByte(), audioFrame[2])
            val audioLen = int32BE(audioFrame, 4)
            assertEquals(audioFrame.size - 8, audioLen)
            assertTrue(
                java.util.zip.GZIPInputStream(
                    audioFrame.copyOfRange(8, 8 + audioLen).inputStream(),
                ).use { it.readBytes() }.contentEquals(pcm),
            )
            val partialFrame = frameBytes(
                type = 2,
                flag = 0,
                serial = 1,
                payload = "{\"result\":{\"text\":\"你好\"}}".toByteArray(Charsets.UTF_8),
            )
            transport.injectBytes(partialFrame)
            assertTrue(listener.partialLatch.await(10, TimeUnit.SECONDS))
            assertEquals(listOf("你好"), listener.partials)
            val finalFrame = frameBytes(
                type = 2,
                flag = 3,
                serial = 1,
                payload = "{\"result\":{\"text\":\"你好世界\"}}".toByteArray(Charsets.UTF_8),
                sequence = -1,
            )
            transport.injectBytes(finalFrame)
            assertTrue(listener.finalLatch.await(10, TimeUnit.SECONDS))
            assertEquals(listOf("你好世界"), listener.finals)
            assertTrue(listener.endedLatch.await(10, TimeUnit.SECONDS))
        } finally {
            host.kill()
        }
    }

    @Test
    fun doubaoStop_sendsLastNoSeq() {
        val store = PluginStore(tempDir(), MapPrefsStore())
        val plugin = installedFromAsset(store, "builtin.doubao")
        val transport = RecordingTransport()
        val secrets = MapSecretStore()
        secrets.put("builtin.doubao", "api_key", "test-api-key")
        val listener = RecordingListener()
        val host = PluginHost(
            plugin,
            emptyMap(),
            secrets,
            transport,
            listener,
            JdkBase64Codec,
        )
        try {
            host.start()
            assertTrue(listener.ready.await(10, TimeUnit.SECONDS))
            awaitCondition { transport.sentBytes.size == 1 }
            host.stop()
            awaitCondition { transport.sentBytes.size == 2 }
            val last = transport.sentBytes[1]
            // 空末帧：类型 2、标志 2、gzip 压缩，载荷解压后为空。
            assertEquals(0x22.toByte(), last[1])
            assertEquals(0x01.toByte(), last[2])
            val gzLen = int32BE(last, 4)
            assertEquals(last.size - 8, gzLen)
            assertTrue(
                java.util.zip.GZIPInputStream(
                    last.copyOfRange(8, 8 + gzLen).inputStream(),
                ).use { it.readBytes() }.contentEquals(ByteArray(0)),
            )
        } finally {
            host.kill()
        }
    }

    @Test
    fun doubaoAudio_fullChunk_framesByteExactWithoutBudget() {
        // 2026-10-30 音频预算轮：真实豆包脚本 audio() 经宿主组帧桥接，
        // 整块 1280 字节 PCM 帧字节与宿主金向量一致，且不触发停用。
        val store = PluginStore(tempDir(), MapPrefsStore())
        val plugin = installedFromAsset(store, "builtin.doubao")
        val transport = RecordingTransport()
        val secrets = MapSecretStore()
        secrets.put("builtin.doubao", "api_key", "test-api-key")
        val listener = RecordingListener()
        val host = PluginHost(
            plugin,
            mapOf("model" to "豆包大模型流式 v2（推荐）"),
            secrets,
            transport,
            listener,
            JdkBase64Codec,
        )
        try {
            host.start()
            assertTrue(listener.ready.await(10, TimeUnit.SECONDS))
            awaitCondition { transport.sentBytes.size == 1 }
            val pcm = ByteArray(1280) { (it % 251).toByte() }
            host.audio(JdkBase64Codec.encodeToString(pcm))
            awaitCondition { transport.sentBytes.size == 2 }
            assertTrue(
                transport.sentBytes[1].contentEquals(PluginHost.buildAudioFrameBytes(pcm)),
            )
            // 头字节与 gzip 回绕：类型 2、gzip 压缩，解压后等于原 PCM。
            val sent = transport.sentBytes[1]
            assertEquals(0x11.toByte(), sent[0])
            assertEquals(0x20.toByte(), sent[1])
            assertEquals(0x01.toByte(), sent[2])
            assertEquals(0x00.toByte(), sent[3])
            assertTrue(
                java.util.zip.GZIPInputStream(
                    sent.copyOfRange(8, sent.size).inputStream(),
                ).use { it.readBytes() }.contentEquals(pcm),
            )
            assertEquals(1, listener.budgetLatch.count)
            assertEquals(1, listener.errorLatch.count)
        } finally {
            host.kill()
        }
    }

    @Test
    fun doubaoScript_usesHostFrameBridges() {
        val script = assetBytes("builtin.doubao").toString(Charsets.UTF_8)
        assertTrue(
            "config frame must go through net.configFrame",
            script.contains("net.configFrame("),
        )
        assertTrue(
            "responses must go through net.doubaoResult",
            script.contains("net.doubaoResult("),
        )
        assertTrue(
            "response parsing must not run JS byte loops any more",
            !script.contains("net.gunzip("),
        )
        assertTrue(
            "stop must send an empty last frame through net.audioFrame",
            script.contains("net.audioFrame('', true)"),
        )
        assertTrue(
            "old raw frame() helper must not remain",
            !script.contains("frame(3"),
        )
    }

    // 2026-10-30 响应解析轮：宿主解析与脚本旧 onMessage 逐字节一致。
    private fun responseFrame(
        flag: Int,
        payload: ByteArray,
        compressed: Boolean,
        type: Int = 2,
        idSection: ByteArray? = null,
        sequence: Int? = null,
    ): ByteArray {
        val out = ArrayList<Byte>()
        out.add(0x11.toByte())
        out.add(((type shl 4) or flag).toByte())
        out.add(((1 shl 4) or if (compressed) 1 else 0).toByte())
        out.add(0.toByte())
        if (idSection != null) {
            out.add(0.toByte())
            out.add(0.toByte())
            out.add(0.toByte())
            out.add(0.toByte())
            out.add((idSection.size shr 24).toByte())
            out.add((idSection.size shr 16).toByte())
            out.add((idSection.size shr 8).toByte())
            out.add(idSection.size.toByte())
            out.addAll(idSection.toList())
        }
        if (sequence != null) {
            out.add((sequence shr 24).toByte())
            out.add((sequence shr 16).toByte())
            out.add((sequence shr 8).toByte())
            out.add(sequence.toByte())
        }
        out.add((payload.size shr 24).toByte())
        out.add((payload.size shr 16).toByte())
        out.add((payload.size shr 8).toByte())
        out.add(payload.size.toByte())
        out.addAll(payload.toList())
        return out.toByteArray()
    }

    private fun gzipBytes(data: ByteArray): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        java.util.zip.GZIPOutputStream(out).use { it.write(data) }
        return out.toByteArray()
    }

    @Test
    fun doubaoResponse_rawPartial_extractsResultText() {
        val parsed = PluginHost.parseDoubaoResponse(
            responseFrame(0, "{\"result\":{\"text\":\"你好\"}}".toByteArray(Charsets.UTF_8), false),
        )
        assertNotNull(parsed)
        assertEquals("你好", parsed!!.text)
        assertEquals(false, parsed.final)
        assertEquals(false, parsed.error)
    }

    @Test
    fun doubaoResponse_gzipPartial_extractsAfterDecompress() {
        val parsed = PluginHost.parseDoubaoResponse(
            responseFrame(
                0,
                gzipBytes("{\"result\":{\"text\":\"你好世界\"}}".toByteArray(Charsets.UTF_8)),
                true,
            ),
        )
        assertNotNull(parsed)
        assertEquals("你好世界", parsed!!.text)
        assertEquals(false, parsed.final)
    }

    @Test
    fun doubaoResponse_utterancesAndResults_joinInOrder() {
        val fromUtterances = PluginHost.parseDoubaoResponse(
            responseFrame(
                0,
                "{\"result\":{\"utterances\":[{\"text\":\"你\"},{\"text\":\"好\"}]}}"
                    .toByteArray(Charsets.UTF_8),
                false,
            ),
        )
        assertEquals("你好", fromUtterances!!.text)
        val fromResults = PluginHost.parseDoubaoResponse(
            responseFrame(
                0,
                "{\"result\":{\"results\":[{\"text\":\"a\"},{\"text\":\"b\"}]}}"
                    .toByteArray(Charsets.UTF_8),
                false,
            ),
        )
        assertEquals("ab", fromResults!!.text)
        val fromString = PluginHost.parseDoubaoResponse(
            responseFrame(0, "{\"result\":\"直接\"}".toByteArray(Charsets.UTF_8), false),
        )
        assertEquals("直接", fromString!!.text)
        val fromTop = PluginHost.parseDoubaoResponse(
            responseFrame(0, "{\"text\":\"顶层\"}".toByteArray(Charsets.UTF_8), false),
        )
        assertEquals("顶层", fromTop!!.text)
    }

    @Test
    fun doubaoResponse_finalFlagAndIdSection() {
        val parsed = PluginHost.parseDoubaoResponse(
            responseFrame(
                3,
                "{\"result\":{\"text\":\"完\"}}".toByteArray(Charsets.UTF_8),
                false,
                sequence = -1,
            ),
        )
        assertNotNull(parsed)
        assertEquals("完", parsed!!.text)
        assertEquals(true, parsed.final)
        val withId = PluginHost.parseDoubaoResponse(
            responseFrame(
                4,
                "{\"result\":{\"text\":\"带id\"}}".toByteArray(Charsets.UTF_8),
                false,
                idSection = "xyz".toByteArray(Charsets.UTF_8),
            ),
        )
        assertEquals("带id", withId!!.text)
        assertEquals(false, withId.final)
    }

    @Test
    fun doubaoResponse_errorShortBadPlenBadGzipBadJson_drop() {
        val errorFrame = byteArrayOf(0x11, 0xF0.toByte(), 0x10, 0x00, 0, 0, 0, 0)
        val error = PluginHost.parseDoubaoResponse(errorFrame)
        assertNotNull(error)
        assertEquals(true, error!!.error)
        assertNull(PluginHost.parseDoubaoResponse(byteArrayOf(0x11, 0x20, 0x10)))
        val badPlen = responseFrame(0, "{}".toByteArray(Charsets.UTF_8), false)
            .copyOf().also { it[7] = 99 }
        assertNull(PluginHost.parseDoubaoResponse(badPlen))
        val badGzip = responseFrame(0, "not-gzip".toByteArray(Charsets.UTF_8), true)
        assertNull(PluginHost.parseDoubaoResponse(badGzip))
        val badJson = responseFrame(0, "not json".toByteArray(Charsets.UTF_8), false)
        assertNull(PluginHost.parseDoubaoResponse(badJson))
    }

    @Test
    fun doubaoStreamingEndpoint_isAsync() {
        val script = assetBytes("builtin.doubao").toString(Charsets.UTF_8)
        assertTrue(
            "doubao streaming endpoint must be bigmodel_async",
            script.contains("api/v3/sauc/bigmodel_async"),
        )
        assertTrue(
            "old sync endpoint must not remain",
            !script.contains("api/v3/sauc/bigmodel'") &&
                !script.contains("api/v3/sauc/bigmodel\""),
        )
        assertTrue(
            "request model_name stays bigmodel",
            script.contains("model_name: 'bigmodel'"),
        )
    }
}
