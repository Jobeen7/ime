package com.jobeen.ime.base.webserver

import java.security.KeyStore
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * WO54 W54-8：测试用 SSLContext（test 源集，不打包）。
 *
 * 服务端用测试固件 test-cert.p12（PKCS12，alias test，RSA 2048，
 * CN=Jime Wi-Fi Transfer，storepass/keypass 均为 test，仅测试用，
 * 永不进生产）；客户端用 trust-all（测握手不校验自签名）。
 */
object TestSslContext {

    private const val FIXTURE_PATH = "webserver/test-cert.p12"
    private const val FIXTURE_PASSWORD = "test"
    private const val FIXTURE_ALIAS = "test"

    fun loadFixtureCertificate(): X509Certificate {
        val keyStore = loadFixtureKeyStore()
        return keyStore.getCertificate(FIXTURE_ALIAS) as X509Certificate
    }

    fun server(): SSLContext {
        val keyStore = loadFixtureKeyStore()
        val keyManagerFactory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        keyManagerFactory.init(keyStore, FIXTURE_PASSWORD.toCharArray())
        return SSLContext.getInstance("TLS").apply {
            init(keyManagerFactory.keyManagers, null, SecureRandom())
        }
    }

    fun trustAllClient(): SSLContext {
        val trustAll = arrayOf<TrustManager>(
            object : X509TrustManager {
                override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
                override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = Unit
                override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) = Unit
            },
        )
        return SSLContext.getInstance("TLS").apply {
            init(null, trustAll, SecureRandom())
        }
    }

    private fun loadFixtureKeyStore(): KeyStore {
        val stream = TestSslContext::class.java.classLoader
            ?.getResourceAsStream(FIXTURE_PATH)
            ?: error("test fixture not found: $FIXTURE_PATH")
        return stream.use {
            KeyStore.getInstance("PKCS12").apply { load(it, FIXTURE_PASSWORD.toCharArray()) }
        }
    }
}
