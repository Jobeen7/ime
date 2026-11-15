package com.jobeen.ime.base.webserver

import android.content.SharedPreferences
import java.io.File
import java.net.InetAddress
import java.net.Socket
import java.nio.file.Files
import java.security.MessageDigest
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * WO54 W54-8：HTTPS 层主机单测（真实 SSLServerSocket＋TLS 客户端，
 * 127.0.0.1 临时端口）。
 *
 * 证书生成本身测不了（主机 JVM 上没有 AndroidKeyStore）；测的是
 * 指纹、换发检测、握手、http 拒绝、协议选择。
 */
class WebServerHttpsTest {

    private lateinit var tempRoot: File
    private lateinit var server: WebServer
    private var port: Int = -1

    private val passwordHash = WifiTransferAuth.hashPassword("secret123")

    @Before
    fun setUp() {
        tempRoot = Files.createTempDirectory("webserver-https-test").toFile()
        val store = FileWebServerStore(tempRoot) { 10_000L }
        val actions = object : WebServerActions {
            override suspend fun deploy(): Boolean = true
            override suspend fun sync(): Boolean = true
            override fun applySkin(name: String, skinJson: String, manifestJson: String): Result<Unit> =
                Result.success(Unit)

            override suspend fun switchUserDataDir(dir: String): SwitchUserDataResult =
                SwitchUserDataResult.OK

            override fun staticAsset(relativePath: String): ByteArray? =
                if (relativePath == "index.html") "<html>hi</html>".toByteArray(Charsets.UTF_8) else null
        }
        val router = WebServerRouter(store, actions, { passwordHash })
        server = WebServer(router, TestSslContext.server(), portRange = 0..0)
        port = server.start() ?: error("server did not start")
    }

    @After
    fun tearDown() {
        server.close()
        tempRoot.deleteRecursively()
    }

    private fun tlsSocket(): SSLSocket {
        val socket = TestSslContext.trustAllClient().socketFactory
            .createSocket("127.0.0.1", port) as SSLSocket
        socket.soTimeout = 5_000
        socket.startHandshake()
        return socket
    }

    private fun rawHttps(request: String): String {
        tlsSocket().use { socket ->
            socket.getOutputStream().write(request.toByteArray(Charsets.UTF_8))
            socket.getOutputStream().flush()
            return socket.getInputStream().readBytes().toString(Charsets.UTF_8)
        }
    }

    // ---- ① 指纹格式＋值 ----

    @Test
    fun `fingerprint format is colon separated uppercase sha256`() {
        val cert = TestSslContext.loadFixtureCertificate()
        val fingerprint = WifiTransferCertManager.fingerprint(cert)
        assertTrue(Regex("^([0-9A-F]{2}:){31}[0-9A-F]{2}$").matches(fingerprint))
    }

    @Test
    fun `fingerprint value matches direct message digest computation`() {
        val cert = TestSslContext.loadFixtureCertificate()
        val fingerprint = WifiTransferCertManager.fingerprint(cert)
        // 直算，不用生产格式化函数自证：手写十六进制表逐字节拼。
        val digest = MessageDigest.getInstance("SHA-256").digest(cert.encoded)
        val hexDigits = "0123456789ABCDEF"
        val expected = StringBuilder()
        for ((index, byte) in digest.withIndex()) {
            if (index > 0) expected.append(':')
            val value = byte.toInt() and 0xFF
            expected.append(hexDigits[value shr 4])
            expected.append(hexDigits[value and 0x0F])
        }
        assertEquals(expected.toString(), fingerprint)
    }

    // ---- ② needsRotation 三态 ----

    @Test
    fun `needsRotation is false far from expiry true near or past expiry`() {
        val now = 1_700_000_000_000L
        val dayMs = 24L * 3600L * 1000L
        assertFalse(WifiTransferCertManager.needsRotation(now + 31 * dayMs, now))
        assertTrue(WifiTransferCertManager.needsRotation(now + 29 * dayMs, now))
        assertTrue(WifiTransferCertManager.needsRotation(now - 1L, now))
    }

    // ---- ③ checkRotation 三态 ----

    @Test
    fun `checkRotation first false same false different true`() {
        val prefs = FakeSharedPreferences()
        assertFalse(WifiTransferCertManager.checkRotation(prefs, "AA:BB"))
        assertFalse(WifiTransferCertManager.checkRotation(prefs, "AA:BB"))
        assertTrue(WifiTransferCertManager.checkRotation(prefs, "CC:DD"))
        assertFalse(WifiTransferCertManager.checkRotation(prefs, "CC:DD"))
    }

