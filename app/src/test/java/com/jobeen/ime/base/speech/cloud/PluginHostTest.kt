// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.base.speech.cloud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** 会话宿主：真实引擎＋内存传输／密钥，验桥接、隔离、预算、沙箱文件。 */
class PluginHostTest {

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

    private fun fixture(script: String): PluginStore.InstalledPlugin {
        val dir = Files.createTempDirectory("host-test").toFile()
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
            authHeaders = listOf(AuthHeaderDecl("Authorization", "api_key", "Bearer ")),
        )
        return PluginStore.InstalledPlugin(meta, scriptFile, sandbox)
    }

    private fun baseScript(body: String): String = """
        register({
          id: 'p1', version: '1.0', hosts: ['asr.example.com'],
          features: [{ type: 'voice.asr', sampleRate: 16000 }],
          settings: [
            { key: 'api_key', type: 'secret', label: '密钥' },
            { key: 'model', type: 'text', label: '模型' }
          ],
          auth: [{ header: 'Authorization', field: 'api_key', prefix: 'Bearer ' }]
        });
        function start(ctx) {}
        function stop(ctx) {}
        $body
    """.trimIndent()

    @Test
    fun audio_partialAndFinalDelivered() {
        val script = baseScript(
            "function audio(ctx) { if (ctx.pcmBase64 === 'AAAA') { cb.partial('hello'); } else { cb.final('world'); } }",
        )
        val listener = RecordingListener()
        val host = PluginHost(
            fixture(script),
            mapOf("model" to "a"),
            MapSecretStore(),
            FakeTransport(),
            listener,
            JdkBase64Codec,
        )
        try {
            host.start()
            assertTrue(listener.ready.await(10, TimeUnit.SECONDS))
            host.audio("AAAA")
            assertTrue(listener.partialLatch.await(10, TimeUnit.SECONDS))
            assertEquals(listOf("hello"), listener.partials)
            host.audio("BBBB")
            assertTrue(listener.finalLatch.await(10, TimeUnit.SECONDS))
            assertEquals(listOf("world"), listener.finals)
        } finally {
            host.kill()
        }
    }

    private fun gunzip(data: ByteArray): ByteArray {
        return java.util.zip.GZIPInputStream(data.inputStream()).use { it.readBytes() }
    }

    @Test
    fun audioFrameBytes_matchesScriptFrameFormat() {
        // 流式协议音频帧：[0x11, 0x20/0x22, 0x01, 0x00]＋int32BE(gzip 长)
        // ＋gzip(PCM)，解压后须等于原 PCM。
        val empty = PluginHost.buildAudioFrameBytes(ByteArray(0))
        assertEquals(0x11.toByte(), empty[0])
        assertEquals(0x20.toByte(), empty[1])
        assertEquals(0x01.toByte(), empty[2])
        assertEquals(0x00.toByte(), empty[3])
        assertTrue(gunzip(empty.copyOfRange(8, empty.size)).contentEquals(ByteArray(0)))
        val pcm = ByteArray(1280) { (it % 251).toByte() }
        val frame = PluginHost.buildAudioFrameBytes(pcm)
        assertEquals(0x11.toByte(), frame[0])
        assertEquals(0x20.toByte(), frame[1])
        assertEquals(0x01.toByte(), frame[2])
        assertEquals(0x00.toByte(), frame[3])
        val gzLen = ((frame[4].toInt() and 0xFF) shl 24) or
            ((frame[5].toInt() and 0xFF) shl 16) or
            ((frame[6].toInt() and 0xFF) shl 8) or
            (frame[7].toInt() and 0xFF)
        assertEquals(frame.size - 8, gzLen)
        assertTrue(gunzip(frame.copyOfRange(8, frame.size)).contentEquals(pcm))
        val last = PluginHost.buildAudioFrameBytes(pcm, last = true)
        assertEquals(0x22.toByte(), last[1])
        assertTrue(gunzip(last.copyOfRange(8, last.size)).contentEquals(pcm))
    }

    @Test
    fun audioFrameBridge_roundTripsFullChunk() {
        // 整块 1280 字节 PCM 经宿主组帧桥接往返，与 buildAudioFrameBytes 一致。
        val script = baseScript(
            "function audio(ctx) { var f = net.audioFrame(ctx.pcmBase64); " +
                "if (f) cb.partial(f); else cb.partial('null'); }",
        )
        val listener = RecordingListener()
        val host = PluginHost(
            fixture(script),
            mapOf("model" to "a"),
            MapSecretStore(),
            FakeTransport(),
            listener,
            JdkBase64Codec,
        )
        val pcm = ByteArray(1280) { (it % 251).toByte() }
        try {
            host.start()
            assertTrue(listener.ready.await(10, TimeUnit.SECONDS))
            host.audio(JdkBase64Codec.encodeToString(pcm))
            assertTrue(listener.partialLatch.await(10, TimeUnit.SECONDS))
            assertEquals(
                listOf(JdkBase64Codec.encodeToString(PluginHost.buildAudioFrameBytes(pcm))),
                listener.partials,
            )
            assertEquals(1, listener.budgetLatch.count)
        } finally {
            host.kill()
        }
    }

    @Test
    fun configFrameBridge_roundTripsJson() {
        // 配置帧：[0x11, 0x10, 0x11, 0x00]＋长度＋gzip(JSON)。
        val json = "{\"model_name\":\"bigmodel\"}".toByteArray(Charsets.UTF_8)
        val frame = PluginHost.buildConfigFrameBytes(json)
        assertEquals(0x11.toByte(), frame[0])
        assertEquals(0x10.toByte(), frame[1])
        assertEquals(0x11.toByte(), frame[2])
        assertEquals(0x00.toByte(), frame[3])
        assertTrue(gunzip(frame.copyOfRange(8, frame.size)).contentEquals(json))
        val script = baseScript(
            "function audio(ctx) { var f = net.configFrame(ctx.pcmBase64); " +
                "if (f) cb.partial(f); else cb.partial('null'); }",
        )
        val listener = RecordingListener()
        val host = PluginHost(
            fixture(script),
            mapOf("model" to "a"),
            MapSecretStore(),
            FakeTransport(),
            listener,
            JdkBase64Codec,
        )
        try {
            host.start()
            assertTrue(listener.ready.await(10, TimeUnit.SECONDS))
            host.audio(JdkBase64Codec.encodeToString(json))
            assertTrue(listener.partialLatch.await(10, TimeUnit.SECONDS))
            assertEquals(
                listOf(JdkBase64Codec.encodeToString(PluginHost.buildConfigFrameBytes(json))),
                listener.partials,
            )
        } finally {
            host.kill()
        }
    }

    @Test
    fun gunzipBridge_roundTripsGzipPayload() {
        // gunzip 桥：服务端 gzip 载荷经桥解压后原样返回。
        val raw = "hello-response".toByteArray(Charsets.UTF_8)
        val gzOut = java.io.ByteArrayOutputStream()
        java.util.zip.GZIPOutputStream(gzOut).use { it.write(raw) }
        val script = baseScript(
            "function audio(ctx) { var d = net.gunzip(ctx.pcmBase64); " +
                "if (d) cb.partial(d); else cb.partial('null'); }",
        )
        val listener = RecordingListener()
        val host = PluginHost(
            fixture(script),
            mapOf("model" to "a"),
            MapSecretStore(),
            FakeTransport(),
            listener,
            JdkBase64Codec,
        )
        try {
            host.start()
            assertTrue(listener.ready.await(10, TimeUnit.SECONDS))
            host.audio(JdkBase64Codec.encodeToString(gzOut.toByteArray()))
            assertTrue(listener.partialLatch.await(10, TimeUnit.SECONDS))
            assertEquals(
                listOf(JdkBase64Codec.encodeToString(raw)),
                listener.partials,
            )
        } finally {
            host.kill()
        }
    }

    @Test
    fun secretSettings_neverVisibleToScript() {
        val script = baseScript(
            "function audio(ctx) { if (typeof ctx.settings.api_key === 'undefined') { cb.partial('no-secret'); } else { cb.partial('LEAK'); } }",
        )
        val listener = RecordingListener()
        // 故意误传密钥进设置：宿主必须按元数据过滤掉。
        val host = PluginHost(
            fixture(script),
            mapOf("api_key" to "fixture-secret", "model" to "a"),
            MapSecretStore(),
            FakeTransport(),
            listener,
            JdkBase64Codec,
        )
        try {
            host.start()
            assertTrue(listener.ready.await(10, TimeUnit.SECONDS))
            host.audio("AAAA")
            assertTrue(listener.partialLatch.await(10, TimeUnit.SECONDS))
            assertEquals(listOf("no-secret"), listener.partials)
        } finally {
            host.kill()
        }
    }

    @Test
    fun budgetExceeded_disablesPlugin() {
        val script = baseScript("function audio(ctx) { while (true) { } }")
        val listener = RecordingListener()
        val host = PluginHost(
            fixture(script),
            emptyMap(),
            MapSecretStore(),
            FakeTransport(),
            listener,
            JdkBase64Codec,
        )
        try {
            host.start()
            assertTrue(listener.ready.await(10, TimeUnit.SECONDS))
            host.audio("AAAA")
            assertTrue(listener.budgetLatch.await(10, TimeUnit.SECONDS))
        } finally {
            host.kill()
        }
    }

    @Test
    fun hostileScript_failsAtLoad() {
        val script = baseScript("function audio(ctx) { Packages.java.lang.System.exit(0); }")
        val listener = RecordingListener()
        val host = PluginHost(
            fixture(script),
            emptyMap(),
            MapSecretStore(),
            FakeTransport(),
            listener,
            JdkBase64Codec,
        )
        try {
            host.start()
            assertTrue(listener.ready.await(10, TimeUnit.SECONDS))
            // Packages 引用只在函数调用时才解析：必须喂一块音频才触发。
            host.audio("AAAA")
            assertTrue(listener.errorLatch.await(10, TimeUnit.SECONDS))
        } finally {
            host.kill()
        }
    }

    @Test
    fun filesBridge_scopedToSandbox() {
        val script = baseScript(
            """
            function audio(ctx) {
              var ok = files.write('note.txt', 'abc');
              var back = files.read('note.txt');
              var evil = files.write('../evil.txt', 'x');
              if (ok && back === 'abc' && !evil) { cb.partial('files-ok'); }
            }
            """.trimIndent(),
        )
        val plugin = fixture(script)
        val listener = RecordingListener()
        val host = PluginHost(
            plugin,
            emptyMap(),
            MapSecretStore(),
            FakeTransport(),
            listener,
            JdkBase64Codec,
        )
        try {
            host.start()
            assertTrue(listener.ready.await(10, TimeUnit.SECONDS))
            host.audio("AAAA")
            assertTrue(listener.partialLatch.await(10, TimeUnit.SECONDS))
            assertEquals(listOf("files-ok"), listener.partials)
            assertTrue(File(plugin.sandboxDir, "note.txt").exists())
            assertTrue(!File(plugin.sandboxDir.parentFile, "evil.txt").exists())
        } finally {
            host.kill()
        }
    }

    @Test
    fun netBridge_attachesAuthAndReturnsBody() {
        val script = baseScript(
            """
            function audio(ctx) {
              var r = net.httpPost('https://asr.example.com/v1/asr', {}, '', 'application/json');
              if (r.ok) { cb.partial('posted'); } else { cb.partial('failed:' + r.error); }
            }
            """.trimIndent(),
        )
        val transport = FakeTransport()
        val secrets = MapSecretStore()
        secrets.put("p1", "api_key", "fixture-secret")
        val listener = RecordingListener()
        val host = PluginHost(
            fixture(script),
            emptyMap(),
            secrets,
            transport,
            listener,
            JdkBase64Codec,
        )
        try {
            host.start()
            assertTrue(listener.ready.await(10, TimeUnit.SECONDS))
            host.audio("AAAA")
            assertTrue(listener.partialLatch.await(10, TimeUnit.SECONDS))
            assertEquals(listOf("posted"), listener.partials)
            val sent = transport.requests.single()
            assertEquals("Bearer fixture-secret", sent.headers["Authorization"])
        } finally {
            host.kill()
        }
    }

    @Test
    fun missingAudioFunction_failsFast() {
        val script = """
            register({
              id: 'p1', version: '1.0', hosts: ['asr.example.com'],
              features: [{ type: 'voice.asr', sampleRate: 16000 }]
            });
            function start(ctx) {}
        """.trimIndent()
        val listener = RecordingListener()
        val host = PluginHost(
            fixture(script),
            emptyMap(),
            MapSecretStore(),
            FakeTransport(),
            listener,
            JdkBase64Codec,
        )
        try {
            host.start()
            assertTrue(listener.errorLatch.await(10, TimeUnit.SECONDS))
        } finally {
            host.kill()
        }
    }

    @Test
    fun scriptSelfEnd_deliversEnded() {
        val script = baseScript("function audio(ctx) { cb.ended(); }")
        val listener = RecordingListener()
        val host = PluginHost(
            fixture(script),
            emptyMap(),
            MapSecretStore(),
            FakeTransport(),
            listener,
            JdkBase64Codec,
        )
        try {
            host.start()
            assertTrue(listener.ready.await(10, TimeUnit.SECONDS))
            host.audio("AAAA")
            assertTrue(listener.endedLatch.await(10, TimeUnit.SECONDS))
        } finally {
            host.kill()
        }
    }

    // ---- 本批新增：网络执行器／WS 编码前拦截／音频有界队列／kill 取消 ----

    private class DummyWsHandle : NetworkTransport.WsHandle {
        override fun send(text: String): Boolean = true
        override fun sendBytes(bytes: ByteArray): Boolean = true
        override fun close() {}
    }

    private class RecordingTransport : NetworkTransport {
        @Volatile var httpThreadName: String? = null
        override fun httpPost(url: String, headers: Map<String, String>, body: ByteArray): HttpResponse {
            httpThreadName = Thread.currentThread().name
            return HttpResponse(200, "ok".toByteArray())
        }
        override fun websocket(
            url: String,
            headers: Map<String, String>,
            listener: NetworkTransport.WsListener,
        ): NetworkTransport.WsHandle {
            return DummyWsHandle()
        }
    }

    private class CapturingTransport : NetworkTransport {
        @Volatile var wsListener: NetworkTransport.WsListener? = null
        val connectLatch = CountDownLatch(1)
        override fun httpPost(url: String, headers: Map<String, String>, body: ByteArray): HttpResponse {
            return HttpResponse(200, "ok".toByteArray())
        }
        override fun websocket(
            url: String,
            headers: Map<String, String>,
            listener: NetworkTransport.WsListener,
        ): NetworkTransport.WsHandle {
            wsListener = listener
            connectLatch.countDown()
            return DummyWsHandle()
        }
    }

    private class BlockingTransport : NetworkTransport {
        val startedLatch = CountDownLatch(1)
        val releaseLatch = CountDownLatch(1)
        private val calls = AtomicInteger(0)
        override fun httpPost(url: String, headers: Map<String, String>, body: ByteArray): HttpResponse {
            if (calls.incrementAndGet() == 1) {
                startedLatch.countDown()
                releaseLatch.await(30, TimeUnit.SECONDS)
            }
            return HttpResponse(200, "ok".toByteArray())
        }
        override fun websocket(
            url: String,
            headers: Map<String, String>,
            listener: NetworkTransport.WsListener,
        ): NetworkTransport.WsHandle {
            return DummyWsHandle()
        }
    }

    private class CancellableBlockingTransport : NetworkTransport, CancellableTransport {
        val startedLatch = CountDownLatch(1)
        val blockLatch = CountDownLatch(1)
        val completedLatch = CountDownLatch(1)
        override fun httpPost(url: String, headers: Map<String, String>, body: ByteArray): HttpResponse {
            startedLatch.countDown()
            blockLatch.await(30, TimeUnit.SECONDS)
            completedLatch.countDown()
            return HttpResponse(200, "ok".toByteArray())
        }
        override fun cancelInflight() {
            blockLatch.countDown()
        }
        override fun websocket(
            url: String,
            headers: Map<String, String>,
            listener: NetworkTransport.WsListener,
        ): NetworkTransport.WsHandle {
            return DummyWsHandle()
        }
    }

    private fun wsScript(): String = """
        register({
          id: 'p1', version: '1.0', hosts: ['asr.example.com'],
          features: [{ type: 'voice.asr', sampleRate: 16000 }],
          settings: [
            { key: 'api_key', type: 'secret', label: '密钥' },
            { key: 'model', type: 'text', label: '模型' }
          ],
          auth: [{ header: 'Authorization', field: 'api_key', prefix: 'Bearer ' }]
        });
        function start(ctx) { net.websocketConnect('wss://asr.example.com/ws', {}); }
        function stop(ctx) {}
        function audio(ctx) {}
        function onMessage(ctx) { cb.partial(ctx.data); }
    """.trimIndent()

    private fun wsHost(transport: NetworkTransport): Pair<PluginHost, RecordingListener> {
        val secrets = MapSecretStore()
        secrets.put("p1", "api_key", "fixture-secret")
        val listener = RecordingListener()
        val host = PluginHost(fixture(wsScript()), emptyMap(), secrets, transport, listener, JdkBase64Codec)
        return host to listener
    }

    @Test
    fun httpPost_runsOnNetworkExecutor() {
        val script = baseScript(
            "function audio(ctx) { var r = net.httpPost('https://asr.example.com/v1/asr', {}, '', 'application/json'); if (r.ok) { cb.partial('posted'); } else { cb.partial('failed:' + r.error); } }",
        )
        val transport = RecordingTransport()
        val secrets = MapSecretStore()
        secrets.put("p1", "api_key", "fixture-secret")
        val listener = RecordingListener()
        val host = PluginHost(fixture(script), emptyMap(), secrets, transport, listener, JdkBase64Codec)
        try {
            host.start()
            assertTrue(listener.ready.await(10, TimeUnit.SECONDS))
            host.audio("AAAA")
            assertTrue(listener.partialLatch.await(10, TimeUnit.SECONDS))
            assertEquals(listOf("posted"), listener.partials)
            assertEquals("cloud-voice-network", transport.httpThreadName)
        } finally {
            host.kill()
        }
    }

    @Test
    fun wsInboundSmallBytes_delivered() {
        val transport = CapturingTransport()
        val (host, listener) = wsHost(transport)
        try {
            host.start()
            assertTrue(listener.ready.await(10, TimeUnit.SECONDS))
            assertTrue(transport.connectLatch.await(10, TimeUnit.SECONDS))
            val ws = transport.wsListener ?: error("websocket listener must be captured")
            ws.onMessage(null, "hi".toByteArray())
            assertTrue(listener.partialLatch.await(10, TimeUnit.SECONDS))
            assertEquals(listOf("aGk="), listener.partials)
        } finally {
            host.kill()
        }
    }

    @Test
    fun wsInboundOversizeBytes_droppedBeforeEncode() {
        val transport = CapturingTransport()
        val (host, listener) = wsHost(transport)
        try {
            host.start()
            assertTrue(listener.ready.await(10, TimeUnit.SECONDS))
            assertTrue(transport.connectLatch.await(10, TimeUnit.SECONDS))
            val ws = transport.wsListener ?: error("websocket listener must be captured")
            ws.onMessage(null, ByteArray(900_000) { 7 })
            Thread.sleep(500)
            assertEquals(emptyList<String>(), listener.partials)
            assertEquals(1L, listener.partialLatch.count)
        } finally {
            host.kill()
        }
    }

    @Test
    fun audioQueue_boundedAndDropsWhenWorkerBlocked() {
        val script = baseScript(
            "function audio(ctx) { net.httpPost('https://asr.example.com/v1/asr', {}, '', 'application/json'); }",
        )
        val transport = BlockingTransport()
        val secrets = MapSecretStore()
        secrets.put("p1", "api_key", "fixture-secret")
        val listener = RecordingListener()
        val host = PluginHost(fixture(script), emptyMap(), secrets, transport, listener, JdkBase64Codec)
        try {
            host.start()
            assertTrue(listener.ready.await(10, TimeUnit.SECONDS))
            host.audio("AAAA")
            assertTrue(transport.startedLatch.await(10, TimeUnit.SECONDS))
            repeat(40) { host.audio("AAAA") }
            assertTrue(
                "backlog must stay bounded, was=${host.audioBacklogSize}",
                host.audioBacklogSize <= PluginHost.MAX_AUDIO_BACKLOG,
            )
            assertTrue(
                "congestion must drop blocks, dropped=${host.audioDroppedCount}",
                host.audioDroppedCount > 0,
            )
            transport.releaseLatch.countDown()
            val deadline = System.currentTimeMillis() + 10_000
            while (host.audioBacklogSize != 0 && System.currentTimeMillis() < deadline) {
                Thread.sleep(50)
            }
            assertEquals(0, host.audioBacklogSize)
            assertEquals(1L, listener.errorLatch.count)
        } finally {
            transport.releaseLatch.countDown()
            host.kill()
        }
    }

    @Test
    fun kill_cancelsInflightHttp() {
        val script = baseScript(
            "function audio(ctx) { net.httpPost('https://asr.example.com/v1/asr', {}, '', 'application/json'); }",
        )
        val transport = CancellableBlockingTransport()
        val secrets = MapSecretStore()
        secrets.put("p1", "api_key", "fixture-secret")
        val listener = RecordingListener()
        val host = PluginHost(fixture(script), emptyMap(), secrets, transport, listener, JdkBase64Codec)
        try {
            host.start()
            assertTrue(listener.ready.await(10, TimeUnit.SECONDS))
            host.audio("AAAA")
            assertTrue(transport.startedLatch.await(10, TimeUnit.SECONDS))
            host.kill()
            // 关键断言：测试线程从未手动放行，放行只能来自 kill 的取消。
            assertTrue(transport.completedLatch.await(5, TimeUnit.SECONDS))
            val deadline = System.currentTimeMillis() + 5_000
            while (host.audioBacklogSize != 0 && System.currentTimeMillis() < deadline) {
                Thread.sleep(50)
            }
            assertEquals(0, host.audioBacklogSize)
        } finally {
            transport.blockLatch.countDown()
            host.kill()
        }
    }
}
