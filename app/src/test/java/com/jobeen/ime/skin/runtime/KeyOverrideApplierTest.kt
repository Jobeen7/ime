package com.jobeen.ime.skin.runtime

import com.jobeen.ime.data.keyboard.theme.KeyboardColors
import com.jobeen.ime.input.keyboard.impl.QwertyKeyboard
import com.jobeen.ime.input.keyboard.impl.T9Keyboard
import com.jobeen.ime.input.keyboard.key.KeyDef.Appearance.Variant
import com.jobeen.ime.input.keyboard.key.KeyDef
import com.jobeen.ime.input.keyboard.key.KeyboardAction
import com.jobeen.ime.skin.model.AdmittedSound
import com.jobeen.ime.skin.model.Degradation
import com.jobeen.ime.skin.model.DynamicTextSource
import com.jobeen.ime.skin.model.KeyAnimationSpec
import com.jobeen.ime.skin.model.KeyForegroundLayer
import com.jobeen.ime.skin.model.KeyOverride
import com.jobeen.ime.skin.model.SkinAction
import com.jobeen.ime.skin.model.SkinSnapshot
import com.jobeen.ime.skin.model.SoundBindings
import com.jobeen.ime.skin.model.WhitelistAction
import com.jobeen.ime.skin.store.SkinGenRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 覆写装配器（详设 §4.2）：恒等透传、复制纪律、槽位合并、
 * 多项长按候选条、占位符、音效三级解析、出厂静态零修改。
 */
class KeyOverrideApplierTest {

    private val scheme = KeyboardColors.ColorScheme(
        keyBackground = 1, keyPressed = 2, keyBorderStroke = 3,
        specialKeyBackground = 4, specialKeyPressed = 5, specialKeyBorderStroke = 6,
        accentKeyBackground = 7, accentKeyPressed = 8, accentKeyBorderStroke = 9,
        keyText = 10, specialKeyText = 11, accentKeyText = 12, altText = 13,
        background = 14, cornerRadius = 5f, keyHMargin = 3f, keyVMargin = 4f,
        panel = KeyboardColors.ColorScheme.PanelColors(
            background = 20, toolbarText = 21, toolbarActived = 22, toolbarIcon = 23,
            candidateBackground = 24, candidateText = 25, candidateIndex = 26,
            candidateDivider = 27, toolbarPressed = 28,
        ),
        pinner = KeyboardColors.ColorScheme.PinnerColors(
            background = 30, textColor = 31, secondaryTextColor = 32,
        ),
        toastBackground = 40, toastText = 41,
    )

    private fun snapshot(
        overrides: Map<String, KeyOverride> = emptyMap(),
        sounds: SoundBindings = SoundBindings.EMPTY,
    ) = SkinSnapshot(
        ref = SkinGenRef("skin-1", "h".repeat(64), 1),
        name = "test",
        colorScheme = scheme,
        geometryPresence = emptySet(),
        effectiveGeometry = emptyMap(),
        background = null,
        degradations = emptySet(),
        layoutId = null,
        keyOverrides = overrides,
        soundBindings = sounds,
    )

    private val noPlaceholders = emptyMap<String, String>()

    private fun applyQwerty(snapshot: SkinSnapshot?) =
        KeyOverrideApplier.apply("Qwerty", QwertyKeyboard.buildLayout(Variant.Accent), snapshot, noPlaceholders)

    private fun KeyDef.pressAction(): KeyboardAction? =
        behaviors.filterIsInstance<KeyDef.Behavior.Press>().firstOrNull()?.action

    private fun KeyDef.longPressAction(): KeyboardAction? =
        behaviors.filterIsInstance<KeyDef.Behavior.LongPress>().firstOrNull()?.action

    private fun List<List<KeyDef>>.byId(id: String): KeyDef =
        flatten().first { it.id == id }

    // ---------- 恒等透传 ----------

    @Test
    fun identityWhenNoSnapshotOrNoOverrides() {
        val layout = QwertyKeyboard.buildLayout(Variant.Accent)
        assertSame(layout, KeyOverrideApplier.apply("Qwerty", layout, null, noPlaceholders))
        assertSame(layout, KeyOverrideApplier.apply("Qwerty", layout, snapshot(), noPlaceholders))
    }

