package com.jobeen.ime.skin.model

import com.jobeen.ime.data.keyboard.theme.KeyboardColors
import com.jobeen.ime.skin.store.SkinGenRef
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 快照构建（定稿三.4/十章 + 详设 §6.2）：合成、presence、toast 回退、降级。 */
class SkinSnapshotBuilderTest {

    private val fallbacks = GeometryFallbacks(12f, 6f, 5f, 10f)
    private val ref = SkinGenRef("skin-1", "h".repeat(64), 1)

    private val defaultScheme = KeyboardColors.ColorScheme(
        keyBackground = 100,
        keyPressed = 101,
        keyBorderStroke = 102,
        specialKeyBackground = 200,
        specialKeyPressed = 201,
        specialKeyBorderStroke = 202,
        accentKeyBackground = 300,
        accentKeyPressed = 301,
        accentKeyBorderStroke = 302,
        keyText = 400,
        specialKeyText = 401,
        accentKeyText = 402,
        altText = 403,
        background = 500,
        cornerRadius = 5f,
        keyHMargin = 3f,
        keyVMargin = 4f,
        panel = KeyboardColors.ColorScheme.PanelColors(
            background = 600, toolbarText = 601, toolbarActived = 602, toolbarIcon = 603,
            candidateBackground = 604, candidateText = 605, candidateIndex = 606,
            candidateDivider = 607, toolbarPressed = 608,
        ),
        pinner = KeyboardColors.ColorScheme.PinnerColors(
            background = 700, textColor = 701, secondaryTextColor = 702,
        ),
        toastBackground = 999,
        toastText = 998,
    )

    /** 夹具默认深色板：已有 stylesDark 时不动；否则在根对象末尾补一色深色板 */
    private fun withDefaultDark(json: String): String {
        if (json.contains("\"stylesDark\"")) return json
        val trimmed = json.trimEnd()
        require(trimmed.endsWith("}")) { "fixture JSON must end with }" }
        return trimmed.dropLast(1) + """, "stylesDark": {"keyBackground": "#FF222222"}}"""
    }

    private fun validated(json: String): ValidatedSkin {
        // 2026-10-29 双模式硬要求：夹具默认补深色板（被测行为与深色板
        // 无关时用此默认；深色板自身行为由显式 stylesDark 用例覆盖）
        val tree = Json.parseToJsonElement(withDefaultDark(json)) as JsonObject
        val result = SkinValidator.validate(
            tree, 143, fallbacks, assetKind = { id ->
                when (id) {
                    "bg_main" -> AssetKind.IMAGE
                    else -> null
                }
            },
        )
        assertTrue(result is SkinValidator.Result.Valid)
        return (result as SkinValidator.Result.Valid).skin
    }

    private fun build(
        json: String,
        tier: MemoryTier = MemoryTier.NORMAL,
        sha: (String) -> String? = { "a".repeat(64) },
        targetW: Int = 1000,
        targetH: Int = 400,
    ) = SkinSnapshotBuilder.build(validated(json), ref, defaultScheme, tier, sha, targetW, targetH)

    @Test
    fun partialStylesComposeOverDefault() {
        val snapshot = build(
            """{"formatVersion": 3, "styles": {"keyBackground": "#FF0000AA", "panel": {"candidateText": "#FF0000BB"}}}""",
        )
        assertEquals(0xFF0000AA.toInt(), snapshot.colorScheme.keyBackground)
        assertEquals(0xFF0000BB.toInt(), snapshot.colorScheme.panel.candidateText)
        assertEquals(101, snapshot.colorScheme.keyPressed)
        assertEquals(500, snapshot.colorScheme.background)
    }

    @Test
    fun toastFallsBackToComposedSpecialAndKeyText() {
        val snapshot = build("""{"formatVersion": 3, "styles": {"specialKeyBackground": "#FF000111"}}""")
        // toast 未给：回退合成后的 specialKeyBackground / keyText，而非默认方案自带的 999/998
        assertEquals(0xFF000111.toInt(), snapshot.colorScheme.toastBackground)
        assertEquals(400, snapshot.colorScheme.toastText)
        val explicit = build("""{"formatVersion": 3, "styles": {"toastBackground": "#FF000222"}}""")
        assertEquals(0xFF000222.toInt(), explicit.colorScheme.toastBackground)
    }

    @Test
    fun geometryPresenceComposition() {
        val snapshot = build(
            """{"formatVersion": 3, "styles": {"keyBackground": "#FFFFFFFF"}, "appearance": {"cornerRadius": 8.0, "bottomInsetDp": 16.0}}""",
        )
        assertEquals(
            setOf(GeometryField.CORNER_RADIUS, GeometryField.BOTTOM_INSET),
            snapshot.geometryPresence,
        )
        assertEquals(8f, snapshot.colorScheme.cornerRadius)
        // 非 presence 几何取默认方案值占位（resolve 时再读偏好）
        assertEquals(3f, snapshot.colorScheme.keyHMargin)
        assertEquals(16f, snapshot.effectiveGeometry.getValue(GeometryField.BOTTOM_INSET))
        assertEquals(6f, snapshot.effectiveGeometry.getValue(GeometryField.KEY_H_MARGIN))
    }

