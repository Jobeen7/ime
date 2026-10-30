package com.jobeen.ime.skin.runtime

import com.jobeen.ime.data.keyboard.theme.KeyboardColors
import com.jobeen.ime.input.keyboard.key.KeyDef
import com.jobeen.ime.input.keyboard.key.KeyboardAction
import com.jobeen.ime.skin.model.AdmittedSound
import com.jobeen.ime.skin.model.KeyOverride
import com.jobeen.ime.skin.model.LayoutResolution
import com.jobeen.ime.skin.model.ResolvedLayout
import com.jobeen.ime.skin.model.SkinAction
import com.jobeen.ime.skin.model.SkinSnapshot
import com.jobeen.ime.skin.model.SoundBindings
import com.jobeen.ime.skin.store.SkinGenRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * V4 覆写装配复用核查（B3 详设 §3.1/§9.1 V4）：自创布局基础结构
 * 过 B2 的 KeyOverrideApplier，与出厂键同一语义逐槽对表——
 * 单侧合并、长按多项装配、三级音效解析、恒等快路径、跨行保真。
 */
class CustomLayoutOverrideAssemblyTest {

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

    private fun keyDef(
        id: String,
        label: String,
        behaviors: Set<KeyDef.Behavior> = setOf(
            KeyDef.Behavior.Press(KeyboardAction.KeySequenceAction(label)),
        ),
        rowSpan: Int = 1,
    ): KeyDef = KeyDef(
        appearance = KeyDef.Appearance.Text(
            displayText = label, textSize = 18f, percentWidth = 0.5f, rowSpan = rowSpan,
        ),
        behaviors = behaviors,
        id = id,
    )

    private val baseLayout: List<List<KeyDef>> = listOf(
        listOf(
            keyDef(
                "lay-a:K-1", "q",
                behaviors = setOf(
                    KeyDef.Behavior.Press(KeyboardAction.KeySequenceAction("q")),
                    KeyDef.Behavior.Swipe(
                        upAction = KeyboardAction.KeySequenceAction("Q"),
                        downAction = KeyboardAction.KeySequenceAction("1"),
                    ),
                    KeyDef.Behavior.LongPress(KeyboardAction.KeySequenceAction("qq")),
                ),
            ),
            keyDef("lay-a:K-2", "w"),
        ),
        listOf(
            keyDef("lay-a:K-3", "↵", rowSpan = 2),
        ),
    )

    private fun snapshot(
        keyOverrides: Map<String, KeyOverride> = emptyMap(),
        soundBindings: SoundBindings = SoundBindings.EMPTY,
    ): SkinSnapshot = SkinSnapshot(
        ref = SkinGenRef("skin-1", "h", 1),
        name = "S",
        colorScheme = scheme,
        geometryPresence = emptySet(),
        effectiveGeometry = emptyMap(),
        background = null,
        degradations = emptySet(),
        layoutId = "lay-a",
        keyOverrides = keyOverrides,
        soundBindings = soundBindings,
        resolvedLayout = ResolvedLayout("lay-a", 1, "A", baseLayout),
        layoutResolution = LayoutResolution.RESOLVED,
    )

    private fun apply(
        snap: SkinSnapshot,
    ): List<List<KeyDef>> = CustomLayoutRuntime.effectiveLayout(snap, emptyMap())!!

    @Test
    fun swipeOverrideMergesSingleSide() {
        val snap = snapshot(
            mapOf(
                "lay-a:K-1" to KeyOverride(swipeUp = SkinAction.Text("!")),
            ),
        )
        val def = apply(snap)[0][0]
        val swipe = def.behaviors.filterIsInstance<KeyDef.Behavior.Swipe>().single()
        assertEquals(
            "!",
            (swipe.upAction as KeyboardAction.KeySequenceAction).sequence,
        )
        // 未覆写的下行保留布局缺省（单侧合并，与出厂键同规）
        assertEquals(
            "1",
            (swipe.downAction as KeyboardAction.KeySequenceAction).sequence,
        )
        // 未命中的键保持原样（同一引用，不复制）
        assertSame(baseLayout[0][1], apply(snap)[0][1])
    }

    @Test
    fun longPressOverrideAssembly() {
        // 单项 → LongPress 行为替换
        val single = snapshot(
            mapOf(
                "lay-a:K-1" to KeyOverride(
                    longPress = listOf(SkinAction.Text("9")),
                ),
            ),
        )
        val defSingle = apply(single)[0][0]
        val lp = defSingle.behaviors.filterIsInstance<KeyDef.Behavior.LongPress>().single()
        assertEquals(
            "9",
            (lp.action as KeyboardAction.KeySequenceAction).sequence,
        )
        assertNull(defSingle.popups)
        // 多项 → 只挂 Popup.Keyboard，布局缺省 LongPress 被移除
        val multi = snapshot(
            mapOf(
                "lay-a:K-1" to KeyOverride(
                    longPress = listOf(SkinAction.Text("8"), SkinAction.Text("9")),
                ),
            ),
        )
        val defMulti = apply(multi)[0][0]
        assertTrue(defMulti.behaviors.none { it is KeyDef.Behavior.LongPress })
        val popup = defMulti.popups!!.filterIsInstance<KeyDef.Popup.Keyboard>().single()
        assertEquals(2, popup.keys.size)
    }

    @Test
    fun soundResolutionThreeLevels() {
        val admitted = mapOf(
            "snd_global" to AdmittedSound("snd_global", "a".repeat(64), "ogg", 100),
            "snd_key" to AdmittedSound("snd_key", "b".repeat(64), "ogg", 100),
        )
        val bindings = SoundBindings(
            admitted = admitted,
            defaultTapAssetId = "snd_global",
            rejected = emptyList(),
            skippedByDegradation = false,
        )
        // 逐键命中 > 全局 tap：K-1 绑逐键音，K-2 吃全局
        val snap = snapshot(
            mapOf("lay-a:K-1" to KeyOverride(soundRef = "snd_key")),
            soundBindings = bindings,
        )
        val built = apply(snap)
        assertEquals("snd_key", built[0][0].skinSoundAssetId)
        assertEquals("snd_global", built[0][1].skinSoundAssetId)
        // 逐键引用未接纳 → 回退全局（不静音不报错）
        val unadmitted = snapshot(
            mapOf("lay-a:K-1" to KeyOverride(soundRef = "snd_missing")),
            soundBindings = bindings,
        )
        assertEquals("snd_global", apply(unadmitted)[0][0].skinSoundAssetId)
    }

    @Test
    fun spanSurvivesLabelOverride() {
        val snap = snapshot(
            mapOf("lay-a:K-3" to KeyOverride(label = "回车")),
        )
        val def = apply(snap)[1][0]
        assertEquals("回车", (def.appearance as KeyDef.Appearance.Text).displayText)
        assertEquals(2, def.appearance.rowSpan)
    }

    @Test
    fun identityWhenOverridesMissEverything() {
        // 覆写表非空但全不命中自创键（只有出厂 ID）、且无全局音效
        // → 恒等返回基础结构引用（恒等快路径纪律不因自创布局破例）
        val snap = snapshot(
            mapOf("KEY_Q" to KeyOverride(label = "x")),
        )
        assertSame(baseLayout, apply(snap))
        assertEquals(0, CustomLayoutRuntime.overrideHitCount(snap))
    }
}
