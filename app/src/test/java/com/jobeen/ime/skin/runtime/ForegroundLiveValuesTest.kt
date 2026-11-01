// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.skin.runtime

import com.jobeen.ime.data.keyboard.theme.KeyboardColors
import com.jobeen.ime.input.keyboard.impl.QwertyKeyboard
import com.jobeen.ime.input.keyboard.key.KeyDef
import com.jobeen.ime.input.keyboard.key.KeyDef.Appearance.Variant
import com.jobeen.ime.skin.model.DynamicTextSource
import com.jobeen.ime.skin.model.KeyForegroundLayer
import com.jobeen.ime.skin.model.KeyOverride
import com.jobeen.ime.skin.model.SkinSnapshot
import com.jobeen.ime.skin.store.SkinGenRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 前景层实时变量（审计 K 余项）：对端特殊变量映射、
 * 构建期快照解析、运行时刷新模板留存。
 */
class ForegroundLiveValuesTest {

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

    @Test
    fun peerVariablesMapToDynamicSources() {
        assertEquals(
            DynamicTextSource.COMPOSING,
            DynamicTextSource.fromPeerVariable("\$rimePreedit"),
        )
        assertEquals(
            DynamicTextSource.CANDIDATE_FIRST,
            DynamicTextSource.fromPeerVariable("\$rimeCandidate"),
        )
        assertEquals(
            DynamicTextSource.CANDIDATE_COMMENT,
            DynamicTextSource.fromPeerVariable("\$rimeCandidateComment"),
        )
        assertEquals(
            DynamicTextSource.SCHEME_NAME,
            DynamicTextSource.fromPeerVariable("\$rimeSchemaName"),
        )
        assertEquals(
            DynamicTextSource.RETURN_KEY_TYPE,
            DynamicTextSource.fromPeerVariable("\$returnKeyType"),
        )
        assertNull(DynamicTextSource.fromPeerVariable("\$rimeUnknown"))
        assertNull(DynamicTextSource.fromPeerVariable("rimeCandidate"))
    }

    @Test
    fun liveTextResolvesKnownVariablesAndEmptiesMissing() {
        val values = mapOf(
            DynamicTextSource.CANDIDATE_FIRST.jsonName to "你好",
            DynamicTextSource.SCHEME_NAME.jsonName to "万象",
        )
        assertEquals(
            "首选：你好",
            KeyOverrideApplier.resolveForegroundLiveText("首选：\$rimeCandidate", values),
        )
        // 无值变量替为空串，永不原样露出记号。
        assertEquals(
            "组字：",
            KeyOverrideApplier.resolveForegroundLiveText("组字：\$rimePreedit", values),
        )
        // 未知记号原样保留（再走占位替换口径）。
        assertEquals(
            "\$rimeUnknown",
            KeyOverrideApplier.resolveForegroundLiveText("\$rimeUnknown", values),
        )
        assertEquals(
            "万象·你好",
            KeyOverrideApplier.resolveForegroundLiveText(
                "\$rimeSchemaName·\$rimeCandidate", values,
            ),
        )
    }

    @Test
    fun liveTemplateKeptOnlyWhenTemplateHasLiveVariable() {
        assertEquals(
            "首选：\$rimeCandidate",
            KeyOverrideApplier.foregroundLiveTemplate("首选：\$rimeCandidate"),
        )
        assertNull(KeyOverrideApplier.foregroundLiveTemplate("静态文字"))
        assertNull(KeyOverrideApplier.foregroundLiveTemplate(null))
        assertNull(KeyOverrideApplier.foregroundLiveTemplate("\$rimeUnknown"))
    }

    @Test
    fun applyResolvesForegroundLiveTextAndKeepsTemplate() {
        val snapshot = SkinSnapshot(
            ref = SkinGenRef("skin-1", "h".repeat(64), 1),
            name = "test",
            colorScheme = scheme,
            geometryPresence = emptySet(),
            effectiveGeometry = emptyMap(),
            background = null,
            degradations = emptySet(),
            layoutId = null,
            keyOverrides = mapOf(
                "KEY_Q" to KeyOverride(
                    foregroundLayers = listOf(
                        KeyForegroundLayer(type = "text", text = "首选：\$rimeCandidate"),
                        KeyForegroundLayer(type = "text", text = "静态层"),
                    ),
                ),
            ),
        )
        val result = KeyOverrideApplier.apply(
            "Qwerty",
            QwertyKeyboard.buildLayout(Variant.Accent),
            snapshot,
            emptyMap(),
            dynamicTexts = mapOf(DynamicTextSource.CANDIDATE_FIRST to "你好"),
        )
        val copied = result.flatten().first { it.id == "KEY_Q" }
        val layers = copied.skinForegroundLayers!!
        assertEquals(2, layers.size)
        assertEquals("首选：你好", layers[0].text)
        assertEquals("首选：\$rimeCandidate", layers[0].dynamicTemplate)
        assertEquals("静态层", layers[1].text)
        assertNull(layers[1].dynamicTemplate)
    }
}
