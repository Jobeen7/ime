package com.jobeen.ime.skin.layout

import com.jobeen.ime.input.keyboard.impl.NumberKeyboard
import com.jobeen.ime.input.keyboard.impl.QwertyKeyboard
import com.jobeen.ime.input.keyboard.impl.T15Keyboard
import com.jobeen.ime.input.keyboard.impl.T9Keyboard
import com.jobeen.ime.input.keyboard.key.KeyDef
import com.jobeen.ime.input.keyboard.key.KeyboardAction
import com.jobeen.ime.skin.model.SkinAction
import com.jobeen.ime.skin.runtime.KeyOverrideApplier
import java.io.File
import java.nio.file.Files
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 内置布局接应：id 登记、校验口径、覆盖装配、代次目录解析（2026-10-28 修复轮）。 */
class BuiltinLayoutTest {

    @Test
    fun builtinIdsMatchFactoryKeyboardNames() {
        assertEquals(
            setOf(T9Keyboard.NAME, QwertyKeyboard.NAME, T15Keyboard.NAME, NumberKeyboard.NAME),
            BuiltinLayouts.IDS,
        )
        assertTrue(BuiltinLayouts.isBuiltin("T9"))
        assertTrue(!BuiltinLayouts.isBuiltin("custom11"))
    }

    // ---- 校验口径 ----

    private fun layoutJson(
        id: String,
        keys: String,
        heightPercent: String? = null,
    ): String = "{\"formatVersion\": 3, \"meta\": {\"id\": \"$id\", \"name\": \"$id\"}, " +
        (if (heightPercent != null) "\"heightPercent\": $heightPercent, " else "") +
        "\"rows\": [{\"keys\": [$keys]}]}"

    private fun keyJson(
        stableId: String,
        label: String = "x",
        weight: Double = 1.0,
        rowSpan: Int = 1,
        tap: String? = null,
    ): String {
        val tapPart = if (tap != null) ", \"tap\": {\"text\": \"$tap\"}" else ""
        return "{\"stableId\": \"$stableId\", \"label\": \"$label\", " +
            "\"widthWeight\": $weight, \"rowSpan\": $rowSpan$tapPart}"
    }

    @Test
    fun builtinFileMayOmitTap_customFileMayNot() {
        val json = layoutJson("T9", keyJson("T9_KEY_2"))
        val tree = Json.parseToJsonElement(json)
        val builtin = LayoutValidator.validate(tree, "T9", allowMissingTap = true)
        assertTrue("expected Valid, got $builtin", builtin is LayoutValidation.Valid)
        assertNull((builtin as LayoutValidation.Valid).layout.rows.single().single().tap)
        val custom = LayoutValidator.validate(tree, "T9", allowMissingTap = false)
        assertTrue("expected Invalid, got $custom", custom is LayoutValidation.Invalid)
    }

    @Test
    fun heightPercentRange() {
        val ok = LayoutValidator.validate(
            Json.parseToJsonElement(layoutJson("T9", keyJson("T9_KEY_2"), "120")),
            "T9", allowMissingTap = true,
        ) as LayoutValidation.Valid
        assertEquals(120.0, ok.layout.heightPercent)
        val bad = LayoutValidator.validate(
            Json.parseToJsonElement(layoutJson("T9", keyJson("T9_KEY_2"), "20")),
            "T9", allowMissingTap = true,
        ) as LayoutValidation.Valid
        assertNull(bad.layout.heightPercent)
        assertTrue(bad.issues.any { it.path == "heightPercent" })
        val absent = LayoutValidator.validate(
            Json.parseToJsonElement(layoutJson("T9", keyJson("T9_KEY_2"))),
            "T9", allowMissingTap = true,
        ) as LayoutValidation.Valid
        assertNull(absent.layout.heightPercent)
    }

    // ---- 覆盖装配 ----

    private fun textKey(id: String, text: String, width: Float): KeyDef = KeyDef(
        appearance = KeyDef.Appearance.Text(
            displayText = text, textSize = 15f, percentWidth = width,
        ),
        behaviors = setOf(KeyDef.Behavior.Press(KeyboardAction.CommitAction(text))),
        id = id,
    )

