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
            "switch_schema" to SkinAction.Command(WhitelistAction.SWITCH_SCHEMA, "wanxiang"),
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
            "toggle_ascii_mode" to SkinAction.Command(WhitelistAction.TOGGLE_ASCII_MODE),
            "tab" to SkinAction.Command(WhitelistAction.TAB),
            "escape" to SkinAction.Command(WhitelistAction.ESCAPE),
            "home" to SkinAction.Command(WhitelistAction.HOME),
            "end" to SkinAction.Command(WhitelistAction.END),
            "settings" to SkinAction.Command(WhitelistAction.SETTINGS),
            "system_settings" to SkinAction.Command(WhitelistAction.SYSTEM_SETTINGS),
            "run_script" to SkinAction.Command(WhitelistAction.RUN_SCRIPT, "s.one"),
            "open_script" to SkinAction.Command(WhitelistAction.OPEN_SCRIPT),
            "none" to SkinAction.Command(WhitelistAction.NONE),
            "dismiss_keyboard" to SkinAction.Command(WhitelistAction.DISMISS_KEYBOARD),
            "open_url" to SkinAction.Command(WhitelistAction.OPEN_URL, "https://example.com"),
            "return_primary_keyboard" to SkinAction.Command(WhitelistAction.RETURN_PRIMARY_KEYBOARD),
            "return_last_keyboard" to SkinAction.Command(WhitelistAction.RETURN_LAST_KEYBOARD),
            "shift" to SkinAction.Command(WhitelistAction.MOMENTARY_SHIFT),
            "symbol" to SkinAction.Command(WhitelistAction.SYMBOL, "€"),
            "#简繁切换" to SkinAction.Command(WhitelistAction.HASH_TRADITIONAL),
            "#RimeSwitcher" to SkinAction.Command(WhitelistAction.HASH_RIME_SWITCHER),
            "#次选上屏" to SkinAction.Command(WhitelistAction.HASH_SECOND_CANDIDATE),
            "#三选上屏" to SkinAction.Command(WhitelistAction.HASH_THIRD_CANDIDATE),
            "#方案切换" to SkinAction.Command(WhitelistAction.HASH_SWITCH_SCHEME),
            "#行首" to SkinAction.Command(WhitelistAction.HASH_LINE_START),
            "#行尾" to SkinAction.Command(WhitelistAction.HASH_LINE_END),
            "#换行" to SkinAction.Command(WhitelistAction.HASH_NEWLINE),
            "#Enter" to SkinAction.Command(WhitelistAction.HASH_ENTER),
            "#重输" to SkinAction.Command(WhitelistAction.HASH_RETYPE),
            "#cut" to SkinAction.Command(WhitelistAction.HASH_CUT),
            "#copy" to SkinAction.Command(WhitelistAction.HASH_COPY),
            "#paste" to SkinAction.Command(WhitelistAction.HASH_PASTE),
            "#deleteText" to SkinAction.Command(WhitelistAction.HASH_DELETE_TEXT),
            "#undo" to SkinAction.Command(WhitelistAction.HASH_UNDO),
            "#redo" to SkinAction.Command(WhitelistAction.HASH_REDO),
            "#verticalCandidatesPageUp" to SkinAction.Command(WhitelistAction.HASH_VERTICAL_PAGE_UP),
            "#verticalCandidatesPageDown" to SkinAction.Command(WhitelistAction.HASH_VERTICAL_PAGE_DOWN),
            "#showPhraseView" to SkinAction.Command(WhitelistAction.HASH_SHOW_PHRASE_VIEW),
            "#candidatesBarStateToggle" to SkinAction.Command(WhitelistAction.HASH_CANDIDATES_BAR_TOGGLE),
            "#rimePreviousPage" to SkinAction.Command(WhitelistAction.HASH_RIME_PREV_PAGE),
            "#rimeNextPage" to SkinAction.Command(WhitelistAction.HASH_RIME_NEXT_PAGE),
            "#toggleEmbeddedInputMode" to SkinAction.Command(WhitelistAction.HASH_TOGGLE_EMBEDDED_INPUT),
            "#toggleScriptView" to SkinAction.Command(WhitelistAction.HASH_TOGGLE_SCRIPT_VIEW),
            "#clearSystemPasteboard" to SkinAction.Command(WhitelistAction.HASH_CLEAR_CLIPBOARD),
            "#toggleSymbolBar" to SkinAction.Command(WhitelistAction.HASH_TOGGLE_SYMBOL_BAR),
            "#keyboardMenu" to SkinAction.Command(WhitelistAction.HASH_KEYBOARD_MENU),
            "#toggleCommandView" to SkinAction.Command(WhitelistAction.HASH_TOGGLE_COMMAND_VIEW),
            "#selectText" to SkinAction.Command(WhitelistAction.HASH_SELECT_TEXT),
            "#showPasteboardView" to SkinAction.Command(WhitelistAction.HASH_SHOW_PASTEBOARD_VIEW),
            "#subCollectionPageUp" to SkinAction.Command(WhitelistAction.HASH_SUB_COLLECTION_PAGE_UP),
            "#subCollectionPageDown" to SkinAction.Command(WhitelistAction.HASH_SUB_COLLECTION_PAGE_DOWN),
            "#keyboardPerformance" to SkinAction.Command(WhitelistAction.HASH_KEYBOARD_PERFORMANCE),
            // P3 新增通知动作（解析期值格式已校验，样本须合法）。
            "character" to SkinAction.Command(WhitelistAction.CHARACTER, "a"),
            "send_keys" to SkinAction.Command(WhitelistAction.SEND_KEYS, "ctrl+a"),
            "send_key_event" to SkinAction.Command(WhitelistAction.SEND_KEY_EVENT, "ctrl+a"),
            "rime_option" to SkinAction.Command(WhitelistAction.RIME_OPTION, "ascii"),
            "delay" to SkinAction.Command(WhitelistAction.DELAY, "100"),
            "snippet" to SkinAction.Command(WhitelistAction.SNIPPET, "hi"),
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
    fun lockToggleNamesRejectedAsUnknown() {
        // 板锁已删（.22）：锁切换名不再是白名单动作，按未知动作剔除。
        for (name in listOf("symbolic_lock_toggle", "emoji_lock_toggle")) {
            val rejection = singleRejection(
                """{"tap": {"type": "command", "action": "$name"}}""",
            )
            assertEquals("$name 应按未知动作剔除", BindingRejectReason.UNKNOWN_ACTION, rejection.reason)
            assertEquals(KeySlot.TAP, rejection.slot)
        }
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
    fun switchLayoutEmojiTargetAccepted() {
        // W19-4：切表情板。switch_layout "Emoji" 是出厂六布局之一
        // （LAYOUT_TARGETS），解析后应为 Command(SWITCH_LAYOUT, "Emoji")，不被剔除。
        val result = parseEntry(
            """{"tap": {"type": "command", "action": "switch_layout", "value": "Emoji"}}""",
        )
        assertTrue("Emoji 不应被剔除: ${result.rejections}", result.rejections.isEmpty())
        assertEquals(
            SkinAction.Command(WhitelistAction.SWITCH_LAYOUT, "Emoji"),
            result.overrides.getValue("KEY_Q").tap,
        )
    }

    @Test
    fun switchLayoutMissingValueRejected() {
        val rejection = singleRejection(
            """{"tap": {"type": "command", "action": "switch_layout"}}""",
        )
        assertEquals(BindingRejectReason.VALUE_MISSING, rejection.reason)
    }

    @Test
    fun switchSchemaBlankValueRejected() {
        val rejection = singleRejection(
            """{"tap": {"type": "command", "action": "switch_schema", "value": "  "}}""",
        )
        assertEquals(BindingRejectReason.VALUE_INVALID, rejection.reason)
    }

    @Test
    fun switchSchemaMissingValueRejected() {
        val rejection = singleRejection(
            """{"tap": {"type": "command", "action": "switch_schema"}}""",
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

    @Test
    fun pressedFields_parseAndFallback() {
        val result = parseEntry(
            """{"pressedIcon": "close", "pressedImage": "img_pressed"}""",
        )
        val override = result.overrides["KEY_Q"]
        assertEquals("close", override?.pressedIcon)
        assertEquals("img_pressed", override?.pressedImage)

        val blank = parseEntry("""{"pressedIcon": "", "pressedImage": 7}""")
        assertNull(blank.overrides["KEY_Q"]?.pressedIcon)
        assertNull(blank.overrides["KEY_Q"]?.pressedImage)
    }

    // ---------- 审计 E 新增节点与槽位 ----------

    @Test
    fun symbolNodeParsedAsLiteralCommand() {
        val result = parseEntry("""{"tap": {"type": "symbol", "value": "€"}}""")
        assertTrue(result.rejections.isEmpty())
        assertEquals(
            SkinAction.Command(WhitelistAction.SYMBOL, "€"),
            result.overrides.getValue("KEY_Q").tap,
        )
        val blank = parseEntry("""{"tap": {"type": "symbol", "value": " "}}""")
        assertEquals(BindingRejectReason.VALUE_INVALID, blank.rejections.single().reason)
    }

    @Test
    fun sendKeysNodeParsedWithModifiers() {
        val result = parseEntry(
            """{"tap": {"type": "sendKeys", "keys": [
                {"key": "a", "modifiers": ["ctrl"]},
                {"key": "enter"}
            ]}}""",
        )
        assertTrue(result.rejections.isEmpty())
        val keys = (result.overrides.getValue("KEY_Q").tap as SkinAction.SendKeys).keys
        assertEquals(2, keys.size)
        assertEquals(29, keys[0].keyCode)
        assertTrue(keys[0].modifiers.ctrl)
        assertEquals(66, keys[1].keyCode)
        // 未知键名／未知修饰键整组剔除
        assertEquals(
            BindingRejectReason.VALUE_INVALID,
            parseEntry("""{"tap": {"type": "sendKeys", "keys": [{"key": "nope"}]}}""")
                .rejections.single().reason,
        )
        assertEquals(
            BindingRejectReason.VALUE_INVALID,
            parseEntry(
                """{"tap": {"type": "sendKeys", "keys": [{"key": "a", "modifiers": ["function"]}]}}""",
            ).rejections.single().reason,
        )
    }

    @Test
    fun sendKeyEventNodeParsed() {
        val result = parseEntry(
            """{"tap": {"type": "sendKeyEvent", "key": "s", "modifiers": ["ctrl", "shift"]}}""",
        )
        assertTrue(result.rejections.isEmpty())
        assertEquals(
            SkinAction.SendKeyEvent(47, ctrl = true, shift = true),
            result.overrides.getValue("KEY_Q").tap,
        )
    }

    @Test
    fun combineNodeParsedInOrderAndRejectsOnBadChild() {
        val result = parseEntry(
            """{"tap": {"type": "combine", "actions": [
                {"type": "text", "value": "a"},
                {"type": "command", "action": "space"}
            ]}}""",
        )
        assertTrue(result.rejections.isEmpty())
        assertEquals(
            SkinAction.Combine(
                listOf(
                    SkinAction.Text("a"),
                    SkinAction.Command(WhitelistAction.SPACE),
                ),
            ),
            result.overrides.getValue("KEY_Q").tap,
        )
        val bad = parseEntry(
            """{"tap": {"type": "combine", "actions": [
                {"type": "text", "value": "a"},
                {"type": "command", "action": "nope"}
            ]}}""",
        )
        assertNull(bad.overrides["KEY_Q"]?.tap)
        assertEquals(1, bad.rejections.size)
        assertEquals(BindingRejectReason.UNKNOWN_ACTION, bad.rejections.single().reason)
    }

    @Test
    fun momentaryShiftOnlyInTapSlot() {
        val bad = parseEntry(
            """{"swipeUp": {"type": "command", "action": "shift"}}""",
        )
        assertEquals(BindingRejectReason.BAD_SLOT, bad.rejections.single().reason)
    }

    @Test
    fun unmappedHashShortcutsRejectedAsUnknown() {
        // #subCollectionPageUp、#showPasteboardView、#keyboardPerformance
        // 自 v1.1.16.18 起已入白名单（审计 #2 P1-5/P1-4/P2-10），
        // 不再按未知动作剔除；仍未列入的才按未知剔除。
        for (name in listOf(
            "#rimeDeploy", "#rimeSync",
        )) {
            val result = parseEntry(
                """{"tap": {"type": "command", "action": "$name"}}""",
            )
            assertEquals(
                "$name 应按未知动作剔除",
                BindingRejectReason.UNKNOWN_ACTION,
                result.rejections.single().reason,
            )
        }
    }

    @Test
    fun runScriptBlankOrMissingValueRejected() {
        val blank = parseEntry(
            """{"tap": {"type": "command", "action": "run_script", "value": "  "}}""",
        )
        assertNull(blank.overrides["KEY_Q"]?.tap)
        assertEquals(BindingRejectReason.VALUE_INVALID, blank.rejections.single().reason)
        val missing = parseEntry(
            """{"tap": {"type": "command", "action": "run_script"}}""",
        )
        assertNull(missing.overrides["KEY_Q"]?.tap)
        assertEquals(BindingRejectReason.VALUE_MISSING, missing.rejections.single().reason)
        val withValue = parseEntry(
            """{"tap": {"type": "command", "action": "open_script", "value": "x"}}""",
        )
        assertNull(withValue.overrides["KEY_Q"]?.tap)
        assertEquals(BindingRejectReason.VALUE_FORBIDDEN, withValue.rejections.single().reason)
    }

    @Test
    fun openUrlBlankValueRejected() {
        val result = parseEntry(
            """{"tap": {"type": "command", "action": "open_url", "value": " "}}""",
        )
        assertEquals(BindingRejectReason.VALUE_INVALID, result.rejections.single().reason)
    }

    @Test
    fun preeditUppercasedRepeatAnimationAndStateStyleSlotsParsed() {
        val result = parseEntry(
            """{
                "tap": {"type": "text", "value": "q"},
                "preeditTap": {"type": "command", "action": "space"},
                "preeditSwipeUp": {"type": "text", "value": "u"},
                "uppercasedTap": {"type": "command", "action": "caps"},
                "repeat": {"type": "command", "action": "delete"},
                "animations": ["pop", "spin"],
                "swipeUpForegroundStyle": "hot",
                "swipeDownForegroundStyle": "cold",
                "uppercasedForegroundStyle": "upper",
                "capsLockedForegroundStyle": "locked"
            }""",
        )
        assertTrue(result.rejections.isEmpty())
        val override = result.overrides.getValue("KEY_Q")
        assertEquals(SkinAction.Command(WhitelistAction.SPACE), override.preeditTap)
        assertEquals(SkinAction.Text("u"), override.preeditSwipeUp)
        assertEquals(SkinAction.Command(WhitelistAction.CAPS), override.uppercasedTap)
        assertEquals(SkinAction.Command(WhitelistAction.DELETE), override.repeat)
        assertEquals(listOf("pop", "spin"), override.animations)
        assertEquals("hot", override.swipeUpForegroundStyle)
        assertEquals("cold", override.swipeDownForegroundStyle)
        assertEquals("upper", override.uppercasedForegroundStyle)
        assertEquals("locked", override.capsLockedForegroundStyle)
    }

    @Test
    fun animationsExcessItemsDropped() {
        val names = (1..10).joinToString(", ") { "\"a$it\"" }
        val result = parseEntry("""{"animations": [$names]}""")
        assertEquals(8, result.overrides.getValue("KEY_Q").animations?.size)
        assertEquals(2, result.rejections.size)
    }

    @Test
    fun keyCodeTableCoversLettersDigitsAndNamedKeys() {
        assertEquals(29, SkinKeyCodes.keyCode("a"))
        assertEquals(54, SkinKeyCodes.keyCode("Z"))
        assertEquals(7, SkinKeyCodes.keyCode("0"))
        assertEquals(16, SkinKeyCodes.keyCode("9"))
        assertEquals(61, SkinKeyCodes.keyCode("tab"))
        assertEquals(111, SkinKeyCodes.keyCode("escape"))
        assertEquals(122, SkinKeyCodes.keyCode("home"))
        assertEquals(123, SkinKeyCodes.keyCode("end"))
        assertNull(SkinKeyCodes.keyCode("function"))
        assertNull(SkinKeyCodes.keyCode("nope"))
        assertNull(SkinKeyCodes.parseModifiers(listOf("function")))
        assertEquals(
            SkinKeyCodes.ParsedModifiers(ctrl = true, alt = true),
            SkinKeyCodes.parseModifiers(listOf("control", "option")),
        )
    }

    @Test
    fun imageObjectFormParsesSpriteRef() {
        val result = parseEntry(
            """{"tap": {"type": "text", "value": "q"},
              "image": {"file": "sheet1", "image": "btn"}}""",
        )
        val override = result.overrides.getValue("KEY_Q")
        assertNull(override.image)
        assertEquals(SkinImageRef("sheet1", "btn"), override.imageRef)
    }

    @Test
    fun imageRefAloneKeepsOverride() {
        val result = parseEntry("""{"image": {"file": "sheet1", "image": "btn"}}""")
        val override = result.overrides.getValue("KEY_Q")
        assertEquals(SkinImageRef("sheet1", "btn"), override.imageRef)
    }

    @Test
    fun imageStringFormUnchanged() {
        val result = parseEntry(
            """{"tap": {"type": "text", "value": "q"}, "image": "pic1"}""",
        )
        val override = result.overrides.getValue("KEY_Q")
        assertEquals("pic1", override.image)
        assertNull(override.imageRef)
    }

    @Test
    fun imageObjectMissingFieldRejected() {
        val result = parseEntry("""{"image": {"file": "sheet1"}}""")
        assertNull(result.overrides["KEY_Q"])
        assertTrue(result.rejections.isNotEmpty())
    }

    @Test
    fun pressedImageObjectFormParsesSpriteRef() {
        val result = parseEntry(
            """{"tap": {"type": "text", "value": "q"},
              "pressedImage": {"file": "sheet1", "image": "btn_pressed"}}""",
        )
        val override = result.overrides.getValue("KEY_Q")
        assertNull(override.pressedImage)
        assertEquals(SkinImageRef("sheet1", "btn_pressed"), override.pressedImageRef)
    }
}
