package com.jobeen.ime.skin.model

import com.jobeen.ime.base.util.DigestUtil
import java.util.zip.ZipInputStream
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 参考演示包（ref-demo.jime，test resource 与 demo/ 交付件同 sha256）
 * 的包级证据：清单各条目 sha256 与包内文件逐字节吻合；包内 skin.json
 * 经 SkinValidator 校验为 Valid，且 imageAdjust / popupChars / icon /
 * toolbar Symbol 名逐项存在；原始树序列化再校验后三项字段一致
 * （即导出搬运形态下的往返一致）。
 *
 * 未覆盖：JimeImporter 整包导入（需 Android 文件/Store 环境）与
 * 真机渲染像素——列为真机验证项，不在此冒充。
 */
class RefDemoPackageTest {

    @Test
    fun demoPackageManifestHashesAndFields() {
        val bytes = requireNotNull(javaClass.classLoader)
            .getResourceAsStream("ref-demo.jime")?.readBytes()
            ?: error("missing test resource ref-demo.jime")
        val byPath = mutableMapOf<String, ByteArray>()
        ZipInputStream(bytes.inputStream()).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                byPath[entry.name] = zip.readBytes()
                entry = zip.nextEntry
            }
        }
        val manifest = PackageManifest.parse(
            byPath.getValue("manifest.json").decodeToString(),
        ) ?: error("manifest unparseable")
        assertEquals(3, manifest.formatVersion)
        for (content in manifest.contents) {
            assertEquals(
                content.sha256,
                DigestUtil.sha256Hex(byPath.getValue(content.path)),
            )
        }
        val skinContent = manifest.contents.single { it.type == PackageManifest.TYPE_SKIN }
        assertEquals("ref-demo", skinContent.id)
        val asset = manifest.contents.single { it.type == PackageManifest.TYPE_ASSET }
        assertEquals("bg_demo", asset.id)
        assertEquals("assets/bg_demo.png", asset.path)

        val tree = Json.parseToJsonElement(
            byPath.getValue("skin.json").decodeToString(),
        ).jsonObject
        assertEquals("ref-demo", tree.getValue("meta").jsonObject.getValue("id").jsonPrimitive.content)
        val validated = SkinValidator.validate(
            tree, 157, GeometryFallbacks(12f, 6f, 5f, 10f),
            assetKind = { id -> if (id == "bg_demo") AssetKind.IMAGE else null },
        )
        assertTrue(validated is SkinValidator.Result.Valid)
        val skin = (validated as SkinValidator.Result.Valid).skin
        val adjust = skin.appearance.background!!.imageAdjust!!
        assertEquals(85f, adjust.brightnessPercent)
        assertEquals(6f, adjust.blurRadiusDp)
        assertEquals(0f, adjust.rotationDegrees)
        assertEquals(0.08f, adjust.cropLeft)
        assertEquals(0.08f, adjust.cropRight)
        assertEquals(listOf("，", "。", "、"), skin.keyOverrides.getValue("KEY_Q").popupChars)
        assertEquals("backspace", skin.keyOverrides.getValue("KEY_DELETE").icon)
        assertEquals(listOf("a", "b", "c"), skin.keyOverrides.getValue("T9_KEY_2").popupChars)
        val toolbar = skin.tree.getValue("toolbar").jsonObject
        val buttons = toolbar.getValue("buttons") as JsonArray
        assertEquals("content_paste", buttons[0].jsonObject.getValue("icon").jsonPrimitive.content)
        assertEquals("mic", buttons[1].jsonObject.getValue("icon").jsonPrimitive.content)
        assertEquals("expand_more", toolbar.getValue("expandIcon").jsonPrimitive.content)
        assertEquals("expand_less", toolbar.getValue("collapseIcon").jsonPrimitive.content)

        // 原始树序列化 → 再校验：三项字段一致。
        val second = SkinValidator.validate(
            Json.parseToJsonElement(skin.tree.toString()).jsonObject,
            157, GeometryFallbacks(12f, 6f, 5f, 10f),
            assetKind = { id -> if (id == "bg_demo") AssetKind.IMAGE else null },
        )
        assertTrue(second is SkinValidator.Result.Valid)
        val skin2 = (second as SkinValidator.Result.Valid).skin
        assertEquals(adjust, skin2.appearance.background!!.imageAdjust)
        assertEquals(skin.keyOverrides, skin2.keyOverrides)
        assertTrue(SkinJson.deepEquals(skin.tree, skin2.tree))
    }
}
