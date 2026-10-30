package com.jobeen.ime.skin.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 新字段的包往返证据（树层）：演示包 skin.json 的字段取值
 * 在“校验 → 原始树序列化 → 再解析 → 再校验”后逐项一致。
 * 原始树即存储真相（定稿三.2），导出/导入均原样搬运该树，
 * 故树层一致 = .jime 进出包一致；包内清单 sha256 另由交付脚本核对。
 */
class RefDemoRoundtripTest {

    /** 与演示包 skin.json 字段取值一致的文档（背景资产 id 与键 id 相同）。 */
    private val demoJson = """
        {"formatVersion": 3,
         "meta": {"id": "ref-demo", "name": "ref-demo", "version": "1.0"},
         "styles": {"keyBackground": "#FF2E3440", "keyText": "#FFECEFF4",
           "specialKeyBackground": "#FF3B4252", "keyPressed": "#FF4C566A"},
         "stylesDark": {"keyBackground": "#FF222222"},
         "appearance": {"background": {"type": "asset", "asset": "bg_demo",
           "blurRadius": 0, "overlayAlpha": 0,
           "imageAdjust": {"brightnessPercent": 85, "blurRadiusDp": 6,
             "cropLeft": 0.08, "cropRight": 0.08}}},
         "keyMappings": {
           "KEY_Q": {"popupChars": ["，", "。", "、"]},
           "KEY_DELETE": {"icon": "backspace"},
           "T9_KEY_2": {"popupChars": ["a", "b", "c"]},
           "demo_ref:K-r0c1": {"popupChars": ["à", "á"], "icon": "mic"}},
         "toolbar": {"buttons": [{"id": "clipboard", "icon": "content_paste"},
           {"id": "voice", "icon": "mic"}],
           "expandIcon": "expand_more", "collapseIcon": "expand_less"}}
    """.trimIndent()

    private val fallbacks = GeometryFallbacks(12f, 6f, 5f, 10f)

    private fun validate(tree: JsonObject): ValidatedSkin {
        val result = SkinValidator.validate(tree, 157, fallbacks, assetKind = { id ->
            if (id == "bg_demo") AssetKind.IMAGE else null
        })
        assertTrue(result is SkinValidator.Result.Valid)
        return (result as SkinValidator.Result.Valid).skin
    }

    @Test
    fun demoFieldsSurviveTreeRoundtrip() {
        val first = validate(Json.parseToJsonElement(demoJson) as JsonObject)
        // 原始树序列化（导出搬运的形态）→ 再解析 → 再校验
        val serialized = first.tree.toString()
        val second = validate(Json.parseToJsonElement(serialized) as JsonObject)
        assertTrue(SkinJson.deepEquals(first.tree, second.tree))

        val q = second.keyOverrides.getValue("KEY_Q")
        assertEquals(listOf("，", "。", "、"), q.popupChars)
        assertEquals("backspace", second.keyOverrides.getValue("KEY_DELETE").icon)
        assertEquals(listOf("a", "b", "c"), second.keyOverrides.getValue("T9_KEY_2").popupChars)
        val custom = second.keyOverrides.getValue("demo_ref:K-r0c1")
        assertEquals(listOf("à", "á"), custom.popupChars)
        assertEquals("mic", custom.icon)

        val adjust = second.appearance.background!!.imageAdjust!!
        assertEquals(85f, adjust.brightnessPercent)
        assertEquals(6f, adjust.blurRadiusDp)
        assertEquals(0.08f, adjust.cropLeft)
        assertEquals(0.08f, adjust.cropRight)

        // 工具栏声明（本分流只负责 Symbol 名解析接线，构成字段由
        // 工具栏分流落地）：本分支校验不拒未知顶层键，原始树原样
        // 保留，往返后按钮 Symbol 名与两端图标逐项一致。
        val toolbar = second.tree.getValue("toolbar").jsonObject
        val buttons = toolbar.getValue("buttons") as JsonArray
        assertEquals("clipboard", buttons[0].jsonObject.getValue("id").jsonPrimitive.content)
        assertEquals(
            "content_paste",
            buttons[0].jsonObject.getValue("icon").jsonPrimitive.content,
        )
        assertEquals("mic", buttons[1].jsonObject.getValue("icon").jsonPrimitive.content)
        assertEquals("expand_more", toolbar.getValue("expandIcon").jsonPrimitive.content)
        assertEquals("expand_less", toolbar.getValue("collapseIcon").jsonPrimitive.content)
    }
}
