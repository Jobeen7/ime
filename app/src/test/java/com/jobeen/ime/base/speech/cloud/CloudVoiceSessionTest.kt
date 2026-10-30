package com.jobeen.ime.base.speech.cloud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 云端会话：真实引擎宿主＋内存传输／密钥／录音替身，验
 * 预览／提交／结算／失败／超时停用的端到端收尾。
 */
class CloudVoiceSessionTest {

    private class RecordingSink : CloudTextSink {
        val calls = mutableListOf<String>()
        override fun setPreview(text: String) {
            calls.add("preview:$text")
        }

        override fun commit(text: String) {
            calls.add("commit:$text")
        }

        override fun clearPreview() {
            calls.add("clear")
        }
    }

    private class SessionListener : CloudVoiceSession.Listener {
        val started = CountDownLatch(1)
        val done = CountDownLatch(1)
        val failed = CountDownLatch(1)
        var failure: CloudSessionFailure? = null

        override fun onStarted() {
            started.countDown()
        }

        override fun onAmplitude(amp: Float) = Unit

        override fun onDone() {
            done.countDown()
        }

        override fun onFailed(reason: CloudSessionFailure) {
            failure = reason
            failed.countDown()
        }
    }

    private class FakeRecorder : CloudRecorder {
        var onChunk: ((ByteArray) -> Unit)? = null
        var onAmplitude: ((Float) -> Unit)? = null
        var onInitFailed: (() -> Unit)? = null
        var onCaptureFailed: (() -> Unit)? = null
        var onFocusLost: (() -> Unit)? = null
        var started = false
        var stopped = false

        override fun start() {
            started = true
        }

        override fun stop() {
            stopped = true
        }
    }

    private fun fixture(script: String): PluginStore.InstalledPlugin {
        val dir = Files.createTempDirectory("session-test").toFile()
        val pluginDir = File(dir, "p1").apply { mkdirs() }
        val scriptFile = File(pluginDir, "plugin.js").apply { writeText(script) }
        val sandbox = File(pluginDir, "files").apply { mkdirs() }
        val meta = PluginMeta(
            id = "p1",
            version = "1.0",
            hosts = listOf("asr.example.com"),
            capabilities = listOf("voice.asr"),
        )
        return PluginStore.InstalledPlugin(meta, scriptFile, sandbox)
    }

    private fun baseScript(body: String): String = """
        register({
          id: 'p1', version: '1.0', hosts: ['asr.example.com'],
          features: [{ type: 'voice.asr', sampleRate: 16000 }]
        });
        function start(ctx) {}
        function stop(ctx) {}
        $body
    """.trimIndent()

    private fun session(
        script: String,
        sink: RecordingSink,
        listener: SessionListener,
        recorder: FakeRecorder,
        onBudgetDisabled: () -> Unit = {},
    ): CloudVoiceSession = CloudVoiceSession(
        plugin = fixture(script),
        settings = emptyMap(),
        secrets = MapSecretStore(),
        sink = sink,
        listener = listener,
        markBudgetDisabled = onBudgetDisabled,
        recorderFactory = { onChunk, onAmplitude, onInitFailed, onCaptureFailed, onFocusLost ->
            recorder.onChunk = onChunk
            recorder.onAmplitude = onAmplitude
            recorder.onInitFailed = onInitFailed
            recorder.onCaptureFailed = onCaptureFailed
            recorder.onFocusLost = onFocusLost
            recorder
        },
        networkAvailable = { true },
        transport = FakeTransport(),
        codec = JdkBase64Codec,
    )

    @Test
    fun session_partialPreviews_finalCommits() {
        val sink = RecordingSink()
        val listener = SessionListener()
        val recorder = FakeRecorder()
        val script = baseScript(
            "function audio(ctx) { if (ctx.pcmBase64 === 'AAAA') { cb.partial('草稿'); } else { cb.final('成稿'); } }",
        )
        val s = session(script, sink, listener, recorder)
        s.start()
        assertTrue(listener.started.await(10, TimeUnit.SECONDS))
        assertTrue(recorder.started)
        recorder.onChunk!!.invoke(byteArrayOf(0, 0, 0))
        Thread.sleep(300)
        assertTrue(sink.calls.contains("preview:草稿"))
        recorder.onChunk!!.invoke(byteArrayOf(1, 2, 3))
        Thread.sleep(300)
        assertTrue(sink.calls.contains("commit:成稿"))
        s.stop(discard = false)
        assertTrue(recorder.stopped)
    }

    @Test
    fun session_scriptEndedWithoutFinal_commitsLatestPartial_andNotifiesDone() {
        val sink = RecordingSink()
        val listener = SessionListener()
        val recorder = FakeRecorder()
        val script = baseScript(
            "function audio(ctx) { cb.partial('半句'); cb.ended(); }",
        )
        val s = session(script, sink, listener, recorder)
        s.start()
        assertTrue(listener.started.await(10, TimeUnit.SECONDS))
        recorder.onChunk!!.invoke(byteArrayOf(0))
        assertTrue(listener.done.await(10, TimeUnit.SECONDS))
        assertEquals(listOf("preview:半句", "commit:半句"), sink.calls)
    }