    @Test
    fun identityWhenOverridesBelongToOtherLayout() {
        val layout = QwertyKeyboard.buildLayout(Variant.Accent)
        val snap = snapshot(mapOf("T9_KEY_2" to KeyOverride(label = "x")))
        assertSame(layout, KeyOverrideApplier.apply("Qwerty", layout, snap, noPlaceholders))
    }

    @Test
    fun sequenceOnlyOverrideIsIdentity() {
        val layout = QwertyKeyboard.buildLayout(Variant.Accent)
        val snap = snapshot(
            mapOf("KEY_Q" to KeyOverride(tap = SkinAction.SequencePlaceholder)),
        )
        assertSame(layout, KeyOverrideApplier.apply("Qwerty", layout, snap, noPlaceholders))
    }

    // ---------- label ----------

    @Test
    fun labelOverrideCopiesOnlyHitKey() {
        val layout = QwertyKeyboard.buildLayout(Variant.Accent)
        val result = KeyOverrideApplier.apply(
            "Qwerty", layout, snapshot(mapOf("KEY_Q" to KeyOverride(label = "酷"))), noPlaceholders,
        )
        val copied = result.byId("KEY_Q")
        assertNotSame(layout.byId("KEY_Q"), copied)
        assertEquals("酷", (copied.appearance as KeyDef.Appearance.Text).displayText)
        // 未命中键原对象透传；输入布局零修改
        assertSame(layout.byId("KEY_W"), result.byId("KEY_W"))
        assertEquals("q", (layout.byId("KEY_Q").appearance as KeyDef.Appearance.Text).displayText)
    }

    @Test
    fun altTextLabelKeepsAltTextAndStaticLayoutUntouched() {
        // v1.1.16 适配：出厂布局不再是静态单例，改用同一次构建结果
        // 验证“应用不原地修改输入布局”（跨构建 assertSame 已无意义）。
        val factory = T9Keyboard.buildLayout(Variant.Accent)
        val factoryDef = factory.flatten().first { it.id == "T9_KEY_2" }
        val result = KeyOverrideApplier.apply(
            "T9", factory,
            snapshot(mapOf("T9_KEY_2" to KeyOverride(label = "贰"))),
            noPlaceholders,
        )
        val copied = result.byId("T9_KEY_2")
        val appearance = copied.appearance as KeyDef.Appearance.AltText
        assertEquals("贰", appearance.displayText)
        assertEquals("2", appearance.altText)
        // 输入布局绝不原地修改：原对象仍是出厂值，且结果为副本
        assertNotSame(factoryDef, copied)
        assertEquals(
            "ABC",
            (factoryDef.appearance as KeyDef.Appearance.AltText).displayText,
        )
        assertNull(factoryDef.skinSoundAssetId)
    }

    @Test
    fun subLabelTopRidesAltTextAndConvertsTextKey() {
        // AltText 键：底部副显被覆写、顶部小字写入 skinTopText；
        // Text 键：仅顶部小字也转为 AltText（底部为空串）、顶部写入。
        val factory = T9Keyboard.buildLayout(Variant.Accent)
        val result = KeyOverrideApplier.apply(
            "T9", factory,
            snapshot(
                mapOf(
                    "T9_KEY_2" to KeyOverride(subLabel = "底", subLabelTop = "顶"),
                ),
            ),
            noPlaceholders,
        )
        val alt = result.byId("T9_KEY_2").appearance as KeyDef.Appearance.AltText
        assertEquals("底", alt.altText)
        assertEquals("顶", alt.skinTopText)

        val qwerty = QwertyKeyboard.buildLayout(Variant.Accent)
        // 取一个出厂纯 Text 键（非 AltText、非 ImageText）：仅顶部小字
        // 也应转为 AltText（底部为空串）、顶部写入 skinTopText。
        val textKey = qwerty.flatten().first {
            it.appearance is KeyDef.Appearance.Text &&
                it.appearance !is KeyDef.Appearance.AltText &&
                it.appearance !is KeyDef.Appearance.ImageText &&
                it.id != null
        }
        val qResult = KeyOverrideApplier.apply(
            "Qwerty", qwerty,
            snapshot(mapOf(textKey.id!! to KeyOverride(subLabelTop = "顶"))),
            noPlaceholders,
        )
        val converted = qResult.byId(textKey.id!!).appearance as KeyDef.Appearance.AltText
        assertEquals("", converted.altText)
        assertEquals("顶", converted.skinTopText)
    }

