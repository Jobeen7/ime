package com.jobeen.ime.skin.runtime

import com.jobeen.ime.data.keyboard.theme.KeyboardColors
import com.jobeen.ime.input.keyboard.key.KeyDef
import com.jobeen.ime.input.keyboard.key.KeyboardAction
import com.jobeen.ime.skin.model.LayoutResolution
import com.jobeen.ime.skin.model.ResolvedLayout
import com.jobeen.ime.skin.model.SkinSnapshot
import com.jobeen.ime.skin.store.SkinGenRef
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 皮肤旧布局只替换同类主布局（2026-11-02 修：26 键皮肤布局劫持 T9）。
 * 有 keyboards 表项时表项仍优先（作者显式绑定，不在本规则内）；
 * 本文件只覆盖无表皮肤的旧绑定回落路径。
 */
class LayoutClassMatchTest {

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

    private fun keyDef(id: String): KeyDef = KeyDef(
        appearance = KeyDef.Appearance.Text(
            displayText = id, textSize = 18f, percentWidth = 1f,
        ),
        behaviors = setOf(
            KeyDef.Behavior.Press(KeyboardAction.KeySequenceAction(id)),
        ),
        id = id,
    )

    private fun lettersLayout(id: String): ResolvedLayout {
        val rows = listOf("qwertyuiop", "asdfghjkl", "zxcvbnm").map { row ->
            row.map { ch -> keyDef(ch.toString()) }
        }
        return ResolvedLayout(layoutId = id, generation = 1, name = id, baseLayout = rows)
    }

    private fun t9Layout(id: String): ResolvedLayout {
        val rows = listOf(
            listOf("1", "2", "3").map { keyDef(it) },
            listOf("ABC", "DEF").map { keyDef(it) },
        )
        return ResolvedLayout(layoutId = id, generation = 1, name = id, baseLayout = rows)
    }

    private fun snapshot(legacy: ResolvedLayout?): SkinSnapshot = SkinSnapshot(
        ref = SkinGenRef("skin-1", "h", 1),
        name = "S",
        colorScheme = scheme,
        geometryPresence = emptySet(),
        effectiveGeometry = emptyMap(),
        background = null,
        degradations = emptySet(),
        layoutId = legacy?.layoutId,
        resolvedLayout = legacy,
        layoutResolution = if (legacy != null) {
            LayoutResolution.RESOLVED
        } else {
            LayoutResolution.NOT_SET
        },
    )

    @Test
    fun t9SchemeWith26KeySkinFallsBackToFactoryT9() {
        // 用户原报：清新绿（只有 pinyin26）下 T9 方案永远出不了 T9。
        val snap = snapshot(lettersLayout("pinyin26"))
        assertEquals("T9", CustomLayoutRuntime.resolveTypeTarget(snap, "pinyin", "T9"))
        assertEquals("T9", CustomLayoutRuntime.resolveSwitchTarget(snap, "T9", "T9"))
    }

    @Test
    fun qwertySchemeWith26KeySkinKeepsSkinLayout() {
        // .12 口径不变：英文／26 键全拼仍用皮肤自己的 26 键布局。
        val snap = snapshot(lettersLayout("pinyin26"))
        assertEquals("custom:pinyin26", CustomLayoutRuntime.resolveTypeTarget(snap, "pinyin", "Qwerty"))
        assertEquals("custom:pinyin26", CustomLayoutRuntime.resolveSwitchTarget(snap, "Qwerty", "Qwerty"))
    }

    @Test
    fun t9SchemeWithT9SkinKeepsSkinLayout() {
        val snap = snapshot(t9Layout("t9skin"))
        assertEquals("custom:t9skin", CustomLayoutRuntime.resolveTypeTarget(snap, "pinyin", "T9"))
        assertEquals("custom:t9skin", CustomLayoutRuntime.resolveSwitchTarget(snap, "T9", "T9"))
    }

    @Test
    fun qwertySchemeWithT9SkinFallsBackToFactoryQwerty() {
        // 对称：九宫格皮肤不劫持 26 键全拼方案。
        val snap = snapshot(t9Layout("t9skin"))
        assertEquals("Qwerty", CustomLayoutRuntime.resolveTypeTarget(snap, "pinyin", "Qwerty"))
        assertEquals("Qwerty", CustomLayoutRuntime.resolveSwitchTarget(snap, "Qwerty", "Qwerty"))
    }

    @Test
    fun t15SchemeFollowsSameClassRule() {
        val skin26 = snapshot(lettersLayout("pinyin26"))
        assertEquals("T15", CustomLayoutRuntime.resolveTypeTarget(skin26, "pinyin", "T15"))
        assertEquals("T15", CustomLayoutRuntime.resolveSwitchTarget(skin26, "T15", "T15"))
        val skinT9 = snapshot(t9Layout("t9skin"))
        assertEquals("custom:t9skin", CustomLayoutRuntime.resolveTypeTarget(skinT9, "pinyin", "T15"))
    }
}
