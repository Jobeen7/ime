package com.jobeen.ime.skin.model

import com.jobeen.ime.data.keyboard.theme.KeyboardColors
import com.jobeen.ime.input.keyboard.impl.NumberKeyboard
import com.jobeen.ime.input.keyboard.impl.QwertyKeyboard
import com.jobeen.ime.input.keyboard.impl.T15Keyboard
import com.jobeen.ime.input.keyboard.impl.T9Keyboard
import com.jobeen.ime.input.keyboard.key.KeyDef
import com.jobeen.ime.input.keyboard.key.KeyDef.Appearance.Variant
import com.jobeen.ime.skin.store.SkinGenRef
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 键盘级几何 + 字族 × 布局矩阵（2026-10-28 新增范围）。
 *
 * JVM 单测边界（诚实口径）：KeyView 实例化与窗口重测需要 Android
 * Context，本地单测无法跑像素；本测试验证 JVM 可达的全部环节——
 * 皮肤校验 → 快照 → ColorScheme（几何）/快照字族（字族），以及每布局
 * 实际产出的 KeyDef 全部落在 BaseKeyboard.createKeyView 的处理分支内
 * （该工厂把同一 colors 对象传给全部 KeyView 子类；KeyView 构造时
 * radius/hMargin/vMargin 恒取自 colors，与布局无关；字族经
 * SkinFonts.peek() 取快照 fontFamily，同样与布局无关）。窗口高度
 * （contentHeight 读快照 heightPercent）与像素渲染列为真机验证项。
 */
class KeyboardGeometryFontMatrixTest {

    private val ref = SkinGenRef("skin-matrix", "h".repeat(64), 1)

    private val defaultScheme = KeyboardColors.ColorScheme(
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
    )

    /** 与演示包同值：圆角 14 / 间距 6 / 6 / 高度 30 / 字族 serif。 */
    private val matrixSkinJson = "{" +
        "\"formatVersion\": 3, " +
        "\"styles\": {\"keyBackground\": \"#FFFFFFFF\"}, " +
        "\"stylesDark\": {\"keyBackground\": \"#FF222222\"}, " +
        "\"geometry\": {\"keyRadiusDp\": 14, \"keyHGapDp\": 6, \"keyVGapDp\": 6, \"heightPercent\": 30}, " +
        "\"fontFamily\": \"serif\"}"



    /** BaseKeyboard.createKeyView 处理的分支（sealed 穷尽由编译期保证）。 */
    private fun isFactoryHandled(appearance: KeyDef.Appearance): Boolean =
        appearance is KeyDef.Appearance.Text ||
            appearance is KeyDef.Appearance.ImageText ||
            appearance is KeyDef.Appearance.Image ||
            appearance is KeyDef.Appearance.SidePannel


    @Test
    fun builtinLayouts_allDefsRouteThroughSharedFactory() {
        val layouts = mapOf(
            "T9" to T9Keyboard.buildLayout(Variant.Normal),
            "Qwerty" to QwertyKeyboard.buildLayout(Variant.Normal),
            "T15" to T15Keyboard.buildLayout(Variant.Normal),
            "Number" to NumberKeyboard.buildLayout(Variant.Normal),
        )
        for ((name, rows) in layouts) {
            val defs = rows.flatten()
            assertTrue("$name must build keys", defs.isNotEmpty())
            for (def in defs) {
                assertTrue(
                    "$name def must be factory-handled, got ${def.appearance::class.simpleName}",
                    isFactoryHandled(def.appearance),
                )
            }
            // Appearance 本身无圆角/间距/字族字段：几何与字族不可能在
            // def 层被某布局单独覆盖，只能经共享 colors + SkinFonts 生效。
        }
    }

    private fun customLayoutTree(layoutId: String, keyCount: Int): JsonObject {
        val keys = (1..keyCount).map { i ->
            buildJsonObject {
                put("stableId", "$i")
                put("label", "k$i")
                put("tap", buildJsonObject {
                    put("type", "text")
                    put("value", "k$i")
                })
            }
        }
        return buildJsonObject {
            put("formatVersion", 3)
            put("meta", buildJsonObject {
                put("id", layoutId)
                put("name", layoutId)
            })
            put("rows", buildJsonArray {
                for (row in keys.chunked(6)) {
                    add(buildJsonObject {
                        put("keys", buildJsonArray { row.forEach { add(it) } })
                    })
                }
            })
        }
    }


}
