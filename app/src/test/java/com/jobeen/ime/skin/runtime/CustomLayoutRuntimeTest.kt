package com.jobeen.ime.skin.runtime

import com.jobeen.ime.data.keyboard.theme.KeyboardColors
import com.jobeen.ime.input.keyboard.key.KeyDef
import com.jobeen.ime.input.keyboard.key.KeyboardAction
import com.jobeen.ime.skin.model.KeyOverride
import com.jobeen.ime.skin.model.LayoutResolution
import com.jobeen.ime.skin.model.ResolvedLayout
import com.jobeen.ime.skin.model.SkinAction
import com.jobeen.ime.skin.model.SkinSnapshot
import com.jobeen.ime.skin.store.SkinGenRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** 自创布局运行时接缝（B3 详设 §3.3）：主键盘位解析 + 生效布局装配。 */
class CustomLayoutRuntimeTest {

    private val scheme = KeyboardColors.ColorScheme(
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

    private fun keyDef(id: String, label: String): KeyDef = KeyDef(
        appearance = KeyDef.Appearance.Text(
            displayText = label, textSize = 18f, percentWidth = 1f,
        ),
        behaviors = setOf(
            KeyDef.Behavior.Press(KeyboardAction.KeySequenceAction(label)),
        ),
        id = id,
    )

    private fun snapshot(
        resolvedLayout: ResolvedLayout? = null,
        keyOverrides: Map<String, KeyOverride> = emptyMap(),
    ): SkinSnapshot = SkinSnapshot(
        ref = SkinGenRef("skin-1", "h", 1),
        name = "S",
        colorScheme = scheme,
        geometryPresence = emptySet(),
        effectiveGeometry = emptyMap(),
        background = null,
        degradations = emptySet(),
        layoutId = resolvedLayout?.layoutId,
        keyOverrides = keyOverrides,
        resolvedLayout = resolvedLayout,
        layoutResolution = if (resolvedLayout != null) {
            LayoutResolution.RESOLVED
        } else {
            LayoutResolution.NOT_SET
        },
    )

    private val resolvedA = ResolvedLayout(
        layoutId = "lay-a", generation = 1, name = "A",
        baseLayout = listOf(listOf(keyDef("lay-a:K-1", "q"))),
    )

    // ---------- 目标解析（§3.3 优先级表） ----------

    @Test
    fun mainSlotNamesRedirectToCustomWhenResolved() {
        val snap = snapshot(resolvedA)
        for (main in listOf("Qwerty", "T9", "T15")) {
            assertEquals(
                "custom:lay-a",
                CustomLayoutRuntime.resolveSwitchTarget(snap, main, "Qwerty"),
            )
        }
    }

    @Test
    fun sceneAndToolNamesNeverRedirect() {
        val snap = snapshot(resolvedA)
        for (name in listOf("Number", "Symbol", "Emoji")) {
            assertEquals(
                name,
                CustomLayoutRuntime.resolveSwitchTarget(snap, name, "Qwerty"),
            )
        }
    }

    @Test
    fun noResolvedLayoutPassesEverythingThrough() {
        val snap = snapshot(null)
        assertEquals(
            "T9",
            CustomLayoutRuntime.resolveSwitchTarget(snap, "T9", "T9"),
        )
        // 指向不存在布局的 custom 名回退方案默认
        assertEquals(
            "T9",
            CustomLayoutRuntime.resolveSwitchTarget(snap, "custom:lay-a", "T9"),
        )
        assertEquals(
            "Qwerty",
            CustomLayoutRuntime.resolveSwitchTarget(null, "Qwerty", "Qwerty"),
        )
    }

    @Test
    fun customTargetMustBeTheResolvedOne() {
        val snap = snapshot(resolvedA)
        assertEquals(
            "custom:lay-a",
            CustomLayoutRuntime.resolveSwitchTarget(snap, "custom:lay-a", "Qwerty"),
        )
        // 指向别的布局：先回退方案默认，再按主键盘位归一到当前已解析布局
        assertEquals(
            "custom:lay-a",
            CustomLayoutRuntime.resolveSwitchTarget(snap, "custom:lay-b", "Qwerty"),
        )
    }

    @Test
    fun nameHelpers() {
        assertEquals("custom:lay-a", CustomLayoutRuntime.nameFor("lay-a"))
        assertEquals("lay-a", CustomLayoutRuntime.layoutIdOf("custom:lay-a"))
        assertNull(CustomLayoutRuntime.layoutIdOf("Qwerty"))
        assertNull(CustomLayoutRuntime.layoutIdOf("custom:"))
    }

    // ---------- 生效布局装配 ----------

    @Test
    fun effectiveLayoutNullWithoutResolution() {
        assertNull(CustomLayoutRuntime.effectiveLayout(null, emptyMap()))
        assertNull(CustomLayoutRuntime.effectiveLayout(snapshot(null), emptyMap()))
    }

    @Test
    fun effectiveLayoutIdentityWithoutOverrides() {
        val snap = snapshot(resolvedA)
        val effective = CustomLayoutRuntime.effectiveLayout(snap, emptyMap())
        // 无覆写无全局音效：装配器恒等纪律 → 同一引用
        assertSame(resolvedA.baseLayout, effective)
        assertEquals(0, CustomLayoutRuntime.overrideHitCount(snap))
    }

    @Test
    fun effectiveLayoutAppliesKeyMappingsToCustomKeys() {
        // V4 验收面：keyMappings 以逻辑 ID 命中自创键（标签/点按/音效全槽位）
        val snap = snapshot(
            resolvedA,
            keyOverrides = mapOf(
                "lay-a:K-1" to KeyOverride(
                    label = "Q!",
                    tap = SkinAction.Command(
                        com.jobeen.ime.skin.model.WhitelistAction.DELETE,
                    ),
                    soundRef = "",
                ),
            ),
        )
        val effective = CustomLayoutRuntime.effectiveLayout(snap, emptyMap())!!
        val def = effective[0][0]
        assertEquals(
            "Q!",
            (def.appearance as KeyDef.Appearance.Text).displayText,
        )
        val press = def.behaviors.filterIsInstance<KeyDef.Behavior.Press>().single()
        assertTrue(press.action is KeyboardAction.BackspaceAction)
        assertTrue(def.skinSoundMuted)
        assertEquals(1, CustomLayoutRuntime.overrideHitCount(snap))
    }
}