    private fun layoutKey(
        stableId: String,
        label: String = "",
        weight: Double = 1.0,
        rowSpan: Int = 1,
        tap: SkinAction? = null,
        swipeUp: SkinAction? = null,
        swipeDown: SkinAction? = null,
        longPress: List<SkinAction>? = null,
        landscapeWeight: Double? = null,
        styleRef: String? = null,
    ) = LayoutKeyModel(
        stableId = stableId, label = label, widthWeight = weight, rowSpan = rowSpan,
        alignSpanEnd = false, tap = tap, swipeUp = swipeUp, swipeDown = swipeDown,
        longPress = longPress, landscapeWidthWeight = landscapeWeight,
        styleRef = styleRef, styleOverrides = null,
    )

    @Test
    fun arrangementWeightsAndRowSpanWin() {
        val factory = listOf(listOf(
            textKey("T9_KEY_2", "2", 0.5f),
            textKey("T9_KEY_3", "3", 0.5f),
        ))
        val override = LayoutModel(
            layoutId = "T9", name = "T9",
            rows = listOf(listOf(
                layoutKey("T9_KEY_3", weight = 1.0),
                layoutKey("T9_KEY_2", weight = 3.0, rowSpan = 2),
            )),
        )
        val arranged = BuiltinLayoutApplier.apply(factory, override, false, emptyMap())
        assertEquals(listOf("T9_KEY_3", "T9_KEY_2"), arranged.single().map { it.id })
        assertEquals(0.25f, arranged.single()[0].appearance.percentWidth)
        assertEquals(0.75f, arranged.single()[1].appearance.percentWidth)
        assertEquals(2, arranged.single()[1].appearance.rowSpan)
    }

    @Test
    fun perSlotActions_omittedSlotsKeepFactory() {
        val swipeKey = textKey("T9_KEY_2", "2", 0.5f).let {
            KeyDef(
                appearance = it.appearance,
                behaviors = setOf(
                    KeyDef.Behavior.Press(KeyboardAction.CommitAction("2")),
                    KeyDef.Behavior.Swipe(
                        upAction = KeyboardAction.CommitAction("up"),
                        downAction = KeyboardAction.CommitAction("down"),
                    ),
                ),
                id = "T9_KEY_2",
            )
        }
        val factory = listOf(listOf(swipeKey, textKey("T9_KEY_3", "3", 0.5f)))
        val override = LayoutModel(
            layoutId = "T9", name = "T9",
            rows = listOf(listOf(
                layoutKey("T9_KEY_2", swipeUp = SkinAction.Text("U")),
                layoutKey("T9_KEY_3", tap = SkinAction.Text("X"), label = "三"),
            )),
        )
        val arranged = BuiltinLayoutApplier.apply(factory, override, false, emptyMap())
        val k2 = arranged.single()[0]
        val swipe = k2.behaviors.filterIsInstance<KeyDef.Behavior.Swipe>().single()
        assertEquals(KeyOverrideApplier.toRuntime(SkinAction.Text("U")), swipe.upAction)
        assertEquals(KeyboardAction.CommitAction("down"), swipe.downAction)
        assertEquals(
            KeyboardAction.CommitAction("2"),
            k2.behaviors.filterIsInstance<KeyDef.Behavior.Press>().single().action,
        )
        val k3 = arranged.single()[1]
        assertEquals(
            KeyOverrideApplier.toRuntime(SkinAction.Text("X")),
            k3.behaviors.filterIsInstance<KeyDef.Behavior.Press>().single().action,
        )
        assertEquals("三", (k3.appearance as KeyDef.Appearance.Text).displayText)
        assertEquals("2", (k2.appearance as KeyDef.Appearance.Text).displayText)
    }

