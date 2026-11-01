package com.jobeen.ime.skin.model

import com.jobeen.ime.input.keyboard.key.KeyboardAction
import com.jobeen.ime.skin.media.AudioInfo
import com.jobeen.ime.skin.runtime.KeyOverrideApplier
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 顶层 keySound：校验（逐项回退）＋四级音效解析＋接纳过滤。 */
class KeySoundTest {

    private val fallbacks = GeometryFallbacks(12f, 6f, 5f, 10f)

    private fun validate(
        json: String,
        assetKind: (String) -> AssetKind? = { AssetKind.AUDIO },
    ): SkinValidator.Result.Valid {
        val tree = Json.parseToJsonElement(json) as JsonObject
        val result = SkinValidator.validate(tree, 157, fallbacks, assetKind = assetKind)
        assertTrue("expected Valid, got $result", result is SkinValidator.Result.Valid)
        return result as SkinValidator.Result.Valid
    }

    private fun base(keySound: String): String =
        "{\"formatVersion\": 3, \"styles\": {\"keyBackground\": \"#FFFFFFFF\"}, " +
            "\"stylesDark\": {\"keyBackground\": \"#FF222222\"}, \"keySound\": {$keySound}}"

    @Test
    fun keySoundSectionParses() {
        val skin = validate(base(
            "\"actions\": {\"1\": \"snd1\", \"a\": \"sndA\"}, " +
                "\"delete\": \"sndDel\", \"input\": \"sndIn\", \"system\": \"sndSys\"",
        )).skin
        assertEquals(mapOf("1" to "snd1", "a" to "sndA"), skin.keySound.actions)
        assertEquals("sndDel", skin.keySound.delete)
        assertEquals("sndIn", skin.keySound.input)
        assertEquals("sndSys", skin.keySound.system)
    }

    @Test
    fun keySoundNonStringRefsDropPerField() {
        val skin = validate(base(
            "\"actions\": {\"1\": 123, \"2\": \"snd2\"}, \"delete\": 7",
        )).skin
        assertEquals(mapOf("2" to "snd2"), skin.keySound.actions)
        assertNull(skin.keySound.delete)
    }

    @Test
    fun keySoundMissingAssetKeepsRefWithHint() {
        // 与 audio 同口径：资产缺失只记提示，不丢引用；接纳期再过滤。
        val skin = validate(base("\"delete\": \"ghost\""), assetKind = { null }).skin
        assertEquals("ghost", skin.keySound.delete)
    }

    private fun bindings(): SoundBindings = SoundBindings(
        admitted = mapOf(
            "snd1" to AdmittedSound("snd1", "a".repeat(64), "mp3", 1_000),
            "sndDel" to AdmittedSound("sndDel", "b".repeat(64), "mp3", 1_000),
            "sndIn" to AdmittedSound("sndIn", "c".repeat(64), "mp3", 1_000),
            "sndSys" to AdmittedSound("sndSys", "d".repeat(64), "mp3", 1_000),
            "sndTap" to AdmittedSound("sndTap", "e".repeat(64), "mp3", 1_000),
        ),
        defaultTapAssetId = "sndTap",
        rejected = emptyList(),
        skippedByDegradation = false,
        keySoundActions = mapOf("1" to "snd1"),
        keySoundDelete = "sndDel",
        keySoundInput = "sndIn",
        keySoundSystem = "sndSys",
    )

    @Test
    fun resolveSoundKeySoundChain() {
        val b = bindings()
        fun plan(action: KeyboardAction?) = KeyOverrideApplier.resolveSound(null, b, action)
        assertEquals(
            KeyOverrideApplier.SoundPlan.Play("sndDel"),
            plan(KeyboardAction.BackspaceAction),
        )
        assertEquals(
            KeyOverrideApplier.SoundPlan.Play("snd1"),
            plan(KeyboardAction.KeySequenceAction("1")),
        )
        assertEquals(
            KeyOverrideApplier.SoundPlan.Play("sndIn"),
            plan(KeyboardAction.KeySequenceAction("z")),
        )
        assertEquals(
            KeyOverrideApplier.SoundPlan.Play("sndIn"),
            plan(KeyboardAction.SpaceAction),
        )
        assertEquals(
            KeyOverrideApplier.SoundPlan.Play("sndSys"),
            plan(KeyboardAction.LayoutSwitchAction("T9")),
        )
        assertEquals(KeyOverrideApplier.SoundPlan.Play("sndTap"), plan(null))
        // 逐键覆写高于 keySound；空覆写静音（不回退 keySound）。
        assertEquals(
            KeyOverrideApplier.SoundPlan.Play("snd1"),
            KeyOverrideApplier.resolveSound(
                KeyOverride(soundRef = "snd1"), b, KeyboardAction.BackspaceAction,
            ),
        )
        assertEquals(
            KeyOverrideApplier.SoundPlan.Muted,
            KeyOverrideApplier.resolveSound(
                KeyOverride(soundRef = ""), b, KeyboardAction.BackspaceAction,
            ),
        )
    }

    @Test
    fun admitFiltersKeySoundToAdmitted() {
        val facts = AudioAssetFacts(
            sha256 = "f".repeat(64),
            ext = "mp3",
            byteSize = 1_000,
            info = AudioInfo(800, 44_100, 2),
        )
        val result = SkinSoundAdmission.admit(
            referencedInOrder = listOf("sndDel", "ghost"),
            defaultTapAssetId = null,
            facts = { id -> if (id == "sndDel") facts else null },
            audioBudgetBytes = 32L * 1024 * 1024,
            skipAll = false,
            keySoundActions = mapOf("1" to "ghost"),
            keySoundDelete = "sndDel",
            keySoundInput = "ghost",
            keySoundSystem = null,
        )
        assertEquals("sndDel", result.keySoundDelete)
        assertNull(result.keySoundInput)
        assertTrue(result.keySoundActions.isEmpty())
    }
}
