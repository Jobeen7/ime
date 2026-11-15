// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.script

import com.jobeen.ime.data.database.ScriptSeeds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/** 主机单测用假宿主：记录调用，$http 完成由测试显式投递。 */
class FakeScriptHost : ScriptHost {
    val logs = mutableListOf<String>()
    val clipboards = mutableListOf<String>()
    val inserted = mutableListOf<String>()
    val openedUrls = mutableListOf<String>()
    val notifications = mutableListOf<Triple<String, String, String>>()
    val httpCalls = mutableListOf<String>()
    var logSink: (String) -> Unit = {}
    var deliverCompletion: (HttpCompletion) -> Unit = {}
    private var nextId = 1L

    override fun log(text: String) {
        logs.add(text)
        logSink(text)
    }

    override fun http(json: String): Long {
        val id = nextId++
        httpCalls.add(json)
        return id
    }

    /** 测试显式投递一次 $http 完成（模拟 OkHttp 回调）。 */
    fun completeHttp(id: Long, rejected: Boolean, payload: String) {
        deliverCompletion(HttpCompletion(id, rejected, payload))
    }

    override fun setClipboard(text: String) {
        clipboards.add(text)
    }

    override fun insertText(text: String) {
        if (text.isEmpty()) return
        inserted.add(text)
    }

    override fun openUrl(url: String): Boolean {
        // 与 AndroidScriptHost 同口径：无 scheme 不打开（fake 也照此办，
        // runner 测 $openURL 才有意义）。
        val scheme = try {
            java.net.URI(url).scheme
        } catch (e: Exception) {
            null
        } ?: return false
        if (scheme.isEmpty()) return false
        openedUrls.add(url)
        return true
    }

    override fun userNotification(title: String, shortcutName: String, content: String): Boolean {
        notifications.add(Triple(title, shortcutName, content))
        return title.isNotBlank() && content.isNotBlank()
    }

    override fun onHttpCompletion(completion: HttpCompletion) = Unit
}

/**
 * ScriptRunner 单测（WO52 8.1）：执行约定、6 宿主 API、种子脚本、
 * 超时、取消。全部走 runBlocking（真实时间；kotlinx-coroutines-test
 * 不在测试依赖里，不为单测加新依赖）。
 */
class ScriptRunnerTest {

    @Before
    fun requireHostLib() = assumeHostQuickJsAvailable()

    private val runner = ScriptRunner()

    private fun fakeFactory(captured: (FakeScriptHost) -> Unit = {}): ((
        logSink: (String) -> Unit,
        onHttpCompletion: (HttpCompletion) -> Unit,
    ) -> ScriptHost) = { logSink, onCompletion ->
        FakeScriptHost().also {
            it.logSink = logSink
            it.deliverCompletion = onCompletion
            captured(it)
        }
    }

    @Test
    fun run_defaultNewScript_returnsGreeting() = runBlocking {
        val result = runner.run(
            source = ScriptSeeds.DEFAULT_NEW_SCRIPT_CONTENT,
            inputs = ScriptInputs(searchText = "世界"),
            hostFactory = fakeFactory(),
        )
        assertEquals(listOf("你好，世界"), result.output)
        assertTrue(result.logs.isEmpty())
        assertTrue(result.elapsedSeconds >= 0.0)
    }

    @Test
    fun run_unicodeSeed_returnsCodePoints() = runBlocking {
        val result = runner.run(
            source = ScriptSeeds.UNICODE_QUERY_CONTENT,
            inputs = ScriptInputs(searchText = "中文"),
            hostFactory = fakeFactory(),
        )
        assertEquals(listOf("U+4E2D U+6587"), result.output)
        assertEquals(listOf("查询 2 个码位：中文"), result.logs)
    }

    @Test
    fun run_base64Seed_roundTrips() = runBlocking {
        val encoded = runner.run(
            source = ScriptSeeds.BASE64_ENCODE_CONTENT,
            inputs = ScriptInputs(searchText = "中文 abc"),
            hostFactory = fakeFactory(),
        )
        assertEquals(listOf("5Lit5paHIGFiYw=="), encoded.output)
        val decoded = runner.run(
            source = ScriptSeeds.BASE64_DECODE_CONTENT,
            inputs = ScriptInputs(searchText = "5Lit5paHIGFiYw=="),
            hostFactory = fakeFactory(),
        )
        assertEquals(listOf("中文 abc"), decoded.output)
    }

    @Test
    fun run_rollSeed_returnsOneToHundred() = runBlocking {
        val result = runner.run(
            source = ScriptSeeds.ROLL_CONTENT,
            hostFactory = fakeFactory(),
        )
        assertEquals(1, result.output.size)
        val value = result.output.single().toInt()
        assertTrue(value in 1..100)
        assertEquals(listOf("roll: $value"), result.logs)
    }

    @Test
    fun run_inputPriority_searchTextBeatsSelectedAndPasteboard() = runBlocking {
        val source = "async function output() { return [\$searchText + \"|\" + \$selectedText + \"|\" + \$pasteboardContent]; }"
        val result = runner.run(
            source = source,
            inputs = ScriptInputs(searchText = "a", selectedText = "b", pasteboardContent = "c"),
            hostFactory = fakeFactory(),
        )
        assertEquals(listOf("a|b|c"), result.output)
    }

