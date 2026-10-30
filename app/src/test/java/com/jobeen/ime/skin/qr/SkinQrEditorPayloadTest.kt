package com.jobeen.ime.skin.qr

import com.jobeen.ime.skin.model.AssetKind
import com.jobeen.ime.skin.model.GeometryFallbacks
import com.jobeen.ime.skin.model.SkinValidator
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 集成验证：编辑器格式的内联载荷能否被 App 侧 parse + 校验。
 * 编辑器导出写出的 skin.json（含编辑器全部新字段口径：toolbar /
 * geometry / fontFamily / imageAdjust / popupChars / icon /
 * stylesDark / layoutIdLandscape）经 [SkinQrPayload.encodeInline]
 * 编码后，App 侧 parse 须还原同一 skinJson，且校验须 Valid。
 * 注意：内联包不含资产，image 引用在校验期按既有口径回退，
 * 不拒包；含资产的皮肤必须走 URL 型。
 */
class SkinQrEditorPayloadTest {

    private val editorSkinJson = """
        {
          "formatVersion": 3,
          "meta": {"id": "editor-qr-demo", "name": "Editor QR", "author": "", "version": "1.0"},
          "styles": {"keyBackground": "#FFFFFFFF", "keyText": "#FF141414"},
          "stylesDark": {"keyBackground": "#FF1A1A1A"},
          "layoutId": "builtin-t9",
          "layoutIdLandscape": "builtin-t9",
          "geometry": {"keyRadiusDp": 8, "keyHGapDp": 2, "keyVGapDp": 3, "heightPercent": 42},
          "fontFamily": "sans-serif",
          "toolbar": {"buttons": [{"id": "emoji", "icon": "sentiment_satisfied"}], "expandIcon": "menu", "collapseIcon": "keyboard_arrow_down"},
          "appearance": {"background": {"type": "color", "value": "#FFEEEEEE"}},
          "keyMappings": {
            "KEY_A": {"label": "A", "popupChars": ["a", "A"], "icon": "search", "subLabel": "a"}
          }
        }
    """.trimIndent()

    @Test
    fun editorInlinePayloadParsesAndValidates() {
        val encoded = SkinQrPayload.encodeInline(editorSkinJson)
        assertTrue(encoded.startsWith(SkinQrPayload.INLINE_PREFIX))
        val result = SkinQrPayload.parse(encoded)
        assertTrue("expected Ok, got $result", result is SkinQrPayload.ParseResult.Ok)
        val parsed = (result as SkinQrPayload.ParseResult.Ok).parsed
        assertTrue(parsed is SkinQrPayload.Parsed.Inline)
        val inline = parsed as SkinQrPayload.Parsed.Inline
        assertEquals("editor-qr-demo", inline.metaId)
        val tree = Json.parseToJsonElement(inline.skinJson) as JsonObject
        val validated = SkinValidator.validate(
            tree, 157,
            GeometryFallbacks(cornerRadius = 12f, keyHMargin = 6f, keyVMargin = 5f, bottomInsetDp = 10f),
            { null },
        )
        assertTrue("expected Valid, got $validated", validated is SkinValidator.Result.Valid)
        val skin = (validated as SkinValidator.Result.Valid).skin
        assertEquals("sans-serif", skin.fontFamily)
        assertTrue(skin.darkStyles != null)
        assertEquals("builtin-t9", skin.layoutIdLandscape)
        assertTrue(skin.toolbar.present)
        val override = skin.keyOverrides["KEY_A"]
        assertTrue(override != null)
        assertEquals(listOf("a", "A"), override!!.popupChars)
        assertEquals("search", override.icon)
        assertEquals("a", override.subLabel)
    }

    @Test
    fun urlAndLegacyPrefixesBehave() {
        val url = SkinQrPayload.parse(
            "JIME-SKIN-URL:1:https://example.invalid/skin.jime" +
                "#sha256=0000000000000000000000000000000000000000000000000000000000000000",
        )
        assertTrue(url is SkinQrPayload.ParseResult.Ok)
        val legacy = SkinQrPayload.parse("IMEKBTHEME:abc123")
        assertTrue(
            legacy is SkinQrPayload.ParseResult.Rejected &&
                (legacy as SkinQrPayload.ParseResult.Rejected).reason == "legacy_theme_qr",
        )
        val http = SkinQrPayload.parse("JIME-SKIN-URL:1:http://example.invalid/skin.jime")
        assertTrue(http is SkinQrPayload.ParseResult.Rejected)
    }

    @Test
    fun inlineTooLargeRejected() {
        val big = editorSkinJson + " ".repeat(SkinQrPayload.MAX_INLINE_BYTES)
        val result = SkinQrPayload.parse(SkinQrPayload.encodeInline(big))
        assertTrue(result is SkinQrPayload.ParseResult.Rejected)
    }
}
