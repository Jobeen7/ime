package com.jobeen.ime.base.speech.cloud

import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.KeyStore
import java.security.cert.X509Certificate
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLException
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 插件接口测试：前置判定顺序、探针短路、异常分类。 */
class PluginInterfaceTestTest {

    private class RecordingProber(
        private val failures: Map<String, InterfaceProbeFailure> = emptyMap(),
    ) : HostProber {
        val probed = mutableListOf<String>()
        override fun probe(host: String): InterfaceProbeFailure? {
            probed.add(host)
            return failures[host]
        }
    }

    private fun run(
        hosts: List<String> = listOf("a.example.com", "b.example.com"),
        secretsMissing: Boolean = false,
        networkAvailable: Boolean = true,
        prober: HostProber = RecordingProber(),
    ) = PluginInterfaceTest.run(hosts, secretsMissing, networkAvailable, prober)

    @Test
    fun test_missingSecretsWinsAndProbesNothing() {
        val prober = RecordingProber()
        assertEquals(
            PluginInterfaceTestResult.MissingSecrets,
            run(secretsMissing = true, networkAvailable = false, prober = prober),
        )
        assertTrue(prober.probed.isEmpty())
    }

    @Test
    fun test_noNetworkWinsAndProbesNothing() {
        val prober = RecordingProber()
        assertEquals(
            PluginInterfaceTestResult.NoNetwork,
            run(networkAvailable = false, prober = prober),
        )
        assertTrue(prober.probed.isEmpty())
    }

    @Test
    fun test_noHostsReportsNoHosts() {
        val prober = RecordingProber()
        assertEquals(PluginInterfaceTestResult.NoHosts, run(hosts = emptyList(), prober = prober))
        assertTrue(prober.probed.isEmpty())
    }

    @Test
    fun test_allHostsReachable_probesEachInOrder() {
        val prober = RecordingProber()
        assertEquals(PluginInterfaceTestResult.Reachable, run(prober = prober))
        assertEquals(listOf("a.example.com", "b.example.com"), prober.probed)
    }

    @Test
    fun test_firstUnreachableHostReportedAndLaterHostsNotProbed() {
        val prober = RecordingProber(
            mapOf(
                "a.example.com" to InterfaceProbeFailure.TIMEOUT,
                "b.example.com" to InterfaceProbeFailure.DNS,
            ),
        )
        assertEquals(
            PluginInterfaceTestResult.Unreachable("a.example.com", InterfaceProbeFailure.TIMEOUT),
            run(prober = prober),
        )
        assertEquals(listOf("a.example.com"), prober.probed)
    }

    @Test
    fun test_classifyProbeException() {
        assertEquals(
            InterfaceProbeFailure.DNS,
            PluginInterfaceTest.classifyProbeException(UnknownHostException("a.example.com")),
        )
        assertEquals(
            InterfaceProbeFailure.TIMEOUT,
            PluginInterfaceTest.classifyProbeException(SocketTimeoutException("timed out")),
        )
        assertEquals(
            InterfaceProbeFailure.TLS,
            PluginInterfaceTest.classifyProbeException(SSLException("handshake failed")),
        )
        assertEquals(
            InterfaceProbeFailure.CONNECT,
            PluginInterfaceTest.classifyProbeException(IOException("refused")),
        )
        assertEquals(
            InterfaceProbeFailure.CONNECT,
            PluginInterfaceTest.classifyProbeException(IllegalStateException("other")),
        )
    }

    @Test
    fun test_buildProbeParameters_setsHttpsEndpointIdentificationAndSni() {
        val params = buildProbeParameters("a.example.com")
        assertEquals("HTTPS", params.endpointIdentificationAlgorithm)
        assertEquals(listOf(SNIHostName("a.example.com")), params.serverNames)
    }

    /**
     * 本地 TLS 集成测试（不走外网）：服务端用测试 keystore 起在临时端口，
     * 客户端信任全部证书（单测内专用，只为让链校验通过、由主机名校验定胜负；
     * 生产路径仍走默认信任库）。证书是 keytool 生成的测试夹具
     *（src/test/resources/tls/，口令 testtest，无任何真实用途）。
     */
    @Test
    fun test_probe_matchingHostname_reachable() {
        assertNull(probeAgainstLocalTls("tls/matching.jks", "localhost"))
    }

    @Test
    fun test_probe_mismatchingHostname_tlsFailure() {
        assertEquals(
            InterfaceProbeFailure.TLS,
            probeAgainstLocalTls("tls/mismatching.jks", "localhost"),
        )
    }

    private fun probeAgainstLocalTls(
        keystoreResource: String,
        host: String,
    ): InterfaceProbeFailure? {
        // 注意：资源加载必须在 apply 块之外——apply 内 this 是 KeyStore（JDK
        // 引导类加载器加载，classLoader 为 null），javaClass 会指错对象。
        val keystoreStream = requireNotNull(
            javaClass.classLoader?.getResourceAsStream(keystoreResource),
        ) { "missing test keystore: $keystoreResource" }
        val keyStore = KeyStore.getInstance("JKS").apply {
            keystoreStream.use { load(it, "testtest".toCharArray()) }
        }
        val keyManagerFactory =
            KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        keyManagerFactory.init(keyStore, "testtest".toCharArray())
        val serverContext = SSLContext.getInstance("TLS").apply {
            init(keyManagerFactory.keyManagers, null, null)
        }
        val serverSocket =
            serverContext.serverSocketFactory.createServerSocket(0) as SSLServerSocket
        val acceptThread = thread {
            try {
                val accepted = serverSocket.accept() as SSLSocket
                runCatching { accepted.startHandshake() }
                runCatching { accepted.close() }
            } catch (_: Exception) {
                // 不匹配时服务端握手同样失败，忽略。
            }
        }
        try {
            val clientContext = SSLContext.getInstance("TLS").apply {
                init(null, arrayOf(TRUST_ALL), null)
            }
            return TlsHostProber.probe(host, clientContext.socketFactory, serverSocket.localPort)
        } finally {
            runCatching { serverSocket.close() }
            acceptThread.join(10_000)
        }
    }

    private companion object {
        /** 单测内信任全部证书（只为让链校验通过、由主机名校验定胜负；生产路径走默认信任库）。 */
        private val TRUST_ALL = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = Unit
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) = Unit
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
    }
}
