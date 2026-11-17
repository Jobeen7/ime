// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.skin.runtime

import com.jobeen.ime.data.keyboard.theme.KeyboardColors
import com.jobeen.ime.input.keyboard.impl.QwertyKeyboard
import com.jobeen.ime.input.keyboard.key.KeyDef
import com.jobeen.ime.input.keyboard.key.KeyDef.Appearance.Variant
import com.jobeen.ime.skin.model.DynamicTextSource
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



}
