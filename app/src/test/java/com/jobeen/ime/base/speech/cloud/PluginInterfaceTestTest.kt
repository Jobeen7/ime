package com.jobeen.ime.base.speech.cloud

import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException
import org.junit.Assert.assertEquals
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
}