    @Test
    fun lowTierDisablesBlur() {
        val json = """
            {"formatVersion": 3, "styles": {"keyBackground": "#FFFFFFFF"}, "appearance": {"background":
              {"type": "asset", "asset": "bg_main", "blurRadius": 15, "overlayAlpha": 0.2}}}
        """.trimIndent()
        val normal = build(json, MemoryTier.NORMAL)
        assertEquals(15f, normal.background!!.blurRadius)
        assertTrue(normal.degradations.isEmpty())
        val low = build(json, MemoryTier.LOW)
        assertEquals(0f, low.background!!.blurRadius)
        assertTrue(Degradation.BLUR_DISABLED in low.degradations)
    }

    @Test
    fun extremeTierDownsamplesPreparedKey() {
        val json = """
            {"formatVersion": 3, "styles": {"keyBackground": "#FFFFFFFF"}, "appearance": {"background":
              {"type": "asset", "asset": "bg_main", "blurRadius": 0}}}
        """.trimIndent()
        val extreme = build(json, MemoryTier.EXTREME, targetW = 1000, targetH = 400)
        assertTrue(extreme.background!!.preparedKey.contains("500x200"))
        assertTrue(Degradation.BACKGROUND_DOWNSAMPLED in extreme.degradations)
    }

    @Test
    fun backgroundDroppedWhenShaUnresolved() {
        val json = """
            {"formatVersion": 3, "styles": {"keyBackground": "#FFFFFFFF"}, "appearance": {"background":
              {"type": "asset", "asset": "bg_main"}}}
        """.trimIndent()
        val snapshot = build(json, sha = { null })
        assertNull(snapshot.background)
    }

    @Test
    fun snapshotCarriesKeyOverrides() {
        val snapshot = build(
            """{"formatVersion": 3, "styles": {"keyBackground": "#FFFFFFFF"}, "keyMappings":
              {"KEY_Q": {"label": "Q!", "tap": {"type": "command", "action": "delete_forward"}}}}""",
        )
        val override = snapshot.keyOverrides.getValue("KEY_Q")
        assertEquals("Q!", override.label)
        assertEquals(
            SkinAction.Command(WhitelistAction.DELETE_FORWARD),
            override.tap,
        )
        // 无 keyMappings 的皮肤快照覆写表为空（行为与 B1 一致）
        assertTrue(build("""{"formatVersion": 3, "styles": {"keyBackground": "#FFFFFFFF"}}""").keyOverrides.isEmpty())
    }

    @Test
    fun snapshotAdmitsOnlyReferencedSounds() {        val json = """
            {"formatVersion": 3, "styles": {"keyBackground": "#FFFFFFFF"},
             "audio": {"tap": "snd_tap"},
             "keyMappings": {"KEY_Q": {"sound": "snd_key"}, "KEY_W": {"sound": ""}}}
        """.trimIndent()
        val facts = { id: String ->
            AudioAssetFacts(
                sha256 = "s".repeat(64), ext = "mp3", byteSize = 1_000,
                info = com.jobeen.ime.skin.media.AudioInfo(100, 44_100, 1),
            ).takeIf { id != "snd_unused" }
        }
        val snapshot = SkinSnapshotBuilder.build(
            validated(json), ref, defaultScheme, MemoryTier.NORMAL,
            { "a".repeat(64) }, 1000, 400,
            audioAssetOrder = listOf("snd_unused", "snd_tap", "snd_key"),
            audioFacts = facts,
        )
        // 清单里未被引用的 snd_unused 不接纳；静音哨兵 "" 不进引用集
        assertEquals(setOf("snd_tap", "snd_key"), snapshot.soundBindings.admitted.keys)
        assertEquals("snd_tap", snapshot.soundBindings.defaultTapAssetId)
    }

    // ---- 2026-10-27 并入项链路：派生 → 引用 → 快照（顺序一次定死） ----

    @Test
    fun derivationReferenceSnapshotChain() {
        val snapshot = build(
            """
            {"formatVersion": 3,
             "derivation": {
               "face": "#FFFFFFFF", "pressed": "#FFE0E0E0", "bed": "#FFF2F2F2",
               "ink": "#FF3A3A3C", "accent": "#FF9C824A", "hint": "#FF8A8A8E"},
             "keyStyles": {"gold": {"face": "accentKeyBackground", "text": "keyText"}}}
            """.trimIndent(),
        )
        // 派生展开进快照方案：accent 令牌 = 金色
        assertEquals(0xFF9C824A.toInt(), snapshot.colorScheme.accentKeyBackground)
        assertEquals(0xFF3A3A3C.toInt(), snapshot.colorScheme.keyText)
        // 样式表随快照透传；解析目标就是派生后的快照方案
        val resolved = com.jobeen.ime.skin.runtime.KeyStyleResolver.resolve(
            "gold", null, snapshot.keyStyles, snapshot.colorScheme,
        )
        assertEquals(0xFF9C824A.toInt(), resolved.style?.face)
        assertEquals(0xFF3A3A3C.toInt(), resolved.style?.text)
        assertTrue(resolved.warnings.isEmpty())
    }
}
