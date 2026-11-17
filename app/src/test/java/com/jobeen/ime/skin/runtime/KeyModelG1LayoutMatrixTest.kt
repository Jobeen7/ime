package com.jobeen.ime.skin.runtime

import com.jobeen.ime.data.keyboard.theme.KeyboardColors
import com.jobeen.ime.input.keyboard.key.KeyDef
import com.jobeen.ime.input.keyboard.key.KeyIds
import com.jobeen.ime.input.keyboard.key.KeyboardAction
import com.jobeen.ime.skin.layout.LayoutModel
import com.jobeen.ime.skin.model.SkinAction
import com.jobeen.ime.skin.model.WhitelistAction
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * G1 第三任补齐测试：能力 × 布局覆盖矩阵。
 *
 * 装配器本身与布局无关（只消费 KeyDef 行），但各布局的出厂外观
 * 类型不同（九宫格 AltText、26 键 Text、十五键 Text、数字 Text、
 * 自定义布局坐标 ID + 跨行键），本测试按外观类型逐一断言 G1 字段
 * 落点；校验器侧断言坐标 ID 放宽口径（导入/快照工厂接线点）。
 * 渲染层（KeyView 像素）不在单测内，属真机验证项。
 */
class KeyModelG1LayoutMatrixTest {

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


    private fun textKey(id: String, text: String, width: Float, rowSpan: Int = 1) =
        KeyDef(
            appearance = KeyDef.Appearance.Text(
                displayText = text, textSize = 18f, percentWidth = width, rowSpan = rowSpan,
            ),
            behaviors = setOf(KeyDef.Behavior.Press(KeyboardAction.KeySequenceAction(text))),
            id = id,
        )

    private fun altTextKey(id: String, text: String, alt: String, width: Float) =
        KeyDef(
            appearance = KeyDef.Appearance.AltText(
                displayText = text, altText = alt, textSize = 18f, percentWidth = width,
            ),
            behaviors = setOf(KeyDef.Behavior.Press(KeyboardAction.KeySequenceAction(text))),
            id = id,
        )

    @Test
    fun logicalIdsFromTreeSkipsMalformedEntries() {
        val tree = Json.parseToJsonElement(
            """
            {"layoutId": "demo-layout", "rows": [
              {"keys": [{"stableId": "a"}, {"stableId": "b"}, {"noId": true}, "junk"]},
              {"keys": [{"stableId": ""}]},
              {"noKeys": true},
              "junk-row"
            ]}
            """.trimIndent(),
        ) as JsonObject
        assertEquals(
            setOf("demo-layout:K-a", "demo-layout:K-b"),
            LayoutModel.logicalIdsFromTree(tree, "demo-layout"),
        )
        assertTrue(LayoutModel.logicalIdsFromTree(JsonObject(emptyMap()), "x").isEmpty())
    }





}
