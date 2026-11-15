// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.script

import android.content.Context
import android.content.ContextWrapper
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 脚本宿主单测（WO52 8.1）：$http 参数解析（纯函数）、AndroidScriptHost
 * 的 6 个 API（fake 注入，不碰 Android 框架方法——Robolectric 禁用，
 * 故用 DataAuditFixesTest 同款 FakeContext（ContextWrapper(null)＋
 * getApplicationContext()=this），不调系统服务）、OkHttp 执行器对本地
 * JDK HttpServer 的真实往返。
 */
class ScriptHostTest {

    private class FakeContext : ContextWrapper(null) {
        override fun getApplicationContext(): Context = this
    }

    private val fakeContext: Context get() = FakeContext()

    private var httpServer: HttpServer? = null

    @After
    fun tearDown() {
        httpServer?.stop(0)
        httpServer = null
    }

    // --- HttpRequestParser ---

    @Test
    fun parse_getDefaults() {
        val req = HttpRequestParser.parse("""{"url":"https://example.com/x"}""")
        assertEquals("https://example.com/x", req.url)
        assertEquals("GET", req.method)
        assertTrue(req.headers.isEmpty())
        assertEquals(null, req.body)
        assertEquals(15.0, req.timeoutSeconds, 0.0)
    }

    @Test
    fun parse_methodUppercasedAndHeadersKept() {
        val req = HttpRequestParser.parse(
            """{"url":"https://example.com/x","method":"post","header":{"X-A":"1","Content-Type":"application/json"},"body":{"a":1}}""",
        )
        assertEquals("POST", req.method)
        assertEquals("1", req.headers["X-A"])
        assertEquals("application/json", req.mediaType)
        assertEquals("""{"a":1}""", req.body?.toString(Charsets.UTF_8))
    }

    @Test
    fun parse_formBody_urlEncoded() {
        val req = HttpRequestParser.parse(
            """{"url":"https://example.com/x","method":"POST","header":{"Content-Type":"application/x-www-form-urlencoded"},"body":{"q":"a b","n":"1"}}""",
        )
        assertEquals("q=a%20b&n=1", req.body?.toString(Charsets.UTF_8))
    }

    @Test
    fun parse_emptyBodyMethods_getEmptyBody() {
        val req = HttpRequestParser.parse("""{"url":"https://example.com/x","method":"POST"}""")
        assertEquals(0, req.body?.size)
    }

    @Test
    fun parse_getIgnoresBody() {
        val req = HttpRequestParser.parse(
            """{"url":"https://example.com/x","method":"GET","body":{"a":1}}""",
        )
        assertEquals(null, req.body)
    }

    @Test
    fun parse_timeoutClamped() {
        assertEquals(0.1, HttpRequestParser.parse("""{"url":"https://example.com/","timeout":0}""").timeoutSeconds, 0.0)
        assertEquals(60.0, HttpRequestParser.parse("""{"url":"https://example.com/","timeout":600}""").timeoutSeconds, 0.0)
        assertEquals(15.0, HttpRequestParser.parse("""{"url":"https://example.com/","timeout":"bad"}""").timeoutSeconds, 0.0)
        assertEquals(2.5, HttpRequestParser.parse("""{"url":"https://example.com/","timeout":"2.5"}""").timeoutSeconds, 0.0)
    }

    @Test(expected = HttpParamException::class)
    fun parse_missingUrl_throws() {
        HttpRequestParser.parse("""{"method":"GET"}""")
    }

    @Test(expected = HttpParamException::class)
    fun parse_nonHttpUrl_throws() {
        HttpRequestParser.parse("""{"url":"ftp://example.com/x"}""")
    }

    @Test(expected = HttpParamException::class)
    fun parse_badJson_throws() {
        HttpRequestParser.parse("not json")
    }

    @Test(expected = HttpParamException::class)
    fun parse_nonObject_throws() {
        HttpRequestParser.parse("""["a"]""")
    }

    // --- AndroidScriptHost dispatch ---

    private fun fakeHost(
        clipboard: MutableList<String> = mutableListOf(),
        inserted: MutableList<String> = mutableListOf(),
        opened: MutableList<String> = mutableListOf(),
        notified: MutableList<Triple<String, String, String>> = mutableListOf(),
        httpExecutor: HttpExecutor = HttpExecutor { _, _, _ -> },
    ): AndroidScriptHost {
        val context = fakeContext
        return AndroidScriptHost(
            context,
            httpClient = OkHttpClient(),
            httpExecutor = httpExecutor,
            onInsertText = { inserted.add(it) },
            clipboardWriter = { clipboard.add(it) },
            urlOpener = { opened.add(it); true },
            notifier = { title, shortcut, content ->
                notified.add(Triple(title, shortcut, content))
                true
            },
        )
    }

