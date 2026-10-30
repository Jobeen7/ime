package com.jobeen.ime.skin.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** G1 校验器：皮肤级字号、派生公式、图片资产清单过滤。 */
class KeyModelG1ValidatorTest {

    private val fallbacks = GeometryFallbacks(
        cornerRadius = 12f, keyHMargin = 6f, keyVMargin = 5f, bottomInsetDp = 10f,
    )
    private val assetKinds = mapOf(
        "img_key" to AssetKind.IMAGE,
        "img_badge" to AssetKind.IMAGE,
        "snd_tap" to AssetKind.AUDIO,
        "img_big" to AssetKind.IMAGE,
    )
    private val assetSizes = mapOf(
        "img_key" to 100L,
        "img_badge" to 200L,
        "img_big" to SkinValidator.MAX_KEY_IMAGE_BYTES + 1,
    )

    private fun validate(json: String): SkinValidator.Result.Valid {
        val tree = Json.parseToJsonElement(json) as JsonObject
        val result = SkinValidator.validate(
            tree, 157, fallbacks, { assetKinds[it] }, assetBytes = { assetSizes[it] },
        )
        assertTrue("expected Valid, got $result", result is SkinValidator.Result.Valid)
        return result as SkinValidator.Result.Valid
    }

    private fun baseSkin(extra: String): String = """
        {
          "formatVersion": 3,
          "meta": {"id": "g1-demo", "name": "G1", "author": "", "version": "1.0"},
          "styles": {"keyBackground": "#FFFFFFFF", "keyText": "#FF141414"},
          "stylesDark": {"keyBackground": "#FF222222", "keyText": "#FFFFFFFF"},
          $extra
        }
    """.trimIndent()

    @Test
    fun skinTextSizesParsedAndInvalidDropped() {
        val result = validate(
            baseSkin(
                """"textSizes": {"main": 20, "sub": 11, "symbol": 44, "nope": 12}""",
            ),
        )
        assertEquals(
            mapOf(KeyTextRole.MAIN to 20f, KeyTextRole.SUB to 11f),
            result.skin.textSizes,
        )
        assertTrue(result.issues.any { it.path == "textSizes.symbol" })
        assertTrue(result.issues.any { it.path == "textSizes.nope" })
    }

    @Test
    fun derivationFormulaOverridesFixedMapping() {
        val result = validate(
            baseSkin(
                """
                "derivation": {"face": "#FFFFFFFF", "pressed": "#FFEEEEEE", "bed": "#FFF5F5F5",
                  "ink": "#FF141414", "accent": "#FF9C824A", "hint": "#FF999999"},
                "derivationFormulas": {"keyBorderStroke": "darken(face, 0.5)"}
                """.trimIndent(),
            ),
        )
        // 白色 darken 0.5 → #FF7F7F7F；显式 styles 未给 keyBorderStroke，故公式值生效
        assertEquals(0xFF7F7F7F.toInt(), result.skin.styles.colors[StyleField.KEY_BORDER_STROKE])
    }

    @Test
    fun derivationFormulaInvalidFallsBackToFixedMapping() {
        val result = validate(
            baseSkin(
                """
                "derivation": {"face": "#FFFFFFFF", "pressed": "#FFEEEEEE", "bed": "#FFF5F5F5",
                  "ink": "#FF141414", "accent": "#FF9C824A", "hint": "#FF999999"},
                "derivationFormulas": {"keyBorderStroke": "blur(face, 0.5)", "nope": "face"}
                """.trimIndent(),
            ),
        )
        // 非法公式回退固定映射：描边 = 键面加深 0.88 → 255*0.88 = 224
        assertEquals(0xFFE0E0E0.toInt(), result.skin.styles.colors[StyleField.KEY_BORDER_STROKE])
        assertTrue(
            result.issues.any { it.path == "derivationFormulas.keyBorderStroke" },
        )
        assertTrue(result.issues.any { it.path == "derivationFormulas.nope" })
    }

    @Test
    fun explicitStyleBeatsFormula() {
        val tree = Json.parseToJsonElement(
            """
            {
              "formatVersion": 3,
              "meta": {"id": "g1-demo", "name": "G1", "author": "", "version": "1.0"},
              "styles": {"keyBackground": "#FFFFFFFF", "keyText": "#FF141414",
                "keyBorderStroke": "#FF0000FF"},
              "stylesDark": {"keyBackground": "#FF222222", "keyText": "#FFFFFFFF"},
              "derivation": {"face": "#FFFFFFFF", "pressed": "#FFEEEEEE", "bed": "#FFF5F5F5",
                "ink": "#FF141414", "accent": "#FF9C824A", "hint": "#FF999999"},
              "derivationFormulas": {"keyBorderStroke": "darken(face, 0.5)"}
            }
            """.trimIndent(),
        ) as JsonObject
        val result = SkinValidator.validate(
            tree, 157, fallbacks, { assetKinds[it] }, assetBytes = { assetSizes[it] },
        )
        assertTrue(result is SkinValidator.Result.Valid)
        val skin = (result as SkinValidator.Result.Valid).skin
        assertEquals(0xFF0000FF.toInt(), skin.styles.colors[StyleField.KEY_BORDER_STROKE])
    }

    @Test
    fun keyImageFilteredByKindAndSize() {
        val result = validate(
            baseSkin(
                """
                "keyMappings": {
                  "KEY_Q": {"image": "img_key", "tap": {"type": "text", "value": "q"}},
                  "KEY_W": {"image": "snd_tap", "tap": {"type": "text", "value": "w"}},
                  "KEY_E": {"image": "img_missing", "tap": {"type": "text", "value": "e"}},
                  "KEY_R": {"image": "img_big", "tap": {"type": "text", "value": "r"}},
                  "KEY_T": {"foregroundLayers": [
                    {"type": "image", "image": "img_badge"},
                    {"type": "image", "image": "snd_tap"}
                  ], "tap": {"type": "text", "value": "t"}}
                }
                """.trimIndent(),
            ),
        )
        val overrides = result.skin.keyOverrides
        assertEquals("img_key", overrides.getValue("KEY_Q").image)
        assertNull(overrides.getValue("KEY_W").image) // 音频资产作键面：类型非法
        assertNull(overrides.getValue("KEY_E").image) // 清单缺失
        assertNull(overrides.getValue("KEY_R").image) // 超 1MB
        // 前景坏 image 层整层丢弃，好层保留；其余绑定不受影响
        assertEquals(1, overrides.getValue("KEY_T").foregroundLayers!!.size)
        assertEquals("img_badge", overrides.getValue("KEY_T").foregroundLayers!![0].image)
        assertEquals(
            SkinAction.Text("t"),
            overrides.getValue("KEY_T").tap,
        )
        assertTrue(
            result.issues.any {
                it.kind == SkinIssue.Kind.RESOURCE_FALLBACK &&
                    it.path == "keyMappings.KEY_W.image"
            },
        )
    }
}
