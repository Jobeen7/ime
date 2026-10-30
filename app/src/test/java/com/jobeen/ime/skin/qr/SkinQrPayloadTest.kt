package com.jobeen.ime.skin.qr

import com.jobeen.ime.skin.model.PackageManifest
import java.util.zip.ZipInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 皮肤二维码载荷约定（2026-10-27 新增分流）：分流、编解码、URL 门、组包清单一致性。 */
class SkinQrPayloadTest {

    private val skinJson =
        "{\"formatVersion\": 3, \"meta\": {\"id\": \"qr-demo\", \"name\": \"QR 演示\"}," +
            " \"styles\": {\"keyBackground\": \"#FFFFFFFF\"}}"

    private fun rejected(raw: String?): SkinQrPayload.ParseResult.Rejected {
        val result = SkinQrPayload.parse(raw)
        assertTrue("expected Rejected, got $result", result is SkinQrPayload.ParseResult.Rejected)
        return result as SkinQrPayload.ParseResult.Rejected
    }

    private fun parsed(raw: String): SkinQrPayload.Parsed {
        val result = SkinQrPayload.parse(raw)
        assertTrue("expected Ok, got $result", result is SkinQrPayload.ParseResult.Ok)
        return (result as SkinQrPayload.ParseResult.Ok).parsed
    }

    @Test
    fun inlineRoundTrip() {
        val encoded = SkinQrPayload.encodeInline(skinJson)
        assertTrue(encoded.startsWith(SkinQrPayload.INLINE_PREFIX))
        val inline = parsed(encoded) as SkinQrPayload.Parsed.Inline
        assertEquals(skinJson, inline.skinJson)
        assertEquals("qr-demo", inline.metaId)
    }

    @Test
    fun inlineRejectsBadBase64AndNonJson() {
        assertEquals("qr_base64_invalid", rejected("JIME-SKIN:1:!!!not-base64!!!").reason)
        assertEquals("qr_base64_invalid", rejected("JIME-SKIN:1:A").reason) // 长度 % 4 == 1
        val notJson = SkinQrPayload.encodeInline("not json")
        assertEquals("qr_skin_unparseable", rejected(notJson).reason)
    }

    @Test
    fun inlineRejectsOversize() {
        val big = "{\"formatVersion\": 3, \"styles\": {\"keyBackground\": \"#FFFFFFFF\"}," +
            " \"pad\": \"${"x".repeat(20 * 1024)}\"}"
        assertEquals("qr_inline_too_large", rejected(SkinQrPayload.encodeInline(big)).reason)
    }

    @Test
    fun unknownPrefixAndEmptyRejected() {
        assertEquals("qr_unknown_prefix", rejected("https://example.com/skin.jime").reason)
        assertEquals("qr_empty", rejected("").reason)
        assertEquals("qr_empty", rejected(null).reason)
        assertEquals("qr_inline_empty", rejected("JIME-SKIN:1:").reason)
    }

    @Test
    fun legacyThemeCodeDetectedNotImported() {
        assertEquals(
            "legacy_theme_qr",
            rejected("IMEKBTHEME:{\"id\":\"x\"}").reason,
        )
    }

    @Test
    fun urlValidWithAndWithoutShaFragment() {
        val sha = "a".repeat(64)
        val withSha = parsed("JIME-SKIN-URL:1:https://example.com/skins/demo.jime#sha256=$sha")
            as SkinQrPayload.Parsed.Url
        assertEquals("https://example.com/skins/demo.jime", withSha.url)
        assertEquals(sha, withSha.sha256)
        val withoutSha = parsed("JIME-SKIN-URL:1:https://example.com/skins/demo.jime")
            as SkinQrPayload.Parsed.Url
        assertEquals("https://example.com/skins/demo.jime", withoutSha.url)
        assertNull(withoutSha.sha256)
    }

    @Test
    fun urlRejectsHttpUserinfoBadFragmentAndTooLong() {
        assertEquals(
            "qr_url_not_https",
            rejected("JIME-SKIN-URL:1:http://example.com/demo.jime").reason,
        )
        assertEquals(
            "qr_url_userinfo_rejected",
            rejected("JIME-SKIN-URL:1:https://user@example.com/demo.jime").reason,
        )
        assertEquals(
            "qr_url_fragment_invalid",
            rejected("JIME-SKIN-URL:1:https://example.com/demo.jime#md5=abc").reason,
        )
        assertEquals(
            "qr_url_fragment_invalid",
            rejected("JIME-SKIN-URL:1:https://example.com/demo.jime#sha256=xyz").reason,
        )
        assertEquals(
            "qr_url_fragment_invalid",
            rejected("JIME-SKIN-URL:1:https://example.com/a#b#sha256=${"a".repeat(64)}").reason,
        )
        assertEquals(
            "qr_url_too_long",
            rejected("JIME-SKIN-URL:1:https://example.com/" + "a".repeat(2100)).reason,
        )
    }

    @Test
    fun packageZipManifestMatchesSkinBytes() {
        val bytes = SkinQrPayload.buildPackageZip(skinJson, "qr-demo")
        val entries = LinkedHashMap<String, ByteArray>()
        ZipInputStream(bytes.inputStream()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                entries[entry.name] = zip.readBytes()
            }
        }
        assertEquals(setOf("manifest.json", "skin.json"), entries.keys)
        assertEquals(skinJson.encodeToByteArray().toList(), entries.getValue("skin.json").toList())
        val manifest = PackageManifest.parse(entries.getValue("manifest.json").decodeToString())
        assertTrue(manifest != null)
        val content = manifest!!.skin()
        assertTrue(content != null)
        assertEquals("qr-demo", content!!.id)
        assertEquals("skin.json", content.path)
        // 清单 sha256 与包内 skin.json 字节一致（导入管线会复核）。
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        val hex = digest.digest(entries.getValue("skin.json"))
            .joinToString("") { "%02x".format(it) }
        assertEquals(hex, content.sha256)
    }

    @Test
    fun base64UrlRoundTripEdgeLengths() {
        for (size in listOf(1, 2, 3, 4, 5, 100)) {
            val bytes = ByteArray(size) { (it * 37 + 11).toByte() }
            val encoded = SkinQrPayload.Base64Url.encode(bytes)
            assertTrue(!encoded.contains('=') && !encoded.contains('+') && !encoded.contains('/'))
            assertEquals(bytes.toList(), SkinQrPayload.Base64Url.decode(encoded)!!.toList())
        }
        assertNull(SkinQrPayload.Base64Url.decode("AB=C"))
        assertNull(SkinQrPayload.Base64Url.decode("A==="))
    }
}