    @Test
    fun dispatch_logJoinsArgs() {
        val logs = mutableListOf<String>()
        val context = fakeContext
        val host = AndroidScriptHost(context, logSink = { logs.add(it) })
        host.dispatch("\$log", intArrayOf(2, 3), arrayOf("a", "1"), arrayOf("", ""))
        assertEquals(listOf("a 1"), logs)
    }

    @Test
    fun dispatch_httpReturnsIncrementingIdsAndInvalidUrlCompletesRejected() {
        val completions = mutableListOf<HttpCompletion>()
        val context = fakeContext
        val host = AndroidScriptHost(
            context,
            httpExecutor = HttpExecutor { _, _, _ -> },
            onHttpCompletionListener = { completions.add(it) },
        )
        val id1 = host.dispatch("\$http", intArrayOf(4), arrayOf(""), arrayOf("""{"url":"https://example.com/"}""")) as Long
        val id2 = host.dispatch("\$http", intArrayOf(4), arrayOf(""), arrayOf("""{"url":"::bad"}""")) as Long
        assertEquals(1L, id1)
        assertEquals(2L, id2)
        assertEquals(1, completions.size)
        assertEquals(2L, completions.single().id)
        assertTrue(completions.single().rejected)
    }

    @Test
    fun dispatch_setClipboardAndInsertText() {
        val clipboard = mutableListOf<String>()
        val inserted = mutableListOf<String>()
        val host = fakeHost(clipboard = clipboard, inserted = inserted)
        host.dispatch("\$setClipboard", intArrayOf(2), arrayOf("txt"), arrayOf(""))
        host.dispatch("\$insertText", intArrayOf(2), arrayOf("hello"), arrayOf(""))
        host.dispatch("\$insertText", intArrayOf(2), arrayOf(""), arrayOf(""))
        assertEquals(listOf("txt"), clipboard)
        assertEquals(listOf("hello"), inserted)
        assertEquals(listOf("hello"), host.insertedTexts)
    }

    @Test
    fun dispatch_openUrlRequiresScheme() {
        val opened = mutableListOf<String>()
        val host = fakeHost(opened = opened)
        assertEquals(true, host.dispatch("\$openURL", intArrayOf(2), arrayOf("https://example.com"), arrayOf("")))
        assertEquals(false, host.dispatch("\$openURL", intArrayOf(2), arrayOf("example.com"), arrayOf("")))
        assertEquals(listOf("https://example.com"), opened)
    }

    @Test
    fun dispatch_userNotificationBlankReturnsFalse() {
        val notified = mutableListOf<Triple<String, String, String>>()
        val host = fakeHost(notified = notified)
        assertEquals(true, host.dispatch("\$userNotification", intArrayOf(2, 2, 2), arrayOf("T", "S", "C"), arrayOf("", "", "")))
        assertEquals(false, host.dispatch("\$userNotification", intArrayOf(2, 2, 2), arrayOf("", "S", "C"), arrayOf("", "", "")))
        assertEquals(false, host.dispatch("\$userNotification", intArrayOf(2, 2, 2), arrayOf("T", "S", ""), arrayOf("", "", "")))
        assertEquals(1, notified.size)
    }

    @Test
    fun dispatch_unknownNameReturnsNull() {
        val host = fakeHost()
        assertEquals(null, host.dispatch("\$nope", intArrayOf(), arrayOf(), arrayOf()))
    }

    // --- OkHttp executor end-to-end ---

    @Test
    fun okHttpExecutor_roundTripsAgainstLocalServer() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/x") { exchange ->
            val body = "hello-body".toByteArray(Charsets.UTF_8)
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
        httpServer = server
        val port = server.address.port
        val latch = CountDownLatch(1)
        var completion: HttpCompletion? = null
        val executor = OkHttpHttpExecutor(OkHttpClient())
        executor.execute(
            42L,
            HttpRequestParser.parse("""{"url":"http://127.0.0.1:$port/x","method":"GET"}"""),
        ) {
            completion = it
            latch.countDown()
        }
        assertTrue(latch.await(10, TimeUnit.SECONDS))
        assertEquals(HttpCompletion(42L, false, "hello-body"), completion)
    }

    @Test
    fun okHttpExecutor_connectionRefused_completesRejected() {
        val latch = CountDownLatch(1)
        var completion: HttpCompletion? = null
        // 端口 1 恒拒绝连接（本机无服务）。
        val executor = OkHttpHttpExecutor(OkHttpClient())
        executor.execute(
            7L,
            HttpRequestParser.parse("""{"url":"http://127.0.0.1:1/x","method":"GET","timeout":5}"""),
        ) {
            completion = it
            latch.countDown()
        }
        assertTrue(latch.await(10, TimeUnit.SECONDS))
        assertEquals(7L, completion?.id)
        assertEquals(true, completion?.rejected)
        assertFalse(completion?.payload.isNullOrBlank())
    }
}
