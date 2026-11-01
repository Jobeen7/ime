package com.jobeen.ime.skin.model

import com.jobeen.ime.data.keyboard.theme.FontChoice
import com.jobeen.ime.data.keyboard.theme.FontRole
import com.jobeen.ime.data.keyboard.theme.GlobalFonts
import com.jobeen.ime.data.keyboard.theme.KeyboardColors
import com.jobeen.ime.skin.pkg.ImportAssetPolicy
import com.jobeen.ime.skin.store.SkinGenRef
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 皮肤内嵌字体（审计 C 项）：fontFaces 声明、内嵌引用、快照接纳、生效分层。 */
class SkinBundledFontsTest {

    private val fallbacks = GeometryFallbacks(12f, 6f, 5f, 10f)

    private val kinds: Map<String, AssetKind> = mapOf(
        "font-a" to AssetKind.FONT,
        "pic" to AssetKind.IMAGE,
    )

    private fun validate(
        json: String,
        assetBytes: (String) -> Long? = { null },
    ): SkinValidator.Result.Valid {
        val tree = Json.parseToJsonElement(json) as JsonObject
        val result = SkinValidator.validate(
            tree, 157, fallbacks,
            assetKind = { kinds[it] },
            assetBytes = assetBytes,
        )
        assertTrue("expected Valid, got $result", result is SkinValidator.Result.Valid)
        return result as SkinValidator.Result.Valid
    }

    private fun baseJson(extra: String): String =
        """{"formatVersion": 3, "styles": {"keyBackground": "#FFFFFFFF"},
           "stylesDark": {"keyBackground": "#FF222222"}, $extra}"""

    @Test
    fun fontAssetKindAndImportPolicyKeepFontFiles() {
        assertEquals(AssetKind.FONT, PackageManifest.assetKindOf("fonts/a.ttf"))
        assertEquals(AssetKind.FONT, PackageManifest.assetKindOf("fonts/b.OTF"))
        assertEquals(AssetKind.IMAGE, PackageManifest.assetKindOf("x.png"))
        val manifest = PackageManifest(
            contents = listOf(
                PackageManifest.Content("asset", "font-a", "fonts/a.ttf", "s"),
            ),
        )
        val decision = ImportAssetPolicy.evaluate(manifest.assets()) { 1024L }
        assertEquals(listOf("font-a"), decision.kept.map { it.id })
        assertTrue(decision.excluded.isEmpty())
    }

    @Test
    fun validFaceParsesAndWrongKindOversizeDuplicateDropIndividually() {
        val result = validate(
            baseJson(
                """"fontFaces": [
                  {"name": "MyFont", "url": "font-a"},
                  {"name": "PicFont", "url": "pic"},
                  {"name": "myfont", "url": "font-a"},
                  {"name": "", "url": "font-a"},
                  {"name": "NoUrl", "url": ""},
                  {"name": "Big", "url": "big-font"}
                ]""",
            ),
            assetBytes = { if (it == "big-font") 64L * 1024 * 1024 else null },
        )
        // big-font 清单未知类别（assetKind 为 null）暂收后被字节数上限剔除。
        assertEquals(
            listOf(SkinFontFace("MyFont", "font-a")),
            result.skin.skinFontFaces,
        )
        val codes = result.issues.map { it.detail }
        assertTrue("font_face_not_font" in codes)
        assertTrue("font_face_duplicate_name" in codes)
        assertTrue("font_face_name_empty" in codes)
        assertTrue("font_face_url_empty" in codes)
        assertTrue("font_face_size_invalid" in codes)
    }

    @Test
    fun unknownKindFaceAcceptedTentatively() {
        val result = validate(
            baseJson(""""fontFaces": [{"name": "Later", "url": "not-in-manifest"}]"""),
        )
        assertEquals(
            listOf(SkinFontFace("Later", "not-in-manifest")),
            result.skin.skinFontFaces,
        )
    }