    @Test
    fun longPressSingleBecomesBehavior_multiBecomesPopup() {
        val factory = listOf(listOf(
            textKey("T9_KEY_2", "2", 0.5f),
            textKey("T9_KEY_3", "3", 0.5f),
        ))
        val override = LayoutModel(
            layoutId = "T9", name = "T9",
            rows = listOf(listOf(
                layoutKey("T9_KEY_2", longPress = listOf(SkinAction.Text("z"))),
                layoutKey("T9_KEY_3", longPress = listOf(SkinAction.Text("a"), SkinAction.Text("b"))),
            )),
        )
        val arranged = BuiltinLayoutApplier.apply(factory, override, false, emptyMap())
        assertEquals(1, arranged.single()[0].behaviors.filterIsInstance<KeyDef.Behavior.LongPress>().size)
        assertNull(arranged.single()[0].popups)
        val popup = arranged.single()[1].popups?.filterIsInstance<KeyDef.Popup.Keyboard>()?.single()
        assertEquals(2, popup?.keys?.size)
    }

    @Test
    fun unknownIdsDropped_emptyFallsBackToFactory() {
        val factory = listOf(listOf(textKey("T9_KEY_2", "2", 1f)))
        val override = LayoutModel(
            layoutId = "T9", name = "T9",
            rows = listOf(listOf(layoutKey("NO_SUCH_KEY"))),
        )
        assertEquals(factory, BuiltinLayoutApplier.apply(factory, override, false, emptyMap()))
    }

    @Test
    fun landscapeWeightsUsedInLandscapeOnly() {
        val factory = listOf(listOf(
            textKey("T9_KEY_2", "2", 0.5f),
            textKey("T9_KEY_3", "3", 0.5f),
        ))
        val override = LayoutModel(
            layoutId = "T9", name = "T9",
            rows = listOf(listOf(
                layoutKey("T9_KEY_2", weight = 1.0, landscapeWeight = 3.0),
                layoutKey("T9_KEY_3", weight = 1.0, landscapeWeight = 1.0),
            )),
        )
        val portrait = BuiltinLayoutApplier.apply(factory, override, false, emptyMap())
        assertEquals(0.5f, portrait.single()[0].appearance.percentWidth)
        val landscape = BuiltinLayoutApplier.apply(factory, override, true, emptyMap())
        assertEquals(0.75f, landscape.single()[0].appearance.percentWidth)
        assertEquals(0.25f, landscape.single()[1].appearance.percentWidth)
    }

    @Test
    fun styleRefCarriedForLaterResolution() {
        val factory = listOf(listOf(textKey("T9_KEY_2", "2", 1f)))
        val override = LayoutModel(
            layoutId = "T9", name = "T9",
            rows = listOf(listOf(layoutKey("T9_KEY_2", styleRef = "gold"))),
        )
        val arranged = BuiltinLayoutApplier.apply(factory, override, false, emptyMap())
        assertEquals("gold", arranged.single().single().skinStyleRef)
    }

    // ---- 代次目录解析（含重置路径） ----

    @Test
    fun resolveFromGenDir_missingAndInvalidFallBack() {
        val genDir = Files.createTempDirectory("builtin-gen").toFile()
        val layoutsDir = File(genDir, "layouts").apply { mkdirs() }
        val validT9 = layoutJson("T9", keyJson("T9_KEY_2", label = "2"))
        File(layoutsDir, "T9.json").writeText(validT9)
        val dupQwerty = "{\"formatVersion\": 3, \"meta\": {\"id\": \"Qwerty\", \"name\": \"Q\"}, " +
            "\"rows\": [{\"keys\": [" +
            keyJson("KEY_Q") + ", " + keyJson("KEY_Q") + "]}]}"
        File(layoutsDir, "Qwerty.json").writeText(dupQwerty)
        val first = resolveBuiltinLayouts(genDir)
        assertEquals(setOf("T9"), first.layouts.keys)
        assertEquals(setOf("Qwerty"), first.invalid.keys)
        // 重置路径：文件删除后即无覆盖（下一次快照构建回退出厂布局）。
        File(layoutsDir, "T9.json").delete()
        val afterReset = resolveBuiltinLayouts(genDir)
        assertTrue(afterReset.layouts.isEmpty())
    }
}
