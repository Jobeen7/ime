package com.jobeen.ime.base.webserver

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** WO52 4.4：Wi-Fi 传输鉴权纯逻辑（JWT 签发／校验／过期／篡改）。 */
class WifiTransferAuthTest {

    private val passwordHash = WifiTransferAuth.hashPassword("secret123")

    @Test
    fun `password hash is stable sha256 hex`() {
        assertEquals(64, passwordHash.length)
        assertEquals(passwordHash, WifiTransferAuth.hashPassword("secret123"))
        assertTrue(passwordHash != WifiTransferAuth.hashPassword("other"))
    }

    @Test
    fun `issued token verifies within ttl`() {
        val token = WifiTransferAuth.issueToken(passwordHash, nowMs = 1_000L)
        assertTrue(WifiTransferAuth.verifyToken(passwordHash, token, nowMs = 1_000L))
        assertTrue(
            WifiTransferAuth.verifyToken(
                passwordHash, token,
                nowMs = 1_000L + 23L * 3600L * 1000L,
            ),
        )
    }

    @Test
    fun `token expires after 24h`() {
        val token = WifiTransferAuth.issueToken(passwordHash, nowMs = 1_000L)
        assertFalse(
            WifiTransferAuth.verifyToken(
                passwordHash, token,
                nowMs = 1_000L + 24L * 3600L * 1000L + 1L,
            ),
        )
    }

    @Test
    fun `tampered token fails`() {
        val token = WifiTransferAuth.issueToken(passwordHash, nowMs = 1_000L)
        val parts = token.split(".")
        val tamperedPayload = WifiTransferAuth.Base64Url.encode(
            """{"sub":"jime","exp":9999999999}""".toByteArray(Charsets.UTF_8),
        )
        assertFalse(
            WifiTransferAuth.verifyToken(
                passwordHash, "${parts[0]}.$tamperedPayload.${parts[2]}", nowMs = 1_000L,
            ),
        )
        assertFalse(
            WifiTransferAuth.verifyToken(passwordHash, "$token-x", nowMs = 1_000L),
        )
        assertFalse(WifiTransferAuth.verifyToken(passwordHash, "not.a.token", nowMs = 1_000L))
    }

    @Test
    fun `token signed with other password fails`() {
        val token = WifiTransferAuth.issueToken(passwordHash, nowMs = 1_000L)
        val otherHash = WifiTransferAuth.hashPassword("different")
        assertFalse(WifiTransferAuth.verifyToken(otherHash, token, nowMs = 1_000L))
    }

    @Test
    fun `bearer token extraction is case-insensitive on header name`() {
        assertEquals(
            "abc.def.ghi",
            WifiTransferAuth.bearerToken(mapOf("authorization" to "Bearer abc.def.ghi")),
        )
        assertEquals(
            "abc.def.ghi",
            WifiTransferAuth.bearerToken(mapOf("Authorization" to "Bearer abc.def.ghi")),
        )
        assertNull(WifiTransferAuth.bearerToken(mapOf("Authorization" to "Basic abc")))
        assertNull(WifiTransferAuth.bearerToken(mapOf("Authorization" to "Bearer ")))
        assertNull(WifiTransferAuth.bearerToken(emptyMap()))
    }

    @Test
    fun `base64url round-trips arbitrary bytes`() {
        val bytes = ByteArray(256) { it.toByte() }
        val encoded = WifiTransferAuth.Base64Url.encode(bytes)
        assertFalse(encoded.contains('+'))
        assertFalse(encoded.contains('/'))
        assertFalse(encoded.contains('='))
        assertTrue(WifiTransferAuth.Base64Url.decode(encoded).contentEquals(bytes))
        assertEquals("", WifiTransferAuth.Base64Url.encode(ByteArray(0)))
        assertEquals(0, WifiTransferAuth.Base64Url.decode("").size)
    }
}