    // ---- ④ TLS 握手成功 GET / 200 ----

    @Test
    fun `tls handshake succeeds and get root serves index`() {
        val response = rawHttps("GET / HTTP/1.1\r\nHost: x\r\nConnection: close\r\n\r\n")
        assertTrue(response.startsWith("HTTP/1.1 200"))
        assertTrue(response.contains("<html>hi</html>"))
    }

    // ---- ⑤ 明文 Socket 发 GET 收不到 HTTP 响应 ----

    @Test
    fun `plaintext socket gets no http response`() {
        val received = runCatching {
            Socket("127.0.0.1", port).use { socket ->
                socket.soTimeout = 5_000
                socket.getOutputStream()
                    .write("GET / HTTP/1.1\r\nHost: x\r\n\r\n".toByteArray(Charsets.UTF_8))
                socket.getOutputStream().flush()
                socket.getInputStream().readBytes()
            }
        }.getOrNull() ?: ByteArray(0)
        assertFalse(received.toString(Charsets.UTF_8).startsWith("HTTP/"))
    }

    // ---- ⑥ 服务端 enabledProtocols ⊆ {TLSv1.2, TLSv1.3} ----

    @Test
    fun `server socket enabled protocols are only tls12 and tls13`() {
        val socket = TestSslContext.server().serverSocketFactory
            .createServerSocket(0, 50, InetAddress.getByName("0.0.0.0")) as SSLServerSocket
        socket.use {
            it.enabledProtocols = WebServer.selectTlsProtocols(it.supportedProtocols)
            val enabled = it.enabledProtocols.toSet()
            assertTrue(enabled.isNotEmpty())
            assertTrue(enabled.all { protocol -> protocol == "TLSv1.2" || protocol == "TLSv1.3" })
        }
    }

    /** 内存假 SharedPreferences（主机单测无 Android 运行时）。 */
    private class FakeSharedPreferences : SharedPreferences {
        private val values = HashMap<String, Any?>()

        override fun getAll(): MutableMap<String, *> = HashMap(values)

        override fun getString(key: String?, defValue: String?): String? =
            (values[key] as? String) ?: defValue

        @Suppress("UNCHECKED_CAST")
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
            (values[key] as? MutableSet<String>) ?: defValues

        @Suppress("UNCHECKED_CAST")
        override fun getInt(key: String?, defValue: Int): Int =
            (values[key] as? Int) ?: defValue

        override fun getLong(key: String?, defValue: Long): Long =
            (values[key] as? Long) ?: defValue

        override fun getFloat(key: String?, defValue: Float): Float =
            (values[key] as? Float) ?: defValue

        override fun getBoolean(key: String?, defValue: Boolean): Boolean =
            (values[key] as? Boolean) ?: defValue

        override fun contains(key: String?): Boolean = values.containsKey(key)

        override fun edit(): SharedPreferences.Editor = FakeEditor(values)

        override fun registerOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener?,
        ) = Unit

        override fun unregisterOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener?,
        ) = Unit

        private class FakeEditor(
            private val values: HashMap<String, Any?>,
        ) : SharedPreferences.Editor {
            private val pending = HashMap<String, Any?>()
            private val removed = HashSet<String>()
            private var clearAll = false

            override fun putString(key: String?, value: String?): SharedPreferences.Editor =
                apply { pending[key!!] = value }

            override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor =
                apply { pending[key!!] = values }

            override fun putInt(key: String?, value: Int): SharedPreferences.Editor =
                apply { pending[key!!] = value }

            override fun putLong(key: String?, value: Long): SharedPreferences.Editor =
                apply { pending[key!!] = value }

            override fun putFloat(key: String?, value: Float): SharedPreferences.Editor =
                apply { pending[key!!] = value }

            override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor =
                apply { pending[key!!] = value }

            override fun remove(key: String?): SharedPreferences.Editor =
                apply { removed.add(key!!) }

            override fun clear(): SharedPreferences.Editor = apply { clearAll = true }

            override fun commit(): Boolean {
                apply()
                return true
            }

            override fun apply() {
                if (clearAll) values.clear()
                removed.forEach { values.remove(it) }
                values.putAll(pending)
            }
        }
    }
}