    @Test
    fun run_hostApis_logClipboardInsertOpenUrlNotification() = runBlocking {
        var fake: FakeScriptHost? = null
        val source = """
            async function output() {
              ${'$'}log("hello");
              ${'$'}setClipboard("copied");
              ${'$'}insertText("inserted");
              ${'$'}insertText("");
              var opened = ${'$'}openURL("https://example.com");
              var noted = ${'$'}userNotification("T", "S", "C");
              return [String(opened), String(noted)];
            }
        """.trimIndent()
        val result = runner.run(
            source = source,
            hostFactory = fakeFactory { fake = it },
        )
        assertEquals(listOf("true", "true"), result.output)
        assertEquals(listOf("hello"), result.logs)
        assertEquals(listOf("copied"), fake!!.clipboards)
        assertEquals(listOf("inserted"), result.insertedTexts)
        assertEquals(listOf("https://example.com"), fake!!.openedUrls)
        assertEquals(listOf(Triple("T", "S", "C")), fake!!.notifications)
    }

    @Test
    fun run_openUrlWithoutScheme_returnsFalse() = runBlocking {
        val result = runner.run(
            source = "async function output() { return [String(\$openURL(\"example.com\"))]; }",
            hostFactory = fakeFactory(),
        )
        assertEquals(listOf("false"), result.output)
    }

    @Test
    fun run_httpCompletion_reachesScriptCallback() = runBlocking {
        var fake: FakeScriptHost? = null
        val source = """
            var result = null;
            function __jimeHttpResult(id, rejected, payload) { result = rejected ? "ERR:" + payload : payload; }
            async function output() {
              var id = ${'$'}http('{"url":"https://example.com/x"}');
              var deadline = Date.now() + 10000;
              while (result === null && Date.now() < deadline) { await Promise.resolve(); }
              return ["id=" + id, "result=" + result];
            }
        """.trimIndent()
        val deferred = async {
            runner.run(source = source, hostFactory = fakeFactory { fake = it })
        }
        delay(500)
        fake!!.completeHttp(1L, false, "hello-body")
        val result = deferred.await()
        assertEquals(listOf("id=1", "result=hello-body"), result.output)
        assertEquals(listOf("{\"url\":\"https://example.com/x\"}"), fake!!.httpCalls)
    }

    @Test
    fun run_httpFailure_reachesScriptCallbackAsError() = runBlocking {
        var fake: FakeScriptHost? = null
        val source = """
            var result = null;
            function __jimeHttpResult(id, rejected, payload) { result = rejected ? "ERR:" + payload : payload; }
            async function output() {
              ${'$'}http('{"url":"https://example.com/x"}');
              var deadline = Date.now() + 10000;
              while (result === null && Date.now() < deadline) { await Promise.resolve(); }
              return [result];
            }
        """.trimIndent()
        val deferred = async {
            runner.run(source = source, hostFactory = fakeFactory { fake = it })
        }
        delay(500)
        fake!!.completeHttp(1L, true, "Invalid HTTP URL")
        val result = deferred.await()
        assertEquals(listOf("ERR:Invalid HTTP URL"), result.output)
    }

    @Test
    fun run_missingOutput_throwsNoOutputFunction() = runBlocking {
        try {
            runner.run(source = "var a = 1;", hostFactory = fakeFactory())
            fail("应抛 ScriptRunException")
        } catch (e: ScriptRunException) {
            assertEquals(ScriptFailure.NoOutputFunction, e.failure)
        }
    }

    @Test
    fun run_rejectedOutput_throwsError() = runBlocking {
        try {
            runner.run(
                source = "async function output() { throw new Error(\"nope\"); }",
                hostFactory = fakeFactory(),
            )
            fail("应抛 ScriptRunException")
        } catch (e: ScriptRunException) {
            val failure = e.failure as ScriptFailure.Error
            assertTrue(failure.message.contains("nope"))
        }
    }

    @Test
    fun run_syntaxError_throwsError() = runBlocking {
        try {
            runner.run(source = "async function output( {", hostFactory = fakeFactory())
            fail("应抛 ScriptRunException")
        } catch (e: ScriptRunException) {
            assertTrue(e.failure is ScriptFailure.Error)
        }
    }

    @Test
    fun run_infiniteLoop_throwsTimeout() = runBlocking {
        try {
            runner.run(
                source = "async function output() { while (true) {} }",
                hostFactory = fakeFactory(),
                timeoutNanos = 2_000_000_000L,
            )
            fail("应抛 ScriptRunException")
        } catch (e: ScriptRunException) {
            assertEquals(ScriptFailure.Timeout, e.failure)
        }
    }

    @Test
    fun run_pendingPromise_cancelThrowsCancellation() = runBlocking {
        val started = System.nanoTime()
        val deferred = async {
            runner.run(
                source = "async function output() { await new Promise(function() {}); return [\"never\"]; }",
                hostFactory = fakeFactory(),
            )
        }
        delay(300)
        deferred.cancel()
        try {
            deferred.await()
            fail("应抛 CancellationException")
        } catch (e: CancellationException) {
            // 预期：取消原样抛出（引擎已销毁）。C-L-02：取消不须等
            // 30 秒截止——300ms 后取消，数秒内即返回。
            val elapsedMs = (System.nanoTime() - started) / 1_000_000
            assertTrue("取消应在截止前返回，实际 ${elapsedMs}ms", elapsedMs < 5_000)
        }
    }
}