    @Test
    fun mnemonicOffSuppressesSkinHintsButKeepsFactoryAlt() {
        // 助记关：皮肤附加小字（上下）全部压制，出厂副显保留；
        // 装饰关：前景叠加层压制（视图层阴影另读偏好，不在此测）。
        val factory = T9Keyboard.buildLayout(Variant.Accent)
        val off = KeyOverrideApplier.apply(
            "T9", factory,
            snapshot(
                mapOf(
                    "T9_KEY_2" to KeyOverride(
                        subLabel = "底",
                        subLabelTop = "顶",
                        foregroundLayers = listOf(KeyForegroundLayer(type = "text", text = "·")),
                    ),
                ),
            ),
            noPlaceholders,
            mnemonicEnabled = false,
            decorationEnabled = false,
        )
        val suppressed = off.byId("T9_KEY_2").appearance as KeyDef.Appearance.AltText
        assertEquals("2", suppressed.altText)
        assertNull(suppressed.skinTopText)
        assertNull(suppressed.skinForegroundLayers)

        val on = KeyOverrideApplier.apply(
            "T9", factory,
            snapshot(
                mapOf(
                    "T9_KEY_2" to KeyOverride(
                        subLabel = "底",
                        subLabelTop = "顶",
                        foregroundLayers = listOf(KeyForegroundLayer(type = "text", text = "·")),
                    ),
                ),
            ),
            noPlaceholders,
        )
        val kept = on.byId("T9_KEY_2").appearance as KeyDef.Appearance.AltText
        assertEquals("底", kept.altText)
        assertEquals("顶", kept.skinTopText)
        assertEquals(1, kept.skinForegroundLayers!!.size)
    }

    @Test
    fun keyAnimationStampsAllKeysAndSpaceTakesSpaceSpec() {
        // 通用动效写全部键；空格键取空格专属规格；降级关闭时全不写。
        val qwerty = QwertyKeyboard.buildLayout(Variant.Accent)
        val keyAnim = KeyAnimationSpec(enabled = true, pressedScale = 0.95f, durationMs = 100)
        val spaceAnim = KeyAnimationSpec(enabled = true, pressedScale = 0.9f, durationMs = 200)
        val base = snapshot(emptyMap()).copy(
            keyAnimation = keyAnim,
            spaceAnimation = spaceAnim,
        )
        val result = KeyOverrideApplier.apply("Qwerty", qwerty, base, noPlaceholders)
        assertEquals(keyAnim, result.byId("KEY_Q").appearance.skinKeyAnimation)
        assertEquals(spaceAnim, result.byId("KEY_SPACE").appearance.skinKeyAnimation)

        val degraded = base.copy(
            degradations = setOf(Degradation.ANIMATIONS_DISABLED),
        )
        val degradedResult = KeyOverrideApplier.apply("Qwerty", qwerty, degraded, noPlaceholders)
        assertNull(degradedResult.byId("KEY_Q").appearance.skinKeyAnimation)
        assertNull(degradedResult.byId("KEY_SPACE").appearance.skinKeyAnimation)

        // 空格专属未写时空格跟随通用规格。
        val follow = base.copy(spaceAnimation = null)
        val followResult = KeyOverrideApplier.apply("Qwerty", qwerty, follow, noPlaceholders)
        assertEquals(keyAnim, followResult.byId("KEY_SPACE").appearance.skinKeyAnimation)
    }

    // ---------- 槽位合并 ----------

    @Test
    fun tapOverrideReplacesPressKeepsFactoryLongPress() {
        val result = applyQwerty(
            snapshot(mapOf("KEY_Q" to KeyOverride(tap = SkinAction.Text("x")))),
        )
        val def = result.byId("KEY_Q")
        assertEquals(KeyboardAction.KeySequenceAction("x"), def.pressAction())
        assertEquals(KeyboardAction.CommitAction("1"), def.longPressAction())
    }

