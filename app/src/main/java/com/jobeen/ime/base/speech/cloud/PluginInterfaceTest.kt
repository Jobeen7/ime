package com.jobeen.ime.base.speech.cloud

import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLException
import javax.net.ssl.SSLParameters
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import kotlinx.coroutines.CancellationException

/**
 * 插件接口测试（CyIME H9 对口：设置里填好接口就能直接点测试）。
 *
 * 宿主侧探针，不跑插件脚本：不触脚本预算、不占会话、不需麦克风、不读密钥值。
 * 只验证接口地址可达（域名解析＋TCP＋TLS 握手到声明域名的 443 端口），
 * 握手时校验证书主机名（HTTPS 端点识别＋SNI），受信任但域名不匹配的
 * 证书判为不可达；不验证密钥是否正确——密钥错只能在真实语音时由服务端报错。
 */

/** 单个域名探针失败原因。 */
enum class InterfaceProbeFailure { DNS, TIMEOUT, TLS, CONNECT }

sealed interface PluginInterfaceTestResult {
    /** 全部声明域名均可达（只验证接口地址可达，不验证密钥是否正确）。 */
    data object Reachable : PluginInterfaceTestResult
    /** 任一凭证密钥未填：不探针。 */
    data object MissingSecrets : PluginInterfaceTestResult
    /** 本机无网络：不探针。 */
    data object NoNetwork : PluginInterfaceTestResult
    /** 插件未声明域名：无可探目标。 */
    data object NoHosts : PluginInterfaceTestResult
    /** 首个不可达域名＋原因。 */
    data class Unreachable(val host: String, val failure: InterfaceProbeFailure) :
        PluginInterfaceTestResult
}

/** 单域名探针：返回 null 表示可达，否则为失败原因。调用方注入，便于单测。 */
fun interface HostProber {
    fun probe(host: String): InterfaceProbeFailure?
}

object PluginInterfaceTest {
    /** 每域名连接＋握手超时 5 秒。 */
    const val PROBE_TIMEOUT_MS = 5_000

    /**
     * 整次探测总时限 30 秒：插件最多声明 32 个 host，慢但成功时
     * 逐个探会累计到数分钟；总时限只在 host 间检查，单次总耗时
     * 上限为总时限＋一个在途探针的耗时（连接 5 秒＋握手 5 秒，DNS 不计）。
     */
    const val TOTAL_DEADLINE_MS = 30_000L

    /**
     * 判定顺序（与 [CloudVoiceGate] 同口径，首个不满足即定）：
     * 密钥未填 → 无网络 → 无域名 → 逐域名探针，首个不可达即定。
     *
     * host 间查两件事，任一命中即停，不再往后探：
     * ① [isCancelled] 为真时抛 [CancellationException]（调用方取消，
     * 无结果可写——UI 的 Job 取消即走此路）；
     * ② 超过 [deadlineMs] 时把当前 host 记为超时不可达。
     * [clockMs] 可注入，便于单测用假时钟定量验总耗时。
     */
    fun run(
        hosts: List<String>,
        secretsMissing: Boolean,
        networkAvailable: Boolean,
        prober: HostProber,
        deadlineMs: Long = TOTAL_DEADLINE_MS,
        clockMs: () -> Long = { System.currentTimeMillis() },
        isCancelled: () -> Boolean = { false },
    ): PluginInterfaceTestResult {
        if (secretsMissing) return PluginInterfaceTestResult.MissingSecrets
        if (!networkAvailable) return PluginInterfaceTestResult.NoNetwork
        if (hosts.isEmpty()) return PluginInterfaceTestResult.NoHosts
        val startMs = clockMs()
        for (host in hosts) {
            if (isCancelled()) throw CancellationException("probe cancelled")
            if (clockMs() - startMs >= deadlineMs) {
                return PluginInterfaceTestResult.Unreachable(host, InterfaceProbeFailure.TIMEOUT)
            }
            val failure = prober.probe(host)
            if (failure != null) return PluginInterfaceTestResult.Unreachable(host, failure)
        }
        return PluginInterfaceTestResult.Reachable
    }

    /** 探针异常 → 失败原因（纯函数，可单测）。 */
    fun classifyProbeException(e: Exception): InterfaceProbeFailure = when (e) {
        is UnknownHostException -> InterfaceProbeFailure.DNS
        is SocketTimeoutException -> InterfaceProbeFailure.TIMEOUT
        is SSLException -> InterfaceProbeFailure.TLS
        else -> InterfaceProbeFailure.CONNECT
    }
}

/** 探针 TLS 参数（纯函数）：HTTPS 端点识别＋SNI（取声明域名）。 */
fun buildProbeParameters(host: String): SSLParameters {
    return SSLParameters().apply {
        endpointIdentificationAlgorithm = "HTTPS"
        serverNames = listOf(SNIHostName(host))
    }
}

/** 真实探针：TCP 连接＋TLS 握手到 host:443，超时即失败；握手校验主机名。 */
object TlsHostProber : HostProber {
    /** 在途探针的 socket：UI 取消时关掉它，阻塞的连接／握手随即抛错返回。 */
    @Volatile
    private var activeSocket: SSLSocket? = null

    /** 取消在途探针（无在途探针时为空操作）。 */
    fun cancelActiveProbe() {
        runCatching { activeSocket?.close() }
    }

    override fun probe(host: String): InterfaceProbeFailure? {
        // getDefault() 声明返回 SocketFactory，实际恒为 SSLSocketFactory。
        return probe(host, SSLSocketFactory.getDefault() as SSLSocketFactory, 443)
    }

    /** 可注入 seam（单测用）：socketFactory 与端口可换，生产路径恒为默认工厂＋443。 */
    internal fun probe(
        host: String,
        socketFactory: SSLSocketFactory,
        port: Int,
    ): InterfaceProbeFailure? {
        return try {
            val socket = socketFactory.createSocket() as SSLSocket
            activeSocket = socket
            try {
                socket.sslParameters = buildProbeParameters(host)
                socket.connect(
                    InetSocketAddress(host, port),
                    PluginInterfaceTest.PROBE_TIMEOUT_MS,
                )
                socket.soTimeout = PluginInterfaceTest.PROBE_TIMEOUT_MS
                socket.startHandshake()
            } finally {
                if (activeSocket === socket) activeSocket = null
                runCatching { socket.close() }
            }
            null
        } catch (e: Exception) {
            PluginInterfaceTest.classifyProbeException(e)
        }
    }
}
