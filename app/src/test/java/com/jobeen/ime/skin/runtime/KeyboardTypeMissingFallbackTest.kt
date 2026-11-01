package com.jobeen.ime.skin.runtime

import com.jobeen.ime.data.keyboard.theme.KeyboardColors
import com.jobeen.ime.input.keyboard.key.KeyDef
import com.jobeen.ime.input.keyboard.key.KeyboardAction
import com.jobeen.ime.skin.model.KeyboardTypeResolved
import com.jobeen.ime.skin.model.LayoutResolution
import com.jobeen.ime.skin.model.ResolvedLayout
import com.jobeen.ime.skin.model.SkinSnapshot
import com.jobeen.ime.skin.store.SkinGenRef
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 缺失文件回退语义（多主槽一期）：表项绑定指向缺失/非法布局
 * 文件时，该类型绝不空白——按类型回落内置键盘；pinyin 另有
 * 旧顶层绑定可回落。缺失结论（MISSING/INVALID）与未声明
 * 同等处理，区别只在诊断日志，不在目标名。
 */
class KeyboardTypeMissingFallbackTest {

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

    private fun resolved(id: String) = ResolvedLayout(
        layoutId = id, generation = 1, name = id,
        baseLayout = listOf(listOf(keyDef("$id:K-1"))),
    )

    private fun failedEntry(resolution: LayoutResolution) = KeyboardTypeResolved(
        portraitResolution = resolution,
        landscapeResolution = resolution,
    )

    private fun snapshot(
        table: Map<String, KeyboardTypeResolved> = emptyMap(),
        legacy: ResolvedLayout? = null,
    ): SkinSnapshot = SkinSnapshot(
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
        resolvedKeyboards = table,
    )

    @Test
    fun missingToolEntriesFallBackToBuiltinNeverBlank() {
        val snap = snapshot(
            table = mapOf(
                "emojis" to failedEntry(LayoutResolution.MISSING),
                "images" to failedEntry(LayoutResolution.MISSING),
                "numberPad" to failedEntry(LayoutResolution.INVALID),
                "symbolic" to failedEntry(LayoutResolution.INVALID),
                "numeric" to failedEntry(LayoutResolution.MISSING),
                "myBoard" to failedEntry(LayoutResolution.MISSING),
            ),
        )
        // emojis → 内置表情键盘，绝不空白（任务明文要求）。
        assertEquals("Emoji", CustomLayoutRuntime.resolveTypeTarget(snap, "emojis", "T9"))
        assertEquals("Emoji", CustomLayoutRuntime.resolveTypeTarget(snap, "images", "T9"))
        assertEquals("Number", CustomLayoutRuntime.resolveTypeTarget(snap, "numberPad", "T9"))
        assertEquals("Symbol", CustomLayoutRuntime.resolveTypeTarget(snap, "symbolic", "T9"))
        assertEquals("Number", CustomLayoutRuntime.resolveTypeTarget(snap, "numeric", "T9"))
        assertEquals("T9", CustomLayoutRuntime.resolveTypeTarget(snap, "myBoard", "T9"))
    }

    @Test
    fun missingPinyinFallsBackToLegacyThenSchemaDefault() {
        val withLegacy = snapshot(
            table = mapOf("pinyin" to failedEntry(LayoutResolution.MISSING)),
            legacy = resolved("old-lay"),
        )
        assertEquals(
            "custom:old-lay",
            CustomLayoutRuntime.resolveTypeTarget(withLegacy, "pinyin", "T9"),
        )
        val withoutLegacy = snapshot(
            table = mapOf("pinyin" to failedEntry(LayoutResolution.INVALID)),
        )
        assertEquals("T9", CustomLayoutRuntime.resolveTypeTarget(withoutLegacy, "pinyin", "T9"))
    }

    @Test
    fun oneDirectionMissingDoesNotPoisonOtherDirection() {
        val snap = snapshot(
            table = mapOf(
                "pinyin" to KeyboardTypeResolved(
                    portraitResolution = LayoutResolution.MISSING,
                    landscape = resolved("pin-land"),
                    landscapeResolution = LayoutResolution.RESOLVED,
                ),
            ),
        )
        assertEquals("T9", CustomLayoutRuntime.resolveTypeTarget(snap, "pinyin", "T9"))
        assertEquals(
            "custom:pin-land",
            CustomLayoutRuntime.resolveTypeTarget(snap, "pinyin", "T9", landscape = true),
        )
    }

    @Test
    fun nullSnapshotStillResolvesBuiltins() {
        for ((type, expected) in mapOf(
            "alphabetic" to "Qwerty",
            "pinyin" to "T9",
            "emojis" to "Emoji",
            "images" to "Emoji",
            "numeric" to "Number",
            "numberPad" to "Number",
            "symbolic" to "Symbol",
            "myBoard" to "T9",
        )) {
            assertEquals(
                expected,
                CustomLayoutRuntime.resolveTypeTarget(null, type, "T9"),
            )
        }
    }
}