    @Test
    fun swipeSingleSideCreatesNewSwipe() {
        val result = applyQwerty(
            snapshot(
                mapOf(
                    "KEY_Q" to KeyOverride(
                        swipeUp = SkinAction.Command(WhitelistAction.DELETE),
                    ),
                ),
            ),
        )
        val swipe = result.byId("KEY_Q").behaviors
            .filterIsInstance<KeyDef.Behavior.Swipe>().single()
        assertEquals(KeyboardAction.BackspaceAction, swipe.upAction)
        assertNull(swipe.downAction)
    }

    @Test
    fun swipeOverrideMergesFactoryOtherSide() {
        val result = applyQwerty(
            snapshot(
                mapOf(
                    "KEY_DELETE" to KeyOverride(swipeDown = SkinAction.Text("x")),
                ),
            ),
        )
        val swipe = result.byId("KEY_DELETE").behaviors
            .filterIsInstance<KeyDef.Behavior.Swipe>().single()
        assertEquals(KeyboardAction.UndoAction, swipe.upAction) // 出厂上滑保留
        assertEquals(KeyboardAction.KeySequenceAction("x"), swipe.downAction)
    }

    @Test
    fun repeatSurvivesTapOverrideOnBackspace() {
        val result = applyQwerty(
            snapshot(mapOf("KEY_DELETE" to KeyOverride(tap = SkinAction.Text("x")))),
        )
        val def = result.byId("KEY_DELETE")
        assertTrue(def.behaviors.any { it is KeyDef.Behavior.Repeat })
        assertEquals(KeyboardAction.KeySequenceAction("x"), def.pressAction())
    }

    @Test
    fun longPressSingleBecomesDirectBehavior() {
        val result = applyQwerty(
            snapshot(
                mapOf(
                    "KEY_Q" to KeyOverride(
                        longPress = listOf(
                            SkinAction.Command(WhitelistAction.DELETE_FORWARD),
                        ),
                    ),
                ),
            ),
        )
        val def = result.byId("KEY_Q")
        assertEquals(KeyboardAction.DeleteForwardAction, def.longPressAction())
        assertTrue(def.popups == null || def.popups.none { it is KeyDef.Popup.Keyboard })
    }

    @Test
    fun longPressMultiBecomesPopupStripOnly() {
        val items = listOf(
            SkinAction.Text("1"),
            SkinAction.Command(WhitelistAction.COMMIT_DIRECT, "!"),
            SkinAction.Command(WhitelistAction.DELETE),
        )
        val result = applyQwerty(
            snapshot(mapOf("KEY_Q" to KeyOverride(longPress = items))),
        )
        val def = result.byId("KEY_Q")
        // 候选条形态：无 LongPress 行为（单槽监听不被遮蔽，定稿四.7）
        assertNull(def.longPressAction())
        val popup = def.popups?.filterIsInstance<KeyDef.Popup.Keyboard>()?.single()
        assertEquals(
            listOf(
                KeyboardAction.KeySequenceAction("1"),
                KeyboardAction.LiteralCommitAction("!"),
                KeyboardAction.BackspaceAction,
            ),
            popup?.keys,
        )
    }

    @Test
    fun voiceSingleLongPressKeepsBehaviorForm() {
        // voice 穿线依赖 Behavior.LongPress 分支（详设 §4.2-5）
        val result = applyQwerty(
            snapshot(
                mapOf(
                    "KEY_SPACE" to KeyOverride(
                        longPress = listOf(
                            SkinAction.Command(WhitelistAction.VOICE_INPUT),
                        ),
                    ),
                ),
            ),
        )
        assertEquals(
            KeyboardAction.VoiceInputAction(),
            result.byId("KEY_SPACE").longPressAction(),
        )
    }

    // ---------- 占位符 ----------

    @Test
    fun placeholdersSubstituteInLabelOnly() {
        val values = mapOf("schemaName" to "万象", "versionName" to "1.2.0")
        val result = KeyOverrideApplier.apply(
            "Qwerty", QwertyKeyboard.buildLayout(Variant.Accent),
            snapshot(
                mapOf(
                    "KEY_Q" to KeyOverride(
                        label = "\$schemaName·\$versionName·\$bogus",
                        tap = SkinAction.Command(
                            WhitelistAction.COMMIT_DIRECT, "\$schemaName",
                        ),
                    ),
                ),
            ),
            values,
        )
        val def = result.byId("KEY_Q")
        assertEquals(
            "万象·1.2.0·\$bogus",
            (def.appearance as KeyDef.Appearance.Text).displayText,
        )
        // 动作 value 永不替换（替换即改语义，详设 §3.7-1）
        assertEquals(
            KeyboardAction.LiteralCommitAction("\$schemaName"),
            def.pressAction(),
        )
    }

