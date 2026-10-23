package com.jobeen.ime.skin.model

import com.jobeen.ime.input.keyboard.key.KeyIds
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * keyMappings 强类型解析（详设 §3）：白名单全表每项至少一合法
 * 样本 + 一非法样本；逐项剔除口径（坏绑定只剔自身）。
 */
class SkinKeyMappingsParserTest {

    private fun parse(json: String): SkinKeyMappingsParser.ParseResult {
        val tree = Json.parseToJsonElement(json) as JsonObject
        return SkinKeyMappingsParser.parse(tree, KeyIds::isOverridable, KeyIds::isAnchor)
    }

    private fun parseEntry(entryJson: String): SkinKeyMappingsParser.ParseResult =
        parse("""{"KEY_Q": $entryJson}""")

    // ---------- 合法样本：白名单全表 ----------

    @Test
    fun everyWhitelistActionHasValidSample() {
        // 格式名 → 期望解析出的 Command（voice_input 单独测：仅长按独占）
        val samples: Map<String, SkinAction.Command> = mapOf(
            "commit_direct" to SkinAction.Command(WhitelistAction.COMMIT_DIRECT, ","),
            "literal_newline" to SkinAction.Command(WhitelistAction.LITERAL_NEWLINE),
            "enter" to SkinAction.Command(WhitelistAction.ENTER),
            "delete" to SkinAction.Command(WhitelistAction.DELETE),
            "delete_forward" to SkinAction.Command(WhitelistAction.DELETE_FORWARD),
            "space" to SkinAction.Command(WhitelistAction.SPACE),
            "cursor_left" to SkinAction.Command(WhitelistAction.CURSOR_LEFT),
            "cursor_right" to SkinAction.Command(WhitelistAction.CURSOR_RIGHT),
            "rotate_schema" to SkinAction.Command(WhitelistAction.ROTATE_SCHEMA),
            "open_schema_picker" to SkinAction.Command(WhitelistAction.OPEN_SCHEMA_PICKER),
            "switch_system_ime" to SkinAction.Command(WhitelistAction.SWITCH_SYSTEM_IME),
            "open_system_ime_picker" to SkinAction.Command(WhitelistAction.OPEN_SYSTEM_IME_PICKER),
            "hide_keyboard" to SkinAction.Command(WhitelistAction.HIDE_KEYBOARD),
            "caps" to SkinAction.Command(WhitelistAction.CAPS),
            "switch_layout" to SkinAction.Command(WhitelistAction.SWITCH_LAYOUT, "T9"),
            "resume" to SkinAction.Command(WhitelistAction.RESUME),
            "clear" to SkinAction.Command(WhitelistAction.CLEAR),
            "undo" to SkinAction.Command(WhitelistAction.UNDO),
            "redo" to SkinAction.Command(WhitelistAction.REDO),
        )
        // 白名单封闭性：样本表必须覆盖枚举全集（除 voice_input 单独覆盖）
        assertEquals(
            WhitelistAction.entries.toSet() - WhitelistAction.VOICE_INPUT,
            samples.values.map { it.action }.toSet(),
        )
        for ((formatName, expected) in samples) {
            val valueJson = expected.value?.let { """, "value": "$it"""" } ?: ""
            val result = parseEntry(
                """{"tap": {"type": "command", "action": "$formatName"$valueJson}}""",
            )
            assertTrue("$formatName 不应被剔除: ${result.rejections}", result.rejections.isEmpty())
            assertEquals(formatName, expected, result.overrides.getValue("KEY_Q").tap)
        }
    }

    @Test
    fun textActionValid() {
        val result = parseEntry("""{"tap": {"type": "text", "value": "q"}}""")
        assertEquals(SkinAction.Text("q"), result.overrides.getValue("KEY_Q").tap)
    }

    @Test
    fun voiceInputValidOnlyAsSoleLongPress() {
        val result = parseEntry(
            """{"longPress": [{"type": "command", "action": "voice_input"}]}""",
        )
        assertTrue(result.rejections.isEmpty())
        assertEquals(
            listOf(SkinAction.Command(WhitelistAction.VOICE_INPUT)),
            result.overrides.getValue("KEY_Q").longPress,
        )
    }

    @Test
    fun longPressArrayOrderPreserved() {
        val result = parseEntry(
            """{"longPress": [
                {"type": "text", "value": "1"},
                {"type": "command", "action": "commit_direct", "value": "!"},
                {"type": "command", "action": "delete"}
            ]}""",
        )
        assertEquals(
            listOf(
                SkinAction.Text("1"),
                SkinAction.Command(WhitelistAction.COMMIT_DIRECT, "!"),
                SkinAction.Command(WhitelistAction.DELETE),
            ),
            result.overrides.getValue("KEY_Q").longPress,
        )
    }

    @Test
    fun labelAndSoundFieldsParsed() {
        val result = parseEntry("""{"label": "酷", "sound": "snd_pop"}""")
        val override = result.overrides.getValue("KEY_Q")
        assertEquals("酷", override.label)
        assertEquals("snd_pop", override.soundRef)
        // 空串 = 静音哨兵
        val muted = parseEntry("""{"sound": ""}""")
        assertEquals("", muted.overrides.getValue("KEY_Q").soundRef)
    }

    @Test
    fun sequenceRejectedAsRemoved() {
        // B3 裁定删除 sequence 占位（详设 §6.6）：解析一律拒绝，
        // 不再产生 SequencePlaceholder，sequenceSlots 恒空
        val result = parseEntry("""{"tap": {"type": "sequence", "steps": []}}""")
        // 唯一槽位被剔 → 整条覆写不产出（条目级口径与其它全剔情形一致）
        assertFalse("KEY_Q" in result.overrides)
        assertTrue(result.sequenceSlots.isEmpty())
        val rejection = result.rejections.single()
        assertEquals(BindingRejectReason.SEQUENCE_REMOVED, rejection.reason)
        assertEquals(KeySlot.TAP, rejection.slot)
    }

    // ---------- 非法样本：逐项剔除 ----------

    private fun singleRejection(entryJson: String): KeyBindingRejection {
        val result = parseEntry(entryJson)
        assertEquals("应恰一条剔除: ${result.rejections}", 1, result.rejections.size)
        return result.rejections.single()
    }

    @Test
    fun unknownActionNameRejected() {
        val rejection = singleRejection(
            """{"tap": {"type": "command", "action": "select_schema"}}""",
        )
        assertEquals(BindingRejectReason.UNKNOWN_ACTION, rejection.reason)
        assertEquals(KeySlot.TAP, rejection.slot)
    }

    @Test
    fun deprecatedInsertCharTreatedAsUnknown() {
        val rejection = singleRejection("""{"tap": {"type": "insert_char", "value": "x"}}""")
        assertEquals(BindingRejectReason.UNKNOWN_ACTION, rejection.reason)
    }

    @Test
    fun missingTypeRejected() {
        val rejection = singleRejection("""{"tap": {"value": "q"}}""")
        assertEquals(BindingRejectReason.UNKNOWN_ACTION, rejection.reason)
    }

    @Test
    fun textWithoutValueRejected() {
        val rejection = singleRejection("""{"tap": {"type": "text"}}""")
        assertEquals(BindingRejectReason.VALUE_MISSING, rejection.reason)
    }

    @Test
    fun commitDirectWithoutValueRejected() {
        val rejection = singleRejection(
            """{"tap": {"type": "command", "action": "commit_direct"}}""",
        )
        assertEquals(BindingRejectReason.VALUE_MISSING, rejection.reason)
    }

    @Test
    fun forbiddenValueRejected() {
        val rejection = singleRejection(
            """{"tap": {"type": "command", "action": "enter", "value": "x"}}""",
        )
        assertEquals(BindingRejectReason.VALUE_FORBIDDEN, rejection.reason)
    }

    @Test
    fun switchLayoutBadTargetRejected() {
        val rejection = singleRejection(
            """{"tap": {"type": "command", "action": "switch_layout", "value": "Dvorak"}}""",
        )
        assertEquals(BindingRejectReason.VALUE_INVALID, rejection.reason)
    }

    @Test
    fun switchLayoutMissingValueRejected() {
        val rejection = singleRejection(
            """{"tap": {"type": "command", "action": "switch_layout"}}""",
        )
        assertEquals(BindingRejectReason.VALUE_MISSING, rejection.reason)
    }

    @Test
    fun voiceInputInTapRejectedBadSlot() {
        val rejection = singleRejection(
            """{"tap": {"type": "command", "action": "voice_input"}}""",
        )
        assertEquals(BindingRejectReason.BAD_SLOT, rejection.reason)
    }

    @Test
    fun voiceInputNotExclusiveDropsOnlyVoice() {
        val result = parseEntry(
            """{"longPress": [
                {"type": "text", "value": "1"},
                {"type": "command", "action": "voice_input"}
            ]}""",
        )
        assertEquals(
            listOf(KeyBindingRejection("KEY_Q", KeySlot.LONG_PRESS, BindingRejectReason.VOICE_NOT_EXCLUSIVE)),
            result.rejections,
        )
        // 其余元素保留
        assertEquals(
            listOf(SkinAction.Text("1")),
            result.overrides.getValue("KEY_Q").longPress,
        )
    }

    @Test
    fun unknownKeyIdRejectedWholeEntry() {
        val result = parse("""{"NOPE_KEY": {"tap": {"type": "text", "value": "q"}}}""")
        assertTrue(result.overrides.isEmpty())
        assertEquals(
            listOf(KeyBindingRejection("NOPE_KEY", null, BindingRejectReason.UNKNOWN_ID)),
            result.rejections,
        )
    }

    @Test
    fun t9Key0IsUnknownId() {
        // 审计事实：T9 无 0 键——引用它与引用任意未知 ID 同处理
        val result = parse("""{"T9_KEY_0": {"tap": {"type": "text", "value": "0"}}}""")
        assertEquals(BindingRejectReason.UNKNOWN_ID, result.rejections.single().reason)
    }

    @Test
    fun anchorIdNotOverridable() {
        val result = parse("""{"SYM_RETURN": {"label": "回"}}""")
        assertTrue(result.overrides.isEmpty())
        assertEquals(
            BindingRejectReason.ANCHOR_NOT_OVERRIDABLE,
            result.rejections.single().reason,
        )
    }

    @Test
    fun labelTooLongDropsLabelOnly() {
        val longLabel = "x".repeat(SkinKeyMappingsParser.MAX_LABEL_LENGTH + 1)
        val result = parseEntry(
            """{"label": "$longLabel", "tap": {"type": "text", "value": "q"}}""",
        )
        val override = result.overrides.getValue("KEY_Q")
        assertNull(override.label)
        assertEquals(SkinAction.Text("q"), override.tap)
        assertEquals(BindingRejectReason.STRUCTURE, result.rejections.single().reason)
    }

    @Test
    fun structureErrorsRejectedPerItem() {
        // 条目非对象
        var result = parse("""{"KEY_Q": 42}""")
        assertEquals(BindingRejectReason.STRUCTURE, result.rejections.single().reason)
        // 动作节点非对象
        result = parseEntry("""{"tap": "q"}""")
        assertEquals(BindingRejectReason.STRUCTURE, result.rejections.single().reason)
        // longPress 非数组
        result = parseEntry("""{"longPress": {"type": "text", "value": "q"}}""")
        assertEquals(BindingRejectReason.STRUCTURE, result.rejections.single().reason)
        assertEquals(KeySlot.LONG_PRESS, result.rejections.single().slot)
        // sound 非字符串
        result = parseEntry("""{"sound": 7}""")
        assertEquals(BindingRejectReason.STRUCTURE, result.rejections.single().reason)
    }

    @Test
    fun longPressAllInvalidFallsBackWholeSlot() {
        val result = parseEntry(
            """{"longPress": [{"type": "command", "action": "nope"}]}""",
        )
        // 槽位剔空 → 整键无有效字段 → 不进覆写表
        assertTrue(result.overrides.isEmpty())
        assertEquals(BindingRejectReason.UNKNOWN_ACTION, result.rejections.single().reason)
    }

    @Test
    fun badBindingDoesNotPoisonSiblingKeys() {
        val result = parse(
            """{
                "KEY_Q": {"tap": {"type": "command", "action": "nope"}},
                "KEY_W": {"tap": {"type": "text", "value": "w"}}
            }""",
        )
        assertNull(result.overrides["KEY_Q"])
        assertEquals(SkinAction.Text("w"), result.overrides.getValue("KEY_W").tap)
        assertEquals(1, result.rejections.size)
    }
}