    @Test
    fun nonArrayFontFacesFallsBackToEmpty() {
        val result = validate(baseJson(""""fontFaces": {"name": "X", "url": "font-a"}"""))
        assertTrue(result.skin.skinFontFaces.isEmpty())
        assertTrue(result.issues.any { it.detail == "font_faces_not_array" })
    }

    @Test
    fun fontFamilyAndRoleFontsMayReferenceFaceName() {
        val result = validate(
            baseJson(
                """"fontFaces": [{"name": "MyFont", "url": "font-a"}],
                  "fontFamily": "myfont",
                  "roleFonts": {"key": "MYFONT", "candidate": "serif", "popup": "nope"}""",
            ),
        )
        assertEquals("MyFont", result.skin.fontFamily)
        assertEquals(
            mapOf(FontRole.KEY to "MyFont", FontRole.CANDIDATE to "serif"),
            result.skin.roleFonts,
        )
        assertEquals(listOf(SkinFontFace("MyFont", "font-a")), result.skin.skinFontFaces)
    }

    @Test
    fun snapshotCarriesFacesAndAdmitsOnlyManifestListed() {
        val validated = validate(
            baseJson(
                """"fontFaces": [{"name": "MyFont", "url": "font-a"},
                                 {"name": "Ghost", "url": "ghost"}]""",
            ),
        ).skin
        val defaultScheme = KeyboardColors.ColorScheme(
            keyBackground = 100, keyPressed = 101, keyBorderStroke = 102,
            specialKeyBackground = 200, specialKeyPressed = 201, specialKeyBorderStroke = 202,
            accentKeyBackground = 300, accentKeyPressed = 301, accentKeyBorderStroke = 302,
            keyText = 400, specialKeyText = 401, accentKeyText = 402, altText = 403,
            background = 500, cornerRadius = 5f, keyHMargin = 3f, keyVMargin = 4f,
            panel = KeyboardColors.ColorScheme.PanelColors(
                background = 600, toolbarText = 601, toolbarActived = 602, toolbarIcon = 603,
                candidateBackground = 604, candidateText = 605, candidateIndex = 606,
                candidateDivider = 607, toolbarPressed = 608,
            ),
            pinner = KeyboardColors.ColorScheme.PinnerColors(
                background = 700, textColor = 701, secondaryTextColor = 702,
            ),
            toastBackground = 999, toastText = 998,
        )
        val snapshot = SkinSnapshotBuilder.build(
            validated, SkinGenRef("skin-1", "h".repeat(64), 1),
            defaultScheme, MemoryTier.NORMAL,
            { if (it == "font-a") "a".repeat(64) else null }, 1000, 400,
        )
        assertEquals(
            listOf(
                SkinFontFace("MyFont", "font-a"),
                SkinFontFace("Ghost", "ghost"),
            ),
            snapshot.skinFontFaces,
        )
        assertEquals(mapOf("font-a" to "a".repeat(64)), snapshot.skinFontAssets)
    }

    @Test
    fun effectiveSourcePrefersBundledOverSystemAndGlobal() {
        assertEquals(
            GlobalFonts.EffectiveSource.SkinBundled("MyFont"),
            GlobalFonts.effectiveSource(
                "MyFont", FontChoice.System("sans-serif"),
                emptyMap(), FontRole.KEY, listOf("MyFont"),
            ),
        )
        assertEquals(
            GlobalFonts.EffectiveSource.SkinBundled("MyFont"),
            GlobalFonts.effectiveSource(
                "serif", FontChoice.System("sans-serif"),
                mapOf(FontRole.KEY to "myfont"), FontRole.KEY, listOf("MyFont"),
            ),
        )
        // 未声明为内嵌名时，旧分层不变（系统族名仍胜全局）。
        assertEquals(
            GlobalFonts.EffectiveSource.Skin("serif"),
            GlobalFonts.effectiveSource(
                "serif", FontChoice.System("sans-serif"),
                emptyMap(), FontRole.KEY, emptyList(),
            ),
        )
    }
}