    // ---------- 音效解析 ----------

    private fun soundsWithTap(): SoundBindings = SoundBindings(
        admitted = mapOf(
            "snd_a" to AdmittedSound("snd_a", "s".repeat(64), "mp3", 1_000),
            "snd_tap" to AdmittedSound("snd_tap", "t".repeat(64), "mp3", 1_000),
        ),
        defaultTapAssetId = "snd_tap",
        rejected = emptyList(),
        skippedByDegradation = false,
    )

    @Test
    fun soundResolutionChain() {
        val bindings = soundsWithTap()
        fun plan(override: KeyOverride?) =
            KeyOverrideApplier.resolveSound(override, bindings)
        assertEquals(
            KeyOverrideApplier.SoundPlan.Play("snd_a"),
            plan(KeyOverride(soundRef = "snd_a")),
        )
        assertEquals(
            KeyOverrideApplier.SoundPlan.Muted,
            plan(KeyOverride(soundRef = "")),
        )
        // 未接纳引用回退全局 tap
        assertEquals(
            KeyOverrideApplier.SoundPlan.Play("snd_tap"),
            plan(KeyOverride(soundRef = "ghost")),
        )
        assertEquals(
            KeyOverrideApplier.SoundPlan.Play("snd_tap"),
            plan(KeyOverride(label = "x")),
        )
        assertEquals(
            KeyOverrideApplier.SoundPlan.Play("snd_tap"),
            plan(null),
        )
        // 无任何绑定 → 出厂
        assertEquals(
            KeyOverrideApplier.SoundPlan.Inherit,
            KeyOverrideApplier.resolveSound(null, SoundBindings.EMPTY),
        )
    }

    @Test
    fun globalTapAppliesToUnhitKeysViaCopies() {
        val layout = QwertyKeyboard.buildLayout(Variant.Accent)
        val result = KeyOverrideApplier.apply(
            "Qwerty", layout, snapshot(sounds = soundsWithTap()), noPlaceholders,
        )
        val w = result.byId("KEY_W")
        assertNotSame(layout.byId("KEY_W"), w)
        assertEquals("snd_tap", w.skinSoundAssetId)
        assertFalse(w.skinSoundMuted)
        // 出厂输入零修改
        assertNull(layout.byId("KEY_W").skinSoundAssetId)
    }

    @Test
    fun mutedKeyStaysMutedWithGlobalTap() {
        val result = applyQwerty(
            snapshot(
                overrides = mapOf("KEY_Q" to KeyOverride(soundRef = "")),
                sounds = soundsWithTap(),
            ),
        )
        val q = result.byId("KEY_Q")
        assertTrue(q.skinSoundMuted)
        assertNull(q.skinSoundAssetId)
    }

    // ---------- 运行时映射 ----------