    @Test
    fun session_scriptError_clearsPreview_andNotifiesFailed_lateResultsDropped() {
        val sink = RecordingSink()
        val listener = SessionListener()
        val recorder = FakeRecorder()
        val script = baseScript(
            "function audio(ctx) { cb.partial('半句'); cb.error('boom'); }",
        )
        val s = session(script, sink, listener, recorder)
        s.start()
        assertTrue(listener.started.await(10, TimeUnit.SECONDS))
        recorder.onChunk!!.invoke(byteArrayOf(0))
        assertTrue(listener.failed.await(10, TimeUnit.SECONDS))
        assertEquals(CloudSessionFailure.GENERIC, listener.failure)
        assertEquals(listOf("preview:半句", "clear"), sink.calls)
    }

    @Test
    fun session_stopDiscard_clears_stopKeep_commitsPartial() {
        val sink = RecordingSink()
        val listener = SessionListener()
        val recorder = FakeRecorder()
        val script = baseScript("function audio(ctx) { cb.partial('留着'); }")
        val s = session(script, sink, listener, recorder)
        s.start()
        assertTrue(listener.started.await(10, TimeUnit.SECONDS))
        recorder.onChunk!!.invoke(byteArrayOf(0))
        Thread.sleep(300)
        s.stop(discard = true)
        assertEquals(listOf("preview:留着", "clear"), sink.calls)

        val sink2 = RecordingSink()
        val listener2 = SessionListener()
        val recorder2 = FakeRecorder()
        val s2 = session(script, sink2, listener2, recorder2)
        s2.start()
        assertTrue(listener2.started.await(10, TimeUnit.SECONDS))
        recorder2.onChunk!!.invoke(byteArrayOf(0))
        Thread.sleep(300)
        s2.stop(discard = false)
        assertEquals(listOf("preview:留着", "commit:留着"), sink2.calls)
    }

    @Test
    fun session_budgetLoop_disablesPlugin_andNotifiesBudget() {
        val sink = RecordingSink()
        val listener = SessionListener()
        val recorder = FakeRecorder()
        var budgetMarked = false
        val script = baseScript("function audio(ctx) { while (true) {} }")
        val s = session(script, sink, listener, recorder) { budgetMarked = true }
        s.start()
        assertTrue(listener.started.await(10, TimeUnit.SECONDS))
        recorder.onChunk!!.invoke(byteArrayOf(0))
        assertTrue(listener.failed.await(10, TimeUnit.SECONDS))
        assertEquals(CloudSessionFailure.BUDGET, listener.failure)
        assertTrue(budgetMarked)
        assertTrue(sink.calls.contains("clear"))
    }

    @Test
    fun session_recorderInitFailed_notifiesFailed() {
        val sink = RecordingSink()
        val listener = SessionListener()
        val recorder = FakeRecorder()
        val script = baseScript("function audio(ctx) {}")
        val s = session(script, sink, listener, recorder)
        s.start()
        assertTrue(listener.started.await(10, TimeUnit.SECONDS))
        recorder.onInitFailed!!.invoke()
        assertTrue(listener.failed.await(10, TimeUnit.SECONDS))
        assertEquals(CloudSessionFailure.GENERIC, listener.failure)
    }

    @Test
    fun session_recorderCaptureFailed_settlesPartial_andNotifiesDone() {
        val sink = RecordingSink()
        val listener = SessionListener()
        val recorder = FakeRecorder()
        val script = baseScript("function audio(ctx) { cb.partial('半句'); }")
        val s = session(script, sink, listener, recorder)
        s.start()
        assertTrue(listener.started.await(10, TimeUnit.SECONDS))
        recorder.onChunk!!.invoke(byteArrayOf(0))
        Thread.sleep(300)
        recorder.onCaptureFailed!!.invoke()
        assertTrue(listener.done.await(10, TimeUnit.SECONDS))
        assertEquals(listOf("preview:半句", "commit:半句"), sink.calls)
    }

    @Test
    fun session_networkUnavailable_failsWithoutStartingRecorder() {
        val sink = RecordingSink()
        val listener = SessionListener()
        val recorder = FakeRecorder()
        val script = baseScript("function audio(ctx) {}")
        val s = CloudVoiceSession(
            plugin = fixture(script),
            settings = emptyMap(),
            secrets = MapSecretStore(),
            sink = sink,
            listener = listener,
            markBudgetDisabled = {},
            recorderFactory = { _, _, _, _, _ -> recorder },
            networkAvailable = { false },
            transport = FakeTransport(),
            codec = JdkBase64Codec,
        )
        s.start()
        assertTrue(listener.failed.await(10, TimeUnit.SECONDS))
        assertEquals(CloudSessionFailure.NETWORK, listener.failure)
        assertFalse(recorder.started)
    }
}
