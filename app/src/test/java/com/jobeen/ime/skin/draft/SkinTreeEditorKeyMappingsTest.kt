package com.jobeen.ime.skin.draft

import com.jobeen.ime.skin.model.BindingRejectReason
import com.jobeen.ime.skin.model.SkinJson
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 树编辑器 B2 扩展（详设 §6.3）：keyMappings/audio 节点 get/set、
 * 未知节点往返保真（§10.2-8）、写前预校验、资产引用收集。
 */
class SkinTreeEditorKeyMappingsTest {

    private fun tree(json: String): JsonObject = SkinJson.parseObject(json)!!

    private val base = tree(
        """
        {"formatVersion": 3,
         "meta": {"id": "s1", "name": "测试"},
         "unknownNode": {"x": [1, 2]},
         "keyMappings": {
           "KEY_Q": {"label": "Q", "futureField": {"nested": true},
                     "tap": {"type": "text", "value": "q"}},
           "KEY_W": {"label": "W"}
         },
         "audio": {"tap": "snd_main", "futureSlot": "snd_x"}}
        """.trimIndent(),
    )

    @Test
    fun setKeyEntryPreservesUnknownSiblings() {
        val entry = buildJsonObject { put("label", "酷") }
        val result = SkinTreeEditor.setKeyEntry(base, "KEY_E", entry)
        // 未知根节点、未知条目字段、其余键、audio 未知槽全部保留
        assertTrue(result.containsKey("unknownNode"))
        val q = SkinTreeEditor.keyEntryOf(result, "KEY_Q")!!
        assertTrue(q.containsKey("futureField"))
        assertEquals("W", (SkinTreeEditor.keyEntryOf(result, "KEY_W")!!["label"] as JsonPrimitive).content)
        assertEquals("酷", (SkinTreeEditor.keyEntryOf(result, "KEY_E")!!["label"] as JsonPrimitive).content)
        assertEquals("snd_x", SkinTreeEditor.run {
            ((result["audio"] as JsonObject)["futureSlot"] as JsonPrimitive).content
        })
    }

    @Test
    fun removeKeyEntryAndSectionCleanup() {
        var result = SkinTreeEditor.setKeyEntry(base, "KEY_Q", null)
        assertNull(SkinTreeEditor.keyEntryOf(result, "KEY_Q"))
        assertTrue(SkinTreeEditor.keyEntryOf(result, "KEY_W") != null)
        result = SkinTreeEditor.setKeyEntry(result, "KEY_W", null)
        // 映射删空 → 整段移除
        assertNull(result["keyMappings"])
        assertTrue(result.containsKey("unknownNode"))
    }

    @Test
    fun audioTapGetSetRemove() {
        assertEquals("snd_main", SkinTreeEditor.audioTapOf(base))
        val changed = SkinTreeEditor.setAudioTap(base, "snd_new")
        assertEquals("snd_new", SkinTreeEditor.audioTapOf(changed))
        // 其余 audio 槽保留
        assertEquals(
            "snd_x",
            (((changed["audio"] as JsonObject)["futureSlot"]) as JsonPrimitive).content,
        )
        val removed = SkinTreeEditor.setAudioTap(changed, null)
        assertNull(SkinTreeEditor.audioTapOf(removed))
        // 只剩未知槽时 audio 段保留（不误删未知节点）
        assertTrue(removed.containsKey("audio"))
        val bare = SkinTreeEditor.setAudioTap(tree("""{"formatVersion": 3}"""), "snd_a")
        assertEquals("snd_a", SkinTreeEditor.audioTapOf(bare))
        val bareRemoved = SkinTreeEditor.setAudioTap(bare, null)
        assertNull(bareRemoved["audio"])
    }

    @Test
    fun referencedAssetIdsIncludeKeySounds() {
        val withSounds = tree(
            """
            {"formatVersion": 3,
             "appearance": {"background": {"type": "asset", "asset": "bg_main"}},
             "audio": {"tap": "snd_tap"},
             "keyMappings": {
               "KEY_Q": {"sound": "snd_key"},
               "KEY_W": {"sound": ""},
               "KEY_E": {"label": "E"}
             }}
            """.trimIndent(),
        )
        assertEquals(
            setOf("bg_main", "snd_tap", "snd_key"),
            SkinTreeEditor.referencedAssetIds(withSounds),
        )
    }

    @Test
    fun validateKeyEntryUsesImportRules() {
        // 合法条目 → 无剔除
        val valid = buildJsonObject {
            put("label", "Q")
            put("tap", buildJsonObject {
                put("type", "text")
                put("value", "q")
            })
            put(
                "longPress",
                kotlinx.serialization.json.buildJsonArray {
                    add(buildJsonObject { put("type", "command"); put("action", "voice_input") })
                },
            )
        }
        assertTrue(SkinTreeEditor.validateKeyEntry("KEY_Q", valid).isEmpty())
        // sequence 已删除（B3 §6.6）：写前校验同样拒绝
        val withSequence = buildJsonObject {
            put("tap", buildJsonObject { put("type", "sequence") })
        }
        val sequenceRejections = SkinTreeEditor.validateKeyEntry("KEY_Q", withSequence)
        assertEquals(
            listOf(BindingRejectReason.SEQUENCE_REMOVED),
            sequenceRejections.map { it.reason },
        )
        // voice_input 放错槽 → BAD_SLOT
        val badSlot = buildJsonObject {
            put("tap", buildJsonObject { put("type", "command"); put("action", "voice_input") })
        }
        val rejections = SkinTreeEditor.validateKeyEntry("KEY_Q", badSlot)
        assertEquals(1, rejections.size)
        assertEquals(BindingRejectReason.BAD_SLOT, rejections.single().reason)
        // 未知 ID / 锚点
        assertEquals(
            BindingRejectReason.UNKNOWN_ID,
            SkinTreeEditor.validateKeyEntry("NOPE", valid).single().reason,
        )
        assertEquals(
            BindingRejectReason.ANCHOR_NOT_OVERRIDABLE,
            SkinTreeEditor.validateKeyEntry("SYM_RETURN", valid).single().reason,
        )
    }

    @Test
    fun roundTripFidelityForUntouchedTree() {
        // §10.2-8：不编辑的树经解析→序列化往返，节点集合不变
        val serialized = kotlinx.serialization.json.Json.encodeToString(
            JsonObject.serializer(), base,
        )
        val reparsed = SkinJson.parseObject(serialized)!!
        assertEquals(base.keys, reparsed.keys)
        assertEquals(
            SkinTreeEditor.keyMappingsOf(base),
            SkinTreeEditor.keyMappingsOf(reparsed),
        )
        assertEquals(base["unknownNode"], reparsed["unknownNode"])
    }
}