    @Test
    fun runtimeMappingTable() {
        val cases: List<Pair<SkinAction, KeyboardAction>> = listOf(
            SkinAction.Text("ab") to KeyboardAction.KeySequenceAction("ab"),
            SkinAction.Command(WhitelistAction.COMMIT_DIRECT, ",") to
                KeyboardAction.LiteralCommitAction(","),
            SkinAction.Command(WhitelistAction.LITERAL_NEWLINE) to
                KeyboardAction.CommitAction("\n"),
            SkinAction.Command(WhitelistAction.ENTER) to KeyboardAction.ReturnAction(),
            SkinAction.Command(WhitelistAction.DELETE) to KeyboardAction.BackspaceAction,
            SkinAction.Command(WhitelistAction.DELETE_FORWARD) to
                KeyboardAction.DeleteForwardAction,
            SkinAction.Command(WhitelistAction.SPACE) to KeyboardAction.SpaceAction,
            SkinAction.Command(WhitelistAction.CURSOR_LEFT) to
                KeyboardAction.CursorMoveAction(-1),
            SkinAction.Command(WhitelistAction.CURSOR_RIGHT) to
                KeyboardAction.CursorMoveAction(1),
            SkinAction.Command(WhitelistAction.ROTATE_SCHEMA) to KeyboardAction.RotateSchema,
            SkinAction.Command(WhitelistAction.SWITCH_SCHEMA, "wanxiang") to
                KeyboardAction.SwitchSchemaByValue("wanxiang"),
            SkinAction.Command(WhitelistAction.OPEN_SCHEMA_PICKER) to
                KeyboardAction.ShowInputMethodPickerAction,
            SkinAction.Command(WhitelistAction.SWITCH_SYSTEM_IME) to
                KeyboardAction.LangSwitchAction,
            SkinAction.Command(WhitelistAction.OPEN_SYSTEM_IME_PICKER) to
                KeyboardAction.OpenSystemImePickerAction,
            SkinAction.Command(WhitelistAction.HIDE_KEYBOARD) to
                KeyboardAction.HideKeyboardAction,
            SkinAction.Command(WhitelistAction.CAPS) to KeyboardAction.CapsAction,
            SkinAction.Command(WhitelistAction.SWITCH_LAYOUT, "T9") to
                KeyboardAction.LayoutSwitchAction("T9"),
            SkinAction.Command(WhitelistAction.RESUME) to KeyboardAction.ResumeAction,
            SkinAction.Command(WhitelistAction.CLEAR) to KeyboardAction.ClearAction,
            SkinAction.Command(WhitelistAction.UNDO) to KeyboardAction.UndoAction,
            SkinAction.Command(WhitelistAction.REDO) to KeyboardAction.RedoAction,
            SkinAction.Command(WhitelistAction.VOICE_INPUT) to
                KeyboardAction.VoiceInputAction(),
        )
        // 覆盖白名单全集
        assertEquals(
            WhitelistAction.entries.toSet(),
            cases.mapNotNull { (it.first as? SkinAction.Command)?.action }.toSet(),
        )
        for ((skin, expected) in cases) {
            assertEquals("$skin", expected, KeyOverrideApplier.toRuntime(skin))
        }
    }

    // ---------- G1 动态文字运行时跟随 ----------

    @Test
    fun dynamicTextStampedAndResolvedFromSnapshot() {
        val snap = snapshot(
            mapOf(
                "KEY_Q" to KeyOverride(
                    label = "静态",
                    dynamicText = DynamicTextSource.CANDIDATE_FIRST,
                ),
            ),
        )
        val result = KeyOverrideApplier.apply(
            "Qwerty",
            QwertyKeyboard.buildLayout(Variant.Accent),
            snap,
            noPlaceholders,
            dynamicTexts = mapOf(DynamicTextSource.CANDIDATE_FIRST to "首选"),
        )
        val def = result.byId("KEY_Q")
        assertEquals("首选", (def.appearance as KeyDef.Appearance.Text).displayText)
        assertEquals("candidateFirst", def.appearance.skinDynamicText)
        assertEquals("静态", def.appearance.skinDynamicFallback)
    }

    @Test
    fun dynamicTextFallsBackToStaticWithoutValue() {
        val snap = snapshot(
            mapOf(
                "KEY_Q" to KeyOverride(
                    label = "静态",
                    dynamicText = DynamicTextSource.COMPOSING,
                ),
            ),
        )
        val def = applyQwerty(snap).byId("KEY_Q")
        assertEquals("静态", (def.appearance as KeyDef.Appearance.Text).displayText)
        assertEquals("composing", def.appearance.skinDynamicText)
        assertEquals("静态", def.appearance.skinDynamicFallback)
    }

    @Test
    fun resolveDynamicDisplayTextPrefersNonEmptyValue() {
        assertEquals(
            "首选",
            KeyOverrideApplier.resolveDynamicDisplayText(
                "静态", "candidateFirst", mapOf("candidateFirst" to "首选"),
            ),
        )
        assertEquals(
            "静态",
            KeyOverrideApplier.resolveDynamicDisplayText(
                "静态", "candidateFirst", mapOf("candidateFirst" to ""),
            ),
        )
        assertEquals(
            "静态",
            KeyOverrideApplier.resolveDynamicDisplayText("静态", "candidateFirst", emptyMap()),
        )
        assertEquals(
            "静态",
            KeyOverrideApplier.resolveDynamicDisplayText(
                "静态", null, mapOf("candidateFirst" to "首选"),
            ),
        )
    }
}
